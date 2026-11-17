package cn.iocoder.yudao.module.franchise.finance.todo;
import cn.iocoder.yudao.module.franchise.finance.FinWorkbenchService;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.workflow.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.*;
@Component
public class WorkbenchTodoProvider implements TodoProvider {
 private final FinWorkbenchService service;private final JdbcTemplate jdbc;
 public WorkbenchTodoProvider(FinWorkbenchService service,JdbcTemplate jdbc){this.service=service;this.jdbc=jdbc;}
 public String domain(){return "fr-fin-workbench";}
 public List<TodoItem> list(PostSnapshot post){
  if(!Set.of("finance","finance-lead").contains(post.roleCode()))return List.of();
  List<TodoItem> rows=new ArrayList<>();
  for(var r:service.reimbursements(post.orgId())){
   long remaining=((Number)r.get("eligible_cents")).longValue()-((Number)r.get("reimbursed_cents")).longValue()+((Number)r.get("recovered_cents")).longValue();
   if(remaining!=0)rows.add(new TodoItem(domain(),domain()+":reimburse:"+r.get("payable_id"),r.get("payer_name")+" · "+r.get("stage")+(remaining>0?"":"退款核对"),remaining>0?"待报销":"员工应归还",Math.abs(remaining),true,null,"/pages/admin/finance-desk/index?view=reimbursements",null));
  }
  for(var q:jdbc.queryForList("""
    SELECT q.id::text,CASE q.stage WHEN 'engineering' THEN '材料采购' WHEN 'engineering_expense' THEN '工程费用' ELSE COALESCE(q.stage,'采购/费用') END title,q.recipient,
      q.total_cents-COALESCE((SELECT sum(LEAST(p.amount_cents-COALESCE(p.pending_link_cents,0),p.amount_cents-COALESCE((SELECT sum(f.amount_cents) FROM fr_fin_refund f WHERE f.payment_id=p.id AND f.status='已核实'),0))) FROM fr_fin_payment p WHERE p.payable_id=q.id AND p.status='已核实'),0) remaining
    FROM fr_fin_payable q WHERE q.company_id=? AND q.status='进行中' AND q.source_type IS DISTINCT FROM 'reimbursement' AND q.source_type IS DISTINCT FROM 'design'
      AND (q.due_date IS NULL OR q.due_date<=CURRENT_DATE)
    """,post.orgId())){
   long amount=((Number)q.get("remaining")).longValue();if(amount<=0)continue;
   rows.add(new TodoItem(domain(),domain()+":payable:"+q.get("id"),q.get("recipient")+" · "+q.get("title"),"待付款",amount,true,null,"/pages/admin/finance-desk/index?view=flows&tab=payables",null));
  }
  return rows;
 }
}
