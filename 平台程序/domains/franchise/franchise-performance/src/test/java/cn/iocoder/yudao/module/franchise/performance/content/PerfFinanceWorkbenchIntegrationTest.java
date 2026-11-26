package cn.iocoder.yudao.module.franchise.performance.content;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.springframework.context.annotation.Import;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.command.CommandLogImpl;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
@Import({PerfOperatingService.class, FranchiseCommands.class, CommandLogImpl.class})
class PerfFinanceWorkbenchIntegrationTest extends FranchisePgIntegrationTest {
 @Resource JdbcTemplate jdbc;
 @Resource PerfOperatingService operating;
 @Resource FranchiseCommands commands;

 @Test @org.springframework.transaction.annotation.Transactional
 void monthConfirmationSeparatesCompanyPaymentsFromAdvancesAndKeepsSavedFactsAfterChanges(){
  createCashTables();
  var actor=fixture.post(fixture.store().orgId(),"finance",null);long org=actor.orgId();
  jdbc.update("INSERT INTO fr_fin_receipt VALUES(1,?,100000,'已登记','2026-11-01')",org);
  jdbc.update("INSERT INTO fr_fin_payment VALUES(2,?,20000,'已核实','2026-11-02','COMPANY'),(3,?,30000,'已核实','2026-11-03','EMPLOYEE'),(4,?,99999,'已核实','2026-11-02','COMPANY'),(5,?,88888,'已作废','2026-11-02','COMPANY'),(6,?,77777,'已核实','2026-12-01','COMPANY')",org,org,org+1,org,org);
  var preview=operating.monthReconciliation(actor,"2026-11");
  var current=(Map<String,Object>)preview.get("current");
  assertThat(preview.get("confirmed")).isNull();
  assertThat(current.get("companyPaidCents")).isEqualTo(20000L);
  assertThat(current.get("companyPaidCount")).isEqualTo(1);
  assertThat(current.get("paymentCount")).isEqualTo(1);
  assertThat(current.get("employeeAdvanceCents")).isEqualTo(30000L);
  assertThat(current.get("employeeAdvanceCount")).isEqualTo(1);
  String signature=(String)current.get("sourceSignature");
  var saved=operating.reconcile(actor,"2026-11",signature,"month-first");
  assertThat(saved.get("paidCents")).isEqualTo(20000L);
  assertThat(saved.get("netCents")).isEqualTo(80000L);
  assertThat(saved.get("byUserId")).isEqualTo(String.valueOf(actor.userId()));
  assertThat(saved.get("byName")).isNotNull();assertThat(saved.get("updateTime")).isInstanceOf(String.class);
  assertThat(java.time.OffsetDateTime.parse((String)saved.get("updateTime"))).isNotNull();
  assertThat(operating.monthReconciliation(actor,"2026-11").get("needsReview")).isEqualTo(false);
  // A later reimbursement is COMPANY cash out, while the original employee advance stays separate.
  jdbc.update("INSERT INTO fr_fin_payment VALUES(7,?,10000,'已核实','2026-11-05','COMPANY')",org);
  jdbc.update("INSERT INTO fr_fin_refund VALUES(8,?,1000,'已核实','2026-11-05',NULL,'client','COMPANY'),(9,?,4000,'已核实','2026-11-05',NULL,'expense','COMPANY'),(10,?,2000,'已核实','2026-11-05',NULL,'expense','EMPLOYEE'),(11,?,7000,'已核实','2026-11-05',12,'expense','COMPANY')",org,org,org,org);
  var after=operating.monthReconciliation(actor,"2026-11");
  var latest=(Map<String,Object>)after.get("current");
  var confirmed=(Map<String,Object>)after.get("confirmed");
  assertThat(after.get("needsReview")).isEqualTo(true);
  assertThat(latest.get("companyPaidCents")).isEqualTo(30000L);
  assertThat(latest.get("companyPaidCount")).isEqualTo(2);
  assertThat(latest.get("netCompanyPaidCents")).isEqualTo(26000L);
  assertThat(latest.get("netReceivedCents")).isEqualTo(99000L);
  assertThat(latest.get("netCents")).isEqualTo(73000L);
  assertThat(latest.get("employeeNetAdvanceCents")).isEqualTo(28000L);
  assertThat(confirmed.get("netCents")).isEqualTo(saved.get("netCents"));
  assertThat(confirmed.get("updateTime")).isEqualTo(saved.get("updateTime"));
  // Lost acknowledgement retries the exact confirmation; it cannot silently confirm newly entered cash.
  assertThat(JsonUtils.toJsonString(operating.reconcile(actor,"2026-11",signature,"month-first"))).isEqualTo(JsonUtils.toJsonString(saved));
  assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_command_event WHERE org_id=? AND command='perf-month-reconcile'",Long.class,org)).isEqualTo(1);
  // Explicitly confirming the newer ledger does not erase the first command's immutable acknowledgement.
  operating.reconcile(actor,"2026-11",(String)latest.get("sourceSignature"),"month-second");
  assertThat(JsonUtils.toJsonString(operating.reconcile(actor,"2026-11",signature,"month-first"))).isEqualTo(JsonUtils.toJsonString(saved));
  var latestConfirmed=(Map<String,Object>)operating.monthReconciliation(actor,"2026-11").get("confirmed");
  assertThat(latestConfirmed.get("netCents")).isEqualTo(73000L);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_command_event WHERE org_id=? AND command='perf-month-reconcile'",Long.class,org)).isEqualTo(2);
 }

 @Test @org.springframework.transaction.annotation.Transactional
 void employeeRecoveryIsASubsetOfCashReceiptsAndKeepsLegacyConfirmation() {
  createCashTables();
  var actor=fixture.post(fixture.store().orgId(),"finance",null);
  var other=fixture.post(fixture.store().orgId(),"finance",null);
  long org=actor.orgId();
  jdbc.update("""
      INSERT INTO fr_fin_receipt VALUES
      (1,?,1000000,'已登记','2026-11-01'), (2,?,20000,'已登记','2026-11-02'),
      (3,?,99000,'已作废','2026-11-03'), (4,?,88000,'已登记','2026-12-01'),
      (5,?,77000,'已登记','2026-11-05')
      """,org,org,org,org,other.orgId());
  jdbc.update("INSERT INTO fr_fin_refund VALUES(6,?,3000,'已核实','2026-11-06',NULL,'expense','EMPLOYEE')",org);
  for (long id : List.of(2L,3L,4L,5L)) {
   commands.record(id==5L?other:actor,"fin-employee-recovery",id,1,"fin-employee-recovery",Map.of("fixture",id),"recover-fixture-"+id);
  }
  var oldFacts=jdbc.queryForList("""
      SELECT 'receipt' AS kind,id::text AS id,amount_cents,'COMPANY' AS funding,received_date::text AS business_date
      FROM fr_fin_receipt WHERE company_id=? AND status='已登记'
          AND received_date>='2026-11-01'::date AND received_date<'2026-12-01'::date
      UNION ALL
      SELECT refund_direction,id::text,amount_cents,funding_type,date::text FROM fr_fin_refund WHERE company_id=?
      ORDER BY kind,id
      """,org,org);
  String oldSignature=PerfSupport.signature(oldFacts.stream().map(JsonUtils::toJsonString).toList());
  jdbc.update("""
      INSERT INTO fr_perf_reconciliation(id,company_id,period,income_cents,paid_cents,net_cents,receipt_count,payment_count,by_user_id,source_snapshot)
      VALUES(?,?, '2026-11',1020000,0,1020000,2,0,?,?)
      """,com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),org,actor.userId(),
      JsonUtils.toJsonString(Map.of("sourceSignature",oldSignature,"grossReceiptsCents",1020000,"grossReceiptsCount",2)));
  var preview=operating.monthReconciliation(actor,"2026-11");
  var current=(Map<String,Object>)preview.get("current");
  var oldConfirmed=(Map<String,Object>)preview.get("confirmed");
  assertThat(preview.get("needsReview")).isEqualTo(false);
  assertThat(oldConfirmed).containsEntry("incomeCents",1020000L).doesNotContainKeys("employeeRecoveryCents","employeeRecoveryCount");
  assertThat(current).containsEntry("employeeRecoveryCents",20000L).containsEntry("employeeRecoveryCount",1)
      .containsEntry("employeeReturnedCents",3000L).containsEntry("grossReceiptsCents",1020000L)
      .containsEntry("grossReceiptsCount",2).containsEntry("incomeCents",1020000L)
      .containsEntry("netCents",1020000L).containsEntry("sourceSignature",oldSignature);
  var confirmed=operating.reconcile(actor,"2026-11",oldSignature,"employee-recovery-breakdown");
  assertThat(((Number)confirmed.get("employeeRecoveryCents")).longValue()).isEqualTo(20000L);
  assertThat(((Number)confirmed.get("employeeRecoveryCount")).intValue()).isEqualTo(1);
  assertThat(JsonUtils.toJsonString(operating.reconcile(actor,"2026-11",oldSignature,"employee-recovery-breakdown")))
      .isEqualTo(JsonUtils.toJsonString(confirmed));
 }

 @Test @org.springframework.transaction.annotation.Transactional
 void stalePreviewCannotConfirmAndEqualTotalsStillDetectChangedSourceRows(){
  createCashTables();var actor=fixture.post(fixture.store().orgId(),"finance",null);long org=actor.orgId();
  jdbc.update("INSERT INTO fr_fin_payment VALUES(1,?,20000,'已核实','2026-11-03','COMPANY')",org);
  var current=(Map<String,Object>)operating.monthReconciliation(actor,"2026-11").get("current");
  String old=(String)current.get("sourceSignature");
  operating.reconcile(actor,"2026-11",old,"before-change");
  jdbc.update("UPDATE fr_fin_payment SET id=2 WHERE id=1");
  assertThat(operating.monthReconciliation(actor,"2026-11").get("needsReview")).isEqualTo(true);
  assertThatThrownBy(()->operating.reconcile(actor,"2026-11",old,"stale-preview"))
      .isInstanceOf(ServiceException.class).hasMessageContaining("账有变化");
  assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_command_event WHERE org_id=? AND command='perf-month-reconcile'",Long.class,org)).isEqualTo(1);
 }

 @Test @org.springframework.transaction.annotation.Transactional
 void legacyConfirmationKeepsItsOriginalAmountsAndReplaysOldFingerprint(){
  var actor=fixture.post(fixture.store().orgId(),"finance",null);long org=actor.orgId(),id=com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
  jdbc.update("INSERT INTO fr_perf_reconciliation(id,company_id,period,income_cents,paid_cents,net_cents,receipt_count,payment_count,by_user_id) VALUES(?,?,'2026-11',100000,20000,80000,1,2,?)",id,org,actor.userId());
  commands.record(actor,PerfOperatingService.AGGREGATE,id,1,"perf-month-reconcile",Map.of("period","2026-11"),"old-version");
  var saved=operating.reconcile(actor,"2026-11","old-version");
  assertThat(saved.get("legacy")).isEqualTo(true);
  assertThat(saved.get("netCents")).isEqualTo(80000L);
  assertThat(saved.get("paymentCount")).isEqualTo(2);
  assertThat(saved).doesNotContainKey("sourceSignature");
  assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_command_event WHERE org_id=? AND command='perf-month-reconcile'",Long.class,org)).isEqualTo(1);
 }

 private void createCashTables(){
  // Only cross-domain cash facts are temporary; confirmation and idempotency use migrated production tables.
  jdbc.execute("CREATE TEMP TABLE fr_fin_receipt(id bigint,company_id bigint,amount_cents bigint,status text,received_date date)");
  jdbc.execute("CREATE TEMP TABLE fr_fin_payment(id bigint,company_id bigint,amount_cents bigint,status text,pay_date date,funding_type text)");
  jdbc.execute("CREATE TEMP TABLE fr_fin_refund(id bigint,company_id bigint,amount_cents bigint,status text,date date,replaced_by_id bigint,refund_direction text,funding_type text)");
 }


 @Test @org.springframework.transaction.annotation.Transactional
 void allocatedDepositAndRefundUseOneCurrentOwnerAndTheirOriginalOccurrenceMonths(){
  createTables();
  jdbc.execute("ALTER TABLE fr_sale_contract ADD total_cents bigint, ADD author_user_id bigint");
  jdbc.execute("ALTER TABLE fr_eng_project ADD manager_user_id bigint");
  // 本域测试无销售/内容/工程生产依赖，临时表只提供跨域只读查询所需列。
  jdbc.execute("CREATE TEMP TABLE fr_customer(id bigint,company_id bigint,owner_user_id bigint)");
  jdbc.execute("CREATE TEMP TABLE fr_customer_follow(customer_id bigint,company_id bigint,create_time timestamptz)");
  jdbc.execute("CREATE TEMP TABLE fr_eng_plan_node(id bigint,company_id bigint,status text,completed_at date,end_date date,responsible_user_id bigint)");
  jdbc.execute("CREATE TEMP TABLE fr_content_publication(id bigint,company_id bigint,status text,published_at timestamptz,author_user_id bigint)");
  jdbc.execute("CREATE TEMP TABLE fr_lead(id bigint,company_id bigint,status text,update_time timestamptz,registrar_user_id bigint)");
  jdbc.execute("CREATE TEMP TABLE fr_eng_supply_receipt(id bigint,company_id bigint,kind text,actual_quantity numeric,quality text,quality_resolution jsonb,occurred_at date,author_user_id bigint)");
  jdbc.execute("CREATE TEMP TABLE fr_commission(id bigint,company_id bigint)");
  jdbc.execute("CREATE TEMP TABLE fr_commission_submission(commission_id bigint,version int,total_cents bigint,submitted_by_user_id bigint,create_time timestamptz)");
  jdbc.execute("CREATE TEMP TABLE fr_commission_progress(id bigint,commission_id bigint,company_id bigint,kind text,actor_user_id bigint,create_time timestamptz)");
  var actor=fixture.post(fixture.store().orgId(),"finance",null);long org=actor.orgId();
  jdbc.update("INSERT INTO fr_customer VALUES(20,?,101)",org);
  jdbc.update("INSERT INTO fr_sale_contract(id,company_id,customer_id,status,signed_date) VALUES(10,?,20,'SIGNED','2026-11-01')",org);
  jdbc.update("INSERT INTO fr_fin_receipt(id,company_id,amount_cents,status,received_date,source_type,source_ref) VALUES(1,?,1000000,'已登记','2026-10-10','business','sale-allocation:10:30:1')",org);
  jdbc.update("INSERT INTO fr_sale_stage_receipt VALUES(?,10,30,1),(?,10,31,1)",org,org);
  jdbc.update("INSERT INTO fr_fin_refund(id,company_id,receipt_id,amount_cents,status,date,refund_direction) VALUES(2,?,1,200000,'已核实','2026-11-05','client')",org);
  var metrics=new PerfMetrics(jdbc);
  var october=metrics.sources(actor,"2026-10",List.of());
  var november=metrics.sources(actor,"2026-11",List.of());
  assertThat(PerfMetrics.taskValue(october,"auto:collectionsAmount",null)).isEqualTo(1000000);
  assertThat(PerfMetrics.taskValue(october,"auto:collectionsAmount",101L)).isEqualTo(1000000);
  assertThat(PerfMetrics.taskValue(november,"auto:collectionsAmount",null)).isEqualTo(-200000);
  assertThat(PerfMetrics.taskValue(november,"auto:collectionsAmount",101L)).isEqualTo(-200000);
  assertThat(october.stream().filter(r->r.type().equals("auto:collectionsAmount"))).hasSize(1);
  // 沿用现有实时“当前负责人”归属；换人后不复用服务实例上一次查询的永久缓存。
  jdbc.update("UPDATE fr_customer SET owner_user_id=102 WHERE id=20");
  assertThat(PerfMetrics.taskValue(metrics.sources(actor,"2026-10",List.of()),"auto:collectionsAmount",101L)).isZero();
  assertThat(PerfMetrics.taskValue(metrics.sources(actor,"2026-10",List.of()),"auto:collectionsAmount",102L)).isEqualTo(1000000);
  assertThat(PerfMetrics.taskValue(metrics.sources(actor,"2026-11",List.of()),"auto:collectionsAmount",102L)).isEqualTo(-200000);
  // 归属裁决仍能覆盖原收款以及关联退款，不改变现有申诉规则。
  var adjusted=metrics.sources(actor,"2026-11",List.of(new PerfMetrics.Adjustment("fin-receipt:1",101L,false)));
  assertThat(PerfMetrics.taskValue(adjusted,"auto:collectionsAmount",101L)).isEqualTo(-200000);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM fr_fin_receipt",Long.class)).isEqualTo(1);
  jdbc.update("UPDATE fr_customer SET company_id=? WHERE id=20",org+999);
  assertThat(PerfMetrics.taskValue(metrics.sources(actor,"2026-10",List.of()),"auto:collectionsAmount",102L)).isZero();
 }
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
  createHomeTables();
  jdbc.execute("SET LOCAL TIME ZONE 'UTC'");
  var boss=fixture.post(fixture.store().orgId(),"boss",null);
  long org=boss.orgId(); var home=new HomeMetricsService(jdbc);
  jdbc.update("INSERT INTO fr_customer VALUES(1,?,'客户','', '2026-10-31 16:00:01+00')",org);
  jdbc.update("INSERT INTO fr_sale_contract(id,company_id,customer_id,status) VALUES(10,?,1,'SIGNED'),(20,?,1,'SIGNED')",org,org);
  jdbc.update("INSERT INTO fr_eng_project VALUES(11,?,NULL,1,'旧项目'),(12,?,10,1,'已绑定')",org,org);
  jdbc.update("INSERT INTO fr_sale_payment_stage(id,company_id,customer_id,contract_id,amount_cents,due_date,stage) VALUES(31,?,1,10,850,'2020-01-01','CAP_DONE'),(32,?,1,20,500,'2020-01-01','tail')",org,org);
  jdbc.update("""
      INSERT INTO fr_fin_receipt(id,company_id,customer_id,project_id,amount_cents,status,received_date,source_type,source_ref)
      VALUES(41,?,1,11,300,'已登记','2026-11-01','business','sale:20'),(42,?,NULL,11,200,'已登记','2026-11-01','manual','employee-recovery:7'),
       (43,?,NULL,11,150,'已登记','2026-11-01','manual',NULL)
      """,org,org,org);
  jdbc.update("INSERT INTO fr_fin_refund(id,company_id,receipt_id,amount_cents,status,date,refund_direction) VALUES(51,?,41,40,'已核实','2026-11-02','client'),(52,?,42,50,'已核实','2026-11-02','client'),(53,?,43,30,'已核实','2026-11-02','client')",org,org,org);
  jdbc.update("INSERT INTO fr_sale_stage_receipt VALUES(?,20,32,41,200)",org);
  for(String role:List.of("owner","business")){
   var net=home.detail(boss,role,"2026-11","customerNet",1);
   assertThat(((Number)net.get("cents")).longValue()).isEqualTo(380L);
   assertThat((List<Map<String,Object>>)net.get("rows")).extracting(row -> row.get("id")).containsExactlyInAnyOrder("41","43","51","53");
  }
  var dues=(List<Map<String,Object>>)home.detail(boss,"owner","2026-11","projectDue",1).get("rows");
  assertThat(dues).extracting(r->r.get("stage_code"),r->r.get("amount")).containsExactlyInAnyOrder(
     tuple("tail",340L),tuple("CAP_DONE",850L));
  var customers=(List<Map<String,Object>>)home.detail(boss,"owner","2026-11","newCustomers",1).get("rows");
  assertThat(customers).extracting(r->r.get("date")).containsExactly("2026-11-01");
 }

 @Test @org.springframework.transaction.annotation.Transactional
 void rectificationMetricsAndTeamPendingUseTheOriginalAssignmentAndDeadline() {
  createHomeTables();
  var store=fixture.store();
  var boss=fixture.post(store.orgId(),"boss",null);
  var manager=fixture.post(store.orgId(),"project-manager",store.engineeringTeam());
  var assigned=fixture.post(store.orgId(),"engineer",store.engineeringTeam());
  long org=boss.orgId();
  jdbc.execute("ALTER TABLE fr_eng_project ADD manager_user_id bigint, ADD assigned_engineer_user_id bigint, ADD status text");
  jdbc.execute("ALTER TABLE fr_eng_plan_node ADD name text, ADD delay_owner_user_id bigint, ADD responsible_user_id bigint");
  jdbc.execute("CREATE TEMP TABLE fr_eng_rectification(id bigint,company_id bigint,project_id bigint,title text,state text,owner_user_id bigint,due_date date,create_time timestamptz DEFAULT now())");
  jdbc.execute("CREATE TEMP TABLE fr_eng_supply_receipt(id bigint,company_id bigint,project_id bigint,supply_id bigint,quality text,quality_resolution jsonb,occurred_at date)");
  jdbc.update("INSERT INTO fr_eng_project(id,company_id,name,manager_user_id,assigned_engineer_user_id,status) VALUES(11,?,'庭院',?,?,'IN_PROGRESS')",org,manager.userId(),assigned.userId());
  jdbc.update("""
      INSERT INTO fr_eng_rectification(id,company_id,project_id,title,state,owner_user_id,due_date) VALUES
      (51,?,11,'指派整改','REVIEW',?,'2026-12-01'), (52,?,11,'历史整改单','OPEN',NULL,NULL),
      (53,?,11,'已销项','CLOSED',?,NULL), (54,?,11,'跨公司','OPEN',?,NULL), (55,?,11,'人员已调走','OPEN',77,NULL)
      """,org,assigned.userId(),org,org,assigned.userId(),org+1,assigned.userId(),org);
  var home=new HomeMetricsService(jdbc);
  var mine=(List<Map<String,Object>>)home.detail(assigned,"engineering","2026-11","rectifications",1).get("rows");
  assertThat(mine).singleElement().satisfies(r->assertThat(r).containsEntry("id","51").containsEntry("date","2026-12-01"));
  var unassigned=(List<Map<String,Object>>)home.detail(manager,"engineering","2026-11","rectifications",1).get("rows");
  assertThat(unassigned).extracting(r->r.get("id")).containsExactlyInAnyOrder("52","55");
  assertThat(unassigned).allMatch(r->r.get("date")==null && r.get("subtitle").toString().contains("分派"));
  for(String view:List.of("owner","engineering")) {
   var orgRows=(List<Map<String,Object>>)home.detail(boss,view,"2026-11","rectifications",1).get("rows");
   assertThat(orgRows).extracting(r->r.get("id")).containsExactlyInAnyOrder("51","52","55");
  }
  var teams=new HomeTeamService(jdbc);
  var pending=(List<Map<String,Object>>)teams.records(boss,"engineering","2026-11",assigned.userId(),"pending",1).get("rows");
  assertThat(pending).extracting(r->r.get("id")).containsExactly("51");
  var managerRows=(List<Map<String,Object>>)teams.records(boss,"engineering","2026-11",manager.userId(),"pending",1).get("rows");
  assertThat(managerRows).extracting(r->r.get("id")).containsExactlyInAnyOrder("52","55");
  jdbc.update("UPDATE pf_staff_post SET status='REVOKED',revoked_at=now(),revoked_by=? WHERE id=?",boss.userId(),assigned.postId());
  var reassigned=(List<Map<String,Object>>)home.detail(manager,"engineering","2026-11","rectifications",1).get("rows");
  assertThat(reassigned).anyMatch(r->"51".equals(r.get("id"))&&r.get("subtitle").toString().contains("重新分派"));
  jdbc.update("UPDATE pf_staff_post SET status='REVOKED',revoked_at=now(),revoked_by=? WHERE id=?",boss.userId(),manager.postId());
  var unresolved=(List<Map<String,Object>>)home.detail(boss,"engineering","2026-11","rectifications",1).get("rows");
  assertThat(unresolved).hasSize(3).allMatch(r->r.get("subtitle").toString().contains("待组织分派"));
 }

 @Test @org.springframework.transaction.annotation.Transactional
 void replanningKeepsOriginalOverdueNodesOnTheEngineeringAndOwnerWorkbenches() {
  createHomeTables();
  var store=fixture.store();
  var manager=fixture.post(store.orgId(),"engineer",store.engineeringTeam());
  var boss=fixture.post(store.orgId(),"boss",null);
  long org=manager.orgId();
  java.time.LocalDate today=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
  String period=today.toString().substring(0,7);
  jdbc.execute("ALTER TABLE fr_eng_project ADD manager_user_id bigint, ADD assigned_engineer_user_id bigint, ADD customer_name text, ADD status text, ADD create_time timestamptz, ADD area numeric");
  jdbc.execute("ALTER TABLE fr_eng_plan_node ADD name text, ADD delay_owner_user_id bigint, ADD responsible_user_id bigint");
  jdbc.execute("CREATE TEMP TABLE fr_eng_rectification(id bigint,company_id bigint,project_id bigint,title text,state text,owner_user_id bigint,due_date date)");
  jdbc.execute("CREATE TEMP TABLE fr_eng_supply_receipt(id bigint,company_id bigint,project_id bigint,supply_id bigint,quality text,quality_resolution jsonb)");
  jdbc.execute("CREATE TEMP TABLE system_users(id bigint,nickname text)");
  jdbc.update("INSERT INTO fr_eng_project(id,company_id,name,manager_user_id,status,create_time) VALUES(11,?,'改排工程',?,'IN_PROGRESS',now()-interval '5 days'),(12,?,'正常工程',?,'IN_PROGRESS',now())",org,manager.userId(),org,manager.userId());
  jdbc.update("""
      INSERT INTO fr_eng_plan_node(id,company_id,project_id,name,status,baseline_end_date,end_date) VALUES
      (51,?,11,'现场节点','IN_PROGRESS',?,?),(52,?,11,'旧节点','PENDING',NULL,?),
      (53,?,11,'已完节点','DONE',?,?),(54,?,11,'其他门店','PENDING',?,?),(55,?,12,'未来节点','PENDING',?,?)
      """,org,today.minusDays(3),today.plusDays(2),org,today.minusDays(1),org,today.minusDays(2),today.minusDays(1),
      org+1,today.minusDays(2),today.minusDays(2),org,today.plusDays(4),today.plusDays(4));
  var home=new HomeMetricsService(jdbc);
  var overdue=(List<Map<String,Object>>)home.detail(manager,"engineering",period,"overdueNodes",1).get("rows");
  assertThat(overdue).extracting(r->r.get("id"),r->r.get("date")).containsExactlyInAnyOrder(
          tuple("51",today.minusDays(3).toString()),tuple("52",today.minusDays(1).toString()));
  var next=(List<Map<String,Object>>)home.detail(manager,"engineering",period,"nextNodes",1).get("rows");
  assertThat(next).extracting(r->r.get("id"),r->r.get("date")).contains(tuple("51",today.plusDays(2).toString()));
  jdbc.update("UPDATE fr_eng_plan_node SET status='DONE' WHERE id=52");
  var attention=(List<Map<String,Object>>)home.detail(boss,"owner",period,"attention",1).get("rows");
  assertThat(attention).extracting(r->r.get("id")).containsExactly("11");
  var team=new HomeTeamService(jdbc);
  var pending=(List<Map<String,Object>>)team.records(boss,"engineering",period,manager.userId(),"overdue",1).get("rows");
  assertThat(pending).extracting(r->r.get("id")).containsExactly("51");
  var focus=(List<Map<String,Object>>)team.focus(boss,"engineering",period,1).get("rows");
  assertThat(focus).extracting(r->r.get("id")).containsExactly("11","12");
  jdbc.update("UPDATE fr_eng_plan_node SET status='DONE' WHERE id=51");
  assertThat((List<?>)home.detail(manager,"engineering",period,"overdueNodes",1).get("rows")).isEmpty();
 }

 private void createHomeTables(){
  createTables();
  jdbc.execute("CREATE TEMP TABLE fr_customer(id bigint,company_id bigint,name text,summary text,create_time timestamptz)");
  jdbc.execute("ALTER TABLE fr_eng_project ADD name text");
  jdbc.execute("CREATE TEMP TABLE fr_eng_plan_node(id bigint,company_id bigint,project_id bigint,milestone text,status text,node_index int,start_date date,end_date date,baseline_end_date date)");
  jdbc.execute("ALTER TABLE fr_sale_stage_receipt ADD amount_cents bigint");
  jdbc.execute("ALTER TABLE fr_sale_payment_stage ADD customer_id bigint, ADD stage text");
  jdbc.execute("ALTER TABLE fr_fin_receipt ADD customer_id bigint, ADD project_id bigint, ADD deleted boolean DEFAULT false, ADD transaction_no text, ADD voucher_file_id bigint");
  jdbc.execute("ALTER TABLE fr_fin_payable ADD project_id bigint, ADD customer_id bigint, ADD stage text, ADD deleted boolean DEFAULT false");
  jdbc.execute("ALTER TABLE fr_fin_payment ADD project_id bigint, ADD deleted boolean DEFAULT false, ADD transaction_no text, ADD voucher_file_id bigint");
  jdbc.execute("ALTER TABLE fr_fin_refund ADD deleted boolean DEFAULT false, ADD transaction_no text, ADD voucher_file_id bigint, ADD reason text");
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
