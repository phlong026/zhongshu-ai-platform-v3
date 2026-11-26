package cn.iocoder.yudao.module.franchise.finance;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.platform.command.CommandLogImpl;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@Import({FinPayrollService.class,FinProjectAccountingAccess.class,FinWorkbenchService.class,FinReceiptService.class,
    FinPaymentService.class,FinRefundService.class,FranchiseCommands.class,CommandLogImpl.class,FinRefundThreeDirectionIntegrationTest.Config.class})
class FinPayrollIntegrationTest extends FranchisePgIntegrationTest {
    @Resource FinPayrollService service;
    @Resource FinWorkbenchService workbench;
    @Resource FinPaymentService payments;
    @Resource FinRefundService refunds;
    @Resource JdbcTemplate jdbc;
    @Resource PlatformTransactionManager transactions;
    static final long TENANT=61L;
    PostSnapshot finance,sales,engineer;
    long project,contract,stage;
    @BeforeEach void prepare() {
        var store=fixture.store();finance=fixture.post(store.orgId(),"finance",null);
        sales=fixture.post(store.orgId(),"sales",store.salesTeamA());engineer=fixture.post(store.orgId(),"engineer",store.engineeringTeam());
        jdbc.execute("CREATE TABLE IF NOT EXISTS system_users(id bigint primary key,tenant_id bigint,username varchar(64),nickname varchar(64),password varchar(100),mobile varchar(32),status int default 0,deleted int2 default 0)");
        directory(finance);directory(sales);directory(engineer);actor(finance);
        jdbc.execute("CREATE TABLE IF NOT EXISTS fr_eng_project(id bigint primary key,company_id bigint,name text,status text,source_commission_id bigint,sale_contract_id bigint)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS fr_sale_contract(id bigint primary key,company_id bigint,status text,deleted boolean default false,signed_date date)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS fr_sale_payment_stage(id bigint primary key,company_id bigint,contract_id bigint,stage text,amount_cents bigint,status text,deleted boolean default false)");
        jdbc.execute("ALTER TABLE fr_sale_payment_stage ADD COLUMN IF NOT EXISTS label varchar(80)");
        jdbc.execute("ALTER TABLE fr_sale_payment_stage ADD COLUMN IF NOT EXISTS sequence int");
        jdbc.execute("ALTER TABLE fr_sale_payment_stage ADD COLUMN IF NOT EXISTS trigger_type varchar(32)");
        jdbc.execute("ALTER TABLE fr_sale_payment_stage ADD COLUMN IF NOT EXISTS trigger_node_index int");
        jdbc.execute("ALTER TABLE fr_sale_payment_stage ADD COLUMN IF NOT EXISTS plan_version int");
        jdbc.execute("CREATE TABLE IF NOT EXISTS fr_sale_stage_receipt(id bigint,company_id bigint,contract_id bigint,receipt_id bigint,stage_id bigint,amount_cents bigint)");
        jdbc.execute("ALTER TABLE fr_sale_stage_receipt ADD COLUMN IF NOT EXISTS amount_cents bigint");
        project=IdWorker.getId();contract=IdWorker.getId();stage=IdWorker.getId();
        jdbc.update("INSERT INTO fr_eng_project(id,company_id,name,status,sale_contract_id) VALUES(?,?,'人工核算工程','ACTIVE',?)",project,finance.orgId(),contract);
        jdbc.update("INSERT INTO fr_sale_contract(id,company_id,status) VALUES(?,?,'SIGNED')",contract,finance.orgId());
        jdbc.update("INSERT INTO fr_sale_payment_stage(id,company_id,contract_id,stage,label,sequence,trigger_type,trigger_node_index,plan_version,amount_cents,status) VALUES(?,?,?,'STAGE_CUSTOM','外墙验收',4,'NODE_COMPLETED',6,3,20000,'PENDING')",stage,finance.orgId(),contract);
    }
    @AfterEach void clearActor(){TenantContextHolder.clear();org.springframework.security.core.context.SecurityContextHolder.clearContext();}

    @Test void unconfiguredRulesAreExplicitAndManualRecordsDoNotCreatePayablesOrCash() {
        var context=service.context(finance,"2026-11");assertThat(context).containsEntry("manualOnly",true).containsEntry("canWrite",true);
        assertThat((List<?>)context.get("rules")).allSatisfy(r->assertThat(((Map<?,?>)r).get("status")).isEqualTo("UNCONFIGURED"));
        var result=service.saveRecord(finance,"manual",record(sales,"BUSINESS",0,null,300000,0,List.of()));
        var facts=facts(result);assertThat(facts).containsEntry("totalCents","300000").containsEntry("ruleStatus","UNCONFIGURED")
            .containsEntry("calculationMode","MANUAL_ONLY").containsEntry("cashFact",false);
        for(String table:List.of("fr_fin_payable","fr_fin_payment","fr_fin_receipt"))assertThat(jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE company_id=?",Long.class,finance.orgId())).isZero();
        assertThatThrownBy(()->service.saveRecord(finance,"not-confirmed",new FinPayrollService.RecordCommand(sales.userId(),"BUSINESS","2026-11",0,null,1,0,List.of(),"未经核对",false))).hasMessageContaining("人工核算");
    }
    @Test void payrollSettlementRequiresAnExplicitOriginalEvenForSameNamedRecipientAndMonth() {
        long salary=id(service.saveRecord(finance,"payroll-before-cash",record(sales,"BUSINESS",0,null,300000,0,List.of())));
        workbench.register(finance,"old-cash-with-same-name",new FinWorkbenchService.EntryCommand("company","expense",
            "十一月绩效薪酬","绩效薪酬",300000,LocalDate.now(),"本公司员工","公司银行账户",null,null));
        assertThat(service.get(finance,salary)).containsKey("settlement");
        assertThat(settlement(salary)).containsEntry("dueCents","300000").containsEntry("paidCents","0")
            .containsEntry("unpaidCents","300000").containsEntry("registrationCount",0L);
    }
    @SuppressWarnings("unchecked") private Map<String,Object> settlement(long salary) {
        return (Map<String,Object>)service.get(finance,salary).get("settlement");
    }
    private FinPayrollService.PayoutCommand payout(long count,long amount,String txn) {
        return new FinPayrollService.PayoutCommand(count,amount,LocalDate.now(),"财务公司账户",txn,null);
    }
    @Test void payrollPaymentUsesExactlyOneOriginalAcrossRetriesAndTracksRealRefundsVoidsAndVersions() {
        long salary=id(service.saveRecord(finance,"salary",record(sales,"BUSINESS",0,null,10000,0,List.of())));
        var command=payout(0,7500,"PAYROLL-ORIGINAL-"+salary);
        assertThat(service.paymentResult(finance,salary,"actual-cash")).containsEntry("state","NOT_REGISTERED");
        var first=service.registerPayment(finance,salary,"actual-cash",command);
        assertThat(service.paymentResult(finance,salary,"actual-cash")).isEqualTo(first);
        long payment=Long.parseLong(first.get("id"));
        assertThat(service.registerPayment(finance,salary,"actual-cash",command)).isEqualTo(first);
        assertThatThrownBy(()->service.registerPayment(finance,salary,"new-key-same-stale-form",command)).hasMessageContaining("刷新");
        assertThat(settlement(salary)).containsEntry("dueCents","10000").containsEntry("paidCents","7500")
            .containsEntry("unpaidCents","2500").containsEntry("registrationCount",1L).containsEntry("paymentCount",1L).containsEntry("latestPaymentId",Long.toString(payment));
        long payable=jdbc.queryForObject("SELECT payable_id FROM fr_fin_payment WHERE id=?",Long.class,payment);
        assertThat(jdbc.queryForMap("SELECT payroll_record_id,category,recipient FROM fr_fin_payable WHERE id=?",payable))
            .containsEntry("payroll_record_id",salary).containsEntry("category","绩效薪酬").containsEntry("recipient","本公司员工");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_payment WHERE company_id=?",Long.class,finance.orgId())).isOne();
        assertThatThrownBy(()->service.registerPayment(finance,salary,"new-with-old-bank-number",payout(1,1000,command.transactionNo()))).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThat(settlement(salary)).containsEntry("registrationCount",1L);
        long revised=id(service.saveRecord(finance,"correct-salary",record(sales,"BUSINESS",1,null,6000,0,List.of())));
        assertThat(settlement(salary)).containsEntry("currentRecordId",Long.toString(revised)).containsEntry("dueCents","6000").containsEntry("overpaidCents","1500");
        assertThat(facts(service.get(finance,salary))).containsEntry("totalCents","10000");
        assertThatThrownBy(()->service.registerPayment(finance,salary,"stale-version",payout(1,100,null))).hasMessageContaining("刷新");
        assertThat(service.registerPayment(finance,salary,"actual-cash",command)).isEqualTo(first);
        long refund=refunds.registerExpense(finance,"salary-return",new FinRefundService.ExpenseRefundCommand(payment,2000,"多发退回",LocalDate.now(),null,null,null));
        assertThat(settlement(revised)).containsEntry("paidCents","5500").containsEntry("refundedCents","2000").containsEntry("unpaidCents","500");
        var lead=fixture.post(finance.orgId(),"finance-lead",null);directory(lead);actor(lead);
        refunds.void_(lead,refund,"退回金额核对后更正");
        long replacement=refunds.registerExpense(lead,"corrected-return",new FinRefundService.ExpenseRefundCommand(payment,1500,"更正退回",LocalDate.now(),null,null,refund));
        actor(finance);assertThat(settlement(revised)).containsEntry("paidCents","6000").containsEntry("refundedCents","1500");
        actor(lead);refunds.void_(lead,replacement,"核对后作废");payments.void_(lead,payment,"原付款作废",null);
        actor(finance);assertThat(settlement(revised)).containsEntry("paidCents","0").containsEntry("unpaidCents","6000")
            .containsEntry("registrationCount",1L).containsEntry("paymentCount",0L);
        payments.register(finance,"replacement-original",new FinPaymentService.PaymentCommand(payable,5000,LocalDate.now(),null,null));
        assertThat(settlement(revised)).containsEntry("paidCents","5000").containsEntry("unpaidCents","1000");
    }
    @Test void payrollPaymentScopeUsesEmployeeFamilyAndPayrollMonthAndRequiresCurrentStoreFinance() {
        long original=id(service.saveRecord(finance,"april",record(sales,"BUSINESS",0,null,10000,0,List.of())));
        var command=payout(0,1000,null);
        var other=fixture.post(finance.orgId(),"sales",null);directory(other);
        long namesake=id(service.saveRecord(finance,"namesake",record(other,"BUSINESS",0,null,10000,0,List.of())));
        long prior=id(service.saveRecord(finance,"prior",new FinPayrollService.RecordCommand(sales.userId(),"BUSINESS","2026-10",0,null,10000,0,List.of(),"上月工资",true)));
        long dualPost=IdWorker.getId();
        jdbc.update("INSERT INTO pf_staff_post(id,user_id,org_id,role_code,status) VALUES(?,?,?,'engineer','ACTIVE')",dualPost,sales.userId(),finance.orgId());
        long differentFamily=id(service.saveRecord(finance,"dual-role",record(sales,"ENGINEERING",0,null,10000,0,List.of())));
        service.registerPayment(finance,original,"same-key",command);
        assertThatThrownBy(()->service.registerPayment(finance,namesake,"same-key",command)).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        for(long id:new long[]{namesake,prior,differentFamily})assertThat(settlement(id)).containsEntry("paidCents","0");
        var otherFinance=fixture.post(fixture.store().orgId(),"finance",null);directory(otherFinance);actor(otherFinance);
        assertThatThrownBy(()->service.registerPayment(otherFinance,original,"other-org",command)).hasMessageContaining("无权");
        var boss=fixture.post(finance.orgId(),"boss",null);directory(boss);actor(boss);
        assertThat(service.get(boss,original)).containsKey("settlement");
        assertThatThrownBy(()->service.registerPayment(boss,original,"boss",command)).hasMessageContaining("仅本公司财务");
        actor(finance);jdbc.update("UPDATE pf_staff_post SET status='REVOKED',revoked_at=now() WHERE id=?",finance.postId());
        assertThatThrownBy(()->service.registerPayment(finance,original,"same-key",command)).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
    }
    @Test void concurrentPayrollPaymentsFromTheSameFormWriteOneFinanceOriginal() throws Exception {
        long salary=id(service.saveRecord(finance,"concurrent-salary",record(sales,"BUSINESS",0,null,9000,0,List.of())));
        var barrier=new CyclicBarrier(2);var pool=Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> tasks=new ArrayList<>();
            for(int i=0;i<2;i++) { String key="original-"+i;tasks.add(pool.submit(()->{
                actor(finance);try {barrier.await(10,TimeUnit.SECONDS);service.registerPayment(finance,salary,key,payout(0,3000,null));return true;}
                catch(cn.iocoder.yudao.framework.common.exception.ServiceException e){assertThat(e.getCode()).isEqualTo(409);return false;}
                finally{clearActor();}
            })); }
            int success=0;for(var task:tasks)if(task.get(20,TimeUnit.SECONDS))success++;
            assertThat(success).isOne();assertThat(settlement(salary)).containsEntry("registrationCount",1L).containsEntry("paidCents","3000");
        }finally{pool.shutdownNow();}
    }
    @Test void payrollSourceCannotCrossCompaniesOrBeErasedByRollback() throws Exception {
        long salary=id(service.saveRecord(finance,"source-rollback",record(sales,"BUSINESS",0,null,10000,0,List.of())));
        long payment=Long.parseLong(service.registerPayment(finance,salary,"linked-payment",payout(0,1000,null)).get("id"));
        var second=fixture.store();var other=fixture.post(second.orgId(),"finance",null);var employee=fixture.post(second.orgId(),"sales",second.salesTeamA());
        directory(other);directory(employee);actor(other);
        long wrong=id(service.saveRecord(other,"other-company",record(employee,"BUSINESS",0,null,10000,0,List.of())));
        assertThatThrownBy(()->jdbc.update("UPDATE fr_fin_payable SET payroll_record_id=? WHERE id=(SELECT payable_id FROM fr_fin_payment WHERE id=?)",wrong,payment))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        String undo=new org.springframework.core.io.ClassPathResource("db/migration-undo/franchise/V20261126.1070__undo_fr_payroll_payment_source.sql")
            .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(()->jdbc.execute(undo)).rootCause().hasMessageContaining("Payroll payments are linked");
        actor(finance);assertThat(settlement(salary)).containsEntry("paidCents","1000");
    }
    @Test void effectiveRulesAndSalaryHistoryKeepTheirOriginalVersionAndRejectStaleReferences() {
        var v1=service.saveRule(finance,"rule1",rule("BUSINESS",0,"2026-01","管理层原规则"));
        long rule1=Long.parseLong((String)v1.get("id"));
        var first=service.saveRecord(finance,"salary1",record(sales,"BUSINESS",0,rule1,300000,0,List.of()));
        var v2=service.saveRule(finance,"rule2",rule("BUSINESS",1,"2026-11","管理层新规则"));
        long rule2=Long.parseLong((String)v2.get("id"));
        assertThat(((Map<?,?>)facts(service.get(finance,id(first))).get("rule")).get("id")).isEqualTo(Long.toString(rule1));
        assertThatThrownBy(()->service.saveRecord(finance,"stale-rule",record(sales,"BUSINESS",1,rule1,310000,0,List.of()))).hasMessageContaining("适用规则");
        var corrected=service.saveRecord(finance,"salary2",record(sales,"BUSINESS",1,rule2,310000,-1000,List.of()));
        assertThat(facts(corrected)).containsEntry("totalCents","309000");assertThat(corrected.get("version")).isEqualTo(2L);
        assertThat(service.records(finance,"2026-11","BUSINESS",1,20)).containsEntry("total",1L);
        assertThat(service.history(finance,id(first),1,20)).containsEntry("total",2L);
        assertThat(facts(first)).containsEntry("totalCents","300000");
        assertThatThrownBy(()->service.saveRecord(finance,"stale-record",record(sales,"BUSINESS",0,rule2,320000,0,List.of()))).hasMessageContaining("已有变化");
        assertThatThrownBy(()->jdbc.update("UPDATE fr_fin_payroll_rule SET commission_note='rewrite' WHERE id=?",rule1)).hasMessageContaining("immutable");
        assertThatThrownBy(()->jdbc.update("DELETE FROM fr_fin_payroll_record WHERE id=?",id(first))).hasMessageContaining("immutable");
    }
    @Test void dynamicStageReferencesSaveRealNetCollectionsAndDoNotInventEligibilityOrCommissionRates() {
        long receipt=workbench.register(finance,"receipt",new FinWorkbenchService.EntryCommand("project:"+project,"receipt","外墙验收回款","合同",10000,LocalDate.now(),"客户","公司账户",null,null));
        jdbc.update("INSERT INTO fr_sale_stage_receipt(id,company_id,contract_id,receipt_id,stage_id,amount_cents) VALUES(?,?,?,?,?,10000)",IdWorker.getId(),finance.orgId(),contract,receipt,stage);
        refunds.register(finance,"refund",new FinRefundService.RegisterCommand(receipt,2500,"部分退款",LocalDate.now(),null,null,null));
        var evidence=service.project(finance,project);
        @SuppressWarnings("unchecked") var stages=(List<Map<String,Object>>)evidence.get("stages");
        assertThat(stages).singleElement().satisfies(s->assertThat(s).containsEntry("stageId",Long.toString(stage)).containsEntry("label","外墙验收").containsEntry("netCollectedCents","7500"));
        var result=service.saveRecord(finance,"engineer",record(engineer,"ENGINEERING",0,null,250000,0,List.of(new FinPayrollService.CommissionLine(project,stage,12000,"管理层已人工确认的本月提成"))));
        assertThat(facts(result)).containsEntry("commissionCents","12000").containsEntry("totalCents","262000");
        @SuppressWarnings("unchecked") var lines=(List<Map<String,Object>>)facts(result).get("commissions");
        assertThat(((Map<?,?>)lines.get(0).get("stage")).get("netCollectedCents")).isEqualTo("7500");
        assertThat(((Map<?,?>)lines.get(0).get("stage")).get("nodeIndex")).isEqualTo(6);
        // 核算依据保留当时快照，不因以后退款或新规则静默改变历史金额。
        refunds.register(finance,"refund2",new FinRefundService.RegisterCommand(receipt,1000,"后续退款",LocalDate.now(),null,null,null));
        assertThat(facts(service.get(finance,id(result)))).isEqualTo(facts(result));
    }
    @Test void stageAndEmployeeReferencesCannotCrossCompanyOrJobFamily() {
        var other=fixture.post(fixture.store().orgId(),"sales",null);directory(other);
        assertThatThrownBy(()->service.saveRecord(finance,"other-user",record(other,"BUSINESS",0,null,100,0,List.of()))).hasMessageContaining("员工不属于");
        assertThatThrownBy(()->service.saveRecord(finance,"wrong-family",record(sales,"ENGINEERING",0,null,100,0,List.of()))).hasMessageContaining("员工不属于");
        assertThatThrownBy(()->service.saveRecord(finance,"wrong-stage",record(sales,"BUSINESS",0,null,100,0,List.of(new FinPayrollService.CommissionLine(project,stage+1000,100,"核对"))))).hasMessageContaining("付款节点");
        jdbc.update("UPDATE pf_staff_post SET status='REVOKED',revoked_at=now(),revoked_by=? WHERE id=?",finance.userId(),sales.postId());
        assertThatThrownBy(()->service.saveRecord(finance,"departed",record(sales,"BUSINESS",0,null,100,0,List.of()))).hasMessageContaining("员工不属于");
        jdbc.update("UPDATE pf_staff_post SET status='ACTIVE',revoked_at=NULL,revoked_by=NULL,role_code='engineer' WHERE id=?",sales.postId());
        assertThatThrownBy(()->service.saveRecord(finance,"transferred",record(sales,"BUSINESS",0,null,100,0,List.of()))).hasMessageContaining("员工不属于");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_payroll_record WHERE company_id=?",Long.class,finance.orgId())).isZero();
    }
    @Test void ruleAndRecordIdempotencyRejectsDifferentInputsAndNeverDuplicatesHistory() {
        var cmd=rule("BUSINESS",0,"2026-11","已确认");var saved=service.saveRule(finance,"rule-idem",cmd);
        assertThat(service.saveRule(finance,"rule-idem",cmd)).isEqualTo(saved);
        assertThatThrownBy(()->service.saveRule(finance,"rule-idem",rule("BUSINESS",0,"2026-10","改内容"))).isInstanceOf(RuntimeException.class);
        var salary=record(sales,"BUSINESS",0,id(saved),100,0,List.of());var first=service.saveRecord(finance,"salary-idem",salary);
        assertThat(service.saveRecord(finance,"salary-idem",salary)).isEqualTo(first);
        assertThatThrownBy(()->service.saveRecord(finance,"salary-idem",record(sales,"BUSINESS",0,id(saved),200,0,List.of()))).isInstanceOf(RuntimeException.class);
        assertThat(service.history(finance,id(first),1,20)).containsEntry("total",1L);
    }
    @Test void headquartersReadPortRequiresTheActualTenantAndActiveCompanyButExposesNoWrites() {
        var salary=service.saveRecord(finance,"record",record(sales,"BUSINESS",0,null,100,0,List.of()));
        assertThat(service.recordAuthorized(TENANT,finance.orgId(),id(salary))).isEqualTo(salary);
        assertThatThrownBy(()->service.recordAuthorized(TENANT+1,finance.orgId(),id(salary))).hasMessageContaining("当前岗位");
        var other=fixture.post(fixture.store().orgId(),"finance",null);directory(other);
        assertThatThrownBy(()->service.recordAuthorized(TENANT,other.orgId(),id(salary))).hasMessageContaining("无权查看");
        jdbc.update("UPDATE system_dept SET tenant_id=? WHERE id=?",TENANT+1,finance.orgId());
        assertThatThrownBy(()->service.recordsAuthorized(TENANT,finance.orgId(),null,null,1,20)).hasMessageContaining("当前岗位");
        jdbc.update("UPDATE system_dept SET tenant_id=?,status=1 WHERE id=?",TENANT,finance.orgId());
        assertThatThrownBy(()->service.context(finance,"2026-11")).hasMessageContaining("当前岗位");
    }
    @Test void bossAndOwnerReadOnlyAndCurrentRoleCannotBeForgedForWrites() {
        var boss=fixture.post(finance.orgId(),"boss",null);directory(boss);actor(boss);
        assertThat(service.context(boss,"2026-11")).containsEntry("canWrite",false);
        assertThatThrownBy(()->service.saveRule(boss,"boss",rule("BUSINESS",0,"2026-11","已确认"))).hasMessageContaining("仅本公司财务");
        var forged=new PostSnapshot(boss.postId(),boss.userId(),boss.orgId(),"store","finance",null,false,Set.of());currentPost.use(forged);
        assertThatThrownBy(()->service.saveRecord(forged,"forged",record(sales,"BUSINESS",0,null,1,0,List.of()))).hasMessageContaining("当前岗位");
        actor(finance);TenantContextHolder.setTenantId(TENANT+1);
        assertThatThrownBy(()->service.saveRecord(finance,"wrong-tenant",record(sales,"BUSINESS",0,null,1,0,List.of()))).hasMessageContaining("当前岗位");
    }
    @Test void malformedPeriodsAmountsAndUnconfirmedRulesFailWithoutInventedDefaults() {
        assertThatThrownBy(()->service.context(finance,"2026-13")).hasMessageContaining("YYYY-MM");
        assertThatThrownBy(()->service.saveRule(finance,"unknown",new FinPayrollService.RuleCommand("BUSINESS","2026-11",0,"底薪","考核","提成","未经管理层确认",false))).hasMessageContaining("管理层");
        assertThatThrownBy(()->service.saveRecord(finance,"negative",record(sales,"BUSINESS",0,null,1,-2,List.of()))).hasMessageContaining("合计");
        assertThatThrownBy(()->service.saveRecord(finance,"overflow",record(sales,"BUSINESS",0,null,1,Long.MIN_VALUE,List.of()))).hasMessageContaining("调整金额");
        assertThatThrownBy(()->service.records(finance,null,null,1,101)).hasMessageContaining("分页");
    }
    @Test void concurrentCorrectionsSerializeOnEmployeeMonthAndOnlyOneVersionCommits() throws Exception {
        var cmd=record(sales,"BUSINESS",0,null,100,0,List.of());var barrier=new CyclicBarrier(2);var pool=Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> tasks=new ArrayList<>();
            for(int i=0;i<2;i++){final String key="concurrent-"+i;tasks.add(pool.submit(()->{actor(finance);try{barrier.await(10,TimeUnit.SECONDS);service.saveRecord(finance,key,cmd);return true;}catch(cn.iocoder.yudao.framework.common.exception.ServiceException e){assertThat(e.getCode()).isEqualTo(409);return false;}finally{TenantContextHolder.clear();org.springframework.security.core.context.SecurityContextHolder.clearContext();}}));}
            int success=0;for(var task:tasks)if(task.get(20,TimeUnit.SECONDS))success++;assertThat(success).isEqualTo(1);
            assertThat(service.records(finance,"2026-11",null,1,20)).containsEntry("total",1L);
        }finally{pool.shutdownNow();}
    }
    @Test void ruleUpgradeWaitsForPayrollCommitAndOldRuleCannotBeUsedAfterwards() throws Exception {
        long firstRule=id(service.saveRule(finance,"initial-rule",rule("BUSINESS",0,"2026-11","原规则")));
        var saved=new CountDownLatch(1);var release=new CountDownLatch(1);var upgradeStarted=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(2);var tx=new TransactionTemplate(transactions);
        try {
            var payroll=pool.submit(()->{actor(finance);try{return tx.execute(status->{
                var row=service.saveRecord(finance,"salary-before-upgrade",record(sales,"BUSINESS",0,firstRule,100,0,List.of()));
                saved.countDown();await(release);return row;
            });}finally{clearActor();}});
            assertThat(saved.await(10,TimeUnit.SECONDS)).isTrue();
            var upgrade=pool.submit(()->{actor(finance);try{upgradeStarted.countDown();return service.saveRule(finance,"upgrade",rule("BUSINESS",1,"2026-11","新规则"));}finally{clearActor();}});
            assertThat(upgradeStarted.await(10,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->upgrade.get(500,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();var original=payroll.get(15,TimeUnit.SECONDS);var updated=upgrade.get(15,TimeUnit.SECONDS);
            assertThat(updated.get("version")).isEqualTo(2L);
            assertThat(((Map<?,?>)facts(service.get(finance,id(original))).get("rule")).get("id")).isEqualTo(Long.toString(firstRule));
            assertThatThrownBy(()->service.saveRecord(finance,"stale-after-upgrade",record(sales,"BUSINESS",1,firstRule,200,0,List.of()))).hasMessageContaining("适用规则");
        }finally{release.countDown();pool.shutdownNow();}
    }
    private static void await(CountDownLatch latch){try{if(!latch.await(10,TimeUnit.SECONDS))throw new IllegalStateException("transaction release timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
    @Test void rollbackPreservesBothImmutableHistoryAndCommandAuditAtomically() {
        var tx=new TransactionTemplate(transactions);
        assertThatThrownBy(()->tx.execute(status->{service.saveRecord(finance,"rollback",record(sales,"BUSINESS",0,null,100,0,List.of()));throw new IllegalStateException("rollback");})).hasMessageContaining("rollback");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_payroll_record WHERE company_id=?",Long.class,finance.orgId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_command_event WHERE org_id=? AND aggregate_type='fin-payroll-record'",Long.class,finance.orgId())).isZero();
    }

    private FinPayrollService.RuleCommand rule(String family,long version,String month,String note){return new FinPayrollService.RuleCommand(family,month,version,"按劳动约定逐人核对底薪","考核按管理层确认表","提成按项目实际约定人工核对",note,true);}
    private FinPayrollService.RecordCommand record(PostSnapshot person,String family,long version,Long rule,long base,long adjustment,List<FinPayrollService.CommissionLine> lines){return new FinPayrollService.RecordCommand(person.userId(),family,"2026-11",version,rule,base,adjustment,lines,"财务已逐项核对本月人工核算金额",true);}
    private static long id(Map<String,Object> row){return Long.parseLong(row.get("id").toString());}
    @SuppressWarnings("unchecked") private static Map<String,Object> facts(Map<String,Object> row){return (Map<String,Object>)row.get("snapshot");}
    private void directory(PostSnapshot post){jdbc.update("INSERT INTO system_dept(id,name,tenant_id,status,deleted) VALUES(?,'薪酬测试公司',?,0,0) ON CONFLICT(id) DO UPDATE SET tenant_id=EXCLUDED.tenant_id,status=0,deleted=0",post.orgId(),TENANT);jdbc.update("INSERT INTO system_users(id,tenant_id,username,nickname,password,status,deleted) VALUES(?,?,?,'本公司员工','unused',0,0) ON CONFLICT(id) DO UPDATE SET tenant_id=EXCLUDED.tenant_id,status=0,deleted=0",post.userId(),TENANT,"payroll-"+post.userId());}
    private void actor(PostSnapshot post){currentPost.use(post);TenantContextHolder.setTenantId(TENANT);org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(new cn.iocoder.yudao.framework.security.core.LoginUser().setId(post.userId()).setUserType(2).setTenantId(TENANT),null,List.of()));}
}
