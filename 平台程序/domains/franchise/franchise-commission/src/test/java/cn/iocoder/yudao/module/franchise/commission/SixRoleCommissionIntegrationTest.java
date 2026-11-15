package cn.iocoder.yudao.module.franchise.commission;

import cn.iocoder.yudao.module.franchise.api.*;
import cn.iocoder.yudao.module.franchise.crm.*;
import cn.iocoder.yudao.module.franchise.finance.*;
import cn.iocoder.yudao.module.franchise.org.*;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.platform.command.CommandLogImpl;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.workflow.ApprovalServiceImpl;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static cn.iocoder.yudao.module.franchise.test.FranchiseAssertions.assertError;
import static cn.iocoder.yudao.module.franchise.commission.enums.FranchiseCommissionErrorCodes.*;

/** Real PG transactions/settlement; only asset transport and engineer projection are explicit boundary stubs. */
@Import({CommissionService.class,CommissionAccess.class,CommissionQueryAdapter.class,CommissionManifestService.class,
        DesignPricingService.class,DesignFeeSettlementAdapter.class,DesignFinanceLocks.class,CommissionOrgScope.class,
        FranchiseCommands.class,CommandLogImpl.class,ApprovalServiceImpl.class,CustomerService.class,LeadService.class,
        CrmOrgScope.class,CommissionDeliverableAccessAdapter.class,HandoverService.class,SixRoleCommissionIntegrationTest.Config.class})
class SixRoleCommissionIntegrationTest extends FranchisePgIntegrationTest {
    @TestConfiguration static class Config {
        // Model the real asset-service -> grant-port dependency, so a command-service grant implementation cycles.
        @Bean AssetAccessPort assets(CommissionDeliverableAccessPort grants) { return org.mockito.Mockito.mock(AssetAccessPort.class); }
        @Bean StoreServiceAccountPort accounts() { return org.mockito.Mockito.mock(StoreServiceAccountPort.class); }
        @Bean EngineeringProjectContextPort engineering() { return (company,id)->null; }
        @Bean EngineeringPort projects() { return org.mockito.Mockito.mock(EngineeringPort.class); }
        @Bean SaleContractQueryPort contracts() { return org.mockito.Mockito.mock(SaleContractQueryPort.class); }
    }
    @Resource CommissionService commissions;
    @Resource CustomerService customers;
    @Resource LeadService leads;
    @Resource DesignPricingService pricing;
    @Resource JdbcTemplate jdbc;
    @Resource AssetAccessPort assets;
    @Resource CommissionDeliverableAccessPort grants;
    @Resource HandoverService handovers;

    @Test void areaSubmissionFreezesPriceRejectsStaleAndRequiresBothLiveNets() {
        var store=fixture.store();
        PostSnapshot sales=fixture.post(store.orgId(),"sales",store.salesTeamA());
        PostSnapshot design=fixture.post(fixture.headquarters(),"hq-design-admin",null);
        long customer=as(sales,()->leads.capture(sales,new LeadService.CaptureCommand("联调测试",String.valueOf(15000000000L+Math.abs(UUID.randomUUID().getLeastSignificantBits()%800000000L)),"建房","测试"),null).customerId());
        var budget=as(sales,()->customers.saveScheme(sales,customer,new CustomerService.SchemeCommand("BUDGET","预算","{}",new BigDecimal("10"),new BigDecimal("10"),2,new BigDecimal("200.25"),10000L),null));
        var p=as(design,()->pricing.save(design,new DesignPricingService.Save(101,"方案图纸","总部设计",true),key()));
        var stale=new CommissionService.SubmitCommand("二层住宅","方案图纸",null,null,null,null,0,null,null,true,"AREA_FULL",p.version(),null);
        assertError(()->as(sales,()->commissions.submit(sales,customer,stale,null)),
                cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants.BAD_REQUEST);
        assertError(()->as(sales,()->commissions.submit(sales,customer,stale,key())),SUBMISSION_VERSION_STALE);
        var nextPolicy=as(design,()->pricing.save(design,new DesignPricingService.Save(101,"方案图纸","总部设计",true),key()));
        var staleRule=new CommissionService.SubmitCommand("二层住宅","方案图纸",null,null,null,null,budget.getVersion(),null,null,true,"AREA_FULL",p.version(),null);
        assertError(()->as(sales,()->commissions.submit(sales,customer,staleRule,key())),SUBMISSION_VERSION_STALE);
        var input=new CommissionService.SubmitCommand("二层住宅","方案图纸",null,null,null,null,budget.getVersion(),null,null,true,"AREA_FULL",nextPolicy.version(),null);
        String submitKey=key();
        var detail=as(sales,()->commissions.submit(sales,customer,input,submitKey));
        long id=detail.commission().getId();
        assertThat(id).isNotEqualTo(customer);
        assertThat(detail.submissions().get(0).getTotalCents()).isEqualTo(20225);
        assertThat(detail.submissions().get(0).getFirstCents()).isZero();
        assertThat(as(sales,()->commissions.submit(sales,customer,input,submitKey)).submissions()).hasSize(1);
        assertThat(as(sales,()->commissions.context(sales,customer)).commissionId()).isEqualTo(id);
        var complete=new CommissionService.CompleteCommand("图纸校审完成",true);
        var finance=fixture.post(store.orgId(),"finance",null);
        as(finance,()->{commissions.markFirstReady(id,1,finance);return null;});
        assertError(()->as(design,()->commissions.complete(design,id,complete,key())),FIRST_PAYMENT_NOT_READY);
        long payable=jdbc.queryForObject("SELECT id FROM fr_fin_payable WHERE source_ref=?",Long.class,id+":1:FULL");
        long payment=cn.hutool.core.util.IdUtil.getSnowflakeNextId();
        jdbc.update("INSERT INTO fr_fin_payment(id,payable_id,company_id,amount_cents,status,operator_user_id) VALUES(?,?,?,20225,'已核实',?)",payment,payable,store.orgId(),sales.userId());
        assertError(()->as(design,()->commissions.complete(design,id,complete,key())),FIRST_PAYMENT_NOT_READY);
        long receipt=cn.hutool.core.util.IdUtil.getSnowflakeNextId();
        jdbc.update("INSERT INTO fr_fin_receipt(id,company_id,source_type,source_ref,origin_payment_id,name,amount_cents,status,registrar_user_id) VALUES(?,?,'design',?,?,?,20225,'已登记',?)",receipt,design.orgId(),id+":1:FULL",payment,"设计实收",design.userId());
        var done=as(design,()->commissions.complete(design,id,complete,key()));
        assertThat(done.commission().getStatus()).isEqualTo("DESIGN_COMPLETED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_payable WHERE source_type='design' AND source_ref LIKE ?",Long.class,id+":1:%")).isEqualTo(1);
        assertThat(done.allowedActions()).contains("design-dispatch");
        jdbc.update("INSERT INTO fr_fin_refund(id,receipt_id,company_id,amount_cents,status,reason,operator_user_id) VALUES(?,?,?,1,'已核实','测试退款',?)",cn.hutool.core.util.IdUtil.getSnowflakeNextId(),receipt,design.orgId(),design.userId());
        assertThat(as(design,()->commissions.get(design,id)).allowedActions()).doesNotContain("design-dispatch");
        assertThat(as(design,()->commissions.get(design,id)).commission().getStatus()).isEqualTo("DESIGN_COMPLETED");
    }
    @Test void areaCalculationRoundsHalfUpAndRejectsInvalidDimensions() {
        assertThat(DesignPricingService.totalCents(new BigDecimal("1.005"),100)).isEqualTo(101);
        assertError(()->DesignPricingService.totalCents(BigDecimal.ZERO,100),SUBMISSION_INPUT_INVALID);
        long id=cn.hutool.core.util.IdUtil.getSnowflakeNextId();
        var store=fixture.store();long headquarters=fixture.headquarters();
        jdbc.update("INSERT INTO fr_commission(id,company_id,design_org_id,customer_id,customer_name,status,submission_version) VALUES(?,?,?,?,?,'DESIGNING',1)",id,store.orgId(),headquarters,id,"TEST ONLY约束");
        assertThatThrownBy(()->jdbc.update("INSERT INTO fr_commission_submission(id,company_id,design_org_id,commission_id,version,text,scope,total_cents,first_cents,recipient,auth_kind,submitted_by_user_id,submitted_by_post_id,mode) VALUES(?,?,?,?,1,'TEST ONLY','方案',100,0,'设计','STANDARD',1,1,'AREA_FULL')",cn.hutool.core.util.IdUtil.getSnowflakeNextId(),store.orgId(),headquarters,id))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("ck_fr_commission_submission_amount");
        // Do not leave an intentionally incomplete commission visible to later role todo projections.
        jdbc.update("DELETE FROM fr_commission WHERE id=?",id);
    }
    @Test void manifestCannotBeReplacedAndScopeIsRecheckedAfterWithdrawal() {
        var store=fixture.store();
        var sales=fixture.post(store.orgId(),"sales",store.salesTeamA());
        var engineer=fixture.post(store.orgId(),"engineer",store.engineeringTeam());
        var outsider=fixture.post(fixture.store().orgId(),"sales",null);
        var design=fixture.post(fixture.headquarters(),"hq-design-admin",null);
        long customer=as(sales,()->leads.capture(sales,new LeadService.CaptureCommand("成果测试",String.valueOf(15000000000L+Math.abs(UUID.randomUUID().getLeastSignificantBits()%800000000L)),"建房","测试"),null).customerId());
        long id=cn.hutool.core.util.IdUtil.getSnowflakeNextId();
        jdbc.update("INSERT INTO fr_commission(id,company_id,design_org_id,customer_id,customer_name,status,submission_version) VALUES(?,?,?,?,?,'DESIGN_COMPLETED',1)",id,store.orgId(),design.orgId(),customer,"成果测试");
        jdbc.update("INSERT INTO fr_commission_submission(id,company_id,design_org_id,commission_id,version,text,scope,total_cents,first_cents,recipient,auth_kind,submitted_by_user_id,submitted_by_post_id) VALUES(?,?,?,?,1,'测试','方案',100,50,'设计','STANDARD',?,?)",cn.hutool.core.util.IdUtil.getSnowflakeNextId(),store.orgId(),design.orgId(),id,sales.userId(),sales.postId());
        for(String stage:java.util.List.of("FIRST","TAIL")) {
            long payable=cn.hutool.core.util.IdUtil.getSnowflakeNextId(),payment=cn.hutool.core.util.IdUtil.getSnowflakeNextId();
            String ref=id+":1:"+stage;
            jdbc.update("INSERT INTO fr_fin_payable(id,company_id,customer_id,design_org_id,stage,total_cents,recipient,status,source_type,source_ref) VALUES(?,?,?,?,?,50,'测试','进行中','design',?)",payable,store.orgId(),customer,design.orgId(),stage,ref);
            jdbc.update("INSERT INTO fr_fin_payment(id,payable_id,company_id,amount_cents,status,operator_user_id) VALUES(?,?,?,50,'已核实',?)",payment,payable,store.orgId(),sales.userId());
            jdbc.update("INSERT INTO fr_fin_receipt(id,company_id,source_type,source_ref,origin_payment_id,name,amount_cents,status,registrar_user_id) VALUES(?,?,'design',?,?,'TEST ONLY',50,'已登记',?)",cn.hutool.core.util.IdUtil.getSnowflakeNextId(),design.orgId(),ref,payment,design.userId());
        }
        org.mockito.Mockito.reset(assets);
        var dispatch=new CommissionService.DispatchCommand("正式下发","业务",true,"DRAW-V1",101L,102L,103L);
        assertError(()->as(design,()->commissions.dispatch(design,id,dispatch,key())),PROGRESS_INPUT_INVALID);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_commission_dispatch_manifest WHERE commission_id=?",Long.class,id)).isZero();
        org.mockito.Mockito.when(assets.validateReference(org.mockito.ArgumentMatchers.any(AssetAccessPort.Actor.class),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anySet())).thenReturn(true);
        var dispatched=as(design,()->commissions.dispatch(design,id,dispatch,key()));
        assertThat(dispatched.dispatches()).hasSize(1);
        assertThat(as(sales,()->grants.requireReadable(sales,id,1,CommissionDeliverableAccessPort.Slot.RESULT)).assetId()).isEqualTo(101);
        assertError(()->as(outsider,()->grants.requireReadable(outsider,id,1,CommissionDeliverableAccessPort.Slot.RESULT)),COMMISSION_NOT_FOUND);
        assertError(()->as(engineer,()->grants.requireReadable(engineer,id,1,CommissionDeliverableAccessPort.Slot.RESULT)),COMMISSION_FORBIDDEN);
        assertError(()->as(sales,()->handovers.submit(sales,id,new HandoverService.PacketCommand(1,null,null,null,null,null,java.time.LocalDate.now(),"交底",sales.userId(),true,104L),key())),HANDOVER_TARGET_INVALID);
        var packet=as(sales,()->handovers.submit(sales,id,new HandoverService.PacketCommand(1,null,null,null,null,null,java.time.LocalDate.now(),"交底",engineer.userId(),true,104L),key()));
        assertThat(packet.handovers().get(0).getDrawingFile()).contains("102").doesNotContain("http");
        assertThat(as(engineer,()->grants.requireReadable(engineer,id,1,CommissionDeliverableAccessPort.Slot.CONFIRMATION)).assetId()).isEqualTo(104);
        as(engineer,()->handovers.returnPacket(engineer,id,new HandoverService.DecisionCommand("交底需补充",false),key()));
        var newEngineer=fixture.post(store.orgId(),"engineer",store.engineeringTeam());
        as(sales,()->handovers.submit(sales,id,new HandoverService.PacketCommand(1,null,null,null,null,null,java.time.LocalDate.now(),"补充交底",newEngineer.userId(),true,104L),key()));
        assertError(()->as(engineer,()->grants.requireReadable(engineer,id,1,CommissionDeliverableAccessPort.Slot.RESULT)),COMMISSION_FORBIDDEN);
        assertThat(as(newEngineer,()->grants.requireReadable(newEngineer,id,1,CommissionDeliverableAccessPort.Slot.CONFIRMATION)).assetId()).isEqualTo(104);
        jdbc.update("UPDATE fr_commission SET dispatch_seq=NULL WHERE id=?",id);
        assertError(()->as(newEngineer,()->grants.requireReadable(newEngineer,id,1,CommissionDeliverableAccessPort.Slot.RESULT)),COMMISSION_FORBIDDEN);
        assertThat(jdbc.queryForObject("SELECT drawing_asset_id FROM fr_commission_dispatch_manifest WHERE commission_id=?",Long.class,id)).isEqualTo(102);
    }
    private static String key() { return UUID.randomUUID().toString(); }
    private <T> T as(PostSnapshot post,java.util.function.Supplier<T> action) { currentPost.use(post);try{return action.get();}finally{currentPost.clear();} }
}
