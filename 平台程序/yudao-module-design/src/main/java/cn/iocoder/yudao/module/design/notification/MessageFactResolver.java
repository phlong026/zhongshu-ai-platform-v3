package cn.iocoder.yudao.module.design.notification;

import cn.iocoder.yudao.module.infra.zhongshu.delivery.OutboxEventRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.*;

/** T26-10: read-only projection of committed facts, never a writer of balances or business status. */
public final class MessageFactResolver {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate jdbc;
    public MessageFactResolver(DataSource source) { jdbc = new JdbcTemplate(source); }

    public record Fact(String version, String action, Map<String,Object> target,
                       Map<String,Object> values, Timestamp occurredAt) { }

    public Fact fact(OutboxEventRecord event, long user) {
        Map<String,Object> payload = json(event.getPayload());
        String id = id(event.getBizId());
        if (id == null) return legacy(); // historic synthetic IDs cannot prove an exact business version
        String table = switch (event.getEventType()) {
            case "AI_JOB_SETTLED" -> "ai_job";
            case "SUBMISSION_REVIEWED", "SUBMISSION_PUBLISHED" -> "case_submission";
            case "BUDGET_QUOTE_PUBLISHED", "BUDGET_QUOTE_WITHDRAWN" -> "budget_quote";
            case "ORDER_CREDITED" -> "recharge_order";
            case "ORDER_REFUND_REVERSED" -> "refund_order";
            default -> null;
        };
        // Missing historic metadata may remain generic; a known foreign-domain entity may not.
        if (table != null && Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM " + table + " WHERE id=? AND tenant_id<>0)", Boolean.class,positive(id)))) throw unavailable();
        return switch (event.getEventType()) {
            case "AI_JOB_SETTLED" -> payload.get("settlementVersion") == null ? legacy() : settlement(user,id,payload);
            case "SUBMISSION_REVIEWED" -> payload.get("reviewTaskId") == null ? legacy() : review(user,id,payload);
            case "SUBMISSION_PUBLISHED" -> payload.get("publicationId") == null ? legacy() : publication(user,id,payload);
            case "BUDGET_QUOTE_PUBLISHED", "BUDGET_QUOTE_WITHDRAWN" -> quote(user,id,payload,event.getEventType());
            case "ORDER_CREDITED" -> credit(user,id);
            case "ORDER_REFUND_REVERSED" -> refund(user,id);
            default -> legacy();
        };
    }

    private Fact settlement(long user,String jobId,Map<String,Object> payload) {
        Map<String,Object> job = job(user,jobId,false);
        long version = positive(payload.get("settlementVersion"));
        String reason = String.valueOf(payload.get("settlementReason"));
        var row = one("SELECT id,accepted_billable_count,refunded_points,create_time FROM ai_job_settlement "
                + "WHERE tenant_id=0 AND job_id=? AND settlement_version=? AND reason=? AND deleted=FALSE",positive(jobId),version,reason);
        String status = String.valueOf(payload.get("status"));
        if (!Set.of("SUCCEEDED","PARTIALLY_SUCCEEDED","FAILED","CANCELLED").contains(status) || !status.equals(job.get("status"))) throw unavailable();
        long accepted = number(row,"accepted_billable_count");
        boolean ready = accepted>0 && Set.of("SUCCEEDED","PARTIALLY_SUCCEEDED").contains(status);
        var target = target("JOB","jobId",jobId);
        target.put("projectId",job.get("project_ref"));
        target.put("phase",job.get("phase"));
        return new Fact(version+":"+reason,ready ? "SELECT_"+job.get("phase") : "NONE",target,
                Map.of("status",status,"refundPoints",number(row,"refunded_points"),"billable",accepted),time(row,"create_time"));
    }

    private Fact review(long user,String submissionId,Map<String,Object> payload) {
        var row = one("SELECT s.user_id,s.project_id,r.round_no,d.id,d.decision,d.decided_at "
                + "FROM case_submission s JOIN design_project p ON p.id=s.project_id AND p.user_id=s.user_id AND p.tenant_id=s.tenant_id "
                + "JOIN review_task r ON r.submission_id=s.id AND r.tenant_id=s.tenant_id "
                + "JOIN review_decision d ON d.review_task_id=r.id AND d.tenant_id=r.tenant_id "
                + "WHERE s.tenant_id=0 AND s.id=? AND s.user_id=? AND s.deleted=FALSE AND r.id=? AND r.deleted=FALSE AND d.deleted=FALSE",
                positive(submissionId),user,positive(payload.get("reviewTaskId")));
        var target=target("SUBMISSION","submissionId",submissionId);
        target.put("reviewRound",number(row,"round_no"));
        String decision=String.valueOf(row.get("decision"));
        return new Fact("review:"+row.get("id"),"CHANGES_REQUESTED".equals(decision) ? "RESUBMIT_SUBMISSION" : "NONE",
                target,Map.of("decision",decision,"reviewRound",number(row,"round_no")),time(row,"decided_at"));
    }

    private Fact publication(long user,String submissionId,Map<String,Object> payload) {
        var row=one("SELECT p.id,p.create_time,s.result_version_id FROM case_submission s "
                + "JOIN design_project project ON project.id=s.project_id AND project.user_id=s.user_id AND project.tenant_id=s.tenant_id "
                + "JOIN case_publication p ON p.case_id=s.published_case_id AND p.tenant_id=s.tenant_id "
                + "JOIN design_case c ON c.id=p.case_id AND c.tenant_id=p.tenant_id "
                + "WHERE s.tenant_id=0 AND s.id=? AND s.user_id=? AND s.deleted=FALSE AND p.id=? AND p.action='PUBLISH' AND p.deleted=FALSE",
                positive(submissionId),user,positive(payload.get("publicationId")));
        var target=target("SUBMISSION","submissionId",submissionId);
        return new Fact("publication:"+row.get("id"),"NONE",target,Map.of("published",true),time(row,"create_time"));
    }

    private Fact quote(long user,String quoteId,Map<String,Object> payload,String eventType) {
        var row=publicQuote(user,quoteId);
        long version=positive(payload.get("quoteStateVersion"));
        boolean published="BUDGET_QUOTE_PUBLISHED".equals(eventType);
        long actualVersion=number(row,"version")-(published && "WITHDRAWN".equals(row.get("status"))?1:0);
        if (version!=actualVersion || version<2 || time(row,published?"published_at":"withdrawn_at")==null) throw unavailable();
        var target=target("QUOTE","quoteId",quoteId);
        target.put("budgetId",String.valueOf(row.get("estimate_id")));
        target.put("revisionId",String.valueOf(row.get("revision_id")));
        if(row.get("project_id")!=null) target.put("projectId",String.valueOf(row.get("project_id")));
        return new Fact(String.valueOf(version),"NONE",target,
                Map.of("status",published?"PUBLISHED":"WITHDRAWN","finalPriceCents",number(row,"final_price_cents")),
                time(row,published?"published_at":"withdrawn_at"));
    }

    private Fact credit(long user,String orderId) {
        var rows=jdbc.queryForList("SELECT c.id,c.base_points,c.bonus_points,c.credited_at FROM recharge_credit c "
                + "JOIN recharge_order o ON o.id=c.order_id AND o.tenant_id=c.tenant_id WHERE o.tenant_id=0 AND o.id=? AND o.user_id=? AND o.deleted=FALSE AND c.deleted=FALSE",
                positive(orderId),user);
        if(rows.isEmpty()) {
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM recharge_credit WHERE order_id=? AND tenant_id<>0)",Boolean.class,positive(orderId)))) throw unavailable();
            return legacy();
        }
        var row=rows.get(0);
        return new Fact("credit:"+row.get("id"),"NONE",target("ORDER","orderId",orderId),
                Map.of("basePoints",number(row,"base_points"),"bonusPoints",number(row,"bonus_points")),time(row,"credited_at"));
    }

    private Fact refund(long user,String refundId) {
        var rows=jdbc.queryForList("SELECT r.id,r.order_id,r.update_time FROM refund_order r JOIN recharge_order o ON o.id=r.order_id "
                + "WHERE r.tenant_id=0 AND o.tenant_id=0 AND r.id=? AND o.user_id=? AND r.channel_state='SUCCEEDED' AND r.point_reversal_state='REVERSED' "
                + "AND r.deleted=FALSE AND o.deleted=FALSE",positive(refundId),user);
        if(rows.isEmpty()) {
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM refund_order r JOIN recharge_order o ON o.id=r.order_id WHERE r.id=? AND o.tenant_id<>0)",Boolean.class,positive(refundId)))) throw unavailable();
            return legacy();
        }
        var row=rows.get(0);
        return new Fact("refund:"+row.get("id"),"NONE",target("ORDER","orderId",String.valueOf(row.get("order_id"))),
                Map.of("refundCompleted",true),time(row,"update_time"));
    }

    public Map<String,Object> resolveTarget(long user,Map<String,Object> saved) {
        var result=new LinkedHashMap<>(saved);
        switch (String.valueOf(saved.get("type"))) {
            case "JOB" -> {
                String id=requiredId(saved.get("jobId"));
                var job=job(user,id);
                if(saved.containsKey("projectId")&&!String.valueOf(job.get("project_ref")).equals(String.valueOf(saved.get("projectId")))) throw unavailable();
                result.put("projectId",job.get("project_ref")); result.put("phase",job.get("phase"));
                var versions=jdbc.queryForList("SELECT DISTINCT v.id,v.version FROM design_result_version v "
                        + "JOIN design_selection s ON s.project_id=v.project_id AND s.id IN (v.flat_selection_id,v.elevation_selection_id) "
                        + "JOIN design_candidate c ON c.id=s.candidate_id AND c.project_id=s.project_id "
                        + "WHERE v.tenant_id=0 AND s.tenant_id=0 AND c.tenant_id=0 AND v.project_id=? AND c.job_id=? AND v.deleted=FALSE AND s.deleted=FALSE AND c.deleted=FALSE "
                        + "ORDER BY v.version,v.id LIMIT 1",positive(job.get("project_ref")),positive(id));
                if(!versions.isEmpty()) { result.put("type","RESULT"); result.put("resultVersionId",String.valueOf(versions.get(0).get("id"))); }
            }
            case "SUBMISSION" -> {
                var row=submission(user,positive(saved.get("submissionId")));
                if(saved.containsKey("reviewRound")) {
                    long round=positive(saved.get("reviewRound"));
                    one("SELECT r.id FROM review_task r JOIN review_decision d ON d.review_task_id=r.id AND d.tenant_id=r.tenant_id "
                            + "WHERE r.tenant_id=0 AND r.submission_id=? AND r.round_no=? AND r.deleted=FALSE AND d.deleted=FALSE",positive(saved.get("submissionId")),round);
                    result.put("historical",round<number(row,"current_round"));
                }
            }
            case "QUOTE" -> {
                var q=publicQuote(user,requiredId(saved.get("quoteId")));
                if(!String.valueOf(q.get("estimate_id")).equals(String.valueOf(saved.get("budgetId")))
                        || !String.valueOf(q.get("revision_id")).equals(String.valueOf(saved.get("revisionId")))) throw unavailable();
                result.put("status",q.get("status"));
            }
            case "ORDER" -> one("SELECT id FROM recharge_order WHERE tenant_id=0 AND id=? AND user_id=? AND deleted=FALSE",positive(saved.get("orderId")),user);
            case "PROJECT" -> one("SELECT id FROM design_project WHERE tenant_id=0 AND id=? AND user_id=? AND deleted=FALSE",positive(saved.get("projectId")),user);
            default -> throw unavailable();
        }
        return result;
    }

    public String actionState(long user,String action,Map<String,Object> target) {
        if("NONE".equals(action)) return "NONE";
        try {
            var current=resolveTarget(user,target);
            if(action.startsWith("SELECT_")) {
                if("RESULT".equals(current.get("type"))) return "COMPLETED";
                var selected=jdbc.queryForList("SELECT s.id FROM design_selection s JOIN design_candidate c ON c.id=s.candidate_id AND c.project_id=s.project_id "
                        + "JOIN design_project p ON p.id=s.project_id WHERE c.job_id=? AND p.user_id=? "
                        + "AND s.tenant_id=0 AND c.tenant_id=0 AND p.tenant_id=0 AND s.deleted=FALSE AND c.deleted=FALSE AND p.deleted=FALSE",positive(target.get("jobId")),user);
                return selected.isEmpty()?"PENDING":"COMPLETED";
            }
            if("RESUBMIT_SUBMISSION".equals(action)) {
                var s=submission(user,positive(target.get("submissionId")));
                return number(s,"current_round")==positive(target.get("reviewRound")) && "CHANGES_REQUESTED".equals(s.get("status")) ? "PENDING":"COMPLETED";
            }
        } catch(AccessDeniedException ignored) { return "UNAVAILABLE"; }
        return "UNAVAILABLE";
    }

    private Map<String,Object> job(long user,String id) {
        return job(user,id,true);
    }
    private Map<String,Object> submission(long user,long id) {
        return one("SELECT s.current_round,s.status FROM case_submission s JOIN design_project p "
                + "ON p.id=s.project_id AND p.user_id=s.user_id AND p.tenant_id=s.tenant_id "
                + "WHERE s.tenant_id=0 AND s.id=? AND s.user_id=? AND s.deleted=FALSE AND p.deleted=FALSE",id,user);
    }
    private Map<String,Object> job(long user,String id,boolean visible) {
        return one("SELECT j.id,j.phase,j.project_ref,j.status FROM ai_job j JOIN design_project p ON p.id::text=j.project_ref "
                + "WHERE j.tenant_id=0 AND p.tenant_id=j.tenant_id AND j.id=? AND j.user_id=? AND p.user_id=j.user_id AND j.deleted=FALSE"
                + (visible?" AND p.deleted=FALSE":""),positive(id),user);
    }
    private Map<String,Object> publicQuote(long user,String id) {
        return one("SELECT q.*,b.project_id FROM budget_quote q JOIN budget_estimate b ON b.id=q.estimate_id AND b.tenant_id=q.tenant_id "
                + "JOIN budget_revision r ON r.tenant_id=q.tenant_id AND r.id=q.revision_id AND r.estimate_id=b.id AND r.deleted=FALSE "
                + "WHERE q.id=? AND b.user_id=? AND q.tenant_id=0 AND q.status IN ('PUBLISHED','WITHDRAWN') "
                + "AND q.deleted=FALSE AND b.deleted=FALSE AND ((b.project_id IS NOT NULL AND EXISTS(SELECT 1 FROM design_project p "
                + "WHERE p.tenant_id=0 AND p.id=b.project_id AND p.user_id=b.user_id AND p.deleted=FALSE) AND (b.result_version_id IS NULL OR EXISTS(SELECT 1 FROM design_result_version v WHERE v.tenant_id=0 AND v.id=b.result_version_id AND v.project_id=b.project_id AND v.deleted=FALSE))) "
                + "OR (b.project_id IS NULL AND b.result_version_id IS NULL AND b.model='ITEMIZED_V1' AND EXISTS(SELECT 1 FROM budget_draft d "
                + "WHERE d.tenant_id=0 AND d.id=b.draft_id AND d.user_id=b.user_id AND d.deleted=FALSE)))",positive(id),user);
    }
    private Map<String,Object> one(String sql,Object... args) { var rows=jdbc.queryForList(sql,args); if(rows.size()!=1) throw unavailable(); return rows.get(0); }
    private static AccessDeniedException unavailable(){return new AccessDeniedException("消息目标不存在或无权访问");}
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    private static Timestamp time(Map<String,Object> row,String key){return row.get(key) instanceof Timestamp t?t:null;}
    private static Fact legacy(){return new Fact(null,"NONE",Map.of(),Map.of(),null);}
    private static LinkedHashMap<String,Object> target(String type,String key,String value){var t=new LinkedHashMap<String,Object>();t.put("type",type);t.put(key,value);return t;}
    public static String id(Object value){String s=value==null?"":String.valueOf(value);try{return s.matches("[1-9][0-9]{0,18}")&&Long.parseLong(s)>0?s:null;}catch(NumberFormatException e){return null;}}
    private static String requiredId(Object value){String result=id(value);if(result==null)throw unavailable();return result;}
    private static long positive(Object value){return Long.parseLong(requiredId(value));}
    @SuppressWarnings("unchecked") public static Map<String,Object> json(String value){try{return value==null?Map.of():JSON.readValue(value,Map.class);}catch(Exception e){return Map.of();}}
    public static String json(Map<String,Object> value){try{return JSON.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("消息事实序列化失败",e);}}
}
