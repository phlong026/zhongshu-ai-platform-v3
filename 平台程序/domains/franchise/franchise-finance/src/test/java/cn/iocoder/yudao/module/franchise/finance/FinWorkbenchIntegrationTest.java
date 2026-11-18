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
 @Test void failedPaymentRollsBackItsNewPayable(){
  var cmd=new FinWorkbenchService.EntryCommand("company","expense","租车","租赁",10000,LocalDate.now(),"租赁商","现金",null,999999999L);
  assertThatThrownBy(()->workbench.register(post,"bad-voucher",cmd)).isInstanceOf(RuntimeException.class);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_payable WHERE company_id=?",Long.class,post.orgId())).isZero();
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
}
