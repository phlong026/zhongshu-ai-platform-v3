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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDate;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
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
 @Resource FranchiseCommands commands;
 @Resource PlatformTransactionManager transactions;
 PostSnapshot post;
 long bookCustomer;
 @BeforeEach void prepare(){
  var store=fixture.store();post=fixture.post(store.orgId(),FINANCE,null);
  // finance模块独立测试，仅创建跨域只读契约所用列；生产使用各域自己的迁移。
  jdbc.execute("CREATE TABLE IF NOT EXISTS fr_customer(id bigint primary key,company_id bigint,name text,phone text)");
  jdbc.execute("CREATE TABLE IF NOT EXISTS fr_commission(id bigint primary key,company_id bigint,customer_id bigint)");
  bookCustomer=com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
  jdbc.update("INSERT INTO fr_customer(id,company_id,name,phone) VALUES(?,?,'前期账客户',?)",bookCustomer,post.orgId(),Long.toString(bookCustomer));
  jdbc.execute("CREATE TABLE IF NOT EXISTS fr_eng_project(id bigint primary key,company_id bigint,name text,status text,source_commission_id bigint,sale_contract_id bigint)");
  jdbc.execute("ALTER TABLE fr_eng_project ADD COLUMN IF NOT EXISTS customer_id bigint");
  jdbc.execute("CREATE TABLE IF NOT EXISTS fr_sale_stage_receipt(id bigint,company_id bigint,contract_id bigint,receipt_id bigint,stage_id bigint)");
  jdbc.execute("CREATE TABLE IF NOT EXISTS fr_sale_contract(id bigint primary key,company_id bigint,status text,deleted boolean default false,signed_date date)");
  jdbc.execute("CREATE TABLE IF NOT EXISTS fr_sale_payment_stage(id bigint primary key,company_id bigint,contract_id bigint,stage text,amount_cents bigint,status text,deleted boolean default false)");
  jdbc.execute("CREATE TABLE IF NOT EXISTS fr_eng_collection(company_id bigint,project_id bigint,receipt_id bigint)");
  jdbc.execute("CREATE TABLE IF NOT EXISTS fr_eng_supply(id bigint primary key,company_id bigint,project_id bigint,payable_id bigint,material_id bigint)");
  jdbc.execute("""
      CREATE TABLE IF NOT EXISTS fr_eng_expense(
        id bigint PRIMARY KEY,company_id bigint NOT NULL,project_id bigint NOT NULL,name varchar(100) NOT NULL,
        purpose varchar(32) NOT NULL,amount_cents bigint NOT NULL,payable_id bigint,status varchar(16) NOT NULL DEFAULT 'REGISTERED',
        note varchar(500),author_user_id bigint NOT NULL,create_time timestamptz NOT NULL DEFAULT now(),update_time timestamptz NOT NULL DEFAULT now())
      """);

 }
 private FinWorkbenchService.BookCommand bookCommand(String name){return new FinWorkbenchService.BookCommand(name,bookCustomer,null);}
 private void linkBook(long book,long project,String key){
  jdbc.update("UPDATE fr_eng_project SET customer_id=? WHERE id=? AND company_id=?",bookCustomer,project,post.orgId());
  workbench.linkProject(post,book,project,key);
 }
 @Test void newProjectBooksRequireRealCustomerAndOptionalCommissionButOldSuccessfulCreatesReplay(){
  assertThatThrownBy(()->workbench.createBook(post,"missing-source",new FinWorkbenchService.BookCommand("无来源"))).hasMessageContaining("来源客户");
  long foreignCustomer=com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),foreignOrg=fixture.store().orgId();
  jdbc.update("INSERT INTO fr_customer(id,company_id,name,phone) VALUES(?,?,'其他客户','123')",foreignCustomer,foreignOrg);
  assertError(()->workbench.createBook(post,"foreign-source",new FinWorkbenchService.BookCommand("错误客户",foreignCustomer,null)),ORG_MISMATCH);
  long commission=com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
  jdbc.update("INSERT INTO fr_commission(id,company_id,customer_id) VALUES(?,?,?)",commission,post.orgId(),foreignCustomer);
  assertError(()->workbench.createBook(post,"foreign-commission",new FinWorkbenchService.BookCommand("错误委托",bookCustomer,commission)),ORG_MISMATCH);
  long legacy=com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
  jdbc.update("INSERT INTO fr_fin_book(id,company_id,name) VALUES(?,?,'历史账本')",legacy,post.orgId());
  commands.record(post,"fin-book",legacy,1,"fin-book-create",Map.of("name","历史账本"),"legacy-create");
  assertThat(workbench.createBook(post,"legacy-create",new FinWorkbenchService.BookCommand("历史账本"))).isEqualTo(legacy);
  assertThat(jdbc.queryForObject("SELECT customer_id FROM fr_fin_book WHERE id=?",Long.class,legacy)).isNull();
 }
 @Test void sourceIdentifiedBookContinuesOnceWhenItsProjectAppearsAndKeepsOriginalCash(){
  long book=workbench.createBook(post,"before-project",bookCommand("建档前发生的费用"));
  long payment=workbench.register(post,"pre-cost",new FinWorkbenchService.EntryCommand("book:"+book,"expense","前期测量","交通",8600,LocalDate.now(),"测量方","银行",null,null));
  long receipt=workbench.register(post,"pre-receipt",new FinWorkbenchService.EntryCommand("book:"+book,"receipt","客户预付款","预付款",12000,LocalDate.now(),"客户","银行",null,null));
  long project=sourceProject(bookCustomer,null,80001L);
  workbench.linkPendingBooks(post,project);workbench.linkPendingBooks(post,project);
  assertThat(jdbc.queryForObject("SELECT project_id FROM fr_fin_book WHERE id=?",Long.class,book)).isEqualTo(project);
  assertThat(jdbc.queryForObject("SELECT project_id FROM fr_fin_payment WHERE id=?",Long.class,payment)).isEqualTo(project);
  assertThat(jdbc.queryForObject("SELECT project_id FROM fr_fin_receipt WHERE id=?",Long.class,receipt)).isEqualTo(project);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_payment WHERE company_id=?",Long.class,post.orgId())).isEqualTo(1);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_receipt WHERE company_id=?",Long.class,post.orgId())).isEqualTo(1);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_command_event WHERE aggregate_id=? AND command='fin-book-auto-link'",Long.class,book)).isEqualTo(1);
  assertThat(summaryAmount("project:"+project,"received_cents")).isEqualTo(12000);
  assertThat(summaryAmount("project:"+project,"paid_cents")).isEqualTo(8600);
  long another=sourceProject(bookCustomer,null,80002L);
  workbench.linkPendingBooks(post,another);
  assertThat(jdbc.queryForObject("SELECT project_id FROM fr_fin_book WHERE id=?",Long.class,book)).isEqualTo(project);
 }
 @Test void aBookCreatedAfterTheOnlyProjectUsesTheSameContinuation(){
  long project=sourceProject(bookCustomer,null,81001L);
  long book=workbench.createBook(post,"after-project",bookCommand("工程前期账"));
  assertThat(jdbc.queryForObject("SELECT project_id FROM fr_fin_book WHERE id=?",Long.class,book)).isEqualTo(project);
  assertThat(workbench.createBook(post,"after-project",bookCommand("工程前期账"))).isEqualTo(book);
 }
 @Test void twoPossiblePreliminaryBooksStayForExplicitOriginalBookSelection(){
  long first=workbench.createBook(post,"first-book",bookCommand("第一事项"));
  long second=workbench.createBook(post,"second-book",bookCommand("第二事项"));
  long project=sourceProject(bookCustomer,null,81101L);
  workbench.linkPendingBooks(post,project);
  assertThat(jdbc.queryForList("SELECT project_id FROM fr_fin_book WHERE id IN(?,?)",Long.class,first,second)).containsOnlyNulls();
  workbench.linkProject(post,second,project,"choose-original-book");
  assertThat(jdbc.queryForObject("SELECT project_id FROM fr_fin_book WHERE id=?",Long.class,first)).isNull();
  assertThat(jdbc.queryForObject("SELECT project_id FROM fr_fin_book WHERE id=?",Long.class,second)).isEqualTo(project);
 }
 @Test void filingWaitsForConcurrentOriginalBookEntryAndIncludesTheSameCashRow() throws Exception {
  long book=workbench.createBook(post,"concurrent-book",bookCommand("并发前期账"));
  long project=sourceProject(bookCustomer,null,81201L);
  var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var linking=new CountDownLatch(1);
  var executor=Executors.newFixedThreadPool(2);
  try {
   var cash=executor.submit(()->new TransactionTemplate(transactions).execute(status->{
    long receipt=workbench.register(post,"concurrent-cash",new FinWorkbenchService.EntryCommand("book:"+book,"receipt","前期定金","预付款",5000,LocalDate.now(),"客户","银行",null,null));
    entered.countDown();
    try {if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("entry commit timed out");}
    catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
    return receipt;
   }));
   assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
   var link=executor.submit(()->{linking.countDown();workbench.linkPendingBooks(post,project);});
   assertThat(linking.await(10,TimeUnit.SECONDS)).isTrue();
   assertThatThrownBy(()->link.get(300,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
   release.countDown();long receipt=cash.get(10,TimeUnit.SECONDS);link.get(10,TimeUnit.SECONDS);
   assertThat(jdbc.queryForObject("SELECT project_id FROM fr_fin_receipt WHERE id=?",Long.class,receipt)).isEqualTo(project);
   assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_receipt WHERE company_id=?",Long.class,post.orgId())).isEqualTo(1);
  } finally {release.countDown();executor.shutdownNow();}
 }
 @Test void ambiguousCustomerProjectsRequireExplicitSelectionWhileCommissionIdentifiesTheOriginal(){
  long commission=com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
  jdbc.update("INSERT INTO fr_commission(id,company_id,customer_id) VALUES(?,?,?)",commission,post.orgId(),bookCustomer);
  long first=sourceProject(bookCustomer,commission,82001L),second=sourceProject(bookCustomer,null,82002L);
  long specific=workbench.createBook(post,"commission-book",new FinWorkbenchService.BookCommand("委托前期账",bookCustomer,commission));
  assertThat(jdbc.queryForObject("SELECT project_id FROM fr_fin_book WHERE id=?",Long.class,specific)).isEqualTo(first);
  long general=workbench.createBook(post,"customer-book",bookCommand("另一建房事项"));
  workbench.linkPendingBooks(post,second);
  assertThat(jdbc.queryForObject("SELECT project_id FROM fr_fin_book WHERE id=?",Long.class,general)).isNull();
  workbench.linkProject(post,general,second,"explicit-original");
  assertThat(jdbc.queryForObject("SELECT project_id FROM fr_fin_book WHERE id=?",Long.class,general)).isEqualTo(second);
  assertError(()->workbench.linkProject(post,specific,second,"move-commission"),ORG_MISMATCH);
 }
 @Test void oldUnassignedBookRequiresExplicitSourceAndCannotChangeItsCustomer(){
  long book=com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
  jdbc.update("INSERT INTO fr_fin_book(id,company_id,name) VALUES(?,?,'旧待核对账')",book,post.orgId());
  long project=sourceProject(bookCustomer,null,83001L);
  workbench.linkPendingBooks(post,project);
  assertThat(jdbc.queryForObject("SELECT project_id FROM fr_fin_book WHERE id=?",Long.class,book)).isNull();
  assertThatThrownBy(()->workbench.linkProject(post,book,project,"missing-legacy-source")).hasMessageContaining("来源客户");
  var source=new FinWorkbenchService.BookSource(bookCustomer,null);
  workbench.linkProject(post,book,project,"verify-legacy-source",source);
  workbench.linkProject(post,book,project,"verify-legacy-source",source);
  assertThat(jdbc.queryForObject("SELECT customer_id FROM fr_fin_book WHERE id=?",Long.class,book)).isEqualTo(bookCustomer);
  assertError(()->workbench.linkProject(post,book,project,"change-customer",new FinWorkbenchService.BookSource(bookCustomer+1,null)),ORG_MISMATCH);
 }
 @Test void alreadyAllocatedReceiptPreventsAWholeBookFromMovingToAnotherContract(){
  long first=sourceProject(bookCustomer,null,84001L),second=sourceProject(bookCustomer,null,84002L);
  for(boolean stamped:List.of(false,true)){
   long book=workbench.createBook(post,"allocation-book-"+stamped,bookCommand("原收款关联"));
   long receipt=workbench.register(post,"allocation-cash-"+stamped,new FinWorkbenchService.EntryCommand("book:"+book,"receipt","前期定金","预付款",10000,LocalDate.now(),"客户","银行",null,null));
   jdbc.update("UPDATE fr_fin_receipt SET customer_id=?,source_type='business',project_id=? WHERE id=?",bookCustomer,stamped?first:null,receipt);
   jdbc.update("INSERT INTO fr_sale_stage_receipt(id,company_id,contract_id,receipt_id,stage_id) VALUES(?,?,?,?,?)",com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),post.orgId(),84001L,receipt,84011L);
   assertThatThrownBy(()->workbench.linkProject(post,book,second,"wrong-contract-"+stamped)).hasMessageContaining("不能搬移");
   assertThat(jdbc.queryForObject("SELECT project_id FROM fr_fin_book WHERE id=?",Long.class,book)).isNull();
   assertThat(jdbc.queryForObject("SELECT contract_id FROM fr_sale_stage_receipt WHERE receipt_id=?",Long.class,receipt)).isEqualTo(84001L);
  }
 }
 @Test void allocatedCompanyReceiptIsNotProjectedAgainAsCompanyDailyCash(){
  long project=sourceProject(bookCustomer,null,85001L);
  long receipt=workbench.register(post,"unassigned-cash",new FinWorkbenchService.EntryCommand("company","receipt","前期客户定金","预付款",10000,LocalDate.now(),"客户","银行",null,null));
  jdbc.update("UPDATE fr_fin_receipt SET source_type='business',customer_id=? WHERE id=?",bookCustomer,receipt);
  jdbc.update("INSERT INTO fr_sale_stage_receipt(id,company_id,contract_id,receipt_id,stage_id) VALUES(?,?,?,?,?)",com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),post.orgId(),85001L,receipt,85011L);
  assertThat(summaryAmount("company","received_cents")).isZero();
  assertThat(summaryAmount("project:"+project,"received_cents")).isEqualTo(10000);
 }
 @SuppressWarnings("unchecked")
 private long summaryAmount(String scope,String field){return ((Number)((Map<String,Object>)workbench.ledger(post.orgId(),scope).get("summary")).get(field)).longValue();}
 private long sourceProject(long customer,Long commission,Long contract){
  long id=com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
  jdbc.update("INSERT INTO fr_eng_project(id,company_id,name,status,customer_id,source_commission_id,sale_contract_id) VALUES(?,?,?,'ACTIVE',?,?,?)",id,post.orgId(),"来源工程"+id,customer,commission,contract);
  return id;
 }
 @Test void originalEngineeringPayablesAndTheirPaymentsCannotMoveToAnotherProject(){
  long original=assignmentProject("原工程"),target=assignmentProject("另一工程");
  for(String stage:List.of("engineering","engineering_expense")){
   long q=payments.registerPayable(post,"original-"+stage,new FinPaymentService.PayableCommand(post.orgId(),original,stage,10000,"原收款方","原依据"));
   String table=stage.equals("engineering")?"fr_eng_supply":"fr_eng_expense";
   if(table.equals("fr_eng_expense"))jdbc.update("INSERT INTO fr_eng_expense(id,company_id,project_id,payable_id,name,purpose,amount_cents,author_user_id) VALUES(?,?,?,?,'原工程费用','OTHER',10000,?)",com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),post.orgId(),original,q,post.userId());
   else jdbc.update("INSERT INTO fr_eng_supply(id,company_id,project_id,payable_id) VALUES(?,?,?,?)",com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),post.orgId(),original,q);
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
   if(table.equals("fr_eng_expense"))jdbc.update("INSERT INTO fr_eng_expense(id,company_id,project_id,payable_id,name,purpose,amount_cents,author_user_id) VALUES(?,?,?,?,'原工程费用','OTHER',10000,?)",com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),post.orgId(),original,q,post.userId());
   else jdbc.update("INSERT INTO fr_eng_supply(id,company_id,project_id,payable_id) VALUES(?,?,?,?)",com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),post.orgId(),original,q);
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
 @Test void confirmedEngineeringCostsKeepTheirProjectAndDoNotInventPayablesOrCash(){
  long project=com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),otherProject=com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
  jdbc.update("INSERT INTO fr_eng_project(id,company_id,name,status) VALUES(?,?,'费用工程','ACTIVE'),(?,?,'另一工程','ACTIVE')",project,post.orgId(),otherProject,post.orgId());
  long payable=payments.registerPayable(post,"original-labor",new FinPaymentService.PayableCommand(post.orgId(),project,"劳务",16000,"劳务方","原付款凭据"));
  long originalA=com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),originalB=com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
  for(Object[] row:List.of(
      new Object[]{originalA,post.orgId(),project,12000L,null,"REGISTERED"},
      new Object[]{originalB,post.orgId(),project,12000L,null,"REGISTERED"},
      new Object[]{com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),post.orgId(),project,15000L,payable,"REGISTERED"},
      new Object[]{com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),post.orgId(),project,90000L,null,"CANCELLED"},
      new Object[]{com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),post.orgId(),otherProject,8000L,null,"REGISTERED"},
      new Object[]{com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),post.orgId()+10000,project,99999L,null,"REGISTERED"})) {
   jdbc.update("INSERT INTO fr_eng_expense(id,company_id,project_id,amount_cents,payable_id,status,name,purpose,author_user_id) VALUES(?,?,?,?,?,?,'原工程费用','LABOR',1)",row);
  }
  long manual=workbench.register(post,"manual-cost",new FinWorkbenchService.EntryCommand("project:"+project,"payable","财务直接费用","其他",3000,LocalDate.now(),"供应方",null,null,null));
  workbench.register(post,"company-cost",new FinWorkbenchService.EntryCommand("company","payable","公司费用","其他",7000,LocalDate.now(),"供应方",null,null,null));
  long book=workbench.createBook(post,"cost-book",bookCommand("前期账本"));
  assertThat(((Number)((Map<?,?>)workbench.ledger(post.orgId(),"book:"+book).get("summary")).get("committed_cents")).longValue()).isZero();
  linkBook(book,project,"cost-book-link");
  for(String scope:List.of("project:"+project,"book:"+book)) {
   var ledger=workbench.ledger(post.orgId(),scope);var summary=(Map<?,?>)ledger.get("summary");
   assertThat(((Number)summary.get("committed_cents")).longValue()).isEqualTo(43000);
   assertThat(((Number)summary.get("paid_cents")).longValue()).isZero();
   assertThat(((Number)summary.get("outstanding_cents")).longValue()).isEqualTo(19000);
   assertThat((List<?>)ledger.get("entries")).hasSize(2);
  }
  var originalLedger=new FinProjectLedgerService(jdbc);
  var projectLedger=originalLedger.page(post.orgId(),project,1,20);
  assertThat(projectLedger.summary().payableCents()).isEqualTo(43000);
  assertThat(projectLedger.summary().paidCents()).isZero();
  assertThat(projectLedger.entries().getTotal()).isEqualTo(4);
  var originalRows=projectLedger.entries().getList().stream()
      .filter(r->"CONFIRMED_ENGINEERING_COST".equals(r.type())).toList();
  assertThat(originalRows).extracting(FinProjectLedgerService.Row::originalExpenseId)
      .containsExactlyInAnyOrder(Long.toString(originalA),Long.toString(originalB));
  assertThat(originalRows).allSatisfy(r->{
       assertThat(r.amountCents()).isEqualTo(12000);
       assertThat(r.sourceRef()).isNull();
       assertThat(r.status()).isEqualTo("已确认");
      });
  assertThatThrownBy(()->originalLedger.page(post.orgId()+10000,project,1,20)).isInstanceOf(RuntimeException.class);
  assertThat(originalLedger.costAmounts(post.orgId(),project)).containsOnly(Map.entry(payable,16000L),Map.entry(manual,3000L));
  assertThat(((Number)((Map<?,?>)workbench.ledger(post.orgId(),"company").get("summary")).get("committed_cents")).longValue()).isEqualTo(7000);
  assertThat(((Number)((Map<?,?>)workbench.ledger(post.orgId(),"project:"+otherProject).get("summary")).get("committed_cents")).longValue()).isEqualTo(8000);
  assertThatThrownBy(()->workbench.ledger(post.orgId()+10000,"project:"+project)).isInstanceOf(RuntimeException.class);
 }
 @Test void manualEntryResultWaitsForCommitAndStaysWithItsOriginalPost() throws Exception {
  var anotherPost=new PostSnapshot(post.postId()+1,post.userId(),post.orgId(),post.orgType(),post.roleCode(),post.teamId(),post.lead(),post.managedTeamIds());
  for(String type:List.of("receipt","expense","payable")){
   String key="result-"+type;
   assertThat(workbench.registrationResult(post,key)).containsEntry("state","NOT_REGISTERED");
   var cmd=new FinWorkbenchService.EntryCommand("company",type,"原手工收付","其他",10000,LocalDate.now(),"往来方","公司银行",null,null);
   long id=workbench.register(post,key,cmd);
   assertThat(workbench.registrationResult(post,key)).containsEntry("id",Long.toString(id));
   assertThat(workbench.registrationResult(anotherPost,key)).containsEntry("state","NOT_REGISTERED");
   assertThat(workbench.register(post,key,cmd)).isEqualTo(id);
  }
  workbench.createBook(post,"book-only-result",bookCommand("前期账本"));
  assertThat(workbench.registrationResult(post,"book-only-result")).containsEntry("state","NOT_REGISTERED");
  var written=new java.util.concurrent.CountDownLatch(1);var commit=new java.util.concurrent.CountDownLatch(1);
  var pool=Executors.newFixedThreadPool(2);
  try {
   var registration=pool.submit(()->new org.springframework.transaction.support.TransactionTemplate(transactions).execute(tx->{
    long id=workbench.register(post,"in-flight-result",new FinWorkbenchService.EntryCommand("company","expense","处理中原付款","其他",25000,LocalDate.now(),"原收款方","银行",null,null));
    written.countDown();
    try{if(!commit.await(8,TimeUnit.SECONDS))throw new AssertionError("commit was not released");}
    catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new RuntimeException(interrupted);}
    return id;
   }));
   assertThat(written.await(8,TimeUnit.SECONDS)).isTrue();
   var lookup=pool.submit(()->workbench.registrationResult(post,"in-flight-result"));
   org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).until(()->Boolean.TRUE.equals(jdbc.queryForObject(
       "SELECT EXISTS(SELECT 1 FROM pg_locks WHERE locktype='advisory' AND NOT granted)",Boolean.class)));
   assertThat(lookup.isDone()).isFalse();commit.countDown();
   long id=registration.get(10,TimeUnit.SECONDS);
   assertThat(lookup.get(10,TimeUnit.SECONDS)).containsEntry("id",Long.toString(id));
  } finally {commit.countDown();pool.shutdownNow();}
 }
 @Test void manualEntryIsAtomicIdempotentAndScoped(){
  long book=workbench.createBook(post,"book-a",bookCommand("张先生建房"));
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
  long book=workbench.createBook(post,"prebook",bookCommand("前期账本"));
  workbench.register(post,"pre-expense",new FinWorkbenchService.EntryCommand("book:"+book,"expense","前期运输","运输",86000,LocalDate.now(),"运输方","银行",null,null));
  long project=com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
  jdbc.update("INSERT INTO fr_eng_project(id,company_id,name,status) VALUES(?,?,?,'ACTIVE')",project,post.orgId(),"工程项目");
  linkBook(book,project,"link-project");
  var newSummary=(Map<?,?>)workbench.ledger(post.orgId(),"project:"+project).get("summary");
  assertThat(((Number)newSummary.get("paid_cents")).longValue()).isEqualTo(86000);
  assertThat(new FinProjectLedgerService(jdbc).page(post.orgId(),project,1,20).summary().paidCents()).isEqualTo(86000);
 }
 @Test void employeeBalancesRemainInTheirBookAfter501And1001CashEntries(){
  for(int count:List.of(501,1001)) {
   long book=workbench.createBook(post,"large-book-"+count,bookCommand("完整员工往来"+count));
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
  long book=workbench.createBook(post,"employee-book",bookCommand("前期员工往来"));
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
  linkBook(book,project,"link-employee-book");
  linkBook(book,project,"link-employee-book");
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
  long book=workbench.createBook(post,"advance-book",bookCommand("垫付项目"));
  long project=com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
  jdbc.update("INSERT INTO fr_eng_project(id,company_id,name,status) VALUES(?,?,?,'ACTIVE')",project,post.orgId(),"工程项目");
  linkBook(book,project,"advance-project");
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
 @Test void bossProjectLedgerKeepsTypedOriginalsExactIdsAndTheirOwnEvidence(){
  long project=assignmentProject("只读追溯工程"),other=assignmentProject("另一工程");
  long shared=9007199254740993L,receiptVoucher=9007199254740995L,paymentVoucher=9007199254740997L,refundVoucher=9007199254740999L;
  long payment=workbench.register(post,"readonly-payment",new FinWorkbenchService.EntryCommand("project:"+project,"expense","原运输费","运输",86000,LocalDate.now(),"运输商","公司账户","付款流水",null));
  jdbc.update("UPDATE fr_fin_payment SET id=?,voucher_file_id=? WHERE id=?",shared,paymentVoucher,payment);
  long receipt=workbench.register(post,"readonly-receipt",new FinWorkbenchService.EntryCommand("project:"+project,"receipt","客户回款","工程款",100000,LocalDate.now(),"客户甲","收款账户","收款流水",null));
  jdbc.update("UPDATE fr_fin_receipt SET id=?,voucher_file_id=? WHERE id=?",shared,receiptVoucher,receipt);
  long refund=refunds.registerExpense(post,"readonly-refund",new FinRefundService.ExpenseRefundCommand(shared,1000,"运费退回",LocalDate.now(),"退回流水",null,null,"COMPANY"));
  jdbc.update("UPDATE fr_fin_refund SET id=?,voucher_file_id=? WHERE id=?",shared,refundVoucher,refund);
  workbench.supplement(post,shared,"readonly-payment-note",new FinWorkbenchService.Supplement("payment","付款补充说明",null));
  workbench.supplement(post,shared,"readonly-receipt-note",new FinWorkbenchService.Supplement("receipt","收款补充说明",null));
  workbench.supplement(post,shared,"readonly-refund-note",new FinWorkbenchService.Supplement("refund","退回补充说明",null));
  long advance=workbench.register(post,"readonly-advance",new FinWorkbenchService.EntryCommand("project:"+project,"expense","员工交通费","差旅",2000,LocalDate.now(),"车队","员工账户",null,null,"EMPLOYEE",post.userId(),"员工甲"));
  long employeeReturn=refunds.registerExpense(post,"readonly-employee-refund",new FinRefundService.ExpenseRefundCommand(advance,500,"交通退回员工",LocalDate.now(),null,null,null,"EMPLOYEE"));
  jdbc.update("INSERT INTO fr_fin_receipt(id,company_id,project_id,name,source_type,amount_cents,status,registrar_user_id) VALUES(?,?,?,'未核实原收款','manual',3000,'待登记',?)",shared+100,post.orgId(),project,post.userId());
  workbench.register(post,"readonly-other",new FinWorkbenchService.EntryCommand("project:"+other,"receipt","另一工程回款","工程款",999999,LocalDate.now(),"其他客户","其他账户",null,null));
  var result=new FinProjectLedgerService(jdbc).page(post.orgId(),project,1,20);
  assertThat(result.summary().receivedCents()).isEqualTo(100000);
  assertThat(result.summary().paidCents()).isEqualTo(86000);
  assertThat(result.summary().expenseRefundedCents()).isEqualTo(1000);
  var originals=result.entries().getList().stream().filter(row->row.id()==shared).collect(java.util.stream.Collectors.toMap(FinProjectLedgerService.Row::type,row->row));
  assertThat(originals).containsOnlyKeys("PAYMENT","RECEIPT","EXPENSE_REFUND");
  assertThat(originals.values()).allSatisfy(row->assertThat(row.recordId()).isEqualTo(Long.toString(shared)));
  assertThat(originals.get("PAYMENT").details()).isEqualTo(new FinProjectLedgerService.Details("原运输费","运输","运输商","公司账户","付款流水",Long.toString(paymentVoucher),"付款补充说明"));
  assertThat(originals.get("RECEIPT").details()).isEqualTo(new FinProjectLedgerService.Details("客户回款","工程款","客户甲","收款账户","收款流水",Long.toString(receiptVoucher),"收款补充说明"));
  var returnDetails=originals.get("EXPENSE_REFUND").details();
  assertThat(returnDetails.voucherFileId()).isEqualTo(Long.toString(refundVoucher));
  assertThat(returnDetails.transactionNo()).isEqualTo("退回流水");
  assertThat(returnDetails.counterparty()).isEqualTo("运输商");
  assertThat(returnDetails.accountName()).isNull();
  assertThat(returnDetails.note()).isEqualTo("退回补充说明");
  assertThat(result.entries().getList()).anySatisfy(row->{assertThat(row.id()).isEqualTo(employeeReturn);assertThat(row.type()).isEqualTo("EMPLOYEE_REFUND");});
  assertThat(result.entries().getList()).anySatisfy(row->{assertThat(row.id()).isEqualTo(shared+100);assertThat(row.type()).isEqualTo("PENDING_RECEIPT");});
  assertThat(result.entries().getList()).noneMatch(row->"另一工程回款".equals(row.details().name()));
  assertThatThrownBy(()->new FinProjectLedgerService(jdbc).page(post.orgId()+10000,project,1,20)).isInstanceOf(RuntimeException.class);
 }
 @Test void historicalEngineeringExpenseKeepsItsNameWithoutChangingCashOrCrossingOrganizations(){
  jdbc.execute("CREATE TABLE IF NOT EXISTS fr_eng_material(id bigint,company_id bigint,name text)");
  long project=com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
  jdbc.update("INSERT INTO fr_eng_project(id,company_id,name,status) VALUES(?,?,?,'ACTIVE')",project,post.orgId(),"周建军乡墅");
  long q=payments.registerPayable(post,"historical-labor",new FinPaymentService.PayableCommand(post.orgId(),project,"engineering",350000,"兴业劳务班组","施工费用"));
  jdbc.update("INSERT INTO fr_eng_expense(id,company_id,project_id,payable_id,name,purpose,amount_cents,author_user_id) VALUES(?,?,?,?,?,'LABOR',350000,?)",com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),post.orgId(),project,q,"场地排水与围挡人工",post.userId());
  jdbc.update("INSERT INTO fr_eng_expense(id,company_id,project_id,payable_id,name,purpose,amount_cents,author_user_id) VALUES(?,?,?,?,?,'LABOR',350000,?)",com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),post.orgId()+10000,project,q,"其他组织费用",post.userId());
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
