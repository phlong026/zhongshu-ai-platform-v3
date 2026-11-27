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
@Import({CommissionSimulatedPayments.class,CommissionService.class,CommissionAccess.class,CommissionQueryAdapter.class,CommissionManifestService.class,
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
    @Resource CommissionSimulatedPayments simulations;
    @Resource DesignFeeSettlementAdapter settlement;

    @org.junit.jupiter.api.AfterEach
    void resetSimulationScope() { simulations.configureSimulationOrganizations(""); }

    @Test void headquartersPaginationSearchAndCountsIncludeEveryVisibleCommission() {
        var store=fixture.store();var outside=fixture.store();
        var design=historicalDesigner(701L);
        var finance=fixture.post(design.orgId(),"finance",null);
        String prefix="page-"+UUID.randomUUID().toString().substring(0,8)+"-";
        var before=as(design,()->commissions.workbenchCounts(design));
        try {
            for(int i=1;i<=238;i++) {
                long id=cn.hutool.core.util.IdUtil.getSnowflakeNextId();
                jdbc.update("INSERT INTO fr_commission(id,company_id,design_org_id,customer_id,customer_name,status,submission_version) VALUES(?,?,?,?,?,?,1)",
                        id,store.orgId(),i==238?outside.orgId():design.orgId(),id,prefix+String.format("%03d",i),i%2==1?"DESIGNING":"DESIGN_COMPLETED");
            }
            long received=jdbc.queryForObject("SELECT id FROM fr_commission WHERE company_id=? AND customer_name=?",Long.class,store.orgId(),prefix+"001");
            jdbc.update("""
                    INSERT INTO fr_commission_handover(id,company_id,commission_id,version,dispatch_seq,result_file,drawing_file,
                      review_file,confirmation_file,drawing_version,confirmation_date,note,manager_user_id,status,submitted_by_user_id)
                    VALUES(?,?,?,1,1,'result','drawing','review','confirmation','v1',CURRENT_DATE,'历史接收',?,'ACCEPTED',?)
                    """,cn.hutool.core.util.IdUtil.getSnowflakeNextId(),store.orgId(),received,design.userId(),design.userId());
            var seen=new java.util.HashSet<Long>();
            var page=new cn.iocoder.yudao.framework.common.pojo.PageParam();page.setPageSize(20);
            for(int number=1;number<=12;number++) {
                page.setPageNo(number);var result=as(design,()->commissions.page(design,page,prefix,null));
                assertThat(result.getTotal()).isEqualTo(237);
                result.getList().forEach(row->assertThat(seen.add(row.getId())).isTrue());
            }
            assertThat(seen).hasSize(237);page.setPageNo(1);
            for(String suffix:java.util.List.of("101","237")) {
                var found=as(design,()->commissions.page(design,page,prefix+suffix,null));
                assertThat(found.getTotal()).isEqualTo(1);
                assertThat(found.getList().get(0).getCustomerName()).isEqualTo(prefix+suffix);
            }
            assertThat(as(design,()->commissions.page(design,page,prefix,"DESIGN_COMPLETED")).getTotal()).isEqualTo(118);
            assertThat(as(design,()->commissions.page(design,page,prefix,"DESIGNING,DESIGN_COMPLETED")).getTotal()).isEqualTo(237);
            var counts=as(design,()->commissions.workbenchCounts(design));
            assertThat(counts.get("DESIGNING")-before.getOrDefault("DESIGNING",0L)).isEqualTo(119);
            assertThat(counts.get("DESIGN_COMPLETED")-before.getOrDefault("DESIGN_COMPLETED",0L)).isEqualTo(118);
            assertError(()->commissions.workbenchCounts(finance),COMMISSION_FORBIDDEN);
            var storeBoss=fixture.post(store.orgId(),"boss",null);
            assertError(()->commissions.workbenchCounts(storeBoss),COMMISSION_FORBIDDEN);
            assertError(()->commissions.page(design,page,prefix,"DESIGNING) OR 1=1"),
                    cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants.BAD_REQUEST);
        } finally {
            jdbc.update("DELETE FROM fr_commission_handover WHERE company_id=?",store.orgId());
            jdbc.update("DELETE FROM fr_commission WHERE company_id=?",store.orgId());
        }
    }

    @Test @org.springframework.transaction.annotation.Transactional
    void recentHandlingUsesLatestEventsAcrossAllVisibleCommissions() throws Exception {
        var store=fixture.store();var outside=fixture.store();
        var design=historicalDesigner(701L);
        var colleague=historicalDesigner(702L);
        var finance=fixture.post(design.orgId(),"finance",null);
        String prefix="recent-"+UUID.randomUUID().toString().substring(0,8)+"-";
        var time=java.time.OffsetDateTime.parse("2099-11-22T10:00:00Z");
        var before=as(design,()->commissions.workbenchRecent(design));
        var ids=new java.util.ArrayList<Long>();
        for(int i=1;i<=238;i++) {
            long id=cn.hutool.core.util.IdUtil.getSnowflakeNextId();ids.add(id);
            jdbc.update("INSERT INTO fr_commission(id,company_id,design_org_id,customer_id,customer_name,status,submission_version,update_time) VALUES(?,?,?,?,?,'DISPATCHED',1,?)",
                    id,store.orgId(),i==238?outside.orgId():design.orgId(),id,prefix+i,time.minusSeconds(i));
        }
        var page=new cn.iocoder.yudao.framework.common.pojo.PageParam();page.setPageSize(100);
        assertThat(as(design,()->commissions.workbenchRecent(design))).isEqualTo(before);
        assertThat(as(design,()->commissions.page(design,page,prefix)).getList())
                .extracting(c->c.getId()).doesNotContain(ids.get(100),ids.get(236));

        // More recent commission updates and feedback do not move an older handling event to the front.
        recentEvent(store.orgId(),design.orgId(),ids.get(236),"COMPLETE",1,time,colleague);
        recentEvent(store.orgId(),design.orgId(),ids.get(236),"COMPLETE",2,time.plusDays(1),colleague);
        recentEvent(store.orgId(),design.orgId(),ids.get(236),"DISPATCH",1,time.plusDays(1),colleague);
        recentEvent(store.orgId(),design.orgId(),ids.get(100),"COMPLETE",1,time.plusDays(1),colleague);
        for(int i=3;i<=6;i++) recentEvent(store.orgId(),design.orgId(),ids.get(i-1),"COMPLETE",1,time.plusHours(10-i),design);
        recentEvent(store.orgId(),design.orgId(),ids.get(6),"FEEDBACK",1,time.plusDays(2),design);
        // Broken event ownership must not bypass the commission's own organization boundary.
        recentEvent(outside.orgId(),design.orgId(),ids.get(1),"COMPLETE",1,time.plusDays(2),design);
        recentEvent(store.orgId(),outside.orgId(),ids.get(1),"DISPATCH",1,time.plusDays(2),design);
        recentEvent(store.orgId(),design.orgId(),ids.get(237),"COMPLETE",1,time.plusDays(2),design);

        var recent=as(design,()->commissions.workbenchRecent(design));
        var expected=java.util.List.of(ids.get(100),ids.get(236),ids.get(2),ids.get(3),ids.get(4))
                .stream().map(String::valueOf).toList();
        assertThat(recent).extracting(CommissionService.RecentHandling::commissionId).containsExactlyElementsOf(expected);
        assertThat(recent.get(0).customerName()).isEqualTo(prefix+"101");
        assertThat(recent.get(1).kind()).isEqualTo("DISPATCH");
        assertThat(java.time.OffsetDateTime.parse(recent.get(1).handledAt())).isEqualTo(time.plusDays(1));
        assertThat(as(design,()->commissions.workbenchRecent(design))).isEqualTo(recent);
        assertError(()->as(finance,()->commissions.workbenchRecent(finance)),COMMISSION_FORBIDDEN);
        var boss=fixture.post(store.orgId(),"boss",null);
        assertError(()->as(boss,()->commissions.workbenchRecent(boss)),COMMISSION_FORBIDDEN);

        var controller=new cn.iocoder.yudao.module.franchise.commission.controller.admin.CommissionController(commissions,handovers,currentPost);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        currentPost.use(design);
        try {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/franchise/commissions/workbench-recent"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.length()").value(5))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data[0].commissionId").value(expected.get(0)))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data[0].kind").value("COMPLETE"));
        } finally { currentPost.clear(); }
    }

    private void recentEvent(long company,long designOrg,long commission,String kind,int seq,
                             java.time.OffsetDateTime time,PostSnapshot actor) {
        jdbc.update("""
                INSERT INTO fr_commission_progress(id,company_id,design_org_id,commission_id,kind,seq,note,
                  feedback_status,actor_user_id,actor_post_id,create_time) VALUES(?,?,?,?,?,?,'最近办理测试',?,?,?,?)
                """,cn.hutool.core.util.IdUtil.getSnowflakeNextId(),company,designOrg,commission,kind,seq,
                "FEEDBACK".equals(kind)?"PENDING":null,actor.userId(),actor.postId(),time);
    }

    @Test @org.springframework.transaction.annotation.Transactional
    void historicalAreaFeeKeepsFrozenPriceAndStillRequiresBothActualCashNets() {
        var store=fixture.store();var sales=fixture.post(store.orgId(),"sales",store.salesTeamA());
        var design=historicalDesigner(701L);long customer=createCustomer(sales);
        var budget=as(sales,()->customers.saveScheme(sales,customer,new CustomerService.SchemeCommand("BUDGET","预算","{}",new BigDecimal("10"),new BigDecimal("10"),2,new BigDecimal("200.25"),10000L),null));
        var policy=seedPricing(101);
        var input=new CommissionService.SubmitCommand("完整需求","方案图纸",null,null,null,null,budget.getVersion(),null,null,true,"AREA_FULL",policy.version(),null);
        long id=LegacyCommissionFixtures.submission(jdbc,sales,customer,design.orgId(),input,"DESIGN_COMPLETED");
        as(sales,()->{settlement.ensureInitialPayable(id,1,sales);return true;});
        var original=as(sales,()->commissions.get(sales,id));
        assertThat(original.submissions().get(0).getTotalCents()).isEqualTo(20225);
        assertThat(original.submissions().get(0).getFirstCents()).isZero();
        var hq=as(design,()->commissions.get(design,id));
        assertThat(hq.submissions().get(0).getRateCents()).isNull();assertThat(hq.submissions().get(0).getPricingVersion()).isNull();
        var finance=fixture.post(store.orgId(),"finance",null);
        as(finance,()->{commissions.markFirstReady(id,1,finance);return true;});
        assertThat(as(sales,()->commissions.readyToDispatch(original.commission()))).isFalse();
        long payable=jdbc.queryForObject("SELECT id FROM fr_fin_payable WHERE source_ref=?",Long.class,id+":1:FULL");
        long payment=cn.hutool.core.util.IdUtil.getSnowflakeNextId();
        jdbc.update("INSERT INTO fr_fin_payment(id,payable_id,company_id,amount_cents,status,operator_user_id) VALUES(?,?,?,20225,'已核实',?)",payment,payable,store.orgId(),sales.userId());
        assertThat(as(sales,()->commissions.readyToDispatch(original.commission()))).isFalse();
        long receipt=cn.hutool.core.util.IdUtil.getSnowflakeNextId();
        jdbc.update("INSERT INTO fr_fin_receipt(id,company_id,source_type,source_ref,origin_payment_id,name,amount_cents,status,registrar_user_id) VALUES(?,?,'design',?,?,?,20225,'已登记',?)",receipt,design.orgId(),id+":1:FULL",payment,"设计实收",design.userId());
        assertThat(as(sales,()->commissions.readyToDispatch(original.commission()))).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_payable WHERE source_type='design' AND source_ref LIKE ?",Long.class,id+":1:%")).isEqualTo(1);
        assertThat(as(design,()->commissions.get(design,id)).allowedActions()).doesNotContain("design-complete","design-dispatch");
        jdbc.update("INSERT INTO fr_fin_refund(id,receipt_id,company_id,amount_cents,status,reason,operator_user_id) VALUES(?,?,?,1,'已核实','测试退款',?)",cn.hutool.core.util.IdUtil.getSnowflakeNextId(),receipt,design.orgId(),design.userId());
        assertThat(as(sales,()->commissions.readyToDispatch(original.commission()))).isFalse();
        assertThat(as(sales,()->commissions.get(sales,id)).commission().getStatus()).isEqualTo("DESIGN_COMPLETED");
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
        var design=historicalDesigner(701L);
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
        assertError(()->as(design,()->commissions.dispatch(design,id,dispatch,key())),LEGACY_COMMISSION_RETIRED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_commission_dispatch_manifest WHERE commission_id=?",Long.class,id)).isZero();
        org.mockito.Mockito.when(assets.validateReference(org.mockito.ArgumentMatchers.any(AssetAccessPort.Actor.class),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anySet())).thenReturn(true);
        LegacyCommissionFixtures.dispatch(jdbc,id,1,"DRAW-V1",101,102,103);
        var dispatched=as(design,()->commissions.get(design,id));
        assertThat(dispatched.dispatches()).hasSize(1);
        assertThat(as(sales,()->grants.requireReadable(sales,id,1,CommissionDeliverableAccessPort.Slot.RESULT)).assetId()).isEqualTo(101);
        assertError(()->as(outsider,()->grants.requireReadable(outsider,id,1,CommissionDeliverableAccessPort.Slot.RESULT)),COMMISSION_NOT_FOUND);
        assertError(()->as(engineer,()->grants.requireReadable(engineer,id,1,CommissionDeliverableAccessPort.Slot.RESULT)),COMMISSION_FORBIDDEN);
        jdbc.update("UPDATE fr_commission SET status='DESIGNING',dispatch_seq=NULL WHERE id=?",id);
        assertThat(as(sales,()->grants.requireReadable(sales,id,1,CommissionDeliverableAccessPort.Slot.RESULT)).assetId()).isEqualTo(101);
        assertThat(as(design,()->grants.requireReadable(design,id,1,CommissionDeliverableAccessPort.Slot.REVIEW)).assetId()).isEqualTo(103);
        assertError(()->as(engineer,()->grants.requireReadable(engineer,id,1,CommissionDeliverableAccessPort.Slot.RESULT)),COMMISSION_FORBIDDEN);
        LegacyCommissionFixtures.dispatch(jdbc,id,2,"DRAW-V2",201,202,203);
        assertThat(as(sales,()->grants.requireReadable(sales,id,1,CommissionDeliverableAccessPort.Slot.DRAWING)).assetId()).isEqualTo(102);
        assertThat(as(design,()->grants.requireReadable(design,id,1,CommissionDeliverableAccessPort.Slot.REVIEW)).assetId()).isEqualTo(103);
        assertError(()->as(outsider,()->grants.requireReadable(outsider,id,1,CommissionDeliverableAccessPort.Slot.RESULT)),COMMISSION_NOT_FOUND);
        assertError(()->as(sales,()->grants.requireReadable(sales,id,999,CommissionDeliverableAccessPort.Slot.RESULT)),COMMISSION_FORBIDDEN);
        assertError(()->as(engineer,()->grants.requireReadable(engineer,id,1,CommissionDeliverableAccessPort.Slot.RESULT)),COMMISSION_FORBIDDEN);
        assertError(()->as(sales,()->handovers.submit(sales,id,new HandoverService.PacketCommand(2,null,null,null,null,null,java.time.LocalDate.now(),"交底",sales.userId(),true,104L),key())),HANDOVER_TARGET_INVALID);
        var packet=as(sales,()->handovers.submit(sales,id,new HandoverService.PacketCommand(2,null,null,null,null,null,java.time.LocalDate.now(),"交底",engineer.userId(),true,104L),key()));
        assertThat(packet.handovers().get(0).getDrawingFile()).contains("202").doesNotContain("http");
        assertThat(as(engineer,()->grants.requireReadable(engineer,id,2,CommissionDeliverableAccessPort.Slot.CONFIRMATION)).assetId()).isEqualTo(104);
        as(engineer,()->handovers.returnPacket(engineer,id,new HandoverService.DecisionCommand("交底需补充",false),key()));
        var newEngineer=fixture.post(store.orgId(),"engineer",store.engineeringTeam());
        as(sales,()->handovers.submit(sales,id,new HandoverService.PacketCommand(2,null,null,null,null,null,java.time.LocalDate.now(),"补充交底",newEngineer.userId(),true,104L),key()));
        assertError(()->as(engineer,()->grants.requireReadable(engineer,id,2,CommissionDeliverableAccessPort.Slot.RESULT)),COMMISSION_FORBIDDEN);
        assertThat(as(newEngineer,()->grants.requireReadable(newEngineer,id,2,CommissionDeliverableAccessPort.Slot.CONFIRMATION)).assetId()).isEqualTo(104);
        assertError(()->as(newEngineer,()->grants.requireReadable(newEngineer,id,1,CommissionDeliverableAccessPort.Slot.CONFIRMATION)),COMMISSION_FORBIDDEN);
        jdbc.update("UPDATE fr_commission SET dispatch_seq=NULL WHERE id=?",id);
        assertError(()->as(newEngineer,()->grants.requireReadable(newEngineer,id,2,CommissionDeliverableAccessPort.Slot.RESULT)),COMMISSION_FORBIDDEN);
        assertThat(as(sales,()->grants.requireReadable(sales,id,1,CommissionDeliverableAccessPort.Slot.DRAWING)).assetId()).isEqualTo(102);
        assertThat(jdbc.queryForObject("SELECT drawing_asset_id FROM fr_commission_dispatch_manifest WHERE commission_id=? AND dispatch_seq=1",Long.class,id)).isEqualTo(102);
    }
    @Test void retiredSimulationCannotBeReenabledAndHistoryNeverBecomesRealCash() {
        var store=fixture.store();var sales=fixture.post(store.orgId(),"sales",store.salesTeamA());
        var design=historicalDesigner(701L);
        var finance=fixture.post(store.orgId(),"finance",null);var hqFinance=fixture.post(design.orgId(),"finance",null);
        var outsider=fixture.post(fixture.store().orgId(),"finance",null);
        // The headquarters fixture is shared with other tests; preserve its pre-existing real receipts.
        long receiptsBefore=jdbc.queryForObject("SELECT count(*) FROM fr_fin_receipt WHERE company_id IN (?,?)",Long.class,store.orgId(),design.orgId());
        long customer=createCustomer(sales);var budget=saveBudget(sales,customer);
        var policy=seedPricing(100);
        var simulation=new CommissionService.SubmitCommand("完整需求","方案图纸",null,null,null,null,budget.getVersion(),null,null,true,"AREA_FULL",policy.version(),null,null,"TEST_SIMULATION");
        for(String config:java.util.List.of("",Long.toString(store.orgId()),store.orgId()+","+design.orgId())) {
            simulations.configureSimulationOrganizations(config);
            assertThat(as(sales,()->commissions.context(sales,customer)).feeOffer().simulationPaymentMode()).isNull();
            assertError(()->as(sales,()->commissions.submit(sales,customer,simulation,key())),LEGACY_COMMISSION_RETIRED);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_commission WHERE customer_id=?",Long.class,customer)).isZero();
        var formal=new CommissionService.SubmitCommand("完整需求","方案图纸",null,null,null,null,budget.getVersion(),null,null,true,"AREA_FULL",policy.version(),null,null,null);
        long id=LegacyCommissionFixtures.submission(jdbc,sales,customer,design.orgId(),formal,"DESIGNING");
        var row=as(sales,()->commissions.get(sales,id)).commission();
        assertError(()->simulations.create(row,20000),SIMULATION_FORBIDDEN);
        seedHistoricalSimulation(id,store.orgId(),design.orgId(),sales.userId());
        assertError(()->as(sales,()->commissions.simulatePay(sales,id,new CommissionService.SimulatePayCommand("WECHAT",true),key())),SIMULATION_FORBIDDEN);
        assertError(()->simulations.pay(row,"ALIPAY",sales),SIMULATION_FORBIDDEN);
        assertThat(as(finance,()->commissions.paymentReceipt(finance,id)).paid()).isTrue();
        assertThat(as(hqFinance,()->commissions.paymentReceipt(hqFinance,id)).paid()).isTrue();
        assertError(()->as(outsider,()->commissions.paymentReceipt(outsider,id)),COMMISSION_NOT_FOUND);
        assertThat(as(sales,()->commissions.get(sales,id)).allowedActions()).doesNotContain("commission-simulate-pay");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_payment WHERE company_id=?",Long.class,store.orgId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_receipt WHERE company_id IN (?,?)",Long.class,store.orgId(),design.orgId())).isEqualTo(receiptsBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_commission_simulated_order WHERE commission_id=?",Long.class,id)).isEqualTo(1);
    }
    @Test void historicalDeliveryRequiresPerOrderRealSettlementBeforeAccessAndHandover() {
        var store=fixture.store();
        var sales=fixture.post(store.orgId(),"sales",store.salesTeamA());
        var design=historicalDesigner(701L);
        var engineer=fixture.post(store.orgId(),"engineer",store.engineeringTeam());
        var finance=fixture.post(store.orgId(),"finance-lead",null);
        var outsider=fixture.post(fixture.store().orgId(),"finance-lead",null);
        long customer=createCustomer(sales);
        var budget=saveBudget(sales,customer);
        var policy=seedPricing(100);
        var input=new CommissionService.SubmitCommand("完整需求","方案图纸",null,null,null,null,
                budget.getVersion(),null,null,true,"AREA_FULL",policy.version(),null,null,null);
        long id=LegacyCommissionFixtures.submission(jdbc,sales,customer,design.orgId(),input,"DISPATCHED");
        // Import old immutable facts directly; a retired writer must not be reopened to create test history.
        seedHistoricalSimulation(id,store.orgId(),design.orgId(),sales.userId());
        jdbc.update("UPDATE fr_commission SET status='DISPATCHED',dispatch_seq=1,dispatched_submission_version=1 WHERE id=?",id);
        for(String kind:java.util.List.of("COMPLETE","DISPATCH")) jdbc.update("INSERT INTO fr_commission_progress(id,company_id,design_org_id,commission_id,kind,seq,note,recipient,actor_user_id,actor_post_id) VALUES(?,?,?,?,?,1,'历史登记','业务',?,?)",
                cn.hutool.core.util.IdUtil.getSnowflakeNextId(),store.orgId(),design.orgId(),id,kind,design.userId(),design.postId());
        jdbc.update("INSERT INTO fr_commission_dispatch_manifest(id,commission_id,dispatch_seq,submission_version,design_org_id,result_asset_id,drawing_asset_id,review_asset_id,drawing_version,actor_user_id,actor_post_id) VALUES(?,?,1,1,?,101,102,103,'V1',?,?)",
                cn.hutool.core.util.IdUtil.getSnowflakeNextId(),id,design.orgId(),design.userId(),design.postId());
        org.mockito.Mockito.when(assets.validateReference(org.mockito.ArgumentMatchers.any(AssetAccessPort.Actor.class),
                org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anySet())).thenReturn(true);

        var packet=new HandoverService.PacketCommand(1,null,null,null,null,null,java.time.LocalDate.now(),
                "交底",engineer.userId(),true,104L);
        assertError(()->as(sales,()->grants.requireReadable(sales,id,1,CommissionDeliverableAccessPort.Slot.RESULT)),
                HISTORICAL_SETTLEMENT_REQUIRED);
        assertError(()->as(sales,()->handovers.submit(sales,id,packet,key())),HISTORICAL_SETTLEMENT_REQUIRED);
        var review=new CommissionService.HistoricalSettlementCommand(1,"核对历史下发版本与付款凭据");
        assertError(()->as(outsider,()->{commissions.prepareHistoricalSettlement(outsider,id,review,key());return null;}),
                COMMISSION_FORBIDDEN);
        assertError(()->as(sales,()->{commissions.prepareHistoricalSettlement(sales,id,review,key());return null;}),
                COMMISSION_FORBIDDEN);
        assertError(()->as(finance,()->{commissions.prepareHistoricalSettlement(finance,id,
                new CommissionService.HistoricalSettlementCommand(2,"旧版本"),key());return null;}),COMMISSION_STATE_INVALID);
        String reviewKey=key();
        for (int i=0;i<2;i++) as(finance,()->{commissions.prepareHistoricalSettlement(finance,id,review,reviewKey);return null;});
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM pf_command_event WHERE aggregate_type='commission-historical-settlement'
              AND aggregate_id=? AND command='commission-historical-settlement-prepare'
            """,Long.class,id)).isEqualTo(1L);
        String ref=id+":1:FULL";
        long payable=jdbc.queryForObject("SELECT id FROM fr_fin_payable WHERE source_type='design' AND source_ref=?",Long.class,ref);
        long payment=cn.hutool.core.util.IdUtil.getSnowflakeNextId();
        jdbc.update("INSERT INTO fr_fin_payment(id,payable_id,company_id,amount_cents,status,operator_user_id) VALUES(?,?,?,20000,'已核实',?)",
                payment,payable,store.orgId(),finance.userId());
        assertError(()->as(sales,()->grants.requireReadable(sales,id,1,CommissionDeliverableAccessPort.Slot.RESULT)),
                HISTORICAL_SETTLEMENT_REQUIRED);
        jdbc.update("""
            INSERT INTO fr_fin_receipt(id,company_id,source_type,source_ref,origin_payment_id,name,amount_cents,status,registrar_user_id)
            VALUES(?,?,'design',?,?,'核收历史真实付款',20000,'已登记',?)
            """,cn.hutool.core.util.IdUtil.getSnowflakeNextId(),design.orgId(),ref,payment,design.userId());
        // Simulate existing real cash facts recorded before there was a per-order review command.
        jdbc.update("DELETE FROM pf_command_event WHERE aggregate_type='commission-historical-settlement' AND aggregate_id=?",id);
        assertError(()->as(sales,()->grants.requireReadable(sales,id,1,CommissionDeliverableAccessPort.Slot.RESULT)),
                HISTORICAL_SETTLEMENT_REQUIRED);
        assertError(()->as(sales,()->handovers.submit(sales,id,packet,key())),HISTORICAL_SETTLEMENT_REQUIRED);
        as(finance,()->{commissions.prepareHistoricalSettlement(finance,id,review,key());return null;});
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_payable WHERE source_type='design' AND source_ref=?",Long.class,ref)).isEqualTo(1L);
        assertThat(as(sales,()->grants.requireReadable(sales,id,1,CommissionDeliverableAccessPort.Slot.RESULT)).assetId()).isEqualTo(101L);
        assertThat(as(sales,()->handovers.submit(sales,id,packet,key())).commission().getStatus()).isEqualTo("HANDOVER_SUBMITTED");
        assertThat(as(engineer,()->commissions.get(engineer,id)).allowedActions()).containsExactly("packet-return","packet-receive");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_commission_dispatch_manifest WHERE commission_id=?",Long.class,id)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT status FROM fr_commission_simulated_order WHERE commission_id=?",String.class,id)).isEqualTo("PAID");
    }

    private PostSnapshot historicalDesigner(long identity) {
        return new PostSnapshot(identity,identity,fixture.headquarters(),"headquarters","hq-design-admin",null,false,java.util.Set.of());
    }
    private DesignPricingService.Policy seedPricing(long rate) {
        long headquarters=fixture.headquarters();
        int version=jdbc.queryForObject("SELECT COALESCE(MAX(version),0)+1 FROM fr_design_pricing_policy WHERE design_org_id=?",Integer.class,headquarters);
        jdbc.update("INSERT INTO fr_design_pricing_policy(id,design_org_id,version,rate_cents,scope,recipient,active,actor_user_id,actor_post_id) VALUES(?,?,?,?,'方案图纸','总部设计',true,1,1)",cn.hutool.core.util.IdUtil.getSnowflakeNextId(),headquarters,version,rate);
        return pricing.current(headquarters);
    }

    private void seedHistoricalSimulation(long commission,long company,long design,long user) {
        jdbc.update("INSERT INTO fr_commission_simulated_order(commission_id,submission_version,company_id,design_org_id,order_number,amount_cents,status,channel,paid_by_user_id,paid_at) VALUES(?,1,?,?,?,20000,'PAID','WECHAT',?,now())",commission,company,design,"SIM-OLD-"+commission,user);
    }

    private long createCustomer(PostSnapshot sales) {
        return as(sales,()->leads.capture(sales,new LeadService.CaptureCommand("历史兼容验收",
                String.valueOf(15000000000L+Math.abs(UUID.randomUUID().getLeastSignificantBits()%800000000L)),
                "建房","验收"),null).customerId());
    }

    private cn.iocoder.yudao.module.franchise.crm.dal.CustomerSchemeDO saveBudget(PostSnapshot sales,long customer) {
        return as(sales,()->customers.saveScheme(sales,customer,new CustomerService.SchemeCommand(
                "BUDGET","预算","{}",BigDecimal.TEN,BigDecimal.TEN,2,new BigDecimal("200"),123456L),null));
    }

    private static String key() { return UUID.randomUUID().toString(); }
    private <T> T as(PostSnapshot post,java.util.function.Supplier<T> action) { currentPost.use(post);try{return action.get();}finally{currentPost.clear();} }
}
