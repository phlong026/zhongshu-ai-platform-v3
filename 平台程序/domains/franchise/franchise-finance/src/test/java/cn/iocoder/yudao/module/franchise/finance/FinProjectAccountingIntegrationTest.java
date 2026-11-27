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

@Import({FinProjectAccountingService.class,FinProjectAccountingAccess.class,FinWorkbenchService.class,FinReceiptService.class,
    FinPaymentService.class,FinRefundService.class,FranchiseCommands.class,CommandLogImpl.class,
    FinRefundThreeDirectionIntegrationTest.Config.class})
class FinProjectAccountingIntegrationTest extends FranchisePgIntegrationTest {
    @Resource FinProjectAccountingService accounting;
    @Resource FinWorkbenchService workbench;
    @Resource FinPaymentService payments;
    @Resource FinRefundService refunds;
    @Resource JdbcTemplate jdbc;
    @Resource PlatformTransactionManager transactions;
    PostSnapshot finance;
    long project,contract,commission;
    private static final long TENANT=41L;
    @AfterEach void clearActor(){TenantContextHolder.clear();org.springframework.security.core.context.SecurityContextHolder.clearContext();}

    @BeforeEach void prepare() {
        finance=fixture.post(fixture.store().orgId(),"finance",null);
        jdbc.execute("CREATE TABLE IF NOT EXISTS system_users(id bigint primary key,tenant_id bigint,username varchar(64),nickname varchar(64),password varchar(100),mobile varchar(32),status int default 0,deleted int2 default 0)");
        directory(finance);actor(finance);
        // 财务模块单独运行的真实 PG 契约夹具；业务全量启动时由各领域迁移创建这些表。
        jdbc.execute("CREATE TABLE IF NOT EXISTS fr_eng_project(id bigint primary key,company_id bigint,name text,status text,source_commission_id bigint,sale_contract_id bigint)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS fr_sale_contract(id bigint primary key,company_id bigint,status text,deleted boolean default false,signed_date date)");
        jdbc.execute("ALTER TABLE fr_sale_contract ADD COLUMN IF NOT EXISTS total_cents bigint");
        jdbc.execute("ALTER TABLE fr_sale_contract ADD COLUMN IF NOT EXISTS revision bigint DEFAULT 1");
        jdbc.execute("CREATE TABLE IF NOT EXISTS fr_sale_payment_stage(id bigint primary key,company_id bigint,contract_id bigint,stage text,amount_cents bigint,status text,deleted boolean default false)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS fr_sale_stage_receipt(id bigint,company_id bigint,contract_id bigint,receipt_id bigint,stage_id bigint)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS fr_eng_closure(id bigint primary key,company_id bigint,project_id bigint,settled_at date)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS fr_eng_collection(company_id bigint,project_id bigint,receipt_id bigint)");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS fr_eng_expense(
              id bigint PRIMARY KEY,company_id bigint NOT NULL,project_id bigint NOT NULL,name varchar(100) NOT NULL,
              purpose varchar(32) NOT NULL,amount_cents bigint NOT NULL,payable_id bigint,status varchar(16) NOT NULL DEFAULT 'REGISTERED',
              note varchar(500),author_user_id bigint NOT NULL,create_time timestamptz NOT NULL DEFAULT now(),update_time timestamptz NOT NULL DEFAULT now())
            """);
        project=IdWorker.getId();contract=IdWorker.getId();commission=IdWorker.getId();
        jdbc.update("INSERT INTO fr_sale_contract(id,company_id,status,signed_date,total_cents) VALUES(?,?,'SIGNED',?,100000)",contract,finance.orgId(),LocalDate.now());
        jdbc.update("INSERT INTO fr_eng_project(id,company_id,name,status,source_commission_id,sale_contract_id) VALUES(?,?,'项目核算','SETTLED',?,?)",project,finance.orgId(),commission,contract);
        jdbc.update("INSERT INTO fr_eng_closure(id,company_id,project_id,settled_at) VALUES(?,?,?,?)",IdWorker.getId(),finance.orgId(),project,LocalDate.now());
    }

    @Test void preliminaryCostsAndReimbursementAreCountedOnceAndCashIsNotProfit() {
        long book=workbench.createBook(finance,"pre-book",new FinWorkbenchService.BookCommand("客户前期费用"));
        workbench.register(finance,"survey",entry("book:"+book,"expense",10000,"COMPANY"));
        workbench.linkProject(finance,book,project,"link");
        long design=payments.registerPayable(finance,"design",new FinPaymentService.PayableCommand(finance.orgId(),null,"外部设计",20000,"设计方","设计合同"));
        jdbc.update("UPDATE fr_fin_payable SET source_type='design',source_ref=? WHERE id=?",commission+":1:FULL",design);
        payments.register(finance,"advance",new FinPaymentService.PaymentCommand(design,20000,LocalDate.now(),null,null,"EMPLOYEE",finance.userId(),"业务经办",null));
        workbench.register(finance,"customer-paid",entry("project:"+project,"receipt",40000,"COMPANY"));
        workbench.register(finance,"unrelated",entry("company","expense",7000,"COMPANY"));
        workbench.register(finance,"company-debt",entry("company","payable",9000,"COMPANY"));
        var before=accounting.get(finance,project);
        assertThat(before).containsEntry("confirmedCostCents","30000").containsEntry("grossProfitCents","70000")
            .containsEntry("cashNetCents","30000").containsEntry("companyNetPaidCents","10000")
            .containsEntry("payableOutstandingCents","20000");
        long debt=Long.parseLong((String)workbench.reimbursements(finance.orgId()).get(0).get("payable_id"));
        payments.register(finance,"reimburse",new FinPaymentService.PaymentCommand(debt,20000,LocalDate.now(),null,null));
        assertThat(accounting.get(finance,project)).containsEntry("confirmedCostCents","30000")
            .containsEntry("grossProfitCents","70000").containsEntry("cashNetCents","10000")
            .containsEntry("payableOutstandingCents","0");
        @SuppressWarnings("unchecked") var readOnly=(Map<String,Object>)workbench.ledger(finance.orgId(),"project:"+project).get("accounting");
        assertThat(readOnly).containsEntry("canConfirm",false).containsEntry("confirmedCostCents","30000")
            .containsEntry("payableOutstandingCents","0");
    }

    @Test void refundsChangeCashAndCostChangesRequireChangingTheOriginalObligation() {
        long payment=workbench.register(finance,"paid",entry("project:"+project,"expense",30000,"COMPANY"));
        long receipt=workbench.register(finance,"received",entry("project:"+project,"receipt",80000,"COMPANY"));
        refunds.register(finance,"client-return",new FinRefundService.RegisterCommand(receipt,5000,"客户退款",LocalDate.now(),null,null,null));
        refunds.registerExpense(finance,"supplier-return",new FinRefundService.ExpenseRefundCommand(payment,10000,"材料款退回",LocalDate.now(),null,null,null));
        var row=accounting.get(finance,project);
        assertThat(row).containsEntry("netReceivedCents","75000").containsEntry("companyNetPaidCents","20000")
            .containsEntry("cashNetCents","55000").containsEntry("confirmedCostCents","30000").containsEntry("grossProfitCents","70000")
            .containsEntry("payableOutstandingCents","10000");
        long payable=jdbc.queryForObject("SELECT payable_id FROM fr_fin_payment WHERE id=?",Long.class,payment);
        workbench.adjustPayable(finance,payable,"reduce",new FinWorkbenchService.PayableAdjustment(20000,"退料确认后减少成本",null));
        assertThat(accounting.get(finance,project)).containsEntry("cashNetCents","55000").containsEntry("grossProfitCents","80000")
            .containsEntry("payableOutstandingCents","0");
    }

    @Test void dynamicStagesAndInactiveFactsHaveTheSameEffectiveBasisInBothLedgers() {
        long active=workbench.register(finance,"active",entry("project:"+project,"payable",10000,"COMPANY"));
        long advance=payments.register(finance,"advance-to-void",new FinPaymentService.PaymentCommand(active,9000,LocalDate.now(),null,null,"EMPLOYEE",finance.userId(),"经办员工",null));
        long voided=jdbc.queryForObject("SELECT id FROM fr_fin_payable WHERE company_id=? AND source_type='reimbursement' AND source_ref=?",Long.class,finance.orgId(),Long.toString(advance));
        payments.void_(finance,advance,"原垫付登记有误");
        assertThat(jdbc.queryForObject("SELECT status FROM fr_fin_payable WHERE id=?",String.class,voided)).isEqualTo("已作废");
        long deleted=workbench.register(finance,"deleted",entry("project:"+project,"payable",8000,"COMPANY"));
        jdbc.update("UPDATE fr_fin_payable SET deleted=true WHERE id=?",deleted);
        long s1=IdWorker.getId(),s2=IdWorker.getId();
        jdbc.update("INSERT INTO fr_sale_payment_stage(id,company_id,contract_id,stage,amount_cents,status) VALUES(?,?,?,'STAGE_CUSTOM_A',40000,'PENDING'),(?,?,?,'STAGE_CUSTOM_B',60000,'PENDING')",s1,finance.orgId(),contract,s2,finance.orgId(),contract);
        long receipt=workbench.register(finance,"stage-receipt",entry("project:"+project,"receipt",25000,"COMPANY"));
        jdbc.update("INSERT INTO fr_sale_stage_receipt(id,company_id,contract_id,stage_id,receipt_id) VALUES(?,?,?,?,?)",IdWorker.getId(),finance.orgId(),contract,s1,receipt);
        refunds.register(finance,"partial-return",new FinRefundService.RegisterCommand(receipt,5000,"部分退款",LocalDate.now(),null,null,null));
        assertThat(accounting.get(finance,project)).containsEntry("confirmedCostCents","10000").containsEntry("receivableCents","80000")
            .containsEntry("payableOutstandingCents","10000");
        var ledger=(Map<?,?>)workbench.ledger(finance.orgId(),"project:"+project).get("summary");
        assertThat(((Number)ledger.get("committed_cents")).longValue()).isEqualTo(10000);
        assertThat(((Number)ledger.get("outstanding_cents")).longValue()).isEqualTo(10000);
        assertThat(((Number)ledger.get("paid_cents")).longValue()).isZero();
        assertThat(((Number)ledger.get("receivable_cents")).longValue()).isEqualTo(80000);
        var old=new FinProjectLedgerService(jdbc).page(finance.orgId(),project,1,20).summary();
        assertThat(old.receivableCents()).isEqualTo(100000);assertThat(old.payableCents()).isEqualTo(10000);
        assertThat(new FinProjectLedgerService(jdbc).costAmounts(finance.orgId(),project)).containsOnly(Map.entry(active,10000L));
    }

    @Test void confirmationIsImmutableIdempotentAndNewFinancialFactsRequireAnotherVersion() {
        long q=workbench.register(finance,"cost",entry("project:"+project,"payable",30000,"COMPANY"));
        var first=command(accounting.get(finance,project));
        var confirmed=accounting.confirm(finance,project,"confirm-v1",first);
        assertThat(accounting.confirm(finance,project,"confirm-v1",first)).isEqualTo(confirmed);
        assertThat(accounting.get(finance,project)).containsEntry("state","CURRENT");
        workbench.adjustPayable(finance,q,"extra-cost",new FinWorkbenchService.PayableAdjustment(40000,"追加已确认人工费",null));
        assertThat(accounting.get(finance,project)).containsEntry("state","STALE").containsEntry("grossProfitCents","60000");
        assertThatThrownBy(()->accounting.confirm(finance,project,"stale",first)).hasMessageContaining("已有变化");
        var second=accounting.confirm(finance,project,"confirm-v2",command(accounting.get(finance,project)));
        assertThat(second.get("version")).isEqualTo(2L);
        assertThat(((Map<?,?>)confirmed.get("snapshot")).get("grossProfitCents")).isEqualTo("70000");
        assertThat(((Map<?,?>)confirmed.get("snapshot")).get("payableOutstandingCents")).isEqualTo("30000");
        assertThat(((Map<?,?>)second.get("snapshot")).get("payableOutstandingCents")).isEqualTo("40000");
        assertThat(accounting.history(finance,project,1,1)).containsEntry("total",2L);
        assertThatThrownBy(()->jdbc.update("UPDATE fr_fin_project_accounting SET note='覆盖' WHERE id=?",Long.parseLong((String)confirmed.get("id"))))
            .hasMessageContaining("immutable");
        assertThatThrownBy(()->accounting.confirm(finance,project,"confirm-v1",new FinProjectAccountingService.Confirm(first.sourceHash(),0,true,"另一份说明")))
            .isInstanceOf(RuntimeException.class);
    }

    @Test void originalExpenseChangesInvalidateAccountingBeforeAndAfterItsPayableExists() {
        long expense=IdWorker.getId();
        jdbc.update("INSERT INTO fr_eng_expense(id,company_id,project_id,name,purpose,amount_cents,author_user_id) VALUES(?,?,?,'劳务确认','LABOR',10000,?)",expense,finance.orgId(),project,finance.userId());
        var first=command(accounting.get(finance,project));
        var confirmed=accounting.confirm(finance,project,"expense-v1",first);
        assertThat(accounting.get(finance,project)).containsEntry("state","CURRENT").containsEntry("confirmedCostCents","10000").containsEntry("companyNetPaidCents","0");
        jdbc.update("UPDATE fr_eng_expense SET amount_cents=8000 WHERE id=?",expense);
        assertThat(accounting.get(finance,project)).containsEntry("state","STALE").containsEntry("confirmedCostCents","8000");
        assertThatThrownBy(()->accounting.confirm(finance,project,"stale-expense",first)).hasMessageContaining("已有变化");
        long payable=payments.registerPayable(finance,"expense-payable",new FinPaymentService.PayableCommand(finance.orgId(),project,"劳务确认",8000,"劳务方","原费用付款凭据"));
        jdbc.update("UPDATE fr_eng_expense SET payable_id=? WHERE id=?",payable,expense);
        payments.register(finance,"expense-payment",new FinPaymentService.PaymentCommand(payable,1000,LocalDate.now(),null,null));
        assertThat(accounting.get(finance,project)).containsEntry("confirmedCostCents","8000").containsEntry("companyNetPaidCents","1000");
        accounting.confirm(finance,project,"expense-v2",command(accounting.get(finance,project)));
        jdbc.update("UPDATE fr_eng_expense SET amount_cents=7000,note='已付款后原费用核减，待原应付核对' WHERE id=?",expense);
        assertThat(accounting.get(finance,project)).containsEntry("state","STALE").containsEntry("confirmedCostCents","8000");
        assertThat(((Map<?,?>)confirmed.get("snapshot")).get("confirmedCostCents")).isEqualTo("10000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_payable WHERE project_id=?",Long.class,project)).isEqualTo(1);
    }

    @Test void allRowsContributeToHashEvenOutsideTheFiveHundredRowLedgerWindow() {
        var args=new ArrayList<Object[]>();
        for(int i=0;i<510;i++)args.add(new Object[]{IdWorker.getId(),finance.orgId(),project});
        jdbc.batchUpdate("INSERT INTO fr_fin_payable(id,company_id,project_id,stage,total_cents,recipient,status,source_type) VALUES(?,?,?,'项目费用',100,'供应方','进行中','manual')",args);
        var expenseArgs=new ArrayList<Object[]>();
        for(int i=0;i<501;i++)expenseArgs.add(new Object[]{IdWorker.getId(),finance.orgId(),project,finance.userId()});
        jdbc.batchUpdate("INSERT INTO fr_eng_expense(id,company_id,project_id,author_user_id,name,purpose,amount_cents) VALUES(?,?,?,?,'未挂应付原费用','OTHER',100)",expenseArgs);
        var original=accounting.get(finance,project);
        assertThat(original).containsEntry("confirmedCostCents","101100");
        accounting.confirm(finance,project,"full-source",command(original));
        jdbc.update("UPDATE fr_fin_payable SET recipient='已核对的供应方' WHERE id=?",args.get(0)[0]);
        assertThat(accounting.get(finance,project)).containsEntry("state","STALE").containsEntry("confirmedCostCents","101100");
        accounting.confirm(finance,project,"full-expense-source",command(accounting.get(finance,project)));
        jdbc.update("UPDATE fr_eng_expense SET note='核对最早的原费用凭据' WHERE id=?",expenseArgs.get(0)[0]);
        assertThat(accounting.get(finance,project)).containsEntry("state","STALE").containsEntry("confirmedCostCents","101100");
        var ledger=workbench.ledger(finance.orgId(),"project:"+project);
        assertThat((List<?>)ledger.get("entries")).hasSize(500);
        assertThat(((Number)((Map<?,?>)ledger.get("summary")).get("committed_cents")).longValue()).isEqualTo(101100);
    }

    @Test void storeScopeRolesAndSettlementPrerequisitesCannotBeBypassed() {
        var foreign=fixture.post(fixture.store().orgId(),"finance",null);
        var owner=fixture.post(finance.orgId(),"owner",null);
        var hq=fixture.post(fixture.headquarters(),"finance",null);
        var cmd=command(accounting.get(finance,project));
        directory(foreign);directory(owner);directory(hq);
        actor(foreign);
        assertThatThrownBy(()->accounting.get(foreign,project)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->accounting.history(foreign,project,1,20)).isInstanceOf(RuntimeException.class);
        actor(owner);
        assertThat(accounting.get(owner,project)).containsEntry("canConfirm",false);
        assertThatThrownBy(()->accounting.confirm(owner,project,"owner",cmd)).hasMessageContaining("仅本公司财务");
        actor(hq);
        assertThatThrownBy(()->accounting.confirm(hq,project,"hq",cmd)).hasMessageContaining("当前岗位");
        actor(finance);
        assertThatThrownBy(()->accounting.confirm(finance,project,"no-ack",new FinProjectAccountingService.Confirm(cmd.sourceHash(),0,false,"未完成")))
            .hasMessageContaining("完整归集");
        jdbc.update("UPDATE fr_eng_closure SET settled_at=NULL WHERE project_id=?",project);
        assertThat(accounting.get(finance,project)).containsEntry("ready",false).containsEntry("canConfirm",false);
        assertThatThrownBy(()->accounting.confirm(finance,project,"not-settled",command(accounting.get(finance,project)))).hasMessageContaining("工程已结算");
        jdbc.update("UPDATE fr_sale_contract SET status='VOIDED' WHERE id=?",contract);
        assertThat(accounting.get(finance,project)).containsEntry("contractRevenueCents",null).containsEntry("grossProfitCents",null);
        assertThatThrownBy(()->accounting.history(finance,project,0,20)).hasMessageContaining("分页");
    }

    @Test void concurrentConfirmationsKeepOnlyOneExpectedVersion() throws Exception {
        var cmd=command(accounting.get(finance,project));
        var pool=Executors.newFixedThreadPool(2);var start=new CyclicBarrier(2);Long tenant=TenantContextHolder.getTenantId();
        try {
            List<Future<Boolean>> pending=new ArrayList<>();
            for(int i=0;i<2;i++){final String key="race-"+i;pending.add(pool.submit(()->{
                actor(finance);TenantContextHolder.setTenantId(tenant);
                try {start.await(10,TimeUnit.SECONDS);accounting.confirm(finance,project,key,cmd);return true;}
                catch(cn.iocoder.yudao.framework.common.exception.ServiceException error){assertThat(error.getCode()).isEqualTo(409);return false;}
                finally {TenantContextHolder.clear();org.springframework.security.core.context.SecurityContextHolder.clearContext();}
            }));}
            int success=0;for(var future:pending)if(future.get(20,TimeUnit.SECONDS))success++;
            assertThat(success).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_project_accounting WHERE project_id=?",Long.class,project)).isEqualTo(1);
        } finally {pool.shutdownNow();}
    }

    @Test void outerTransactionRollbackLeavesNeitherSnapshotNorAudit() {
        var tx=new TransactionTemplate(transactions);var cmd=command(accounting.get(finance,project));
        assertThatThrownBy(()->tx.execute(status->{accounting.confirm(finance,project,"rollback",cmd);throw new IllegalStateException("rollback fixture");}))
            .hasMessageContaining("rollback fixture");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_project_accounting WHERE project_id=?",Long.class,project)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_command_event WHERE aggregate_type='fin-project-accounting' AND org_id=?",Long.class,finance.orgId())).isZero();
    }

    @Test void tenantRevocationAndForgedCurrentIdentityFailClosed() {
        var cmd=command(accounting.get(finance,project));
        TenantContextHolder.setTenantId(TENANT+1);
        assertThatThrownBy(()->accounting.get(finance,project)).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThatThrownBy(()->accounting.confirm(finance,project,"wrong-tenant",cmd)).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        TenantContextHolder.setTenantId(TENANT);
        var forged=new PostSnapshot(finance.postId()+1,finance.userId(),finance.orgId(),"store","finance",null,false,Set.of());
        assertThatThrownBy(()->accounting.get(forged,project)).hasMessageContaining("当前岗位");
        jdbc.update("UPDATE pf_org_unit SET status='DISABLED' WHERE dept_id=?",finance.orgId());
        assertThatThrownBy(()->accounting.get(finance,project)).hasMessageContaining("当前岗位");
        assertThatThrownBy(()->accounting.confirm(finance,project,"disabled-org",cmd)).hasMessageContaining("当前岗位");
        jdbc.update("UPDATE pf_org_unit SET status='ACTIVE' WHERE dept_id=?",finance.orgId());
        jdbc.update("UPDATE system_dept SET tenant_id=? WHERE id=?",TENANT+1,finance.orgId());
        assertThatThrownBy(()->accounting.history(finance,project,1,20)).hasMessageContaining("当前岗位");
        jdbc.update("UPDATE system_dept SET tenant_id=? WHERE id=?",TENANT,finance.orgId());
        jdbc.update("UPDATE system_users SET status=1 WHERE id=?",finance.userId());
        assertThatThrownBy(()->accounting.get(finance,project)).hasMessageContaining("当前岗位");
        jdbc.update("UPDATE system_users SET status=0 WHERE id=?",finance.userId());
        jdbc.update("UPDATE pf_staff_post SET status='REVOKED',revoked_at=now(),revoked_by=? WHERE id=?",finance.userId(),finance.postId());
        assertThatThrownBy(()->accounting.confirm(finance,project,"revoked-post",cmd)).hasMessageContaining("当前岗位");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_project_accounting WHERE project_id=?",Long.class,project)).isZero();
    }
    @Test void bossCanReadButCannotConfirmEvenWithAForgedFinanceRole() {
        var cmd=command(accounting.get(finance,project));
        var boss=fixture.post(finance.orgId(),"boss",null);directory(boss);actor(boss);
        assertThat(accounting.get(boss,project)).containsEntry("canConfirm",false);
        assertThatThrownBy(()->accounting.confirm(boss,project,"boss-write",cmd)).hasMessageContaining("仅本公司财务");
        var forged=new PostSnapshot(boss.postId(),boss.userId(),boss.orgId(),"store","finance",null,false,Set.of());
        currentPost.use(forged);
        assertThatThrownBy(()->accounting.confirm(forged,project,"forged-finance",cmd)).hasMessageContaining("当前岗位");
    }
    private void directory(PostSnapshot post) {
        jdbc.update("INSERT INTO system_dept(id,name,tenant_id,status,deleted) VALUES(?,'核算测试公司',?,0,0) ON CONFLICT(id) DO UPDATE SET tenant_id=EXCLUDED.tenant_id,status=0,deleted=0",post.orgId(),TENANT);
        jdbc.update("INSERT INTO system_users(id,tenant_id,username,nickname,password,mobile,status,deleted) VALUES(?, ?, ?, '核算测试用户', 'unused', NULL, 0, 0) ON CONFLICT(id) DO UPDATE SET tenant_id=EXCLUDED.tenant_id,status=0,deleted=0",post.userId(),TENANT,"accounting-"+post.userId());
    }
    private void actor(PostSnapshot post) {
        currentPost.use(post);TenantContextHolder.setTenantId(TENANT);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
            new cn.iocoder.yudao.framework.security.core.LoginUser().setId(post.userId()).setUserType(2).setTenantId(TENANT),null,List.of()));
    }
    private FinWorkbenchService.EntryCommand entry(String scope,String type,long cents,String funding) {
        return new FinWorkbenchService.EntryCommand(scope,type,"核算原单","项目费用",cents,LocalDate.now(),"交易对方","登记账户",null,null,funding,finance.userId(),"经办人");
    }
    private FinProjectAccountingService.Confirm command(Map<String,Object> row) {
        return new FinProjectAccountingService.Confirm((String)row.get("sourceHash"),Long.parseLong(row.get("version").toString()),true,"已核对合同与全部已发生项目费用");
    }
}
