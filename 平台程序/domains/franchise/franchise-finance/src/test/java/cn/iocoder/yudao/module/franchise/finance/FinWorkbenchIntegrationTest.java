package cn.iocoder.yudao.module.franchise.finance;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.command.CommandLogImpl;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDate;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static cn.iocoder.yudao.module.franchise.org.FranchiseRoles.FINANCE;

@Import({FinWorkbenchService.class,FinReceiptService.class,FinPaymentService.class,FinRefundService.class,
 FranchiseCommands.class,CommandLogImpl.class,FinRefundThreeDirectionIntegrationTest.Config.class})
class FinWorkbenchIntegrationTest extends FranchisePgIntegrationTest {
 @Resource FinWorkbenchService workbench;
 @Resource FinPaymentService payments;
 @Resource FinRefundService refunds;
 @Resource JdbcTemplate jdbc;
 PostSnapshot post;
 @BeforeEach void prepare(){
  var store=fixture.store();post=fixture.post(store.orgId(),FINANCE,null);
  // finance模块独立测试，仅创建跨域只读契约所用列；生产使用各域自己的迁移。
  jdbc.execute("CREATE TABLE IF NOT EXISTS fr_eng_project(id bigint primary key,company_id bigint,name text,status text,source_commission_id bigint,sale_contract_id bigint)");
  jdbc.execute("CREATE TABLE IF NOT EXISTS fr_sale_stage_receipt(id bigint,company_id bigint,contract_id bigint,receipt_id bigint,stage_id bigint)");
  jdbc.execute("CREATE TABLE IF NOT EXISTS fr_sale_contract(id bigint primary key,company_id bigint,status text,deleted boolean default false,signed_date date)");
  jdbc.execute("CREATE TABLE IF NOT EXISTS fr_sale_payment_stage(id bigint primary key,company_id bigint,contract_id bigint,stage text,amount_cents bigint,status text,deleted boolean default false)");
  jdbc.execute("CREATE TABLE IF NOT EXISTS fr_eng_collection(company_id bigint,project_id bigint,receipt_id bigint)");

 }
 @Test void manualEntryIsAtomicIdempotentAndScoped(){
  long book=workbench.createBook(post,"book-a",new FinWorkbenchService.BookCommand("张先生建房"));
  var cmd=new FinWorkbenchService.EntryCommand("book:"+book,"expense","现场运输","运输",86000,LocalDate.now(),"运输商","公司银行","运输-"+book,null);
  long id=workbench.register(post,"expense-a",cmd);
  assertThat(workbench.register(post,"expense-a",cmd)).isEqualTo(id);
  var ledger=workbench.ledger(post.orgId(),"book:"+book);
  assertThat(((Number)((Map<?,?>)ledger.get("summary")).get("paid_cents")).longValue()).isEqualTo(86000);
  var other=fixture.post(fixture.store().orgId(),FINANCE,null);
  assertThatThrownBy(()->workbench.ledger(other.orgId(),"book:"+book)).isInstanceOf(RuntimeException.class);
  assertThatThrownBy(()->workbench.register(post,"expense-a",new FinWorkbenchService.EntryCommand("company","expense","其他","其他",86000,LocalDate.now(),"运输商","现金",null,null))).isInstanceOf(RuntimeException.class);
 }
 @Test void linkingPreconstructionBookPreservesCashAcrossBothLedgerApis(){
  long book=workbench.createBook(post,"prebook",new FinWorkbenchService.BookCommand("前期账本"));
  workbench.register(post,"pre-expense",new FinWorkbenchService.EntryCommand("book:"+book,"expense","前期运输","运输",86000,LocalDate.now(),"运输方","银行",null,null));
  long project=com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
  jdbc.update("INSERT INTO fr_eng_project(id,company_id,name,status) VALUES(?,?,?,'ACTIVE')",project,post.orgId(),"工程项目");
  workbench.linkProject(post,book,project,"link-project");
  var newSummary=(Map<?,?>)workbench.ledger(post.orgId(),"project:"+project).get("summary");
  assertThat(((Number)newSummary.get("paid_cents")).longValue()).isEqualTo(86000);
  assertThat(new FinProjectLedgerService(jdbc).page(post.orgId(),project,1,20).summary().paidCents()).isEqualTo(86000);
 }
 @Test void refundOfUnallocatedOverpaymentDoesNotCreateSupplierDebt(){
  long q=payments.registerPayable(post,"overpay-q",new FinPaymentService.PayableCommand(post.orgId(),null,"材料",800000,"供应商","采购"));
  long paid=payments.register(post,"overpay-p",new FinPaymentService.PaymentCommand(q,1000000,LocalDate.now(),"over-"+q,null));
  refunds.registerExpense(post,"return-extra",new FinRefundService.ExpenseRefundCommand(paid,200000,"退回多付款",LocalDate.now(),"extra-"+q,null,null));
  long later=payments.register(post,"extra-payment",new FinPaymentService.PaymentCommand(q,1,LocalDate.now(),"later-"+q,null));
  assertThat(jdbc.queryForObject("SELECT pending_link_cents FROM fr_fin_payment WHERE id=?",Long.class,later)).isEqualTo(1);
 }
 @Test void financeCanRecordActualSmallExpenseAdvanceWithoutCompanyCash(){
  workbench.register(post,"travel-advance",new FinWorkbenchService.EntryCommand("company","expense","现场差旅","差旅",86000,LocalDate.now(),"交通服务方","员工个人账户","staff-travel-"+post.orgId(),null,"EMPLOYEE",post.userId(),"经办员工"));
  var summary=(Map<?,?>)workbench.ledger(post.orgId(),"company").get("summary");
  assertThat(((Number)summary.get("paid_cents")).longValue()).isZero();
  assertThat(((Number)summary.get("committed_cents")).longValue()).isEqualTo(86000);
  assertThat(((Number)summary.get("outstanding_cents")).longValue()).isEqualTo(86000);
  var debt=workbench.reimbursements(post.orgId()).get(0);
  payments.register(post,"travel-reimburse",new FinPaymentService.PaymentCommand(Long.parseLong((String)debt.get("payable_id")),86000,LocalDate.now(),"travel-repaid-"+post.orgId(),null));
  var settled=(Map<?,?>)workbench.ledger(post.orgId(),"company").get("summary");
  assertThat(((Number)settled.get("paid_cents")).longValue()).isEqualTo(86000);
  assertThat(((Number)settled.get("committed_cents")).longValue()).isEqualTo(86000);
 }
 @Test void voidingAnUnreimbursedAdvanceClosesTheDebtAndRestoresOnlySupplierBalance(){
  long q=payments.registerPayable(post,"supplier",new FinPaymentService.PayableCommand(post.orgId(),null,"材料",100000,"供应商","采购"));
  long book=workbench.createBook(post,"advance-book",new FinWorkbenchService.BookCommand("垫付项目"));
  long project=com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
  jdbc.update("INSERT INTO fr_eng_project(id,company_id,name,status) VALUES(?,?,?,'ACTIVE')",project,post.orgId(),"工程项目");
  workbench.linkProject(post,book,project,"advance-project");
  jdbc.update("UPDATE fr_fin_payable SET source_type='manual', book_id=?,project_id=? WHERE id=?",book,project,q);
  long advance=payments.register(post,"supplier-advance",new FinPaymentService.PaymentCommand(q,30000,LocalDate.now(),null,null,"EMPLOYEE",post.userId(),"经办员工",null));
  var reimbursement=workbench.reimbursements(post.orgId()).get(0);
  long reimbursementId=Long.parseLong((String)reimbursement.get("payable_id"));
  payments.void_(post,advance,"原垫付登记有误");
  payments.void_(post,advance,"原垫付登记有误");
  assertThat(jdbc.queryForObject("SELECT status FROM fr_fin_payable WHERE id=?",String.class,reimbursementId)).isEqualTo("已作废");
  assertThat(workbench.reimbursements(post.orgId())).isEmpty();
  assertThatThrownBy(() -> workbench.recoverEmployee(post,reimbursementId,"void-recovery",
      new FinWorkbenchService.Recovery(1,LocalDate.now(),"银行",null,null))).hasMessageContaining("应付已作废");
  assertThatThrownBy(()->payments.register(post,"void-reimbursement",new FinPaymentService.PaymentCommand(reimbursementId,1,LocalDate.now(),null,null))).isInstanceOf(RuntimeException.class);
  for(String scope:java.util.List.of("project:"+project,"book:"+book)){
   var summary=(Map<?,?>)workbench.ledger(post.orgId(),scope).get("summary");
   assertThat(((Number)summary.get("outstanding_cents")).longValue()).isEqualTo(100000);
  }
 }
 @Test void supplierReturnAdjustsObligationWithoutNewCash(){
  long q=payments.registerPayable(post,"purchase",new FinPaymentService.PayableCommand(post.orgId(),null,"材料",1000000,"供应商","采购依据"));
  long paid=payments.register(post,"purchase-paid",new FinPaymentService.PaymentCommand(q,1000000,LocalDate.now(),"buy-"+q,null));
  workbench.adjustPayable(post,q,"returned-material",new FinWorkbenchService.PayableAdjustment(800000,"确认退料2000元",null));
  refunds.registerExpense(post,"supplier-return",new FinRefundService.ExpenseRefundCommand(paid,200000,"退料到账",LocalDate.now(),"refund-"+q,null,null));
  assertThat(jdbc.queryForObject("SELECT total_cents FROM fr_fin_payable WHERE id=?",Long.class,q)).isEqualTo(800000);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_payment WHERE payable_id=?",Long.class,q)).isEqualTo(1);
 }
 @Test void ledgerDetailCarriesCounterpartyAccountAndFundingWithoutCreatingAnotherCashFact(){
  workbench.register(post,"detail-entry",new FinWorkbenchService.EntryCommand("company","expense","现场运费","运输",86000,LocalDate.now(),"运输商","公司账户","detail-"+post.orgId(),null));
  long payment=jdbc.queryForObject("SELECT id FROM fr_fin_payment WHERE company_id=?",Long.class,post.orgId());
  workbench.supplement(post,payment,"detail-note",new FinWorkbenchService.Supplement("payment","运输收据已核对",null));
  var result=workbench.ledger(post.orgId(),"company");
  @SuppressWarnings("unchecked") var rows=(java.util.List<Map<String,Object>>)result.get("entries");
  var cash=rows.stream().filter(row->"PAYMENT".equals(row.get("type"))).findFirst().orElseThrow();
  assertThat(cash).containsEntry("counterparty","运输商").containsEntry("account_name","公司账户").containsEntry("source_type","manual").containsEntry("funding_type","COMPANY").containsEntry("note","运输收据已核对");
  assertThat(rows.stream().filter(row->"PAYMENT".equals(row.get("type"))).count()).isEqualTo(1);
 }
 @Test void historicalEngineeringExpenseKeepsItsNameWithoutChangingCashOrCrossingOrganizations(){
  jdbc.execute("CREATE TABLE IF NOT EXISTS fr_eng_expense(company_id bigint,payable_id bigint,name text)");
  jdbc.execute("CREATE TABLE IF NOT EXISTS fr_eng_supply(company_id bigint,payable_id bigint,material_id bigint)");
  jdbc.execute("CREATE TABLE IF NOT EXISTS fr_eng_material(id bigint,company_id bigint,name text)");
  long project=com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
  jdbc.update("INSERT INTO fr_eng_project(id,company_id,name,status) VALUES(?,?,?,'ACTIVE')",project,post.orgId(),"周建军乡墅");
  long q=payments.registerPayable(post,"historical-labor",new FinPaymentService.PayableCommand(post.orgId(),project,"engineering",350000,"兴业劳务班组","施工费用"));
  jdbc.update("INSERT INTO fr_eng_expense(company_id,payable_id,name) VALUES(?,?,?)",post.orgId(),q,"场地排水与围挡人工");
  jdbc.update("INSERT INTO fr_eng_expense(company_id,payable_id,name) VALUES(?,?,?)",post.orgId()+10000,q,"其他组织费用");
  payments.register(post,"historical-labor-paid",new FinPaymentService.PaymentCommand(q,350000,LocalDate.now(),"labor-"+q,null));
  var ledger=workbench.ledger(post.orgId(),"project:"+project);
  @SuppressWarnings("unchecked") var entries=(java.util.List<Map<String,Object>>)ledger.get("entries");
  assertThat(entries.stream().filter(e->"PAYMENT".equals(e.get("type"))).toList()).hasSize(1).allSatisfy(e->assertThat(e).containsEntry("name","场地排水与围挡人工"));
  assertThat(((Number)((Map<?,?>)ledger.get("summary")).get("paid_cents")).longValue()).isEqualTo(350000);
  assertThat(jdbc.queryForObject("SELECT stage FROM fr_fin_payable WHERE id=?",String.class,q)).isEqualTo("engineering");
 }
 @Test void failedPaymentRollsBackItsNewPayable(){
  var cmd=new FinWorkbenchService.EntryCommand("company","expense","租车","租赁",10000,LocalDate.now(),"租赁商","现金",null,999999999L);
  assertThatThrownBy(()->workbench.register(post,"bad-voucher",cmd)).isInstanceOf(RuntimeException.class);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_payable WHERE company_id=?",Long.class,post.orgId())).isZero();
 }
 @Resource org.springframework.transaction.PlatformTransactionManager transactions;

 @Test void sameAdvanceSerializesRefundRecoveryReimbursementAndVoid() throws Exception {
  LocalDate today = LocalDate.now();
  long advance = workbench.register(post,"adv",new FinWorkbenchService.EntryCommand("company","expense","材料采购","材料",10000,today,"供应商","员工个人账户",null,null,"EMPLOYEE",post.userId(),"经办人"));
  long debt = Long.parseLong((String)workbench.reimbursements(post.orgId()).get(0).get("payable_id"));
  var initialPay = new FinPaymentService.PaymentCommand(debt,8000,today,null,null);
  payments.register(post,"first-pay",initialPay);
  refunds.registerExpense(post,"first-refund",new FinRefundService.ExpenseRefundCommand(advance,3000,"退材料款",today,null,null,null,"EMPLOYEE"));
  var extraRefund = new FinRefundService.ExpenseRefundCommand(advance,1000,"退运费",today,null,null,null,"EMPLOYEE");
  var recovery = new FinWorkbenchService.Recovery(1000,today,"工程结算户",null,null);
  var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
  var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
  try {
   // 归还事务持有报销应付行时，让另一连接按正常退款路径进入锁等待。
   long refundId = overlapAtDebt(debt, tx, pool,
           () -> refunds.registerExpense(post,"extra-refund",extraRefund),
           () -> workbench.recoverEmployee(post,debt,"cash-back",recovery));
   assertThat(refunds.registerExpense(post,"extra-refund",extraRefund)).isEqualTo(refundId);
   long recoveryId=workbench.recoverEmployee(post,debt,"cash-back",recovery);
   assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_receipt WHERE company_id=? AND source_ref=?",
     Integer.class,post.orgId(),"employee-recovery:"+debt)).isOne();
   assertThat(recoveryId).isPositive();
   // 先撤销新增退回，再让原退回的作废与报销同时办理。
   refunds.void_(post,refundId,"退回登记更正");
   refunds.void_(post,refundId,"退回登记更正");
   long previous=jdbc.queryForObject("SELECT id FROM fr_fin_refund WHERE payment_id=? AND status='已核实'",Long.class,advance);
   // 一部分报销退还公司，形成 500 分的可报销余额。
   long paidId=payments.register(post,"first-pay",initialPay);
   refunds.registerExpense(post,"reimb-refund",new FinRefundService.ExpenseRefundCommand(paidId,500,"报销退还",today,null,null,null,"COMPANY"));
   var partial = new FinPaymentService.PaymentCommand(debt,500,today,null,null);
   overlapAtDebt(debt, tx, pool,
           () -> { refunds.void_(post,previous,"供应商重新结算"); return null; },
           () -> payments.register(post,"reimburse-in-parallel",partial));
   assertThat(jdbc.queryForObject("SELECT status FROM fr_fin_payable WHERE id=?",String.class,debt)).isEqualTo("进行中");
   var balance = new FinPaymentService.PaymentCommand(debt,3000,today,null,null);
   long tail=payments.register(post,"final-reimbursement",balance);
   assertThat(payments.register(post,"final-reimbursement",balance)).isEqualTo(tail);
   assertThat(jdbc.queryForObject("SELECT status FROM fr_fin_payable WHERE id=?",String.class,debt)).isEqualTo("已结清");
   var book=workbench.reimbursements(post.orgId()).get(0);
   assertThat(((Number)book.get("eligible_cents")).longValue()).isEqualTo(10000);
   assertThat(((Number)book.get("reimbursed_cents")).longValue()-((Number)book.get("recovered_cents")).longValue()).isEqualTo(10000);
   // 撤销最后一笔报销时，原垫付又收到部分退款。两笔有效操作均须提交。
   var rebate = new FinRefundService.ExpenseRefundCommand(advance,1000,"最终折让",today,null,null,null,"EMPLOYEE");
   long rebateId=overlapAtDebt(debt, tx, pool,
           () -> refunds.registerExpense(post,"final-rebate",rebate),
           () -> payments.void_(post,tail,"报销更正"));
   assertThat(refunds.registerExpense(post,"final-rebate",rebate)).isEqualTo(rebateId);
   var reopened=workbench.reimbursements(post.orgId()).get(0);
   assertThat(((Number)reopened.get("eligible_cents")).longValue()).isEqualTo(9000);
   assertThat(((Number)reopened.get("reimbursed_cents")).longValue()-((Number)reopened.get("recovered_cents")).longValue()).isEqualTo(7000);
   assertThat(jdbc.queryForObject("SELECT status FROM fr_fin_payable WHERE id=?",String.class,debt)).isEqualTo("进行中");
   assertThatThrownBy(() -> payments.void_(post,advance,"已报销")).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
  } finally {pool.shutdownNow();}
 }

 private <T> T overlapAtDebt(long debt, org.springframework.transaction.support.TransactionTemplate tx,
                             java.util.concurrent.ExecutorService pool, java.util.concurrent.Callable<T> concurrent,
                             Runnable holderAction) throws Exception {
  var pending=tx.execute(status -> {
   int pid=jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class);
   jdbc.queryForObject("SELECT id FROM fr_fin_payable WHERE id=? FOR UPDATE",Long.class,debt);
   var future=pool.submit(concurrent);
   waitForDatabaseWaiter(pid, future);
   holderAction.run();
   return future;
  });
  return pending.get(10,java.util.concurrent.TimeUnit.SECONDS);
 }

 private void waitForDatabaseWaiter(int pid, java.util.concurrent.Future<?> future) {
  long deadline = System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
  while(System.nanoTime()<deadline) {
   if (future.isDone()) {
    try { future.get(); } catch (Exception error) { throw new AssertionError("并发业务已失败", error); }
    throw new AssertionError("并发写入没有等待同一张应付单");
   }
   if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_locks WHERE NOT granted AND ?=ANY(pg_blocking_pids(pid)))",Boolean.class,pid))) return;
   java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(10));
  }
  throw new AssertionError("并发业务未进入数据库锁等待");
 }

 @Test void employeeAdvanceReimbursementAndRefundDoNotDoubleCash(){
  long q=payments.registerPayable(post,"design-q",new FinPaymentService.PayableCommand(post.orgId(),null,"FULL",240000,"平台","设计依据"));
  jdbc.update("UPDATE fr_fin_payable SET source_type='design',source_ref=?,category='设计' WHERE id=?","123:1:FULL",q);
  long origin=payments.register(post,"employee-paid",new FinPaymentService.PaymentCommand(q,240000,LocalDate.now(),"employee-"+q,null,"EMPLOYEE",post.userId(),"业务人员",null));
  var debt=workbench.reimbursements(post.orgId()).get(0);
  long reimbursement=Long.parseLong((String)debt.get("payable_id"));
  payments.register(post,"reimburse-1",new FinPaymentService.PaymentCommand(reimbursement,100000,LocalDate.now(),"reimburse-a-"+q,null));
  payments.register(post,"reimburse-2",new FinPaymentService.PaymentCommand(reimbursement,140000,LocalDate.now(),"reimburse-b-"+q,null));
  assertThat(((Number)workbench.reimbursements(post.orgId()).get(0).get("reimbursed_cents")).longValue()).isEqualTo(240000);
  assertThatThrownBy(()->payments.register(post,"reimburse-over",new FinPaymentService.PaymentCommand(reimbursement,1,LocalDate.now(),null,null))).isInstanceOf(RuntimeException.class);
  refunds.registerExpense(post,"employee-refund",new FinRefundService.ExpenseRefundCommand(origin,50000,"平台减费",LocalDate.now(),"return-"+q,null,null));
  var after=workbench.reimbursements(post.orgId()).get(0);
  assertThat(((Number)after.get("eligible_cents")).longValue()-((Number)after.get("reimbursed_cents")).longValue()).isEqualTo(-50000);
  assertThat(jdbc.queryForObject("SELECT sum(amount_cents) FROM fr_fin_payment WHERE company_id=? AND funding_type='COMPANY'",Long.class,post.orgId())).isEqualTo(240000);
  assertThat(jdbc.queryForObject("SELECT funding_type FROM fr_fin_refund WHERE payment_id=?",String.class,origin)).isEqualTo("EMPLOYEE");
  long recovered=workbench.recoverEmployee(post,reimbursement,"employee-returned",new FinWorkbenchService.Recovery(50000,LocalDate.now(),"公司账户","recovery-"+q,null));
  assertThat(recovered).isPositive();
  assertThat(new cn.iocoder.yudao.module.franchise.finance.todo.WorkbenchTodoProvider(workbench,jdbc).list(post))
    .noneMatch(item->item.key().contains(":reimburse:"));
  assertThat(((Number)workbench.reimbursements(post.orgId()).get(0).get("recovered_cents")).longValue()).isEqualTo(50000);
  assertThatThrownBy(()->workbench.recoverEmployee(post,reimbursement,"extra-recovery",new FinWorkbenchService.Recovery(1,LocalDate.now(),"现金",null,null))).isInstanceOf(RuntimeException.class);
 }

 @RepeatedTest(3)
 void concurrentAdvanceRefundRecoveryVoidAndSettlementAgreeWithNetCash() throws Exception {
  var day = LocalDate.now();
  long advance = workbench.register(post,"advance",new FinWorkbenchService.EntryCommand("company","expense",
      "差旅","差旅",10000,day,"车队","个人账户",null,null,"EMPLOYEE",post.userId(),"经办员工"));
  long bill = Long.parseLong((String)workbench.reimbursements(post.orgId()).get(0).get("payable_id"));
  long supplier = jdbc.queryForObject("SELECT payable_id FROM fr_fin_payment WHERE id=?",Long.class,advance);
  payments.register(post,"initial-reimbursement",new FinPaymentService.PaymentCommand(bill,10000,day,null,null));
  long employeeRefund = refunds.registerExpense(post,"first-refund",new FinRefundService.ExpenseRefundCommand(
      advance,3000,"退给员工",day,null,null,null,"EMPLOYEE"));
  long companyRefund = refunds.registerExpense(post,"company-refund",new FinRefundService.ExpenseRefundCommand(
      advance,200,"退给公司",day,null,null,null,"COMPANY"));

  Runnable recover = () -> workbench.recoverEmployee(post,bill,"recover",
      new FinWorkbenchService.Recovery(1000,day,"银行","recovery-"+advance,null));
  Runnable refund = () -> refunds.registerExpense(post,"second-refund",new FinRefundService.ExpenseRefundCommand(
      advance,500,"退给员工",day,"refund-"+advance,null,null,"EMPLOYEE"));
  Runnable refresh = () -> jdbc.queryForList("SELECT fr_fin_refresh_payment_bills(?,?,?)",post.orgId(),advance,supplier);
  Runnable voidAdvance = () -> assertThatThrownBy(() -> payments.void_(post,advance,"作废原垫付"))
      .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class)
      .hasMessageContaining("请先作废退回记录");
  Runnable reimburse = () -> payments.register(post,"reimburse-rest",
      new FinPaymentService.PaymentCommand(bill,500,day,"reimburse-"+advance,null));
  concurrently(refund,recover,refund,recover,voidAdvance,refresh,
      () -> assertThatThrownBy(reimburse::run).hasMessageContaining("累计付款超出应付金额"),
      () -> refunds.void_(post,companyRefund,"更正退回"));
  var debt = workbench.reimbursements(post.orgId()).get(0);
  assertThat(((Number)debt.get("eligible_cents")).longValue()).isEqualTo(6500);
  assertThat(((Number)debt.get("recovered_cents")).longValue()).isEqualTo(1000);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_refund WHERE payment_id=? AND status='已核实'",Long.class,advance)).isEqualTo(2);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_receipt WHERE source_ref=?",Long.class,"employee-recovery:"+bill)).isEqualTo(1);
  assertThat(jdbc.queryForObject("SELECT status FROM fr_fin_payable WHERE id=?",String.class,bill)).isEqualTo("已结清");

  refunds.void_(post,employeeRefund,"更正退回");
  assertThat(jdbc.queryForObject("SELECT status FROM fr_fin_payable WHERE id=?",String.class,bill)).isEqualTo("进行中");
  concurrently(reimburse,reimburse,voidAdvance,refresh,() -> refunds.registerExpense(post,"company-return",
      new FinRefundService.ExpenseRefundCommand(advance,100,"退给公司",day,null,null,null,"COMPANY")));
  debt = workbench.reimbursements(post.orgId()).get(0);
  long net = ((Number)debt.get("reimbursed_cents")).longValue()-((Number)debt.get("recovered_cents")).longValue();
  assertThat(net).isEqualTo(9500).isEqualTo(((Number)debt.get("eligible_cents")).longValue());
  assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_payment WHERE payable_id=?",Long.class,bill)).isEqualTo(2);
  assertThat(jdbc.queryForObject("SELECT status FROM fr_fin_payable WHERE id=?",String.class,bill)).isEqualTo("已结清");
  assertThat(jdbc.queryForObject("SELECT status FROM fr_fin_payable WHERE id=?",String.class,supplier)).isEqualTo("进行中");
 }

 private void concurrently(Runnable... operations) throws Exception {
  var pool = Executors.newFixedThreadPool(operations.length);
  var start = new CyclicBarrier(operations.length);
  var results = new ArrayList<Future<?>>();
  try {
   for (var operation : operations) results.add(pool.submit(() -> {
    start.await(10,TimeUnit.SECONDS);
    operation.run();
    return null;
   }));
   for (var result : results) result.get(30,TimeUnit.SECONDS);
  } finally {
   pool.shutdownNow();
  }
 }
}
