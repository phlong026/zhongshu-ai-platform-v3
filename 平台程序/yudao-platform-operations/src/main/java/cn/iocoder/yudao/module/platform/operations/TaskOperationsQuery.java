package cn.iocoder.yudao.module.platform.operations;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.platform.wallet.orgpoint.OrgPointPriceService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** 以真实积分操作关联任务。无法取得远端任务状态时明确显示未关联，不以HTTP调用成功代替生成成功。 */
@Service
@Transactional(readOnly=true)
public class TaskOperationsQuery {
    private final JdbcTemplate jdbc;
    private final OperationsGuard guard;
    private final OrgPointPriceService prices;
    public TaskOperationsQuery(JdbcTemplate jdbc,OperationsGuard guard,OrgPointPriceService prices){this.jdbc=jdbc;this.guard=guard;this.prices=prices;}
    private String cte() {
        boolean clone=Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass('fr_content_digital_clone') IS NOT NULL",Boolean.class));
        String cloneJoin=clone?"LEFT JOIN fr_content_digital_clone c ON c.operation_id=r.operation_id AND c.company_id=r.org_id":"";
        String cloneStatus=clone?"c.status":"NULL::text";
        String cloneError=clone?"c.failure_reason":"NULL::text";
        String cloneTask=clone?"c.id":"NULL::bigint";
        return """
          WITH facts AS (
            SELECT 'operation:'||r.operation_id::text AS task_key,r.org_id,d.name AS org_name,d.deleted=0 AS org_writable,
              r.operation_id::text AS operation_id,r.biz_key,r.subject,r.actor_user_id::text AS actor_id,
              COALESCE(u.nickname,'—') AS actor_name,
              CASE WHEN r.ref_type='content_task' THEN 'CONTENT' WHEN r.ref_type='content-clone' THEN 'CLONE' ELSE 'DESIGN' END AS category,
              COALESCE(t.id,CLONE_TASK)::text AS task_id,
              COALESCE(t.status,CLONE_STATUS,'UNAVAILABLE') AS task_status,
              CASE WHEN x.total>1 THEN 'INCONSISTENT' WHEN x.kind='SETTLE' THEN 'SETTLED' WHEN x.kind='RELEASE' THEN 'RELEASED' ELSE 'PENDING' END AS billing_state,
              (-r.available_delta)::text AS requested,
              CASE WHEN x.total>0 THEN (-r.available_delta-COALESCE(x.delta,0))::text ELSE NULL END AS charged,
              CASE WHEN x.total>0 THEN GREATEST(COALESCE(x.delta,0),0)::text ELSE NULL END AS refunded,
              r.create_time,COALESCE(t.failed_reason,CLONE_ERROR) AS task_error,
              r.ref_type,r.ref_id::text,t.pool_reserved_units::text AS reserved_units,t.provider_task_id,
              t.pool_terminal_intent,COALESCE(t.manual_verify_decision,'') AS manual_decision
            FROM org_point_ledger r JOIN pf_org_unit o ON o.dept_id=r.org_id AND o.kind='ORG'
            JOIN system_dept d ON d.id=o.dept_id AND d.tenant_id=?
            LEFT JOIN system_users u ON u.id=r.actor_user_id AND u.tenant_id=d.tenant_id AND u.deleted=0
            LEFT JOIN fr_content_task t ON t.pool_biz_key=r.biz_key AND t.company_id=r.org_id
            CLONE_JOIN
            LEFT JOIN LATERAL (SELECT COUNT(*) AS total,MAX(l.type) AS kind,SUM(l.available_delta) AS delta
              FROM org_point_ledger l WHERE l.operation_id=r.operation_id AND l.org_id=r.org_id AND l.type IN ('SETTLE','RELEASE')) x ON true
            WHERE r.type='RESERVE'
            UNION ALL
            SELECT 'content:'||t.id::text,t.company_id,d.name,d.deleted=0,NULL::text,t.pool_biz_key,
              t.kind,t.author_user_id::text,COALESCE(u.nickname,'—'),'CONTENT',t.id::text,t.status,'MISSING',
              NULL::text,NULL::text,NULL::text,t.create_time,t.failed_reason,'content_task',t.id::text,
              t.pool_reserved_units::text,t.provider_task_id,t.pool_terminal_intent,COALESCE(t.manual_verify_decision,'')
            FROM fr_content_task t JOIN pf_org_unit o ON o.dept_id=t.company_id AND o.kind='ORG'
            JOIN system_dept d ON d.id=o.dept_id AND d.tenant_id=?
            LEFT JOIN system_users u ON u.id=t.author_user_id AND u.tenant_id=d.tenant_id AND u.deleted=0
            WHERE t.pool_biz_key IS NOT NULL AND NOT EXISTS(SELECT 1 FROM org_point_ledger r WHERE r.biz_key=t.pool_biz_key AND r.org_id=t.company_id AND r.type='RESERVE')
          ), marked AS (
            SELECT f.*,CASE
              WHEN billing_state='MISSING' THEN 'MISSING_BILLING'
              WHEN billing_state='INCONSISTENT' THEN 'INCONSISTENT'
              WHEN billing_state='PENDING' AND task_status IN ('SUBMISSION_UNCERTAIN','UNKNOWN') THEN 'UNCERTAIN'
              WHEN billing_state='PENDING' AND task_status IN ('FAILED','CANCELLED') THEN 'FAILED_PENDING'
              WHEN billing_state='PENDING' AND task_status IN ('SUCCEEDED','READY') THEN 'SUCCESS_PENDING'
              WHEN billing_state='PENDING' AND create_time<now()-interval '15 minutes' THEN 'LONG_PENDING'
              WHEN billing_state IN ('SETTLED','RELEASED') AND task_status IN ('SUBMISSION_UNCERTAIN','UNKNOWN') THEN 'STATE_MISMATCH'
              ELSE 'NONE' END AS anomaly
            FROM facts f
          )
          """.replace("CLONE_TASK",cloneTask).replace("CLONE_STATUS",cloneStatus).replace("CLONE_ERROR",cloneError).replace("CLONE_JOIN",cloneJoin);
    }
    public PageResult<Map<String,Object>> page(Long org,String subject,String keyword,boolean onlyAnomalies,String caseStatus,int page,int size) {
        var actor=guard.require();
        if(page<1||page>100000||size<1||size>100)throw new ServiceException(400,"分页参数无效");
        if(org!=null)guard.organization(actor,org,false);
        if(keyword!=null&&keyword.length()>100)throw new ServiceException(400,"搜索内容最多100字");
        if(caseStatus!=null&&!caseStatus.isBlank()&&!List.of("OPEN","WAITING_EXTERNAL","RESOLVED").contains(caseStatus))throw new ServiceException(400,"核对状态无效");
        String from=" FROM marked f LEFT JOIN pf_task_reconciliation q ON q.tenant_id=? AND q.task_key=f.task_key WHERE true";
        var args=new ArrayList<Object>(List.of(actor.tenantId(),actor.tenantId(),actor.tenantId()));
        if(org!=null){from+=" AND f.org_id=?";args.add(org);}
        if(subject!=null&&!subject.isBlank()){from+=" AND f.subject=?";args.add(subject);}
        if(keyword!=null&&!keyword.isBlank()){from+=" AND LOWER(CONCAT_WS(' ',f.task_key,f.task_id,f.biz_key,f.ref_id,f.org_name,f.actor_name)) LIKE ? ESCAPE '!'";args.add("%"+keyword.trim().toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%");}
        if(onlyAnomalies)from+=" AND (f.anomaly<>'NONE' OR q.status IN ('OPEN','WAITING_EXTERNAL'))";
        if(caseStatus!=null&&!caseStatus.isBlank()){from+=" AND COALESCE(q.status,'OPEN')=?";args.add(caseStatus);}
        String sql=cte();Long total=jdbc.queryForObject(sql+"SELECT COUNT(*)"+from,Long.class,args.toArray());
        args.add(size);args.add((page-1)*size);
        var rows=jdbc.queryForList(sql+"SELECT f.*,COALESCE(q.status,'OPEN') AS case_status,COALESCE(q.version,0) AS case_version"+from+" ORDER BY f.create_time DESC,f.task_key DESC LIMIT ? OFFSET ?",args.toArray());
        var labels=labels();rows.forEach(row->decorate(row,labels));
        return new PageResult<>(rows,total==null?0L:total);
    }
    public Map<String,Object> detail(String key) {
        var actor=guard.require();validateKey(key);
        var rows=jdbc.queryForList(cte()+"SELECT f.*,COALESCE(q.status,'OPEN') AS case_status,COALESCE(q.version,0) AS case_version FROM marked f LEFT JOIN pf_task_reconciliation q ON q.tenant_id=? AND q.task_key=f.task_key WHERE f.task_key=?",actor.tenantId(),actor.tenantId(),actor.tenantId(),key);
        if(rows.isEmpty())throw new ServiceException(404,"任务不存在或超出管理范围");
        var task=rows.get(0);decorate(task,labels());long org=((Number)task.get("org_id")).longValue();
        var ledger=task.get("operation_id")==null?List.of():jdbc.queryForList("SELECT id::text AS id,type,available_delta::text AS delta,price_snapshot::text AS price_snapshot,create_time,note FROM org_point_ledger WHERE org_id=? AND operation_id=? ORDER BY create_time,id",org,Long.parseLong(String.valueOf(task.get("operation_id"))));
        var history=jdbc.queryForList("SELECT a.id,a.action,a.reason,a.before_data::text AS before_data,a.after_data::text AS after_data,a.created_at,COALESCE(u.nickname,a.actor_id::text) AS actor_name FROM pf_ops_action_audit a LEFT JOIN system_users u ON u.id=a.actor_id AND u.tenant_id=a.tenant_id WHERE a.tenant_id=? AND a.org_id=? AND a.after_data->>'taskKey'=? ORDER BY a.created_at DESC",actor.tenantId(),org,key);
        var caseRows=jdbc.queryForList("SELECT status,version,decision,reason,evidence,updated_at FROM pf_task_reconciliation WHERE tenant_id=? AND task_key=?",actor.tenantId(),key);
        return Map.of("task",task,"ledger",ledger,"history",history,"review",caseRows.isEmpty()?Map.of():caseRows.get(0));
    }
    private Map<String,String> labels(){var result=new HashMap<String,String>();prices.catalog().forEach(p->result.put(p.subject(),p.label()));return result;}
    private void decorate(Map<String,Object> task,Map<String,String> labels) {
        String subject=String.valueOf(task.get("subject"));
        String label=labels.getOrDefault(subject,subject);
        task.put("subject_label",label);
        task.put("task_error",safeError(task.get("task_error")));
        boolean writable=Boolean.TRUE.equals(task.get("org_writable"));task.put("can_review",writable);
        boolean pending=writable&&"PENDING".equals(task.get("billing_state")),content="CONTENT".equals(task.get("category"));
        task.put("can_verify",pending&&content&&"SUBMISSION_UNCERTAIN".equals(task.get("task_status"))&&task.get("provider_task_id")==null&&task.get("reserved_units")!=null&&Long.parseLong(String.valueOf(task.get("reserved_units")))>0);
        task.put("can_sync",pending&&content&&List.of("SUCCEEDED","FAILED","CANCELLED").contains(task.get("task_status")));
    }
    public static void validateKey(String key){if(key==null||!key.matches("(?:operation|content):[1-9][0-9]{0,18}"))throw new ServiceException(400,"任务编号无效");}
    private static String safeError(Object value){if(value==null)return "";String clean=String.valueOf(value).replaceAll("(?i)(Bearer\\s+)[^\\s]+","$1***").replaceAll("(?i)(api[_-]?key|access[_-]?token|signature)=([^&\\s]+)","$1=***");return clean.substring(0,Math.min(500,clean.length()));}
}
