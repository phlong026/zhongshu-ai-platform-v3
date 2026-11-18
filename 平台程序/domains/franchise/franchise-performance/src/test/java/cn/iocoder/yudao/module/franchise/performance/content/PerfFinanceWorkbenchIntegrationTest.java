package cn.iocoder.yudao.module.franchise.performance.content;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
class PerfFinanceWorkbenchIntegrationTest extends FranchisePgIntegrationTest {
 @Resource JdbcTemplate jdbc;
 @Test @org.springframework.transaction.annotation.Transactional
 void bossCashDebtAndAgingUseTheSameEffectiveFacts(){
  // 事务回滚时清理临时表，避免不同测试的跨域表结构相互影响。
  createTables();
  var actor=fixture.post(fixture.store().orgId(),"finance",null);long org=actor.orgId(),id=com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
  jdbc.update("INSERT INTO fr_fin_receipt(id,company_id,amount_cents,status,received_date,source_type,name,registrar_user_id) VALUES(?,?,1000000,'已登记','2026-11-10','business','业主款',?)",id,org,actor.userId());
  jdbc.update("INSERT INTO fr_sale_contract(id,company_id,status,signed_date) VALUES(?,?,'SIGNED','2026-11-01')",id,org);
  jdbc.update("INSERT INTO fr_sale_payment_stage(id,company_id,contract_id,amount_cents) VALUES(?,?,?,900000),(?,?,?,100000)",id,org,id,id+1,org,id);
  jdbc.update("INSERT INTO fr_sale_stage_receipt(company_id,contract_id,stage_id,receipt_id) VALUES(?,?,?,?)",org,id,id,id);
  jdbc.update("INSERT INTO fr_fin_payable(id,company_id,total_cents,source_type,source_ref,business_date,recipient) VALUES(?,?,240000,'design','123:1:FULL','2026-11-10','平台'),(?,?,240000,'reimbursement',?,'2026-11-10','业务员')",id,org,id+1,org,String.valueOf(id));
  jdbc.update("INSERT INTO fr_fin_payment(id,company_id,payable_id,amount_cents,status,pay_date,funding_type,operator_user_id) VALUES(?,?,?,240000,'已核实','2026-11-10','EMPLOYEE',?),(?,?,?,100000,'已核实','2026-11-11','COMPANY',?)",id,org,id,actor.userId(),id+1,org,id+1,actor.userId());
  var report=new PerfOperatingService(null,null,null,jdbc).analytics(actor,"2026-11");
  var totals=(Map<?,?>)report.get("totals");
  assertThat(((Number)totals.get("receivableCents")).longValue()).isEqualTo(100000);
  assertThat(((Number)totals.get("payableOutstandingCents")).longValue()).isEqualTo(140000);
  var trend=(List<Map<String,Object>>)report.get("trend");
  assertThat(((Number)trend.get(trend.size()-1).get("paidCents")).longValue()).isEqualTo(100000);
  assertThat(((Number)((Map<?,?>)report.get("receivableAging")).get("undated")).longValue()).isEqualTo(100000);
  assertThat(((Number)((Map<?,?>)report.get("payableAging")).get("undated")).longValue()).isEqualTo(140000);
 }
 @Test @org.springframework.transaction.annotation.Transactional
 void legacyProjectsAndVersionedSaleTermsReconcileWithoutDoubleCounting(){
  createTables();
  var actor=fixture.post(fixture.store().orgId(),"finance",null);
  long org=actor.orgId(),contract=100,stage=101,tail=102,legacy=103,converted=104;
  jdbc.update("INSERT INTO fr_sale_contract(id,company_id,status,signed_date) VALUES(?,?,'SIGNED','2026-10-01')",contract,org);
  jdbc.update("INSERT INTO fr_sale_payment_stage(id,company_id,contract_id,amount_cents,due_date) VALUES(?,?,?,120000,'2026-12-20'),(?,?,?,20000,'2026-12-01')",stage,org,contract,tail,org,contract);
  jdbc.update("INSERT INTO fr_sale_stage_before_change VALUES(?,?,2,'2026-11-04',100000,'2026-10-10',false),(?,?,2,'2026-11-04',0,NULL,true)",org,stage,org,tail);
  jdbc.update("INSERT INTO fr_eng_project(id,company_id,sale_contract_id) VALUES(?,?,NULL),(?,?,?)",legacy,org,converted,org,contract);
  jdbc.update("INSERT INTO fr_eng_collection_term VALUES(?,?,'START',50000,'2026-10-02 00:00:00+08'),(?,?,'START',100000,'2026-10-02 00:00:00+08')",org,legacy,org,converted);
  jdbc.update("INSERT INTO fr_fin_receipt(id,company_id,amount_cents,status,received_date,source_type) VALUES(1,?,25000,'已登记','2026-10-12','business'),(2,?,10000,'已登记','2026-10-14','engineering'),(3,?,20000,'已登记','2026-11-12','business')",org,org,org);
  jdbc.update("INSERT INTO fr_sale_stage_receipt VALUES(?,?,?,1),(?,?,?,3)",org,contract,stage,org,contract,stage);
  jdbc.update("INSERT INTO fr_eng_collection VALUES(?,?,'START',25000,1,'2026-10-12'),(?,?,'START',10000,2,'2026-10-14'),(?,?,'START',5000,NULL,'2026-10-16')",org,converted,org,legacy,org,legacy);
  jdbc.update("INSERT INTO fr_fin_refund(company_id,receipt_id,amount_cents,status,date,refund_direction) VALUES(?,2,2000,'已核实','2026-10-20','client')",org);

  var service=new PerfOperatingService(null,null,null,jdbc);
  var october=service.analytics(actor,"2026-10");
  var oldTotals=(Map<?,?>)october.get("totals");
  assertThat(oldTotals.get("receivableCents")).isEqualTo(112000L);
  assertThat(oldTotals.get("collectedCents")).isEqualTo(38000L);
  assertThat(oldTotals.get("collectionRate")).isEqualTo(38000d/150000);
  assertThat(((Map<?,?>)october.get("receivableAging")).get("d1_30")).isEqualTo(75000L);
  assertThat(((Map<?,?>)october.get("receivableAging")).get("undated")).isEqualTo(37000L);

  var november=service.analytics(actor,"2026-11");
  var newTotals=(Map<?,?>)november.get("totals");
  assertThat(newTotals.get("receivableCents")).isEqualTo(132000L);
  assertThat(newTotals.get("collectedCents")).isEqualTo(58000L);
  assertThat(newTotals.get("collectionRate")).isEqualTo(58000d/190000);
  assertThat(((Map<?,?>)november.get("receivableAging")).get("notDue")).isEqualTo(95000L);
  assertThat(service.analytics(actor,"2026-10").get("totals")).isEqualTo(oldTotals);
 }

 @Test @org.springframework.transaction.annotation.Transactional
 void signedCustomerContractReplacesUnboundProjectTermsOnlyFromItsSigningDate(){
  createTables();
  var actor=fixture.post(fixture.store().orgId(),"finance",null);
  long org=actor.orgId();
  jdbc.update("INSERT INTO fr_eng_project VALUES(101,?,NULL,1001),(102,?,NULL,1002)",org,org);
  jdbc.update("""
      INSERT INTO fr_eng_collection_term VALUES
      (?,101,'START',100000,'2026-10-02 00:00:00+08'),
      (?,102,'START',50000,'2026-10-02 00:00:00+08')
      """,org,org);
  jdbc.update("""
      INSERT INTO fr_sale_contract(id,company_id,customer_id,status,signed_date)
      VALUES(201,?,1001,'SIGNED','2026-11-03'),(202,?,1002,'SIGNED','2026-10-01')
      """,org,org+1);
  jdbc.update("""
      INSERT INTO fr_sale_payment_stage(id,company_id,contract_id,amount_cents,due_date)
      VALUES(301,?,201,120000,'2026-12-20'),(302,?,202,440000,'2026-12-20')
      """,org,org+1);

  var service=new PerfOperatingService(null,null,null,jdbc);
  var october=service.analytics(actor,"2026-10");
  assertThat(((Map<?,?>)october.get("totals")).get("receivableCents")).isEqualTo(150000L);
  var november=service.analytics(actor,"2026-11");
  var totals=(Map<?,?>)november.get("totals");
  assertThat(totals.get("receivableCents")).isEqualTo(170000L);
  assertThat(totals.get("collectedCents")).isEqualTo(0L);
  assertThat(((Map<?,?>)november.get("receivableAging")).get("notDue")).isEqualTo(120000L);
  assertThat(((Map<?,?>)november.get("receivableAging")).get("undated")).isEqualTo(50000L);
 }

 @Test @org.springframework.transaction.annotation.Transactional
 void laterVoidKeepsPriorMonthTermsAndDoesNotReviveTheEngineeringCopy(){
  createTables();
  var actor=fixture.post(fixture.store().orgId(),"finance",null);
  long org=actor.orgId();
  jdbc.update("""
      INSERT INTO fr_sale_contract(id,company_id,customer_id,status,signed_date)
      VALUES(201,?,1001,'VOIDED','2026-10-01'),(202,?,1002,'VOIDED','2026-10-02')
      """,org,org);
  jdbc.update("""
      INSERT INTO pf_command_event(domain,aggregate_type,aggregate_id,org_id,revision,command,occurred_at)
      VALUES ('franchise','fr-sale-contract',201,?,2,'sale-contract-sign','2026-10-01 10:00:00+08'),
      ('franchise','fr-sale-contract',201,?,4,'sale-contract-void','2026-11-01 00:01:00+08'),
      ('franchise','fr-sale-contract',202,?,2,'sale-contract-void','2026-11-01 00:01:00+08')
      """,org,org,org);
  jdbc.update("""
      INSERT INTO fr_sale_payment_stage(id,company_id,contract_id,amount_cents,deleted)
      VALUES(301,?,201,120000,true),(302,?,202,440000,true),(303,?,201,90000,true)
      """,org,org,org);
  jdbc.update("""
      INSERT INTO fr_sale_stage_before_change VALUES
      (?,301,3,'2026-11-01',100000,NULL,false),(?,303,3,'2026-11-01',0,NULL,true)
      """,org,org);
  jdbc.update("INSERT INTO fr_eng_project VALUES(101,?,201,1001)",org);
  jdbc.update("INSERT INTO fr_eng_collection_term VALUES(?,101,'START',50000,'2026-10-02 00:00:00+08')",org);
  var service=new PerfOperatingService(null,null,null,jdbc);
  assertThat(((Map<?,?>)service.analytics(actor,"2026-10").get("totals")).get("receivableCents")).isEqualTo(100000L);
  assertThat(((Map<?,?>)service.analytics(actor,"2026-11").get("totals")).get("receivableCents")).isEqualTo(0L);
 }

 @Test @org.springframework.transaction.annotation.Transactional
 void homeUsesTheOriginalReceiptEffectiveProjectContractAndShanghaiDate() {
  createTables();
  jdbc.execute("SET LOCAL TIME ZONE 'UTC'");
  jdbc.execute("CREATE TEMP TABLE fr_customer(id bigint,company_id bigint,name text,summary text,create_time timestamptz)");
  jdbc.execute("ALTER TABLE fr_eng_project ADD name text");
  jdbc.execute("ALTER TABLE fr_sale_payment_stage ADD customer_id bigint, ADD stage text");
  jdbc.execute("ALTER TABLE fr_fin_receipt ADD customer_id bigint, ADD project_id bigint, ADD deleted boolean DEFAULT false, ADD transaction_no text, ADD voucher_file_id bigint");
  jdbc.execute("ALTER TABLE fr_fin_payable ADD project_id bigint, ADD customer_id bigint, ADD stage text, ADD deleted boolean DEFAULT false");
  jdbc.execute("ALTER TABLE fr_fin_payment ADD project_id bigint, ADD deleted boolean DEFAULT false, ADD transaction_no text, ADD voucher_file_id bigint");
  jdbc.execute("ALTER TABLE fr_fin_refund ADD deleted boolean DEFAULT false, ADD transaction_no text, ADD voucher_file_id bigint, ADD reason text");
  var boss=fixture.post(fixture.store().orgId(),"boss",null);
  long org=boss.orgId(); var home=new HomeMetricsService(jdbc);
  jdbc.update("INSERT INTO fr_customer VALUES(1,?,'客户','', '2026-10-31 16:00:01+00')",org);
  jdbc.update("INSERT INTO fr_sale_contract(id,company_id,customer_id,status) VALUES(10,?,1,'SIGNED'),(20,?,1,'SIGNED')",org,org);
  jdbc.update("INSERT INTO fr_eng_project VALUES(11,?,NULL,1,'旧项目'),(12,?,10,1,'已绑定')",org,org);
  jdbc.update("INSERT INTO fr_sale_payment_stage(id,company_id,customer_id,contract_id,amount_cents,due_date,stage) VALUES(31,?,1,10,850,'2020-01-01','CAP_DONE'),(32,?,1,20,500,'2020-01-01','tail')",org,org);
  jdbc.update("""
      INSERT INTO fr_fin_receipt(id,company_id,customer_id,project_id,amount_cents,status,received_date,source_type,source_ref)
      VALUES(41,?,1,11,300,'已登记','2026-11-01','business','sale:20'),(42,?,NULL,11,200,'已登记','2026-11-01','manual','employee-recovery:7')
      """,org,org);
  jdbc.update("INSERT INTO fr_fin_refund(id,company_id,receipt_id,amount_cents,status,date,refund_direction) VALUES(51,?,41,40,'已核实','2026-11-02','client'),(52,?,42,50,'已核实','2026-11-02','client')",org,org);
  for(String role:List.of("owner","business")){
   var net=home.detail(boss,role,"2026-11","customerNet",1);
   assertThat(((Number)net.get("cents")).longValue()).isEqualTo(260L);
   assertThat((List<?>)net.get("rows")).hasSize(2);
  }
  var dues=(List<Map<String,Object>>)home.detail(boss,"owner","2026-11","projectDue",1).get("rows");
  assertThat(dues).extracting(r->r.get("stage_code"),r->r.get("amount")).containsExactlyInAnyOrder(
     tuple("tail",500L),tuple("CAP_DONE",850L));
  var customers=(List<Map<String,Object>>)home.detail(boss,"owner","2026-11","newCustomers",1).get("rows");
  assertThat(customers).extracting(r->r.get("date")).containsExactly("2026-11-01");
 }

 private void createTables(){
  // Transaction-local temporary contract tables prevent older fixtures in the shared database from changing this test.
  jdbc.execute("CREATE TEMP TABLE fr_fin_receipt(id bigint,company_id bigint,amount_cents bigint,status text,received_date date,source_type text,source_ref text,name text,registrar_user_id bigint)");
  jdbc.execute("CREATE TEMP TABLE fr_fin_payment(id bigint,company_id bigint,payable_id bigint,amount_cents bigint,pending_link_cents bigint,status text,pay_date date,funding_type text,operator_user_id bigint)");
  jdbc.execute("CREATE TEMP TABLE fr_fin_refund(id bigint,company_id bigint,receipt_id bigint,payment_id bigint,amount_cents bigint,status text,date date,replaced_by_id bigint,refund_direction text,funding_type text)");
  jdbc.execute("CREATE TEMP TABLE fr_fin_payable(id bigint,company_id bigint,total_cents bigint,source_type text,source_ref text,business_date date,create_time timestamptz default now(),due_date date,recipient text)");
  jdbc.execute("CREATE TEMP TABLE pf_command_event(domain text,aggregate_type text,aggregate_id bigint,org_id bigint,revision bigint,command text,occurred_at timestamptz)");
  jdbc.execute("CREATE TEMP TABLE fr_sale_contract(id bigint,company_id bigint,customer_id bigint,status text,deleted boolean default false,signed_date date)");
  jdbc.execute("CREATE TEMP TABLE fr_sale_payment_stage(id bigint,company_id bigint,contract_id bigint,amount_cents bigint,deleted boolean default false,due_date date)");
  jdbc.execute("CREATE TEMP TABLE fr_sale_stage_receipt(company_id bigint,contract_id bigint,stage_id bigint,receipt_id bigint)");
  jdbc.execute("CREATE TEMP TABLE fr_sale_stage_before_change(company_id bigint,stage_id bigint,contract_revision bigint,effective_on date,amount_cents bigint,due_date date,deleted boolean)");
  jdbc.execute("CREATE TEMP TABLE fr_eng_project(id bigint,company_id bigint,sale_contract_id bigint,customer_id bigint)");
  jdbc.execute("CREATE TEMP TABLE fr_eng_collection_term(company_id bigint,project_id bigint,stage text,amount_cents bigint,create_time timestamptz)");
  jdbc.execute("CREATE TEMP TABLE fr_eng_collection(company_id bigint,project_id bigint,stage text,amount_cents bigint,receipt_id bigint,occurred_at date)");
 }
}
