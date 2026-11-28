package cn.iocoder.yudao.module.franchise.commission;

import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import cn.iocoder.yudao.module.franchise.commission.CommissionService.CompleteCommand;
import cn.iocoder.yudao.module.franchise.commission.CommissionService.DispatchCommand;
import cn.iocoder.yudao.module.franchise.commission.CommissionService.FeeDecisionCommand;
import cn.iocoder.yudao.module.franchise.commission.CommissionService.FeedbackCommand;
import cn.iocoder.yudao.module.franchise.commission.CommissionService.SubmitCommand;
import cn.iocoder.yudao.module.franchise.commission.HandoverService.DecisionCommand;
import cn.iocoder.yudao.module.franchise.commission.HandoverService.PacketCommand;
import cn.iocoder.yudao.module.franchise.commission.dal.CommissionDO;
import cn.iocoder.yudao.module.franchise.commission.dal.CommissionHandoverDO;
import cn.iocoder.yudao.module.franchise.commission.dal.CommissionProgressDO;
import cn.iocoder.yudao.module.franchise.commission.dal.CommissionSubmissionDO;
import cn.iocoder.yudao.module.franchise.commission.dal.DesignPolicyDO;
import cn.iocoder.yudao.module.franchise.crm.CustomerService;
import cn.iocoder.yudao.module.franchise.crm.CustomerService.SchemeSaveCommand;
import cn.iocoder.yudao.module.franchise.crm.LeadService;
import cn.iocoder.yudao.module.franchise.crm.LeadService.CaptureCommand;
import cn.iocoder.yudao.module.franchise.crm.LeadService.CaptureResult;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.franchise.test.FranchiseFixture.Store;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.platform.command.CommandLogImpl;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.workflow.ApprovalServiceImpl;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.STATE_VERSION_CONFLICT;
import static cn.iocoder.yudao.module.franchise.commission.enums.FranchiseCommissionErrorCodes.*;
import static cn.iocoder.yudao.module.franchise.org.FranchiseRoles.*;
import static cn.iocoder.yudao.module.franchise.test.FranchiseAssertions.assertError;
import static cn.iocoder.yudao.module.platform.workflow.enums.PlatformWorkflowErrorCodes.APPROVAL_SELF_FORBIDDEN;
import static org.assertj.core.api.Assertions.assertThat;

@Import({CommissionService.class, HandoverService.class, DesignPolicyService.class, CommissionAccess.class,
        cn.iocoder.yudao.module.franchise.engineering.content.EngineeringPortImpl.class,
        cn.iocoder.yudao.module.franchise.engineering.content.EngProjectService.class,
        cn.iocoder.yudao.module.franchise.sales.SaleContractQueryAdapter.class,
        CommissionOrgScope.class, FranchiseCommands.class, CommandLogImpl.class, ApprovalServiceImpl.class,
        CustomerService.class, LeadService.class, cn.iocoder.yudao.module.franchise.crm.CrmOrgScope.class})
class CommissionIntegrationTest extends CommissionPgIntegrationTest {
    private static final AtomicLong PHONES = new AtomicLong(15_000_000_000L + System.nanoTime() % 800_000_000L);
    private static final BigDecimal TOTAL = new BigDecimal("680000"), FIRST = new BigDecimal("408000");
    private static final String SCOPE = "方案、施工图与交底", RECIPIENT = "总部财务";
    @Resource private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Resource private LeadService leads;
    @Resource private CustomerService customers;
    @Resource private CommissionService commissions;
    @Resource private HandoverService handovers;
    @Resource private FranchiseCommands commands;
    private Store store;
    private PostSnapshot sales, boss, engineer, finance, otherSales;

    @BeforeEach void organization() {
        store=fixture.store();
        sales=fixture.post(store.orgId(),SALES,store.salesTeamA());
        boss=fixture.post(store.orgId(),BOSS,null);
        engineer=fixture.post(store.orgId(),ENGINEER,store.engineeringTeam());
        finance=fixture.post(store.orgId(),FINANCE_LEAD,null);
        var other=fixture.store();otherSales=fixture.post(other.orgId(),SALES,other.salesTeamA());
    }

    @Test void retiredSubmissionCannotCreateCommissionApprovalOrPayable() {
        long customer=customerWithBudget();
        var before=counts();
        for(var input:List.of(submit(TOTAL,FIRST),submit(new BigDecimal("750000"),FIRST),
                new SubmitCommand("完整需求",SCOPE,null,null,null,null,1,null,null,true,"AREA_FULL",1,null))) {
            assertError(()->as(sales,()->commissions.submit(sales,customer,input,commandKey())),LEGACY_COMMISSION_RETIRED);
        }
        assertThat(counts()).isEqualTo(before);
        assertThat(as(sales,()->commissions.context(sales,customer)).allowedActions()).doesNotContain("commission-submit");
    }

    @Test void originalSuccessfulSubmissionReplaysWithoutWritingAndStillChecksCustomerAndBody() {
        long customer=customerWithBudget();var input=submit(TOTAL,FIRST);String key=commandKey();
        long id=historical(customer,input,"DESIGNING");
        as(sales,()->{commands.record(sales,CommissionService.AGGREGATE,id,1,"commission-submit",input,key);return true;});
        var before=counts();
        var replay=as(sales,()->commissions.submit(sales,customer,input,key));
        assertThat(replay.commission().getId()).isEqualTo(id);
        assertThat(replay.submissions()).hasSize(1);
        assertThat(counts()).isEqualTo(before);
        assertError(()->as(sales,()->commissions.submit(sales,customer,submit(new BigDecimal("750000"),FIRST),key)),
                cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED);
        long another=customerWithBudget();
        assertError(()->as(sales,()->commissions.submit(sales,another,input,key)),
                cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED);
        assertError(()->as(otherSales,()->commissions.submit(otherSales,customer,input,key)),
                cn.iocoder.yudao.module.franchise.crm.enums.FranchiseCrmErrorCodes.CUSTOMER_NOT_FOUND);
    }

    @Test void retiredFeeAndDesignCommandsCannotCreateNewWorkOrFinancialSources() {
        long id=historical(customerWithBudget(),submit(TOTAL,FIRST),"FEE_PENDING");
        var before=counts();
        assertError(()->as(boss,()->commissions.feeDecide(boss,id,new FeeDecisionCommand(true,"同意旧费用"),commandKey())),LEGACY_COMMISSION_RETIRED);
        assertError(()->as(boss,()->commissions.feeDecide(boss,id,new FeeDecisionCommand(false,"退回"),commandKey())),LEGACY_COMMISSION_RETIRED);
        var retired=new PostSnapshot(701L,7L,fixture.headquarters(),"headquarters",DESIGN_ADMIN,null,false,java.util.Set.of());
        assertError(()->as(retired,()->commissions.complete(retired,id,new CompleteCommand("历史设计完成",true),commandKey())),LEGACY_COMMISSION_RETIRED);
        assertError(()->as(retired,()->commissions.dispatch(retired,id,new DispatchCommand("历史下发","业务",true),commandKey())),LEGACY_COMMISSION_RETIRED);
        assertError(()->as(sales,()->commissions.feedback(sales,id,new FeedbackCommand("新修改"),commandKey())),LEGACY_COMMISSION_RETIRED);
        assertThat(counts()).isEqualTo(before);
        assertThat(as(boss,()->commissions.get(boss,id)).commission().getStatus()).isEqualTo("FEE_PENDING");
    }

    @Test void historicalFeeAndSimulationCommandsReplayOnlyTheirExactCommittedBody() {
        long id=historical(customerWithBudget(),submit(TOTAL,FIRST),"DESIGNING");
        var fee=new FeeDecisionCommand(true,"原批准说明");String feeKey=commandKey();
        var simulation=new CommissionService.SimulatePayCommand("WECHAT",true);String simulationKey=commandKey();
        as(boss,()->{commands.record(boss,CommissionService.AGGREGATE,id,2,"commission-fee-decide",fee,feeKey);return true;});
        as(sales,()->{commands.record(sales,CommissionService.AGGREGATE,id,3,"commission-simulate-pay",simulation,simulationKey);return true;});
        var before=counts();
        assertThat(as(boss,()->commissions.feeDecide(boss,id,fee,feeKey)).commission().getId()).isEqualTo(id);
        assertThat(as(sales,()->commissions.simulatePay(sales,id,simulation,simulationKey)).commission().getId()).isEqualTo(id);
        assertError(()->as(boss,()->commissions.feeDecide(boss,id,new FeeDecisionCommand(false,"改后的说明"),feeKey)),
                cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED);
        assertError(()->as(sales,()->commissions.simulatePay(sales,id,new CommissionService.SimulatePayCommand("ALIPAY",true),simulationKey)),
                cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED);
        assertError(()->as(sales,()->commissions.simulatePay(sales,id,simulation,commandKey())),SIMULATION_FORBIDDEN);
        assertThat(counts()).isEqualTo(before);
    }

    @Test void legacySubmittedPacketCanReturnResubmitAndReceiveExactlyOneOriginalProject() {
        long customer=customerWithBudget(),id=historical(customer,submit(TOTAL,FIRST),"DISPATCHED");
        // Receiving a main project requires the customer's signed construction contract.
        long contract=cn.hutool.core.util.IdUtil.getSnowflakeNextId();
        jdbc.update("""
            INSERT INTO fr_sale_contract(id,company_id,customer_id,total_cents,first_cents,signed_date,status,revision,author_user_id,create_time,update_time)
            VALUES(?,?,?,68000000,40800000,CURRENT_DATE,'SIGNED',1,?,now(),now())
            """,contract,store.orgId(),customer,sales.userId());
        LegacyCommissionFixtures.dispatch(jdbc,id,1,"历史图纸V1",101,102,103);
        var packet=packet(engineer.userId());
        as(sales,()->handovers.submit(sales,id,packet,commandKey()));
        assertThat(as(engineer,()->commissions.get(engineer,id)).allowedActions()).containsExactly("packet-return","packet-receive");
        assertError(()->as(sales,()->handovers.returnPacket(sales,id,new DecisionCommand("非指定人",false),commandKey())),HANDOVER_DECISION_FORBIDDEN);
        as(engineer,()->handovers.returnPacket(engineer,id,new DecisionCommand("补充交底",false),commandKey()));
        as(sales,()->handovers.submit(sales,id,packet,commandKey()));
        String receive=commandKey();
        var accepted=as(engineer,()->handovers.receive(engineer,id,new DecisionCommand(null,true),receive));
        as(engineer,()->handovers.receive(engineer,id,new DecisionCommand(null,true),receive));
        assertThat(accepted.commission().getStatus()).isEqualTo("ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_eng_project WHERE source_commission_id=?",Long.class,id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT sale_contract_id FROM fr_eng_project WHERE source_commission_id=?",Long.class,id)).isEqualTo(contract);
        assertThat(as(sales,()->commissions.get(sales,id)).allowedActions()).isEmpty();
    }

    @Test void onlyExistingContinuationActionsAreAdvertisedForLegacyStates() {
        long id=historical(customerWithBudget(),submit(TOTAL,FIRST),"FEE_RETURNED");
        for(String state:List.of("FEE_PENDING","FEE_RETURNED","DESIGNING","DESIGN_COMPLETED","DISPATCHED","HANDOVER_RETURNED")) {
            jdbc.update("UPDATE fr_commission SET status=? WHERE id=?",state,id);
            var actions=as(sales,()->commissions.get(sales,id)).allowedActions();
            assertThat(actions).contains("external-entrust-convert");
            assertThat(actions).doesNotContain("commission-submit","fee-decide","design-complete","design-dispatch","commission-feedback","commission-simulate-pay");
        }
        jdbc.update("UPDATE fr_commission SET status='FEE_RETURNED' WHERE id=?",id);
        assertError(()->as(sales,()->commissions.submit(sales,jdbc.queryForObject("SELECT customer_id FROM fr_commission WHERE id=?",Long.class,id),submit(TOTAL,FIRST),commandKey())),LEGACY_COMMISSION_RETIRED);
    }

    @Test void unsettledHistoricalPacketCanStillBeReturnedButCannotCreateAProject() {
        long id=historical(customerWithBudget(),submit(TOTAL,FIRST),"DISPATCHED");
        LegacyCommissionFixtures.dispatch(jdbc,id,1,"历史图纸V1",101,102,103);
        as(sales,()->handovers.submit(sales,id,packet(engineer.userId()),commandKey()));
        jdbc.update("INSERT INTO fr_commission_simulated_order(commission_id,submission_version,company_id,design_org_id,order_number,amount_cents,status,channel,paid_by_user_id,paid_at) VALUES(?,1,?,?,?,68000000,'PAID','WECHAT',?,now())",
                id,store.orgId(),fixture.headquarters(),"OLD-"+id,sales.userId());
        assertThat(as(engineer,()->commissions.get(engineer,id)).allowedActions()).containsExactly("packet-return");
        var before=counts();
        assertError(()->as(engineer,()->handovers.receive(engineer,id,new DecisionCommand(null,true),commandKey())),HISTORICAL_SETTLEMENT_REQUIRED);
        assertThat(counts()).isEqualTo(before);
        var returned=as(engineer,()->handovers.returnPacket(engineer,id,new DecisionCommand("原资料待补充",false),commandKey()));
        assertThat(returned.commission().getStatus()).isEqualTo("HANDOVER_RETURNED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_eng_project WHERE source_commission_id=?",Long.class,id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_payable WHERE source_type='design' AND source_ref LIKE ?",Long.class,id+":%")).isZero();
    }

    @Test void historicalLegacySplitSettlementCreatesBothActualStagesAtomically() {
        long id=historical(customerWithBudget(),submit(TOTAL,FIRST),"DESIGNING");
        jdbc.update("""
            INSERT INTO fr_commission_simulated_order(commission_id,submission_version,company_id,design_org_id,
                order_number,amount_cents,status,channel,paid_by_user_id,paid_at)
            VALUES(?,1,?,?,?,68000000,'PAID','WECHAT',?,now())
            """,id,store.orgId(),fixture.headquarters(),"HIST-SPLIT-"+id,sales.userId());
        String key=commandKey();
        var command=new CommissionService.HistoricalSettlementCommand(1,"按历史合同核对首期与尾款，转入线下实收登记");
        as(finance,()->commissions.prepareHistoricalSettlement(finance,id,command,key));
        assertThat(jdbc.queryForObject("SELECT total_cents FROM fr_fin_payable WHERE source_type='design' AND source_ref=?",Long.class,id+":1:FIRST")).isEqualTo(40800000L);
        assertThat(jdbc.queryForObject("SELECT total_cents FROM fr_fin_payable WHERE source_type='design' AND source_ref=?",Long.class,id+":1:TAIL")).isEqualTo(27200000L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_payment m JOIN fr_fin_payable p ON p.id=m.payable_id WHERE p.source_ref IN (?,?)",Long.class,id+":1:FIRST",id+":1:TAIL")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_receipt WHERE source_ref IN (?,?)",Long.class,id+":1:FIRST",id+":1:TAIL")).isZero();
        var after=counts();
        as(finance,()->commissions.prepareHistoricalSettlement(finance,id,command,key));
        assertThat(counts()).isEqualTo(after);
        assertThat(as(finance,()->commissions.pendingHistoricalSettlements(finance))).noneMatch(c->c.getId()==id);
    }

    @Test void historicalReadsAndOriginalVersionProgressKeepExistingAccessChecks() {
        long id=historical(customerWithBudget(),submit(TOTAL,FIRST),"DESIGNING");
        assertError(()->as(otherSales,()->commissions.get(otherSales,id)),COMMISSION_NOT_FOUND);
        assertError(()->as(engineer,()->commissions.get(engineer,id)),COMMISSION_NOT_FOUND);
        var retired=new PostSnapshot(701L,7L,fixture.headquarters(),"headquarters",DESIGN_ADMIN,null,false,java.util.Set.of());
        assertThat(as(retired,()->commissions.get(retired,id)).submissions()).allSatisfy(s->{assertThat(s.getTotalCents()).isNull();assertThat(s.getRecipient()).isNull();});
        assertError(()->as(finance,()->commissions.markFirstReady(id,0,finance)),COMMISSION_STATE_INVALID);
        as(finance,()->commissions.markFirstReady(id,1,finance));as(finance,()->commissions.markFirstReady(id,1,finance));
    }

    @Test void commissionEntitiesAreTenantIgnored() {
        for(Class<?> entity:List.of(DesignPolicyDO.class,CommissionDO.class,CommissionSubmissionDO.class,CommissionProgressDO.class,CommissionHandoverDO.class))
            assertThat(entity.isAnnotationPresent(TenantIgnore.class)).as(entity.getSimpleName()).isTrue();
    }

    private long historical(long customer,SubmitCommand input,String status) {
        return LegacyCommissionFixtures.submission(jdbc,sales,customer,fixture.headquarters(),input,status);
    }
    private List<Long> counts() {
        return List.of("fr_commission","fr_commission_submission","fr_commission_progress","pf_approval","fr_fin_payable","pf_command_event")
                .stream().map(table->jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class)).toList();
    }
    private long customerWithBudget() {
        return as(sales,()->{
            long customer=leads.capture(sales,new CaptureCommand("历史客户",String.valueOf(PHONES.incrementAndGet()),"两层建房","测试"),null).customerId();
            customers.saveSchemeInternal(sales,customer,new SchemeSaveCommand("BUDGET","参考预算","{\"kind\":\"budget\"}",new BigDecimal("13"),new BigDecimal("16"),2,new BigDecimal("400"),68000000L,null));
            return customer;
        });
    }
    private static SubmitCommand submit(BigDecimal total,BigDecimal first) { return new SubmitCommand("历史需求",SCOPE,null,total,first,RECIPIENT,1,null,null,true); }
    private static PacketCommand packet(long manager) { return new PacketCommand(1,null,null,null,null,null,LocalDate.now(),"历史成果交底",manager,true,104L); }
    private <T>T as(PostSnapshot post,java.util.function.Supplier<T> action) {currentPost.use(post);try{return action.get();}finally{currentPost.clear();}}
    private void as(PostSnapshot post,Runnable action) {as(post,()->{action.run();return true;});}
}
