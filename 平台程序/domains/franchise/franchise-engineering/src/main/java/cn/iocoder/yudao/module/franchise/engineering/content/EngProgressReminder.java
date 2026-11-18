package cn.iocoder.yudao.module.franchise.engineering.content;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

/** Clock-driven, daily deduplicated inbox notices. Closing the node removes its dynamic todo. */
@Component
public class EngProgressReminder {
    private final JdbcTemplate jdbc;
    public EngProgressReminder(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @Scheduled(fixedDelay=60000,initialDelay=20000)
    public void scan(){scanAt(LocalDate.now(ZoneId.of("Asia/Shanghai")));}
    public int scanAt(LocalDate today){
        var rows=jdbc.queryForList("""
            SELECT n.id,n.project_id,n.name,n.delay_reason,n.delay_owner_user_id,n.recovery_date,
              (?::date-COALESCE(n.baseline_end_date,n.end_date)) AS days,
              p.company_id,p.name AS project_name,p.manager_user_id,p.assigned_engineer_user_id
            FROM fr_eng_plan_node n JOIN fr_eng_project p ON p.id=n.project_id AND p.company_id=n.company_id
            WHERE n.status<>'DONE' AND COALESCE(n.baseline_end_date,n.end_date)<?
              AND p.status NOT IN('ARCHIVED','DELIVERED','SETTLED')
              AND NOT EXISTS(SELECT 1 FROM fr_eng_closure c WHERE c.project_id=p.id AND c.delivered_at IS NOT NULL)
            """,today,today);
        int sent=0;
        for(var r:rows){
            var users=new LinkedHashSet<Long>();
            for(String k:List.of("manager_user_id","assigned_engineer_user_id"))if(r.get(k)!=null)users.add(((Number)r.get(k)).longValue());
            String project=String.valueOf(r.get("project_id")),node=String.valueOf(r.get("id"));
            var context=new LinkedHashMap<String,Object>();context.put("module","engineering");context.put("eventCode","eng-progress-overdue");
            context.put("projectId",project);context.put("projectName",r.get("project_name"));context.put("nodeId",node);
            context.put("actionPath","/pages/build/record/index?id="+project+"&view=delay&nodeId="+node);context.put("actionLabel","查看延期节点");
            context.put("recipientReason","本项目负责人或指定工程师");
            context.put("fields",List.of(Map.of("label","施工节点","value",r.get("name")),Map.of("label","偏差天数","value",r.get("days")+"天")));
            String title=r.get("name")+"延期"+r.get("days")+"天";
            String description=r.get("project_name")+" · "+(r.get("delay_reason")==null?"请登记原因、责任人和处理措施":"请跟进恢复计划，完成后登记节点验收");
            for(long user:users)sent+=jdbc.update("""
                INSERT INTO pf_message(id,user_id,org_id,sender_domain,type,route,object_id,title,description,dedupe_key,read,business_context)
                SELECT ?,?,?,'fr-eng-progress','BUSINESS','engineering',?,?,?,?,FALSE,CAST(? AS jsonb)
                WHERE EXISTS(SELECT 1 FROM pf_staff_post s JOIN pf_org_unit o ON o.dept_id=s.org_id
                  WHERE s.user_id=? AND s.org_id=? AND s.status='ACTIVE' AND o.status='ACTIVE' AND s.role_code IN('engineer','project-manager'))
                ON CONFLICT(sender_domain,dedupe_key) DO NOTHING
                """,cn.hutool.core.util.IdUtil.getSnowflakeNextId(),user,r.get("company_id"),r.get("project_id"),
                    title.substring(0,Math.min(100,title.length())),description.substring(0,Math.min(500,description.length())),
                    node+":"+today+":"+user,JsonUtils.toJsonString(context),user,r.get("company_id"));
        }
        return sent;
    }
}
