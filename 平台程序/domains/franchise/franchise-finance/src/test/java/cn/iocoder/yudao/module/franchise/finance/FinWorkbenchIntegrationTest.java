package cn.iocoder.yudao.module.franchise.finance;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.command.CommandLogImpl;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDate;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static cn.iocoder.yudao.module.franchise.org.FranchiseRoles.FINANCE;
import static cn.iocoder.yudao.module.franchise.test.FranchiseAssertions.assertError;
import static cn.iocoder.yudao.module.franchise.finance.enums.FranchiseFinErrorCodes.*;

@Import({FinWorkbenchService.class,FinReceiptService.class,FinPaymentService.class,FinRefundService.class,
 FranchiseCommands.class,CommandLogImpl.class,FinRefundThreeDirectionIntegrationTest.Config.class})
class FinWorkbenchIntegrationTest extends FranchisePgIntegrationTest {
 @Resource FinWorkbenchService workbench;
 @Resource FinPaymentService payments;
 @Resource FinRefundService refunds;
 @Resource JdbcTemplate jdbc;
 @Resource StaffPostService staffPosts;
 PostSnapshot post;
 @BeforeEach void prepare(){
  var store=fixture.store();post=fixture.post(store.orgId(),FINANCE,null);
  // finance模块独立测试，仅创建跨域只读契约所用列；生产使用各域自己的迁移。
  jdbc.execute("CREATE TABLE IF NOT EXISTS fr_eng_project(id bigint primary key,company_id bigint,name text,status text,source_commission_id bigint,sale_contract_id bigint)");
  jdbc.execute("CREATE TABLE IF NOT EXISTS fr_sale_stage_receipt(id bigint,company_id bigint,contract_id bigint,receipt_id bigint,stage_id bigint)");
  jdbc.execute("CREATE TABLE IF NOT EXISTS fr_sale_contract(id bigint primary key,company_id bigint,status text,deleted boolean default false,signed_date date)");
  jdbc.execute("CREATE TABLE IF NOT EXISTS fr_sale_payment_stage(id bigint primary key,company_id bigint,contract_id bigint,stage text,amount_cents bigint,status text,deleted boolean default false)");
  jdbc.execute("CREATE TABLE IF NOT EXISTS fr_eng_collection(company_id bigint,project_id bigint,receipt_id bigint)");
  jdbc.execute("CREATE TABLE IF NOT EXISTS fr_eng_expense(id bigint primary key,company_id bigint,project_id bigint,payable_id bigint,name text)");
  jdbc.execute("CREATE TABLE IF NOT EXISTS fr_eng_supply(id bigint primary key,company_id bigint,project_id bigint,payable_id bigint,material_id bigint)");

 }
 @Test void originalEngineeringPayablesAndTheirPaymentsCannotMoveToAnotherProject(){
  long original=assignmentProject("原工程"),target=assignmentProject("另一工程");
  for(String stage:List.of("engineering","engineering_expense")){
   long q=payments.registerPayable(post,"original-"+stage,new FinPaymentService.PayableCommand(post.orgId(),original,stage,10000,"原收款方","原依据"));
   String table=stage.equals("engineering")?"fr_eng_supply":"fr_eng_expense";
   jdbc.update("INSERT INTO "+table+"(id,company_id,project_id,payable_id) VALUES(?,?,?,?)",com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),post.orgId(),original,q);
   long paid=payments.register(post,"paid-"+stage,new FinPaymentService.PaymentCommand(q,2000,LocalDate.now(),null,null));
   assertThat(jdbc.queryForObject("SELECT source_type FROM fr_fin_payable WHERE id=?",String.class,q)).isNull();
   assertError(()->workbench.assignPayable(post,q,"move-"+stage,new FinWorkbenchService.Assignment("project:"+target,"仅财务改归属")),FIN_SOURCE_FORBIDDEN);
   assertThat(jdbc.queryForObject("SELECT project_id FROM fr_fin_payable WHERE id=?",Long.class,q)).isEqualTo(original);
   assertThat(jdbc.queryForObject("SELECT project_id FROM fr_fin_payment WHERE id=?",Long.class,paid)).isEqualTo(original);
  }
 }
 @Test void actualEngineeringLinkRejectsEvenAnIncorrectManualUnassignedLabel(){
  long original=assignmentProject("原费用工程"),target=assignmentProject("错误目标");
  for(String table:List.of("fr_eng_expense","fr_eng_supply")){
   long q=workbench.register(post,"old-manual-"+table,new FinWorkbenchService.EntryCommand("company","payable","原工程费用","其他",10000,LocalDate.now(),"原方",null,null,null));
   jdbc.update("INSERT INTO "+table+"(id,company_id,project_id,payable_id) VALUES(?,?,?,?)",com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),post.orgId(),original,q);
   assertError(()->workbench.assignPayable(post,q,"wrong-source-"+table,new FinWorkbenchService.Assignment("project:"+target,"标签不足以证明手工来源")),FIN_SOURCE_FORBIDDEN);
   assertThat(jdbc.queryForObject("SELECT project_id FROM fr_fin_payable WHERE id=?",Long.class,q)).isNull();
  }
 }
 @Test void onlyUnassignedManualExpenseCanBeAssignedOnceWithItsOriginalEmployeeBalance(){
  long target=assignmentProject("补归属工程"),other=assignmentProject("不可再移动");
  long payment=workbench.register(post,"unassigned-advance",new FinWorkbenchService.EntryCommand("company","expense","原垫付费用","差旅",10000,LocalDate.now(),"交通方","员工账户",null,null,"EMPLOYEE",post.userId(),"经办人"));
  long payable=jdbc.queryForObject("SELECT payable_id FROM fr_fin_payment WHERE id=?",Long.class,payment);
  var command=new FinWorkbenchService.Assignment("project:"+target,"补齐原费用的项目");
  workbench.assignPayable(post,payable,"assign-original",command);
  workbench.assignPayable(post,payable,"assign-original",command);
  assertThat(jdbc.queryForObject("SELECT project_id FROM fr_fin_payable WHERE id=?",Long.class,payable)).isEqualTo(target);
  assertThat(jdbc.queryForObject("SELECT project_id FROM fr_fin_payment WHERE id=?",Long.class,payment)).isEqualTo(target);
  assertThat(jdbc.queryForObject("SELECT project_id FROM fr_fin_payable WHERE company_id=? AND source_type='reimbursement' AND source_ref=?",Long.class,post.orgId(),Long.toString(payment))).isEqualTo(target);
  assertError(()->workbench.assignPayable(post,payable,"move-assigned",new FinWorkbenchService.Assignment("project:"+other,"重新搬账")),FIN_SOURCE_FORBIDDEN);
 }
 private long assignmentProject(String name){
  long id=com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
  jdbc.update("INSERT INTO fr_eng_project(id,company_id,name,status) VALUES(?,?,?,'ACTIVE')",id,post.orgId(),name);return id;
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
 @Test void employeeBalancesRemainInTheirBookAfter501And1001CashEntries(){
  for(int count:List.of(501,1001)) {
   long book=workbench.createBook(post,"large-book-"+count,new FinWorkbenchService.BookCommand("完整员工往来"+count));
   String scope="book:"+book;
   long advance=workbench.register(post,"old-advance-"+count,new FinWorkbenchService.EntryCommand(
       scope,"expense","最早垫付","差旅",10000,LocalDate.now().minusDays(1),"车队","员工账户",null,null,"EMPLOYEE",post.userId(),"垫付员工"));
   String debt=reimbursementId(advance);
   var later=new ArrayList<Object[]>();
   for(int i=1;i<count;i++) later.add(new Object[]{com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),post.orgId(),book,post.userId(),LocalDate.now()});
   jdbc.batchUpdate("""
       INSERT INTO fr_fin_receipt(id,company_id,book_id,registrar_user_id,received_date,source_type,name,category,amount_cents,status)
       VALUES(?,?,?,?,?,'manual','后续已登记流水','其他收款',1,'已登记')
       """,later);
   var ledger=workbench.ledger(post.orgId(),scope);
   assertThat((List<?>)ledger.get("entries")).hasSize(500);
   assertThat(ledger.get("truncated")).isEqualTo(true);
   assertThat((List<Map<String,Object>>)ledger.get("entries")).noneMatch(r->String.valueOf(advance).equals(r.get("id")));
   assertThat((List<String>)ledger.get("reimbursementIds")).containsExactly(debt);
   assertThat(((Number)((Map<?,?>)ledger.get("summary")).get("outstanding_cents")).longValue()).isEqualTo(10000);
   assertThat(workbench.reimbursements(post.orgId())).filteredOn(r->debt.equals(r.get("payable_id"))).singleElement()
       .satisfies(r->assertThat(remaining(r)).isEqualTo(10000));
   payments.register(post,"old-advance-reimburse-"+count,new FinPaymentService.PaymentCommand(Long.parseLong(debt),4000,LocalDate.now(),null,null));
   assertThat(workbench.reimbursements(post.orgId())).filteredOn(r->debt.equals(r.get("payable_id"))).singleElement()
       .satisfies(r->assertThat(remaining(r)).isEqualTo(6000));
   assertThat((List<String>)workbench.ledger(post.orgId(),scope).get("reimbursementIds")).containsExactly(debt);
   jdbc.update("UPDATE fr_fin_payment SET deleted=true WHERE id=?",advance);
   assertThat((List<String>)workbench.ledger(post.orgId(),scope).get("reimbursementIds")).isEmpty();
   jdbc.update("UPDATE fr_fin_payment SET deleted=false WHERE id=?",advance);
   jdbc.update("UPDATE fr_fin_payable SET deleted=true WHERE id=?",Long.parseLong(debt));
   assertThat((List<String>)workbench.ledger(post.orgId(),scope).get("reimbursementIds")).isEmpty();
  }
 }
 @Test void employeeBalancesKeepOriginalScopeThroughPartialReimbursementRefundRecoveryAndProjectLink(){
  long book=workbench.createBook(post,"employee-book",new FinWorkbenchService.BookCommand("前期员工往来"));
  long project=com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),otherProject=com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
  jdbc.update("INSERT INTO fr_eng_project(id,company_id,name,status) VALUES(?,?,'工程A','ACTIVE'),(?,?,'工程B','ACTIVE')",project,post.orgId(),otherProject,post.orgId());
  long advance=workbench.register(post,"book-advance",new FinWorkbenchService.EntryCommand(
      "book:"+book,"expense","测量交通","差旅",10000,LocalDate.now(),"车队","员工账户",null,null,"EMPLOYEE",post.userId(),"员工甲"));
  String debt=reimbursementId(advance);
  long companyAdvance=workbench.register(post,"company-advance",new FinWorkbenchService.EntryCommand(
      "company","expense","公司交通","差旅",3000,LocalDate.now(),"车队","员工账户",null,null,"EMPLOYEE",post.userId(),"员工甲"));
  long otherAdvance=workbench.register(post,"other-project-advance",new FinWorkbenchService.EntryCommand(
      "project:"+otherProject,"expense","工程B交通","差旅",5000,LocalDate.now(),"车队","员工账户",null,null,"EMPLOYEE",post.userId(),"员工甲"));
  assertThat((List<String>)workbench.ledger(post.orgId(),"book:"+book).get("reimbursementIds")).containsExactly(debt);
  assertThat((List<String>)workbench.ledger(post.orgId(),"project:"+project).get("reimbursementIds")).isEmpty();
  payments.register(post,"partial-reimburse",new FinPaymentService.PaymentCommand(Long.parseLong(debt),4000,LocalDate.now(),null,null));
  assertThat(workbench.reimbursements(post.orgId())).filteredOn(r->debt.equals(r.get("payable_id"))).singleElement()
      .satisfies(r->assertThat(remaining(r)).isEqualTo(6000));
  refunds.registerExpense(post,"return-to-employee",new FinRefundService.ExpenseRefundCommand(advance,8000,"交通款退回员工",LocalDate.now(),null,null,null,"EMPLOYEE"));
  assertThat(workbench.reimbursements(post.orgId())).filteredOn(r->debt.equals(r.get("payable_id"))).singleElement()
      .satisfies(r->assertThat(remaining(r)).isEqualTo(-2000));
  workbench.linkProject(post,book,project,"link-employee-book");
  workbench.linkProject(post,book,project,"link-employee-book");
  for(String scope:List.of("book:"+book,"project:"+project)) {
   assertThat((List<String>)workbench.ledger(post.orgId(),scope).get("reimbursementIds")).containsExactly(debt);
  }
  workbench.recoverEmployee(post,Long.parseLong(debt),"recover-extra",new FinWorkbenchService.Recovery(2000,LocalDate.now(),"公司银行",null,null));
  assertThat(workbench.reimbursements(post.orgId())).filteredOn(r->debt.equals(r.get("payable_id"))).singleElement()
      .satisfies(r->{assertThat(remaining(r)).isZero();assertThat(r.get("project_id")).isEqualTo(String.valueOf(project));assertThat(r.get("book_id")).isEqualTo(String.valueOf(book));});
  assertThat((List<String>)workbench.ledger(post.orgId(),"project:"+project).get("reimbursementIds")).containsExactly(debt);
  assertThat((List<String>)workbench.ledger(post.orgId(),"company").get("reimbursementIds")).containsExactly(reimbursementId(companyAdvance));
  assertThat((List<String>)workbench.ledger(post.orgId(),"project:"+otherProject).get("reimbursementIds")).containsExactly(reimbursementId(otherAdvance));
  var other=fixture.post(fixture.store().orgId(),FINANCE,null);
  assertThat(workbench.reimbursements(other.orgId())).isEmpty();
  assertThatThrownBy(()->workbench.ledger(other.orgId(),"project:"+project)).isInstanceOf(RuntimeException.class);
  assertThatThrownBy(()->workbench.ledger(other.orgId(),"book:"+book)).isInstanceOf(RuntimeException.class);
 }
 @Test void originalDesignAdvanceFollowsTheCommissionProjectWithoutInventingBookOwnership(){
  long commission=com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),project=com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
  long payable=payments.registerPayable(post,"old-design-payable",new FinPaymentService.PayableCommand(post.orgId(),null,"FULL",12000,"设计方","原设计费用"));
  jdbc.update("UPDATE fr_fin_payable SET source_type='design',source_ref=? WHERE id=?",commission+":1:FULL",payable);
  long advance=payments.register(post,"old-design-advance",new FinPaymentService.PaymentCommand(payable,12000,LocalDate.now(),null,null,"EMPLOYEE",post.userId(),"原经办人",null));
  String debt=reimbursementId(advance);
  jdbc.update("INSERT INTO fr_eng_project(id,company_id,name,status,source_commission_id) VALUES(?,?,'原委托工程','ACTIVE',?)",project,post.orgId(),commission);
  assertThat((List<String>)workbench.ledger(post.orgId(),"project:"+project).get("reimbursementIds")).containsExactly(debt);
  assertThat((List<String>)workbench.ledger(post.orgId(),"company").get("reimbursementIds")).isEmpty();
  assertThat(workbench.reimbursements(post.orgId())).singleElement().satisfies(r->assertThat(r.get("project_id")).isNull());
 }
 private String reimbursementId(long advance) {
  return workbench.reimbursements(post.orgId()).stream().filter(r->String.valueOf(advance).equals(r.get("origin_payment_id")))
      .map(r->(String)r.get("payable_id")).findFirst().orElseThrow();
 }
 private long remaining(Map<String,Object> row) {
  return ((Number)row.get("eligible_cents")).longValue()-((Number)row.get("reimbursed_cents")).longValue()+((Number)row.get("recovered_cents")).longValue();
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
  jdbc.execute("CREATE TABLE IF NOT EXISTS fr_eng_material(id bigint,company_id bigint,name text)");
  long project=com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
  jdbc.update("INSERT INTO fr_eng_project(id,company_id,name,status) VALUES(?,?,?,'ACTIVE')",project,post.orgId(),"周建军乡墅");
  long q=payments.registerPayable(post,"historical-labor",new FinPaymentService.PayableCommand(post.orgId(),project,"engineering",350000,"兴业劳务班组","施工费用"));
  jdbc.update("INSERT INTO fr_eng_expense(id,company_id,project_id,payable_id,name) VALUES(?,?,?,?,?)",com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),post.orgId(),project,q,"场地排水与围挡人工");
  jdbc.update("INSERT INTO fr_eng_expense(id,company_id,project_id,payable_id,name) VALUES(?,?,?,?,?)",com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),post.orgId()+10000,project,q,"其他组织费用");
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

 @Test void departedEmployeeHistoryRemainsWhileAnotherFinanceSettlesTheOriginalAdvance(){
  var day=LocalDate.now();
  var replacement=fixture.post(post.orgId(),FINANCE,null);
  var manager=fixture.post(post.orgId(),"boss",null);
  var other=fixture.post(fixture.store().orgId(),FINANCE,null);
  currentPost.use(post);
  long receipt=workbench.register(post,"departure-old-receipt",new FinWorkbenchService.EntryCommand(
      "company","receipt","历史往来收款","往来",1000,day,"往来方","公司银行",null,null));
  long advance=workbench.register(post,"departure-advance",new FinWorkbenchService.EntryCommand(
      "company","expense","现场差旅","差旅",10000,day,"交通服务方","员工个人账户",null,null,"EMPLOYEE",post.userId(),"原垫付员工"));
  long sourcePayable=jdbc.queryForObject("SELECT payable_id FROM fr_fin_payment WHERE id=?",Long.class,advance);
  long debt=Long.parseLong((String)workbench.reimbursements(post.orgId()).get(0).get("payable_id"));

  // Use the real post revocation service; the four-domain departure orchestration is tested separately.
  currentPost.use(manager);
  staffPosts.revokeAllInOrg(post.userId(),post.orgId(),manager.userId());
  assertThat(staffPosts.findActivePost(post.userId(),post.postId())).isEmpty();
  assertThat(staffPosts.findActivePost(replacement.userId(),replacement.postId())).isPresent();
  currentPost.use(replacement);
  var todos=new cn.iocoder.yudao.module.franchise.finance.todo.WorkbenchTodoProvider(workbench,jdbc);
  assertThat(workbench.reimbursements(replacement.orgId())).singleElement().satisfies(row->{
   assertThat(row.get("payable_id")).isEqualTo(String.valueOf(debt));
   assertThat(row.get("payer_name")).isEqualTo("原垫付员工");
  });
  assertThat(todos.list(replacement)).anyMatch(item->item.key().equals("fr-fin-workbench:reimburse:"+debt));

  currentPost.use(other);
  assertThat(workbench.reimbursements(other.orgId())).isEmpty();
  assertThat(todos.list(other)).isEmpty();
  assertError(()->payments.register(other,"departure-cross-payment",
      new FinPaymentService.PaymentCommand(debt,10000,day,null,null)),PAYABLE_NOT_FOUND);
  assertError(()->workbench.recoverEmployee(other,debt,"departure-cross-recovery",
      new FinWorkbenchService.Recovery(3000,day,"公司银行",null,null)),ORG_MISMATCH);

  currentPost.use(replacement);
  long paid=payments.register(replacement,"departure-reimburse",new FinPaymentService.PaymentCommand(debt,10000,day,null,null));
  assertThat(todos.list(replacement)).noneMatch(item->item.key().equals("fr-fin-workbench:reimburse:"+debt));
  refunds.registerExpense(replacement,"departure-refund",new FinRefundService.ExpenseRefundCommand(
      advance,3000,"供应商退回垫付款",day,null,null,null,"EMPLOYEE"));
  assertThat(todos.list(replacement)).anyMatch(item->item.key().equals("fr-fin-workbench:reimburse:"+debt));
  long recovered=workbench.recoverEmployee(replacement,debt,"departure-returned",
      new FinWorkbenchService.Recovery(3000,day,"公司银行",null,null));
  assertThat(todos.list(replacement)).noneMatch(item->item.key().equals("fr-fin-workbench:reimburse:"+debt));
  assertThat(workbench.reimbursements(replacement.orgId())).singleElement().satisfies(row->{
   assertThat(((Number)row.get("eligible_cents")).longValue()).isEqualTo(7000);
   assertThat(((Number)row.get("reimbursed_cents")).longValue()).isEqualTo(10000);
   assertThat(((Number)row.get("recovered_cents")).longValue()).isEqualTo(3000);
  });
  assertThat(jdbc.queryForMap("SELECT payer_user_id,operator_user_id,payer_name FROM fr_fin_payment WHERE id=?",advance))
      .containsAllEntriesOf(Map.of("payer_user_id",post.userId(),"operator_user_id",post.userId(),"payer_name","原垫付员工"));
  assertThat(jdbc.queryForObject("SELECT registrar_user_id FROM fr_fin_receipt WHERE id=?",Long.class,receipt)).isEqualTo(post.userId());
  assertThat(jdbc.queryForObject("SELECT operator_user_id FROM fr_fin_payment WHERE id=?",Long.class,paid)).isEqualTo(replacement.userId());
  assertThat(jdbc.queryForObject("SELECT registrar_user_id FROM fr_fin_receipt WHERE id=?",Long.class,recovered)).isEqualTo(replacement.userId());
  assertError(()->payments.register(replacement,"departure-new-advance",new FinPaymentService.PaymentCommand(
      sourcePayable,100,day,null,null,"EMPLOYEE",post.userId(),"原垫付员工",null)),FIN_SOURCE_FORBIDDEN);
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
