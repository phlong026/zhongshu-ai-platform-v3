package cn.iocoder.yudao.module.franchise.performance.content;

import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import static cn.iocoder.yudao.module.franchise.api.EngineeringRectificationAssignments.ROUTING;
import static cn.iocoder.yudao.module.franchise.api.EngineeringRectificationAssignments.STATE_LABEL;
import org.springframework.stereotype.Service;
import java.time.YearMonth;
import java.util.*;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.performance.enums.FranchisePerformanceErrorCodes.*;

@Service
public class HomeTeamService {
    private final JdbcTemplate jdbc;
    public HomeTeamService(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private static final String CFG="WITH cfg AS (SELECT ?::bigint org,?::date begin_date,?::date end_date,(now() AT TIME ZONE 'Asia/Shanghai')::date today), ";
    private static final String CUSTOMERS="""
        customers AS (SELECT * FROM fr_customer WHERE company_id=(SELECT org FROM cfg)),
        facts AS (
          SELECT c.owner_user_id member,'customers' kind,c.id::text id,c.name title,c.summary note,c.id::text customer_id,NULL::text project_id,NULL::text node_id FROM customers c
          UNION ALL SELECT c.owner_user_id,'followups',c.id::text,c.name,c.summary,c.id::text,NULL,NULL FROM customers c,cfg
            WHERE c.last_follow_at IS NULL OR c.next_visit_date<=cfg.today
          UNION ALL SELECT DISTINCT f.author_user_id,'visitCustomers',c.id::text,c.name,'本月登记接待',c.id::text,NULL,NULL
            FROM fr_customer_follow f JOIN customers c ON c.id=f.customer_id AND c.company_id=f.company_id,cfg
            WHERE f.contact_kind='VISIT' AND f.occurred_on>=cfg.begin_date AND f.occurred_on<cfg.end_date)
        """;
    private static final String PROJECTS="""
        projects AS (SELECT * FROM fr_eng_project WHERE company_id=(SELECT org FROM cfg)),
        facts AS (
          SELECT p.manager_user_id member,'projects' kind,p.id::text id,p.name title,p.status note,p.customer_id::text customer_id,p.id::text project_id,NULL::text node_id FROM projects p
            WHERE p.status='IN_PROGRESS'
          UNION ALL SELECT COALESCE(n.delay_owner_user_id,n.responsible_user_id,p.manager_user_id),'overdue',n.id::text,p.name,n.name,p.customer_id::text,p.id::text,n.id::text
            FROM projects p JOIN fr_eng_plan_node n ON n.project_id=p.id AND n.company_id=p.company_id,cfg
            WHERE n.status<>'DONE' AND COALESCE(n.baseline_end_date,n.end_date)<cfg.today AND p.status IN ('PENDING_START','IN_PROGRESS','READY_DELIVER')
          UNION ALL SELECT routing.handler,'issue',r.id::text,p.name,r.title||
          """+STATE_LABEL+"""
            ,p.customer_id::text,p.id::text,NULL FROM projects p JOIN fr_eng_rectification r ON r.project_id=p.id AND r.company_id=p.company_id
          """+ROUTING+"""
            WHERE r.state<>'CLOSED'
          UNION ALL SELECT p.manager_user_id,'quality',r.id::text,p.name,'待处置材料质检',p.customer_id::text,p.id::text,r.supply_id::text FROM projects p
            JOIN fr_eng_supply_receipt r ON r.project_id=p.id AND r.company_id=p.company_id
            WHERE r.quality='REJECTED' AND r.quality_resolution->>'result' IS DISTINCT FROM '更换后合格')
        """;
    private String facts(PostSnapshot p,String area,String period){
        if(!"store".equals(p.orgType())||!Set.of("boss","owner").contains(p.roleCode()))throw exception(PERFORMANCE_FORBIDDEN);
        if(!PerfMetrics.validPeriod(period))throw exception(PERF_PERIOD_INVALID);
        return CFG+switch(area){
            case "business" -> CUSTOMERS;
            case "engineering" -> PROJECTS;
            default -> throw exception(PERF_INPUT_INVALID,"无效团队范围");
        };
    }
    public List<Map<String,Object>> members(PostSnapshot p,String area,String period){
        String cte=facts(p,area,period);
        String roles=area.equals("business")?"'sales','sales-lead'":"'engineer','project-manager'";
        var begin=YearMonth.parse(period).atDay(1);
        // Include staff without facts and past staff who still own work or recorded a visit during this month.
        return jdbc.queryForList(cte+", members AS (SELECT DISTINCT user_id FROM pf_staff_post,cfg WHERE org_id=cfg.org AND status='ACTIVE' AND role_code IN ("+roles+
                ") UNION SELECT member FROM facts WHERE member IS NOT NULL) SELECT m.user_id::text id,COALESCE(u.nickname,'待核对人员') name,"+
                "count(*) FILTER(WHERE f.kind IN ('customers','projects')) assigned,count(*) FILTER(WHERE f.kind IN ('followups','overdue','issue','quality')) pending,"+
                "count(*) FILTER(WHERE f.kind='visitCustomers') visits FROM members m LEFT JOIN system_users u ON u.id=m.user_id "+
                "LEFT JOIN facts f ON f.member=m.user_id GROUP BY m.user_id,u.nickname ORDER BY name,m.user_id",p.orgId(),begin,begin.plusMonths(1));
    }
    public Map<String,Object> focus(PostSnapshot p,String area,String period,int page){
        facts(p,area,period);
        if(page<1||page>100000)throw exception(PERF_INPUT_INVALID,"无效页码");
        String sql=area.equals("business")?"""
            SELECT count(*) OVER() total,c.id::text,c.name title,u.nickname owner,coalesce((SELECT f.note FROM fr_customer_follow f WHERE f.company_id=c.company_id AND f.customer_id=c.id ORDER BY f.occurred_on DESC NULLS LAST,f.create_time DESC,f.id DESC LIMIT 1),c.summary) note,c.next_visit_date::text due
            FROM fr_customer c LEFT JOIN system_users u ON u.id=c.owner_user_id
            WHERE c.company_id=? ORDER BY c.next_visit_date NULLS LAST,c.create_time DESC,c.id LIMIT 11 OFFSET ?
            """:"""
            SELECT count(*) OVER() total,p.id::text,p.name title,u.nickname owner,p.status note,p.area,
                (SELECT json_agg(json_build_object('name',n.name,'done',n.status='DONE') ORDER BY n.node_index)::text
                 FROM fr_eng_plan_node n WHERE n.company_id=p.company_id AND n.project_id=p.id) timeline
            FROM fr_eng_project p LEFT JOIN system_users u ON u.id=p.manager_user_id
            WHERE p.company_id=? AND p.status IN ('PENDING_START','IN_PROGRESS','READY_DELIVER')
            ORDER BY CASE WHEN EXISTS(SELECT 1 FROM fr_eng_plan_node n WHERE n.project_id=p.id AND n.company_id=p.company_id AND n.status<>'DONE' AND COALESCE(n.baseline_end_date,n.end_date)<(now() AT TIME ZONE 'Asia/Shanghai')::date) THEN 0 ELSE 1 END,p.create_time DESC,p.id LIMIT 11 OFFSET ?
            """;
        var rows=jdbc.queryForList(sql,p.orgId(),(page-1)*10);
        return Map.of("rows",rows.subList(0,Math.min(10,rows.size())),"more",rows.size()>10,"total",rows.isEmpty()?0:rows.get(0).get("total"));
    }
    public Map<String,Object> records(PostSnapshot p,String area,String period,long userId,String kind,int page){
        String cte=facts(p,area,period);
        var keys=area.equals("business")?Set.of("customers","followups","visitCustomers"):Set.of("projects","pending","overdue");
        if(!keys.contains(kind)||page<1||page>100000)throw exception(PERF_INPUT_INVALID,"无效明细类型或页码");
        var begin=YearMonth.parse(period).atDay(1);
        var rows=jdbc.queryForList(cte+" SELECT id,title,note,kind,customer_id,project_id,node_id FROM facts WHERE member=? AND (kind=? OR (?=\'pending\' AND kind IN (\'overdue\',\'issue\',\'quality\'))) ORDER BY id,kind LIMIT 31 OFFSET ?",
                p.orgId(),begin,begin.plusMonths(1),userId,kind,kind,(page-1)*30);
        return Map.of("rows",rows.subList(0,Math.min(30,rows.size())),"more",rows.size()>30);
    }
}
