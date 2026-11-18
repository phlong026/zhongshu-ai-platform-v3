package cn.iocoder.yudao.module.franchise.sales;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.workflow.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.*;
@Component
public class CollectionDeclarationTodoProvider implements TodoProvider {
 private final JdbcTemplate jdbc;
 public CollectionDeclarationTodoProvider(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public String domain(){return "fr-sale-finance";}
 public List<TodoItem> list(PostSnapshot post){
  if(!Set.of("finance","finance-lead").contains(post.roleCode()))return jdbc.query("SELECT d.id,d.amount_cents,d.return_reason,d.create_time,s.customer_id FROM fr_sale_collection_declaration d JOIN fr_sale_payment_stage s ON s.id=d.stage_id AND s.company_id=d.company_id WHERE d.company_id=? AND d.applicant_user_id=? AND d.status='RETURNED'",
   (rs,n)->new TodoItem(domain(),domain()+":returned:"+rs.getLong(1),"到账资料待补充 · "+rs.getString(3),"待补充",rs.getLong(2),true,null,"/pages/workflow/index?view=contract&id="+rs.getLong(5),rs.getObject(4,java.time.OffsetDateTime.class)),post.orgId(),post.userId());
  return jdbc.query("SELECT id,amount_cents,received_date,create_time FROM fr_sale_collection_declaration WHERE company_id=? AND status='PENDING' ORDER BY create_time",
   (rs,n)->new TodoItem(domain(),domain()+":"+rs.getLong(1),"业主到账 · 待财务核实","待核实",rs.getLong(2),true,null,"/pages/admin/finance-desk/index?view=flows&tab=ledger",rs.getObject(4,java.time.OffsetDateTime.class)),post.orgId());
 }
}
