package cn.iocoder.yudao.module.franchise.performance.content;

import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort;
import java.util.*;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.performance.enums.FranchisePerformanceErrorCodes.*;

/** 首页数字与明细共享同一个事实查询。月份只约束月度成果，未结事项按今天计算。
 * JDBC 显式组织隔离；岗位范围与 CRM / 工程原单一致；不使用绩效调整数替代业务事实。
 */
@Service
public class HomeMetricsService {
    private final JdbcTemplate jdbc;
    public HomeMetricsService(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    record Metric(String label,String unit,boolean monthly,String note,String sql) {}
    private static final String CUSTOMER="SELECT c.id::text id,c.name title,COALESCE(c.summary,'') subtitle,(c.create_time AT TIME ZONE 'Asia/Shanghai')::date date,0::bigint amount,'customer' source_type,c.id::text source_id,c.id::text customer_id,NULL::text project_id FROM customers c WHERE ";
    private static final String PROJECT="SELECT p.id::text id,p.name title,COALESCE(p.customer_name,'') subtitle,(p.create_time AT TIME ZONE 'Asia/Shanghai')::date date,0::bigint amount,'project' source_type,p.id::text source_id,p.customer_id::text customer_id,p.id::text project_id FROM projects p WHERE ";
    private static final String NODE="SELECT n.id::text id,p.name title,n.name subtitle,n.end_date date,0::bigint amount,'node' source_type,n.milestone milestone,n.id::text source_id,p.customer_id::text customer_id,p.id::text project_id FROM fr_eng_plan_node n JOIN projects p ON p.id=n.project_id AND p.company_id=n.company_id WHERE p.status IN ('PENDING_START','IN_PROGRESS','READY_DELIVER') AND n.status<>'DONE' AND ";
    private static final String CONTRACT="SELECT s.id::text id,c.name title,'施工合同'::text subtitle,s.signed_date date,s.total_cents amount,'contract'::text source_type,s.id::text source_id,c.id::text customer_id,NULL::text project_id FROM fr_sale_contract s JOIN customers c ON c.id=s.customer_id AND c.company_id=s.company_id WHERE NOT s.deleted AND s.status='SIGNED' AND s.signed_date >= (SELECT begin_date FROM cfg) AND s.signed_date < (SELECT end_date FROM cfg)";
    private static final String COMMISSION="SELECT s.id::text id,c.name title,s.status subtitle,(s.create_time AT TIME ZONE 'Asia/Shanghai')::date date,0::bigint amount,'commission' source_type,s.id::text source_id,c.id::text customer_id,NULL::text project_id FROM fr_commission s JOIN customers c ON c.id=s.customer_id AND c.company_id=s.company_id WHERE ";
    private static final String HANDOVER="SELECT h.id::text id,s.customer_name title,h.status subtitle,(h.create_time AT TIME ZONE 'Asia/Shanghai')::date date,0::bigint amount,'handover' source_type,s.id::text source_id,s.customer_id::text customer_id,NULL::text project_id FROM fr_commission_handover h JOIN fr_commission s ON s.id=h.commission_id AND s.company_id=h.company_id WHERE h.company_id=(SELECT org FROM cfg) AND h.version=s.handover_version AND ";
    private static String month(String date) { if(date.endsWith("create_time")||date.endsWith("published_at"))date="("+date+" AT TIME ZONE 'Asia/Shanghai')"; return date+">=(SELECT begin_date FROM cfg) AND "+date+"<(SELECT end_date FROM cfg)"; }
    private static String cash(String kind,boolean customersOnly) {
        String q="SELECT * FROM cash WHERE kind='"+kind+"' AND "+month("date");
        if(customersOnly) q+=" AND customer_receipt AND customer_id IN (SELECT id::text FROM customers)";
        return q;
    }
    private Map<String,Metric> definitions(String role,PostSnapshot post) {
        var m=new LinkedHashMap<String,Metric>();
        if(role.equals("business")||role.equals("owner")) {
            add(m,"signed","本月施工签约","元",true,"已签署且未作废的施工合同，按签署日期统计。",CONTRACT);
            add(m,"customerNet","本月净回款","元",true,"财务已登记客户收款减当月有效客户退款；退款按实际发生月扣减。",cash("RECEIPT",true)+" UNION ALL "+cash("REFUND",true));
            add(m,"newCustomers","本月新增","人",true,"本月登记的客户，每位客户只计一次。",CUSTOMER+month("c.create_time"));
            add(m,"visits","实际接待","人",true,"本月明确登记为实际接待的客户去重；电话、预约和普通跟进不计入。",CUSTOMER+"EXISTS(SELECT 1 FROM fr_customer_follow f WHERE f.customer_id=c.id AND f.company_id=c.company_id AND f.contact_kind='VISIT' AND "+month("f.occurred_on")+")");
            String author=post.roleCode().equals("sales")?" AND author_user_id="+post.userId():post.roleCode().equals("sales-lead")&&post.teamId()!=null?" AND author_user_id IN (SELECT owner_user_id FROM customers)":"";
            add(m,"publications","发布内容","条",true,"已登记发布成功的内容，按实际发布时间统计。","SELECT id::text,title,platform subtitle,(published_at AT TIME ZONE 'Asia/Shanghai')::date date,0::bigint amount,'publication' source_type,work_id::text source_id,NULL::text customer_id,NULL::text project_id FROM fr_content_publication WHERE company_id=(SELECT org FROM cfg) AND status='published' AND "+month("published_at")+author);
            add(m,"opportunities","跟进商机","项",false,"有跟进记录且尚未签署施工合同的客户。",CUSTOMER+"c.last_follow_at IS NOT NULL AND NOT EXISTS(SELECT 1 FROM fr_sale_contract s WHERE s.company_id=c.company_id AND s.customer_id=c.id AND s.status='SIGNED' AND NOT s.deleted)");
            add(m,"budgets","待确认预算","项",false,"每位客户最新的预算版本，尚未登记客户确认且未签施工合同。","SELECT b.id::text id,c.name title,b.title||' · V'||b.version subtitle,(b.create_time AT TIME ZONE 'Asia/Shanghai')::date date,COALESCE(b.total_cents,0) amount,'budget' source_type,b.id::text source_id,c.id::text customer_id,NULL::text project_id FROM customers c JOIN LATERAL (SELECT * FROM fr_customer_scheme b WHERE b.customer_id=c.id AND b.company_id=c.company_id AND b.kind='BUDGET' ORDER BY version DESC LIMIT 1) b ON true WHERE b.confirmed_at IS NULL AND NOT EXISTS(SELECT 1 FROM fr_sale_contract s WHERE s.customer_id=c.id AND s.company_id=c.company_id AND s.status='SIGNED' AND NOT s.deleted)");
            add(m,"designs","设计办理中","单",false,"已提交、尚未完成工程接收的有效设计委托。",COMMISSION+"s.status NOT IN ('DRAFT','ACCEPTED','CANCELLED','VOID')");
            add(m,"commissions","设计委托","单",true,"本月首次提交的设计委托，每单计一次。",COMMISSION+"EXISTS(SELECT 1 FROM fr_commission_submission v WHERE v.commission_id=s.id AND v.company_id=s.company_id AND v.version=1 AND "+month("v.create_time")+")");
            add(m,"followups","待跟进","人",false,"未完成首次跟进，或约定回访日期已到的客户。",CUSTOMER.replace("(c.create_time AT TIME ZONE 'Asia/Shanghai')::date date","c.next_visit_date date")+"(c.last_follow_at IS NULL OR c.next_visit_date<=(SELECT today FROM cfg))");
            add(m,"handover","待移交","项",false,"设计成果已下发、待业务提交或重新提交工程移交的委托。",COMMISSION+"s.status IN ('DISPATCHED','HANDOVER_RETURNED')");
        }
        if(role.equals("engineering")||role.equals("owner")) {
            add(m,"building","在建项目","个",false,"当前处于施工中的工程。",PROJECT+"p.status='IN_PROGRESS'");
            add(m,"preparing","待开工","个",false,"已建档或已接收、尚未正式开工的工程。",PROJECT+"p.status='PENDING_START'");
            add(m,"receiving","待接收","个",false,"提交给当前工程负责人的最新待接收移交。",HANDOVER+"h.status='PENDING'"+(role.equals("engineering")?" AND h.manager_user_id="+post.userId():""));
            add(m,"nextNodes","近7天节点","个",false,"今天至未来6天计划到期、尚未完成的施工节点。",NODE+"n.end_date BETWEEN (SELECT today FROM cfg) AND (SELECT today+6 FROM cfg)");
            add(m,"toDeliver","待交付","个",false,"施工节点已完成、尚未登记交付的项目。",PROJECT+"p.status='READY_DELIVER'");
            add(m,"delivered","本月交付","个",true,"按工程交付登记日期统计，每个项目计一次。",PROJECT+"EXISTS(SELECT 1 FROM fr_eng_closure e WHERE e.project_id=p.id AND e.company_id=p.company_id AND "+month("e.delivered_at")+")");
            add(m,"overdueNodes","逾期节点","个",false,"计划结束日期早于今天且仍未完成的节点。",NODE+"n.end_date<(SELECT today FROM cfg)");
            add(m,"materials","待到货材料","单",false,"已安排供货、累计实际到货数量小于供货数量的供货单。","SELECT s.id::text id,p.name title,COALESCE(s.supplier,'待补供应商') subtitle,s.promise_date date,0::bigint amount,'material' source_type,s.id::text source_id,p.customer_id::text customer_id,p.id::text project_id FROM fr_eng_supply s JOIN projects p ON p.id=s.project_id AND p.company_id=s.company_id WHERE p.status IN ('PENDING_START','IN_PROGRESS','READY_DELIVER') AND s.quantity>COALESCE((SELECT sum(r.actual_quantity) FROM fr_eng_supply_receipt r WHERE r.supply_id=s.id AND r.company_id=s.company_id AND r.kind='RECEIPT'),0)");
            add(m,"rectifications","待整改","项",false,"待处理材料质检异常和未销项施工整改单；含待复验。","SELECT r.id::text id,p.name title,'材料质检不合格 · 待处置' subtitle,r.occurred_at date,0::bigint amount,'quality' source_type,r.supply_id::text source_id,p.customer_id::text customer_id,p.id::text project_id FROM fr_eng_supply_receipt r JOIN projects p ON p.id=r.project_id AND p.company_id=r.company_id WHERE r.quality='REJECTED' AND (r.quality_resolution IS NULL OR r.quality_resolution->>'result' IS DISTINCT FROM '更换后合格') UNION ALL SELECT i.id::text,p.name,i.title||CASE i.state WHEN 'REVIEW' THEN ' · 待复验' ELSE ' · 待整改' END,(i.create_time AT TIME ZONE 'Asia/Shanghai')::date,0::bigint,'issue',i.id::text,p.customer_id::text,p.id::text FROM fr_eng_rectification i JOIN projects p ON p.id=i.project_id AND p.company_id=i.company_id WHERE i.state<>'CLOSED'");
            add(m,"projectDue","到期款未收","个",false,"有到期未收施工合同款的项目，每个项目计一次；明细显示待收余额。","SELECT p.id::text id,p.name title,(array_agg(d.subtitle ORDER BY d.date,d.id))[1] subtitle,(array_agg(d.stage_code ORDER BY d.date,d.id))[1] stage_code,(array_agg(d.id ORDER BY d.date,d.id))[1] stage_id,min(d.date) date,sum(d.amount)::bigint amount,'project-receivable' source_type,p.id::text source_id,p.customer_id::text customer_id,p.id::text project_id FROM projects p JOIN debts d ON d.source_id=p.effective_contract_id::text WHERE d.amount>0 AND d.date<=(SELECT today FROM cfg) GROUP BY p.id,p.name,p.customer_id");
            add(m,"attention","需关注工程","个",false,"含逾期未完节点或未销项整改单或材料质量异常的项目去重。",PROJECT+"EXISTS(SELECT 1 FROM fr_eng_plan_node n WHERE n.project_id=p.id AND n.company_id=p.company_id AND n.status<>'DONE' AND n.end_date<(SELECT today FROM cfg)) OR EXISTS(SELECT 1 FROM fr_eng_rectification i WHERE i.project_id=p.id AND i.company_id=p.company_id AND i.state<>'CLOSED') OR EXISTS(SELECT 1 FROM fr_eng_supply_receipt r WHERE r.project_id=p.id AND r.company_id=p.company_id AND r.quality='REJECTED' AND r.quality_resolution->>'result' IS DISTINCT FROM '更换后合格')");
        }
        if(role.equals("finance")||role.equals("owner")) {
            add(m,"received","已登记收款","元",true,"财务已登记的公司收款，包含经营收款及员工归还等。",cash("RECEIPT",false));
            add(m,"paid","已登记付款","元",true,"公司资金已核实付款；员工垫付不计，报销实际付出计一次。",cash("PAYMENT",false));
            add(m,"clientRefund","客户退款","元",true,"本月有效的已核实客户退款。", "SELECT id,title,subtitle,date,-amount amount,source_type,source_id,customer_id,project_id FROM ("+cash("REFUND",false)+") f");
            add(m,"expenseRefund","支出退回","元",true,"本月已核实退回公司账户的支出款。",cash("EXPENSE_REFUND",false));
            add(m,"net","收支净额","元",true,"已登记收款 + 支出退回 − 已登记付款 − 客户退款；不是利润或银行余额。","SELECT id,title,subtitle,date,CASE WHEN kind='PAYMENT' THEN -amount ELSE amount END amount,source_type,source_id,customer_id,project_id FROM cash WHERE "+month("date"));
            add(m,"receivable","待收余额","元",false,"有效施工合同各期约定款减财务确认净实收；按期分别封底为零。","SELECT * FROM debts WHERE amount>0");
            add(m,"dueReceipt","到期待收","元",false,"约定到期日不晚于今天的施工合同待收款，包含逾期款。","SELECT * FROM debts WHERE amount>0 AND date<=(SELECT today FROM cfg)");
            add(m,"payable","待付余额","元",false,"有效应付减实际核实付款；含员工待报销，扣除退款和冲抵影响。","SELECT * FROM obligations WHERE amount>0");
            add(m,"soonPayable","近期需付","元",false,"已逾期及今天至未来6天到期的待付款，未约定日期的款项不混入。","SELECT * FROM obligations WHERE amount>0 AND date<=(SELECT today+6 FROM cfg)");
            add(m,"reimbursements","待报销","笔",false,"员工垫付产生的正向未结报销应付；已包含在待付余额。","SELECT * FROM obligations WHERE amount>0 AND source_type='reimbursement'");
            add(m,"verify","待核实","笔",false,"业务或工程已申报、尚待财务登记的收款。","SELECT d.id::text id,c.name title,'合同到账申报 · 待核实' subtitle,d.received_date date,d.amount_cents amount,'declaration' source_type,d.id::text source_id,c.id::text customer_id,NULL::text project_id FROM fr_sale_collection_declaration d JOIN fr_sale_payment_stage s ON s.id=d.stage_id AND s.company_id=d.company_id JOIN customers c ON c.id=s.customer_id AND c.company_id=s.company_id WHERE d.status='PENDING'");
            add(m,"incomplete","待完善","笔",false,"已生效的收付款与退款中缺少交易号或凭据的记录。","SELECT id,title,subtitle,date,amount,source_type,source_id,customer_id,project_id FROM cash WHERE missing");
        }
        return m;
    }
    private static void add(Map<String,Metric> m,String key,String label,String unit,boolean monthly,String note,String sql) {m.put(key,new Metric(label,unit,monthly,note,sql));}
    private void authorize(PostSnapshot p,String role,String period) {
        if(!PerfMetrics.validPeriod(period)) throw exception(PERF_PERIOD_INVALID);
        if(!"store".equals(p.orgType())) throw exception(PERFORMANCE_FORBIDDEN);
        var permitted=switch(role) {
            case "business" -> Set.of("sales","sales-lead","boss","owner");
            case "engineering" -> Set.of("engineer","project-manager","boss","owner");
            case "finance" -> Set.of("finance","finance-lead","boss","owner");
            case "owner" -> Set.of("boss","owner");
            default -> Set.<String>of();
        };
        if(!permitted.contains(p.roleCode())) throw exception(PERFORMANCE_FORBIDDEN);
    }
    private Object[] args(PostSnapshot p,String period) {var begin=YearMonth.parse(period).atDay(1);return new Object[]{p.orgId(),begin,begin.plusMonths(1),LocalDate.now(ZoneId.of("Asia/Shanghai"))};}
    private String cte(PostSnapshot p) {
        String customerScope="";
        if(p.roleCode().equals("sales")) customerScope=" AND c.owner_user_id="+p.userId();
        if(p.roleCode().equals("sales-lead")&&p.teamId()!=null) {
            String teams=p.managedTeamIds().stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
            customerScope=" AND (c.owner_user_id="+p.userId()+(teams.isBlank()?"":" OR c.team_id IN ("+teams+")")+")";
        }
        String projectScope=Set.of("engineer","project-manager").contains(p.roleCode())?" AND (p.manager_user_id="+p.userId()+" OR p.assigned_engineer_user_id="+p.userId()+")":"";
        return "WITH cfg AS (SELECT ?::bigint org,?::date begin_date,?::date end_date,?::date today), customers AS (SELECT c.* FROM fr_customer c WHERE c.company_id=(SELECT org FROM cfg)"+customerScope+"), projects AS (SELECT p.*, COALESCE(p.sale_contract_id,("
                +UnifiedStageAccountPort.SIGNED_CONTRACT+"AND k.company_id=p.company_id AND k.customer_id=p.customer_id"
                +UnifiedStageAccountPort.LATEST_CONTRACT+")) effective_contract_id FROM fr_eng_project p WHERE p.company_id=(SELECT org FROM cfg)"+projectScope+"), "+COMMON;
    }
    private static final String COMMON="""
        valid_refunds AS (SELECT * FROM fr_fin_refund WHERE company_id=(SELECT org FROM cfg) AND status='已核实' AND replaced_by_id IS NULL AND NOT deleted),
        receipts AS (
          SELECT r.*, (r.source_type IN ('business','engineering') OR
             (r.source_type='manual' AND r.customer_id IS NOT NULL AND COALESCE(r.source_ref,'') NOT LIKE 'employee-recovery:%')) customer_receipt
          FROM fr_fin_receipt r WHERE r.company_id=(SELECT org FROM cfg)
        ),
        cash AS (
          SELECT r.id::text id,r.name title,'已登记收款' subtitle,r.received_date date,r.amount_cents amount,'receipt'::text source_type,r.id::text source_id,COALESCE(r.customer_id,p.customer_id)::text customer_id,r.project_id::text project_id,'RECEIPT' kind,(r.voucher_file_id IS NULL OR NULLIF(trim(r.transaction_no),'') IS NULL) missing,r.customer_receipt
          FROM receipts r LEFT JOIN fr_eng_project p ON p.id=r.project_id AND p.company_id=r.company_id WHERE r.company_id=(SELECT org FROM cfg) AND r.status='已登记' AND NOT r.deleted
          UNION ALL SELECT m.id::text,COALESCE(q.stage,'付款'),COALESCE(q.recipient,'已核实付款'),m.pay_date,m.amount_cents,'payment',m.id::text,q.customer_id::text,m.project_id::text,'PAYMENT',(m.voucher_file_id IS NULL OR NULLIF(trim(m.transaction_no),'') IS NULL),false
          FROM fr_fin_payment m LEFT JOIN fr_fin_payable q ON q.id=m.payable_id AND q.company_id=m.company_id WHERE m.company_id=(SELECT org FROM cfg) AND m.status='已核实' AND m.funding_type='COMPANY' AND NOT m.deleted
          UNION ALL SELECT f.id::text,COALESCE(r.name,q.stage,'退款'),COALESCE(f.reason,'退款'),f.date,CASE WHEN f.refund_direction='client' THEN -f.amount_cents ELSE f.amount_cents END,'refund',f.id::text,COALESCE(r.customer_id,q.customer_id,p.customer_id)::text,COALESCE(r.project_id,m.project_id)::text,CASE WHEN f.refund_direction='client' THEN 'REFUND' ELSE 'EXPENSE_REFUND' END,(f.voucher_file_id IS NULL OR NULLIF(trim(f.transaction_no),'') IS NULL),r.customer_receipt
          FROM valid_refunds f LEFT JOIN receipts r ON r.id=f.receipt_id AND r.company_id=f.company_id LEFT JOIN fr_fin_payment m ON m.id=f.payment_id AND m.company_id=f.company_id LEFT JOIN fr_fin_payable q ON q.id=m.payable_id AND q.company_id=m.company_id LEFT JOIN fr_eng_project p ON p.id=r.project_id AND p.company_id=r.company_id WHERE f.refund_direction='client' OR f.funding_type='COMPANY'
        ),
        debts AS (
          SELECT s.stage stage_code,s.id::text id,c.name title,CASE s.stage WHEN 'SIGN_AFTER' THEN '签约后收款' WHEN 'BASE_DONE' THEN '基础完成款' WHEN 'CAP_DONE' THEN '封顶款' WHEN 'tail' THEN '交付尾款' ELSE s.stage END subtitle,s.due_date date,
          GREATEST(0,s.amount_cents-COALESCE((SELECT sum(r.amount_cents-COALESCE((SELECT sum(f.amount_cents) FROM valid_refunds f WHERE f.receipt_id=r.id AND f.refund_direction='client'),0)) FROM fr_sale_stage_receipt x JOIN fr_fin_receipt r ON r.id=x.receipt_id AND r.company_id=x.company_id WHERE x.stage_id=s.id AND x.company_id=s.company_id AND r.status='已登记' AND NOT r.deleted),0))::bigint amount,
          'receivable'::text source_type,s.contract_id::text source_id,c.id::text customer_id,(SELECT p.id::text FROM projects p WHERE p.effective_contract_id=s.contract_id ORDER BY p.id LIMIT 1) project_id
          FROM fr_sale_payment_stage s JOIN fr_sale_contract k ON k.id=s.contract_id AND k.company_id=s.company_id JOIN customers c ON c.id=s.customer_id AND c.company_id=s.company_id WHERE k.status='SIGNED' AND NOT k.deleted AND NOT s.deleted
        ),
        obligations AS (
          SELECT q.id::text id,COALESCE(q.recipient,'待补收款方') title,COALESCE(q.stage,'待付款') subtitle,q.due_date date,
          (CASE WHEN q.source_type='reimbursement' THEN COALESCE((SELECT m.amount_cents-COALESCE((SELECT sum(f.amount_cents) FROM valid_refunds f WHERE f.payment_id=m.id AND f.funding_type='EMPLOYEE'),0) FROM fr_fin_payment m WHERE m.id::text=q.source_ref AND m.company_id=q.company_id AND m.status='已核实' AND NOT m.deleted),0) ELSE q.total_cents END
          -COALESCE((SELECT sum(LEAST(m.amount_cents-COALESCE(m.pending_link_cents,0),m.amount_cents-COALESCE((SELECT sum(f.amount_cents) FROM valid_refunds f WHERE f.payment_id=m.id),0))) FROM fr_fin_payment m WHERE m.payable_id=q.id AND m.company_id=q.company_id AND m.status='已核实' AND NOT m.deleted),0)
          +CASE WHEN q.source_type='reimbursement' THEN COALESCE((SELECT sum(r.amount_cents) FROM fr_fin_receipt r WHERE r.company_id=q.company_id AND r.source_ref='employee-recovery:'||q.id AND r.status='已登记' AND NOT r.deleted),0) ELSE 0 END)::bigint amount,
          CASE WHEN q.source_type='reimbursement' THEN 'reimbursement' ELSE 'payable' END source_type,q.id::text source_id,q.customer_id::text customer_id,q.project_id::text project_id
          FROM fr_fin_payable q WHERE q.company_id=(SELECT org FROM cfg) AND NOT q.deleted
        )
        """;
    private String query(PostSnapshot p,Metric m) {
        // cash 内部辅助列不向客户端暴露，所有指标共享这九个稳定字段。
        return cte(p)+", rows AS ("+m.sql()+") ";
    }
    private Map<String,Object> summary(PostSnapshot p,String key,Metric m,String period) {
        var sums=jdbc.queryForMap(query(p,m)+"SELECT count(*) total,COALESCE(sum(amount),0) cents,count(*) FILTER(WHERE date<(SELECT today FROM cfg)) overdue_count,COALESCE(sum(amount) FILTER(WHERE date<(SELECT today FROM cfg)),0) overdue_cents,min(date) first_date FROM rows",args(p,period));
        var out=new LinkedHashMap<String,Object>();out.put("key",key);out.put("label",m.label());out.put("unit",m.unit());out.put("monthly",m.monthly());out.put("note",m.note());out.putAll(sums);return out;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> home(PostSnapshot post,String role,String period) {
        authorize(post,role,period);var out=new LinkedHashMap<String,Object>();
        for(var e:definitions(role,post).entrySet())out.put(e.getKey(),summary(post,e.getKey(),e.getValue(),period));
        return Map.of("period",period,"role",role,"asOf",LocalDate.now(ZoneId.of("Asia/Shanghai")).toString(),"metrics",out);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> financialRecord(PostSnapshot post, String role, String period, String key, String type, long id) {
        authorize(post,role,period);
        var metric=definitions(role,post).get(key);
        if(metric==null) throw exception(PERF_INPUT_INVALID,"无效指标");
        String projection=switch(type) {
            case "contract" -> "SELECT id,company_id,status,\'已签署施工合同\' description,total_cents original_cents FROM fr_sale_contract WHERE NOT deleted";
            case "receivable" -> "SELECT id,company_id,stage status,\'合同约定收款期次\' description,amount_cents original_cents FROM fr_sale_payment_stage WHERE NOT deleted";
            case "receipt" -> "SELECT id,company_id,status,name description,amount_cents original_cents FROM fr_fin_receipt WHERE NOT deleted";
            case "payment" -> "SELECT id,company_id,status,'公司付款' description,amount_cents original_cents FROM fr_fin_payment WHERE NOT deleted";
            case "refund" -> "SELECT id,company_id,status,reason description,amount_cents original_cents FROM fr_fin_refund WHERE NOT deleted";
            case "payable","reimbursement" -> "SELECT id,company_id,status,recipient description,total_cents original_cents FROM fr_fin_payable WHERE NOT deleted";
            default -> throw exception(PERF_INPUT_INVALID,"无效原单类型");
        };
        var params=new ArrayList<>(Arrays.asList(args(post,period)));
        params.add(type);
        params.add(id);
        // Recheck membership at read time, including company, sales ownership, metric and month.
        var rows=jdbc.queryForList(query(post,metric)+"SELECT r.id,r.title,r.subtitle,r.date::text,r.amount,"+
            "r.customer_id,r.project_id,f.status,f.description,f.original_cents FROM rows r JOIN ("+projection+
            ") f ON f.id::text="+("receivable".equals(type)?"r.id":"r.source_id")+" AND f.company_id=(SELECT org FROM cfg) WHERE r.source_type=? AND f.id=?",params.toArray());
        if(rows.size()!=1) throw exception(PERF_INPUT_INVALID,"该原单不在当前指标或岗位范围内");
        return rows.get(0);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> detail(PostSnapshot post,String role,String period,String key,int page) {
        authorize(post,role,period);var metric=definitions(role,post).get(key);
        if(metric==null||page<1||page>100000) throw exception(PERF_INPUT_INVALID,"指标或页码无效");
        var out=summary(post,key,metric,period);out.put("period",period);out.put("page",page);out.put("pageSize",30);
        var args=new ArrayList<>(Arrays.asList(args(post,period)));args.add((page-1)*30);
        String routing = switch(key) {
            case "projectDue" -> ",stage_code,stage_id";
            case "nextNodes","overdueNodes" -> ",milestone";
            default -> "";
        };
        out.put("rows",jdbc.queryForList(query(post,metric)+"SELECT id,title,subtitle,date::text,amount,source_type,source_id,customer_id,project_id"+routing+" FROM rows ORDER BY date DESC NULLS LAST,source_type,id LIMIT 30 OFFSET ?",args.toArray()));
        return out;
    }
}
