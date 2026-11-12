package cn.iocoder.yudao.module.aiorchestration.billing;

import cn.iocoder.yudao.module.infra.zhongshu.api.PlatformCostPort;
import cn.iocoder.yudao.module.infra.zhongshu.event.JdbcReliableEventPort;
import cn.iocoder.yudao.module.infra.zhongshu.event.OutboxEventMessage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Durable A cost subject and cumulative facts. No dependency on either application's wallet. */
@Service
@Slf4j
public class PlatformCostService implements PlatformCostPort {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JdbcReliableEventPort events;
    private volatile boolean taskRouting;
    private cn.iocoder.yudao.module.infra.zhongshu.api.ServiceResourceAuthorizationPort serviceAuthorization;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setServiceAuthorization(cn.iocoder.yudao.module.infra.zhongshu.api.ServiceResourceAuthorizationPort authorization) {
        this.serviceAuthorization = authorization;
    }

    private void authorize(Subject subject) {
        if (serviceAuthorization == null) throw new IllegalStateException("SERVICE_RESOURCE_AUTHORIZATION_NOT_WIRED");
        serviceAuthorization.requireActive(subject);
    }

    public PlatformCostService(DataSource dataSource, PlatformTransactionManager transactionManager) {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(15);
        tx = new TransactionTemplate(transactionManager);
        tx.setTimeout(15);
        events = new JdbcReliableEventPort(dataSource);
    }

    @Override public void registerTaskRouting() { taskRouting = true; }

    @Override public void requireReady() {
        if (!taskRouting) throw new IllegalStateException("SERVICE_COST_TASK_ROUTING_NOT_WIRED");
        if (serviceAuthorization == null) throw new IllegalStateException("SERVICE_RESOURCE_AUTHORIZATION_NOT_WIRED");
        execute("readiness", () -> {
            jdbc.queryForList("SELECT billing_mode,cost_business_id FROM ai_job LIMIT 0");
            jdbc.queryForList("SELECT version,prompt_state FROM platform_cost_business LIMIT 0");
            jdbc.queryForList("SELECT business_id,event_key FROM platform_cost_ledger LIMIT 0");
            for (String product : List.of("FLAT:2K", "FLAT:4K", "ELEVATION:2K", "ELEVATION:4K", "BUDGET_ESTIMATE", "NOTE_POLISH")) {
                quote(product);
            }
            return null;
        });
    }

    @Override public Preparation prepare(Subject subject, String product, int count, String fingerprint) {
        validate(subject, fingerprint);
        if (count < 0 || count > 8 || (product.startsWith("FLAT:") || product.startsWith("ELEVATION:")) != (count > 0)) {
            throw new IllegalArgumentException("Invalid service cost quantity");
        }
        return execute("prepare:" + subject.receiptId(), () -> {
            authorize(subject);
            var existing = jdbc.queryForList("SELECT * FROM platform_cost_business WHERE id=? FOR UPDATE", subject.receiptId());
            if (!existing.isEmpty()) {
                var row = existing.get(0);
                checkSubject(row, subject, fingerprint);
                if (!product.equals(row.get("product")) || number(row, "requested_count") != count) conflict();
                return preparation(row);
            }
            var price = quote(product);
            long reserve = count > 0 ? Math.addExact(Math.multiplyExact(count, number(price, "image_micros")), number(price, "prompt_micros"))
                    : number(price, "usage_micros");
            int inserted = jdbc.update("""
                    INSERT INTO platform_cost_business(id,caller_app_id,org_id,tenant_id,service_account_id,
                        request_key,task_key,fingerprint,product,price_version,unit,prompt_micros,image_micros,
                        usage_micros,requested_count,reserved_micros,prompt_state)
                    VALUES(?,?,?,?,?,?,?,?,?,?,'MICRO_CNY',?,?,?,?,?,?) ON CONFLICT DO NOTHING
                    """, subject.receiptId(),subject.callerAppId(),subject.orgId(),subject.tenantId(),subject.serviceAccountId(),
                    subject.requestKey(), "svc:" + subject.receiptId(),fingerprint,product,number(price,"version"),
                    number(price,"prompt_micros"),number(price,"image_micros"),number(price,"usage_micros"),count,reserve,
                    count > 0 ? "PENDING" : "EXEMPT");
            var row = lock(subject.receiptId());
            checkSubject(row, subject, fingerprint);
            if (!product.equals(row.get("product")) || number(row,"requested_count") != count) conflict();
            if (inserted == 1) append(row, "reserve", "RESERVED");
            return preparation(row);
        });
    }

    @Override public Optional<Preparation> recover(Subject subject, String fingerprint) {
        validate(subject, fingerprint);
        return execute("recover:" + subject.receiptId(), () -> {
            var rows = jdbc.queryForList("SELECT * FROM platform_cost_business WHERE id=?", subject.receiptId());
            if (rows.isEmpty()) return Optional.empty();
            checkSubject(rows.get(0),subject,fingerprint);
            return Optional.of(preparation(rows.get(0)));
        });
    }

    @Override public void requirePrepared(Subject subject, String preparationId, String fingerprint) {
        if (!subject.receiptId().equals(preparationId)) conflict();
        execute("require-prepared:" + preparationId, () -> {
            authorize(subject);
            var row = lock(preparationId);
            checkSubject(row, subject, fingerprint);
            if (!List.of("PREPARED", "READY", "EXECUTED").contains(row.get("state"))) {
                throw new IllegalStateException("SERVICE_PREPARATION_NOT_EXECUTABLE");
            }
            return null;
        });
    }

    @Override public long createJob(long accountId, String taskKey, String phase, int count, String projectRef, String resolution) {
        return execute("create-job:" + taskKey, () -> {
            var rows = jdbc.queryForList("SELECT * FROM platform_cost_business WHERE service_account_id=? AND task_key=? FOR UPDATE", accountId,taskKey);
            if (rows.size() != 1) throw new IllegalStateException("SERVICE_COST_PREPARATION_MISSING");
            var row = rows.get(0);
            authorize(new Subject((String)row.get("caller_app_id"), number(row,"org_id"), number(row,"tenant_id"),
                    accountId, (String)row.get("id"), (String)row.get("request_key")));
            if (!(phase + ":" + resolution).equals(row.get("product")) || count != number(row,"requested_count")) conflict();
            if (row.get("job_id") != null) {
                if (!Objects.equals(projectRef,row.get("project_ref")) || !phase.equals(row.get("phase")) || !resolution.equals(row.get("resolution"))) conflict();
                return number(row,"job_id");
            }
            if (!"PREPARED".equals(row.get("state"))) throw new IllegalStateException("SERVICE_PREPARATION_NOT_EXECUTABLE");
            long id = IdWorker.getId();
            jdbc.update("""
                    INSERT INTO ai_job(id,user_id,project_ref,phase,status,requested_count,output_prefix,idempotency_key,
                        unit_point_cost,total_point_cost,request_resolution,prompt_pricing_version,billing_mode,cost_business_id,tenant_id)
                    VALUES(?,?,?,?,'QUEUED',?,?,?,0,0,?,1,'SERVICE_COST',?,?)
                    """,id,accountId,projectRef,phase,count,"ai-quarantine/"+id,taskKey,resolution,row.get("id"),row.get("tenant_id"));
            jdbc.update("UPDATE platform_cost_business SET job_id=?,phase=?,project_ref=?,resolution=?,state='EXECUTED',job_status='QUEUED',version=version+1,updated_at=now() WHERE id=?",
                    id,phase,projectRef,resolution,row.get("id"));
            append(lock((String)row.get("id")), "job-created", "JOB_CREATED");
            events.append(OutboxEventMessage.builder().eventType("AI_JOB_CREATED").bizType("ai_job").bizId(Long.toString(id))
                    .payload(Map.of("jobId",id,"phase",phase,"count",count)).build());
            return id;
        });
    }

    @Override public String reservePrompt(long jobId, long token) {
        return execute("reserve-prompt:" + jobId, () -> {
            var row = lockJob(jobId);
            String state = (String)row.get("prompt_state");
            if (Boolean.TRUE.equals(row.get("images_final")) || List.of("UNKNOWN","CHARGED","RELEASED").contains(state)) {
                throw new IllegalStateException("Prompt outcome requires reconciliation; do not repeat supplier call");
            }
            if ("RESERVED".equals(state)) {
                if (number(row,"prompt_token") == token) return state;
                var expires = (java.sql.Timestamp)row.get("prompt_expires_at");
                if (expires == null || expires.toInstant().isAfter(Instant.now())) throw new IllegalStateException("Prompt reservation still held");
            }
            jdbc.update("UPDATE platform_cost_business SET prompt_state='RESERVED',prompt_token=?,prompt_expires_at=now()+interval '2 minutes',version=version+1,updated_at=now() WHERE id=?",token,row.get("id"));
            var updated = lock((String)row.get("id"));
            append(updated, "prompt-reserve:"+number(updated,"version"), "PROMPT_RESERVED");
            return "RESERVED";
        });
    }

    @Override public boolean finishPrompt(long jobId, long token, String outcome, String callId, String responseHash, boolean currentAttempt) {
        if (!List.of("DISPATCHED","SUCCEEDED","UNKNOWN","NOT_SENT").contains(outcome)) throw new IllegalArgumentException("Invalid prompt outcome");
        if (callId != null && !("plan-" + jobId).equals(callId)) throw new IllegalArgumentException("Prompt call identity mismatch");
        if (currentAttempt && "SUCCEEDED".equals(outcome) && !validHash(responseHash)) throw new IllegalArgumentException("Prompt response receipt required");
        return execute("finish-prompt:"+jobId, () -> {
            var row=lockJob(jobId);
            String state=(String)row.get("prompt_state");
            if (row.get("prompt_call_id") != null && callId != null && !callId.equals(row.get("prompt_call_id"))) conflict();
            if (row.get("prompt_response_hash") != null && responseHash != null && !responseHash.equals(row.get("prompt_response_hash"))) conflict();
            if ("CHARGED".equals(state)) {
                if (!"SUCCEEDED".equals(outcome)) throw new IllegalStateException("Confirmed prompt cannot be released");
                return false;
            }
            if ("RELEASED".equals(state)) {
                if ("NOT_SENT".equals(outcome)) return false;
                throw new IllegalStateException("Released prompt has no dispatched call");
            }
            if ("PENDING".equals(state) && "NOT_SENT".equals(outcome)) return false;
            if (row.get("prompt_token") == null) throw new IllegalStateException("Prompt has not been reserved");
            boolean transfer = currentAttempt && "UNKNOWN".equals(state) && "SUCCEEDED".equals(outcome)
                    && callId != null && callId.equals(row.get("prompt_call_id"));
            if (number(row,"prompt_token") != token && !transfer) throw new IllegalStateException("Prompt reservation token mismatch");
            if ("UNKNOWN".equals(state) && "NOT_SENT".equals(outcome)) {
                throw new IllegalStateException("UNKNOWN_PROMPT_REQUIRES_DURABLE_RECEIPT");
            }
            if ("UNKNOWN".equals(state) && List.of("UNKNOWN","DISPATCHED").contains(outcome)) return false;
            long charged=number(row,"charged_micros"), refunded=number(row,"refunded_micros"), units=number(row,"prompt_units");
            String next;
            if ("SUCCEEDED".equals(outcome)) {
                next="CHARGED";
                charged=Math.addExact(charged,number(row,"prompt_micros"));
                units=1;
            } else if ("NOT_SENT".equals(outcome)) {
                next=Boolean.TRUE.equals(row.get("images_final"))?"RELEASED":"PENDING";
                if ("RELEASED".equals(next)) refunded=Math.addExact(refunded,number(row,"prompt_micros"));
            } else next="UNKNOWN";
            jdbc.update("""
                    UPDATE platform_cost_business SET prompt_state=?,prompt_token=?,prompt_expires_at=NULL,
                        prompt_call_id=COALESCE(?,prompt_call_id),prompt_response_hash=COALESCE(?,prompt_response_hash),
                        charged_micros=?,refunded_micros=?,prompt_units=?,version=version+1,updated_at=now() WHERE id=?
                    """, next,"PENDING".equals(next)?null:token,callId,responseHash,charged,refunded,units,row.get("id"));
            var updated=lock((String)row.get("id"));
            append(updated,"prompt:"+number(updated,"version"),"PROMPT_"+next);
            return true;
        });
    }

    @Override public void settleImages(long jobId,int validCount,String status) {
        execute("settle:"+jobId, () -> {
            var row=lockJob(jobId);
            if (Boolean.TRUE.equals(row.get("images_final"))) {
                if (number(row,"image_units") != validCount || !status.equals(row.get("job_status"))) conflict();
                return null;
            }
            int requested=(int)number(row,"requested_count");
            if (validCount < 0 || validCount > requested || !List.of("SUCCEEDED","PARTIALLY_SUCCEEDED","FAILED","CANCELLED").contains(status)) {
                throw new IllegalArgumentException("Invalid terminal image facts");
            }
            long charged=Math.addExact(number(row,"charged_micros"),Math.multiplyExact(validCount,number(row,"image_micros")));
            long refunded=Math.addExact(number(row,"refunded_micros"),Math.multiplyExact(requested-validCount,number(row,"image_micros")));
            String prompt=(String)row.get("prompt_state");
            if (List.of("PENDING","RESERVED").contains(prompt)) {
                prompt="RELEASED";
                refunded=Math.addExact(refunded,number(row,"prompt_micros"));
            }
            jdbc.update("UPDATE platform_cost_business SET images_final=TRUE,image_units=?,job_status=?,prompt_state=?,charged_micros=?,refunded_micros=?,version=version+1,updated_at=now() WHERE id=?",
                    validCount,status,prompt,charged,refunded,row.get("id"));
            append(lock((String)row.get("id")),"images-final","IMAGES_FINAL");
            return null;
        });
    }

    @Override public Facts facts(long jobId) {
        return execute("facts:"+jobId, () -> {
            var row=lockJob(jobId);
            boolean terminal=Boolean.TRUE.equals(row.get("images_final")) && List.of("CHARGED","RELEASED","EXEMPT").contains(row.get("prompt_state"));
            return new Facts(jobId,number(row,"version"),(String)row.get("job_status"),terminal,(int)number(row,"requested_count"),
                    (int)number(row,"image_units"),number(row,"reserved_micros"),number(row,"charged_micros"),number(row,"refunded_micros"),
                    number(row,"prompt_units"),number(row,"image_units"),number(row,"usage_units"));
        });
    }

    @Override public void requireOwner(Subject subject,long jobId) {
        execute("owner:"+jobId, () -> {
            var row=lockJob(jobId);
            if (!subject.callerAppId().equals(row.get("caller_app_id")) || subject.orgId()!=number(row,"org_id")
                    || subject.tenantId()!=number(row,"tenant_id") || subject.serviceAccountId()!=number(row,"service_account_id")) conflict();
            return null;
        });
    }

    @Override public boolean beginExternal(String id) {
        return execute("dispatch:"+id, () -> {
            var row=lock(id);
            if (row.get("result_json")!=null) return false;
            if (!"PREPARED".equals(row.get("state"))) throw new IllegalStateException("SERVICE_PREPARATION_UNKNOWN_RECONCILE_RECEIPT");
            authorize(storedSubject(row));
            jdbc.update("UPDATE platform_cost_business SET state='UNKNOWN',version=version+1,updated_at=now() WHERE id=?",id);
            append(lock(id),"external-dispatch","EXTERNAL_DISPATCHED");
            return true;
        });
    }

    @Override public void authorizeExternalRecovery(String id) {
        execute("authorize-external-recovery:"+id, () -> {
            var row=lock(id);
            if (!"UNKNOWN".equals(row.get("state")) || row.get("result_json")!=null) {
                throw new IllegalStateException("SERVICE_PREPARATION_NOT_RECOVERABLE");
            }
            authorize(storedSubject(row));
            jdbc.update("UPDATE platform_cost_business SET version=version+1,updated_at=now() WHERE id=?",id);
            var updated=lock(id);
            append(updated,"external-recovery:"+number(updated,"version"),"EXTERNAL_NOT_FOUND_RETRY");
            return null;
        });
    }

    @Override public void saveExternalResult(String id,String resultJson) {
        if (resultJson==null) throw new IllegalArgumentException("Result required");
        execute("external-result:"+id, () -> {
            var row=lock(id);
            if (row.get("result_json")!=null) {
                if (!resultJson.equals(row.get("result_json"))) conflict();
                return null;
            }
            if (!"UNKNOWN".equals(row.get("state"))) conflict();
            jdbc.update("UPDATE platform_cost_business SET result_json=?,state='READY',version=version+1,updated_at=now() WHERE id=?",resultJson,id);
            append(lock(id),"external-result","EXTERNAL_RESULT");
            return null;
        });
    }

    @Override public void recordExternalFailure(String id, String evidence) {
        if (evidence == null || evidence.isBlank()) throw new IllegalArgumentException("Failure receipt evidence required");
        execute("external-failure:"+id, () -> {
            var row=lock(id);
            if ("EXTERNAL_FAILED".equals(row.get("state"))) {
                if (!evidence.equals(row.get("failure_evidence"))) conflict();
                return null;
            }
            if (!"UNKNOWN".equals(row.get("state")) || row.get("result_json")!=null) conflict();
            jdbc.update("UPDATE platform_cost_business SET state='EXTERNAL_FAILED',failure_evidence=?,version=version+1,updated_at=now() WHERE id=?",evidence,id);
            append(lock(id),"external-failed","EXTERNAL_FAILED");
            return null;
        });
    }

    @Override public boolean rejectPrepared(Subject subject,String fingerprint) {
        validate(subject,fingerprint);
        return execute("reject-prepared:"+subject.receiptId(), () -> {
            var rows=jdbc.queryForList("SELECT * FROM platform_cost_business WHERE id=? FOR UPDATE",subject.receiptId());
            if (rows.isEmpty()) return true; // No durable dispatch can exist without the preparation row.
            var row=rows.get(0);
            checkSubject(row,subject,fingerprint);
            if ("REJECTED".equals(row.get("state"))) return true;
            if (row.get("job_id")!=null || !List.of("PREPARED","EXTERNAL_FAILED").contains(row.get("state"))
                    || number(row,"charged_micros")!=0 || row.get("result_json")!=null) return false;
            jdbc.update("UPDATE platform_cost_business SET state='REJECTED',job_status='FAILED',images_final=TRUE,prompt_state='RELEASED',refunded_micros=reserved_micros,version=version+1,updated_at=now() WHERE id=?",subject.receiptId());
            append(lock(subject.receiptId()),"rejected","PREPARATION_REJECTED");
            return true;
        });
    }

    @Override public void finishRecoveredPolish(Subject subject,String fingerprint,String resultJson) {
        validate(subject,fingerprint);
        execute("finish-recovered-polish:"+subject.receiptId(), () -> {
            var row=lock(subject.receiptId());
            checkSubject(row,subject,fingerprint);
            if (!"NOTE_POLISH".equals(row.get("product")) || !List.of("READY","EXECUTED").contains(row.get("state"))
                    || resultJson==null || !resultJson.equals(row.get("result_json"))) {
                throw new IllegalStateException("SERVICE_POLISH_RESULT_NOT_CONFIRMED");
            }
            finishUsage(subject.receiptId(),resultJson);
            return null;
        });
    }

    @Override public void finishUsage(String id,String resultJson) {
        execute("usage:"+id, () -> {
            var row=lock(id);
            if (number(row,"requested_count")!=0) conflict();
            if ("EXECUTED".equals(row.get("state"))) {
                if (!Objects.equals(resultJson,row.get("result_json"))) conflict();
                return null;
            }
            if (!List.of("PREPARED","READY").contains(row.get("state"))) throw new IllegalStateException("SERVICE_PREPARATION_UNKNOWN_RECONCILE_RECEIPT");
            jdbc.update("UPDATE platform_cost_business SET result_json=?,state='EXECUTED',job_status='SUCCEEDED',images_final=TRUE,usage_units=1,charged_micros=usage_micros,version=version+1,updated_at=now() WHERE id=?",resultJson,id);
            append(lock(id),"usage-final","USAGE_FINAL");
            return null;
        });
    }

    private Map<String,Object> quote(String product) {
        var rows=jdbc.queryForList("SELECT * FROM platform_cost_price WHERE product=? AND active=TRUE AND unit='MICRO_CNY'",product);
        if (rows.size()!=1) throw new IllegalStateException("SERVICE_COST_PRICE_NOT_CONFIGURED:"+product);
        return rows.get(0);
    }
    private Map<String,Object> lock(String id) { return jdbc.queryForMap("SELECT * FROM platform_cost_business WHERE id=? FOR UPDATE",id); }
    private Map<String,Object> lockJob(long id) {
        return jdbc.queryForMap("SELECT b.* FROM platform_cost_business b JOIN ai_job j ON j.cost_business_id=b.id AND j.billing_mode='SERVICE_COST' WHERE b.job_id=? AND j.id=? FOR UPDATE OF b",id,id);
    }
    private void append(Map<String,Object> row,String key,String type) {
        jdbc.update("""
                INSERT INTO platform_cost_ledger(id,business_id,version,event_key,event_type,reserved_micros,charged_micros,
                    refunded_micros,prompt_units,image_units,usage_units) VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """,IdWorker.getId(),row.get("id"),row.get("version"),key,type,row.get("reserved_micros"),row.get("charged_micros"),
                row.get("refunded_micros"),row.get("prompt_units"),row.get("image_units"),row.get("usage_units"));
    }
    private <T> T execute(String operation,Supplier<T> action) {
        try { return tx.execute(status -> action.get()); }
        catch (RuntimeException error) {
            log.warn("[platformCost][operation={}] 内部成本事务失败",operation,error);
            throw error;
        }
    }
    private static Subject storedSubject(Map<String,Object> row) {
        return new Subject((String)row.get("caller_app_id"),number(row,"org_id"),number(row,"tenant_id"),
                number(row,"service_account_id"),(String)row.get("id"),(String)row.get("request_key"));
    }
    private static Preparation preparation(Map<String,Object> row) {
        return new Preparation((String)row.get("id"),(String)row.get("task_key"),(String)row.get("state"),(String)row.get("result_json"));
    }
    private static long number(Map<String,Object> row,String column) { return ((Number)row.get(column)).longValue(); }
    private static void validate(Subject subject,String fingerprint) {
        if (subject==null || subject.orgId()<=0 || subject.serviceAccountId()<=0 || subject.tenantId()<0) throw new IllegalArgumentException("Invalid service cost subject");
        text(subject.callerAppId(),80); text(subject.receiptId(),124); text(subject.requestKey(),200); text(fingerprint,128);
    }
    private static void text(String value,int max) {
        if (value==null || value.isBlank() || value.length()>max) throw new IllegalArgumentException("Invalid service cost identity");
    }
    private static void checkSubject(Map<String,Object> row,Subject subject,String fingerprint) {
        if (!subject.callerAppId().equals(row.get("caller_app_id")) || subject.orgId()!=number(row,"org_id")
                || subject.tenantId()!=number(row,"tenant_id") || subject.serviceAccountId()!=number(row,"service_account_id")
                || !subject.requestKey().equals(row.get("request_key")) || !fingerprint.equals(row.get("fingerprint"))) conflict();
    }
    private static boolean validHash(String value) {
        if (value==null || value.length()!=64) return false;
        for (int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if (!(c>='0' && c<='9') && !(c>='a' && c<='f')) return false;
        }
        return true;
    }
    private static void conflict() { throw new IllegalStateException("SERVICE_COST_IDEMPOTENCY_CONFLICT"); }
}
