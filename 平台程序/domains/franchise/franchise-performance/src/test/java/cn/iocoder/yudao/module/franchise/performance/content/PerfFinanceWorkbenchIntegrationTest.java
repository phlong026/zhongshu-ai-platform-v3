package cn.iocoder.yudao.module.franchise.performance.content;
import cn.iocoder.yudao.module.franchise.test.FranchisePgIntegrationTest;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
class PerfFinanceWorkbenchIntegrationTest extends FranchisePgIntegrationTest {
 @Resource JdbcTemplate jdbc;
 @Test void bossCashDebtAndAgingUseTheSameEffectiveFacts(){
  // 经营域独立测试：以跨域查询契约建最小表；生产由各域迁移建完整表。
  jdbc.execute("CREATE TABLE IF NOT EXISTS fr_fin_receipt(id bigint,company_id bigint,amount_cents bigint,status text,received_date date,source_type text,source_ref text,name text,registrar_user_id bigint)");
  jdbc.execute("CREATE TABLE IF NOT EXISTS fr_fin_payment(id bigint,company_id bigint,payable_id bigint,amount_cents bigint,pending_link_cents bigint,status text,pay_date date,funding_type text,operator_user_id bigint)");
  jdbc.execute("CREATE TABLE IF NOT EXISTS fr_fin_refund(id bigint,company_id bigint,receipt_id bigint,payment_id bigint,amount_cents bigint,status text,date date,replaced_by_id bigint,refund_direction text,funding_type text)");
  jdbc.execute("CREATE TABLE IF NOT EXISTS fr_fin_payable(id bigint,company_id bigint,total_cents bigint,source_type text,source_ref text,business_date date,create_time timestamptz default now(),due_date date,recipient text)");
  jdbc.execute("CREATE TABLE IF NOT EXISTS fr_sale_contract(id bigint,company_id bigint,status text,deleted boolean default false,signed_date date)");
  jdbc.execute("CREATE TABLE IF NOT EXISTS fr_sale_payment_stage(id bigint,company_id bigint,contract_id bigint,amount_cents bigint,deleted boolean default false,due_date date)");
  jdbc.execute("CREATE TABLE IF NOT EXISTS fr_sale_stage_receipt(company_id bigint,contract_id bigint,stage_id bigint,receipt_id bigint)");
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
}
