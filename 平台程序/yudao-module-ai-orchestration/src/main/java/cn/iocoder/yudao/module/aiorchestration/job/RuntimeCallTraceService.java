package cn.iocoder.yudao.module.aiorchestration.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Non-billing, immutable request records. A recovered call retains its original attempt/model/version. */
@Service
public class RuntimeCallTraceService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> STATES = Set.of("REQUESTED", "DISPATCHED", "SUCCEEDED", "UNKNOWN", "NOT_SENT");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public RuntimeCallTraceService(DataSource ds, PlatformTransactionManager transactionManager) {
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(transactionManager);
    }

    public record Reference(String assetId, Integer floorNo, String sourceCaseVersionId, String sourceType,
                            String sourceSha256, String submittedSha256, String mimeType, long sizeBytes) { }
    public record RequestSpec(String promptVersion, String model, String endpoint, String systemPrompt,
                              String promptText, String size, Double temperature, Integer maxTokens,
                              Integer n, String quality, List<Reference> references) { }
    public record ResponseSpec(String promptText, String sha256, Integer width, Integer height,
                               String mimeType, Long sizeBytes) { }
    public record Event(int attemptNo, long fencingToken, String callId, int sourceAttemptNo,
                        long sourceFencingToken, String stage, int slot, String state,
                        RequestSpec request, ResponseSpec response, String errorCode) { }
    public record Trace(String callId, int sourceAttemptNo, String stage, int slot, String state,
                        JsonNode request, JsonNode response, String errorCode, Instant createdAt, Instant updatedAt) { }

    public boolean record(long jobId, Event event) {
        validate(jobId, event);
        String request = json(event.request()), response = event.response() == null ? null : json(event.response());
        if (request.getBytes(StandardCharsets.UTF_8).length > 524288
                || (response != null && response.getBytes(StandardCharsets.UTF_8).length > 65536)) invalid();
        return Boolean.TRUE.equals(tx.execute(status -> {
            var jobs = jdbc.queryForList("SELECT tenant_id, status, phase, requested_count, fencing_token, claim_expires_at "
                    + "FROM ai_job WHERE id=? AND deleted=FALSE FOR UPDATE", jobId);
            if (jobs.isEmpty()) throw new IllegalStateException("RUNTIME_TRACE_LEASE");
            var job = jobs.get(0);
            long tenantId = ((Number) job.get("tenant_id")).longValue();
            Timestamp expiry = (Timestamp) job.get("claim_expires_at");
            if (!"FLAT".equals(job.get("phase")) || !"RUNNING".equals(job.get("status"))
                    || ((Number) job.get("fencing_token")).longValue() != event.fencingToken()
                    || expiry == null || !expiry.toInstant().isAfter(Instant.now())
                    || event.slot() > ((Number) job.get("requested_count")).intValue()
                    || !attemptExists(tenantId, jobId, event.attemptNo(), event.fencingToken(), true)
                    || event.sourceAttemptNo() > event.attemptNo()
                    || !attemptExists(tenantId, jobId, event.sourceAttemptNo(), event.sourceFencingToken(), false)) {
                throw new IllegalStateException("RUNTIME_TRACE_LEASE");
            }
            List<Map<String, Object>> old = existing(jobId, event.callId());
            if (old.isEmpty()) {
                jdbc.update("INSERT INTO ai_runtime_call_trace(id,job_id,call_id,source_attempt_no,source_fencing_token,"
                                + "stage,candidate_slot_no,state,request_spec,response_spec,error_code) VALUES(?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?)",
                        com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(), jobId, event.callId(), event.sourceAttemptNo(), event.sourceFencingToken(), event.stage(),
                        event.slot(), event.state(), request, response, event.errorCode());
                return true;
            }
            return updateExisting(jobId, event, request, response, old.get(0));
        }));
    }

    /**
     * Metadata-only compensation after the original execution can no longer write through its lease.
     * It never inserts a call, opens an attempt, accepts an image or invokes any billing operation.
     * A live source attempt must continue through record(); missing/deleted records cannot be resurrected.
     */
    public String reconcile(long jobId, Event event) {
        validate(jobId, event);
        if (event.attemptNo() != event.sourceAttemptNo() || event.fencingToken() != event.sourceFencingToken()
                || !Set.of("SUCCEEDED", "UNKNOWN", "NOT_SENT").contains(event.state())) invalid();
        String request = json(event.request()), response = event.response() == null ? null : json(event.response());
        if (request.getBytes(StandardCharsets.UTF_8).length > 524288
                || (response != null && response.getBytes(StandardCharsets.UTF_8).length > 65536)) invalid();
        return tx.execute(status -> {
            var jobs = jdbc.queryForList("SELECT tenant_id,status,phase,requested_count,fencing_token,claim_expires_at,deleted "
                    + "FROM ai_job WHERE id=? FOR UPDATE", jobId);
            if (jobs.isEmpty() || Boolean.TRUE.equals(jobs.get(0).get("deleted"))) return "DISCARDED";
            var job = jobs.get(0);
            long tenantId = ((Number) job.get("tenant_id")).longValue();
            if (!"FLAT".equals(job.get("phase")) || event.slot() > ((Number) job.get("requested_count")).intValue()) invalid();
            var old = existing(jobId, event.callId());
            if (!old.isEmpty()) assertSameRequest(event, request, old.get(0));
            var attempts = jdbc.queryForList("SELECT finished_at FROM ai_job_attempt WHERE tenant_id=? "
                    + "AND job_id=? AND attempt_no=? AND fencing_token=? AND deleted=FALSE",
                    tenantId, jobId, event.sourceAttemptNo(), event.sourceFencingToken());
            if (attempts.size() != 1) throw new IllegalStateException("RUNTIME_TRACE_SOURCE");
            Timestamp expiry = (Timestamp) job.get("claim_expires_at");
            boolean liveSource = "RUNNING".equals(job.get("status"))
                    && ((Number) job.get("fencing_token")).longValue() == event.sourceFencingToken()
                    && expiry != null && expiry.toInstant().isAfter(Instant.now())
                    && attempts.get(0).get("finished_at") == null;
            // Also avoid dropping a registration whose HTTP acknowledgement is still in flight.
            if (liveSource) return "RETRY";
            if (old.isEmpty()) return "DISCARDED";
            return updateExisting(jobId, event, request, response, old.get(0)) ? "RECORDED" : "DISCARDED";
        });
    }

    private List<Map<String, Object>> existing(long jobId, String callId) {
        return jdbc.queryForList("SELECT source_attempt_no,source_fencing_token,stage,candidate_slot_no,"
                + "state,request_spec::text,response_spec::text,error_code FROM ai_runtime_call_trace "
                + "WHERE job_id=? AND call_id=? FOR UPDATE", jobId, callId);
    }

    private static void assertSameRequest(Event event, String request, Map<String, Object> row) {
        if (((Number) row.get("source_attempt_no")).intValue() != event.sourceAttemptNo()
                || ((Number) row.get("source_fencing_token")).longValue() != event.sourceFencingToken()
                || !Objects.equals(row.get("stage"), event.stage())
                || ((Number) row.get("candidate_slot_no")).intValue() != event.slot()
                || !tree((String) row.get("request_spec")).equals(tree(request))) {
            throw new IllegalStateException("RUNTIME_TRACE_CONFLICT");
        }
    }

    private boolean updateExisting(long jobId, Event event, String request, String response, Map<String, Object> row) {
        assertSameRequest(event, request, row);
        String previous = (String) row.get("state");
        if (previous.equals(event.state())) {
            if (!Objects.equals(tree((String) row.get("response_spec")), tree(response))
                    || !Objects.equals(row.get("error_code"), event.errorCode())) {
                throw new IllegalStateException("RUNTIME_TRACE_CONFLICT");
            }
            return true;
        }
        // Replayed registration/dispatch cannot erase a later response; no second paid call is authorized here.
        if (rank(event.state()) < rank(previous)) return true;
        if ("SUCCEEDED".equals(previous) || "NOT_SENT".equals(previous)
                || rank(event.state()) == rank(previous)) throw new IllegalStateException("RUNTIME_TRACE_CONFLICT");
        return jdbc.update("UPDATE ai_runtime_call_trace SET state=?,response_spec=?::jsonb,error_code=?,update_time=now() "
                        + "WHERE job_id=? AND call_id=?", event.state(), response, event.errorCode(), jobId, event.callId()) == 1;
    }

    /** Caller must enforce admin detail permission. Never included in app or paginated task DTOs. */
    public List<Trace> list(long jobId) {
        return jdbc.query("SELECT t.* FROM ai_runtime_call_trace t JOIN ai_job j ON j.id=t.job_id "
                        + "WHERE j.tenant_id=0 AND t.job_id=? AND j.deleted=FALSE ORDER BY t.candidate_slot_no,t.call_id",
                (rs, i) -> new Trace(rs.getString("call_id"), rs.getInt("source_attempt_no"), rs.getString("stage"),
                        rs.getInt("candidate_slot_no"), rs.getString("state"), tree(rs.getString("request_spec")),
                        tree(rs.getString("response_spec")), rs.getString("error_code"),
                        rs.getTimestamp("create_time").toInstant(), rs.getTimestamp("update_time").toInstant()), jobId);
    }

    private boolean attemptExists(long tenant, long job, int attempt, long token, boolean unfinished) {
        return Integer.valueOf(1).equals(jdbc.queryForObject("SELECT count(*) FROM ai_job_attempt "
                + "WHERE tenant_id=? AND job_id=? AND attempt_no=? AND fencing_token=? AND deleted=FALSE"
                + (unfinished ? " AND finished_at IS NULL" : ""), Integer.class, tenant, job, attempt, token));
    }

    private static void validate(long job, Event e) {
        if (e == null || job <= 0 || e.attemptNo() < 1 || e.fencingToken() < 1 || e.sourceAttemptNo() < 1
                || e.sourceFencingToken() < 1 || !STATES.contains(e.state() == null ? "" : e.state())) invalid();
        boolean plan = "PLAN".equals(e.stage());
        if ((!plan && !"GENERATE".equals(e.stage())) || (plan ? e.slot() != 0 : e.slot() < 1 || e.slot() > 4)
                || !Objects.equals(e.callId(), plan ? "plan-" + job : "image-" + job + "-" + e.slot())) invalid();
        RequestSpec r = e.request();
        if (r == null || !matches(r.promptVersion(), "[A-Za-z0-9_.:-]{1,64}")
                || !matches(r.model(), "[A-Za-z0-9_.:/-]{1,128}") || !text(r.promptText(), 64000)
                || r.references() == null || r.references().size() > 8) invalid();
        if (plan) {
            if (!"/chat/completions".equals(r.endpoint()) || !text(r.systemPrompt(), 4000)
                    || r.maxTokens() == null || r.maxTokens() != 1800 || r.temperature() == null
                    || !Double.isFinite(r.temperature()) || r.temperature() < 0 || r.temperature() > 2
                    || r.size() != null || r.n() != null || r.quality() != null) invalid();
        } else if (!Objects.equals(r.endpoint(), r.references().isEmpty() ? "/images/generations" : "/images/edits")
                || r.systemPrompt() != null || r.temperature() != null || r.maxTokens() != null
                || !matches(r.size(), "[1-9][0-9]{1,4}x[1-9][0-9]{1,4}") || !Integer.valueOf(1).equals(r.n())
                || (r.quality() != null && !matches(r.quality(), "[A-Za-z0-9_-]{1,32}"))) invalid();
        for (Reference ref : r.references()) {
            if (ref == null || !matches(ref.assetId(), "[1-9][0-9]{0,18}")
                    || (ref.floorNo() != null && ref.floorNo() < 1)
                    || (ref.sourceCaseVersionId() != null && !matches(ref.sourceCaseVersionId(), "[1-9][0-9]{0,18}"))
                    || (ref.sourceType() != null && !Set.of("CASE_FLOOR_PLAN", "USER_SKETCH").contains(ref.sourceType()))
                    || !matches(ref.sourceSha256(), "[a-f0-9]{64}") || !matches(ref.submittedSha256(), "[a-f0-9]{64}")
                    || !Set.of("image/png", "image/jpeg").contains(ref.mimeType() == null ? "" : ref.mimeType())
                    || ref.sizeBytes() < 1 || ref.sizeBytes() > 20L * 1024 * 1024) invalid();
        }
        if (Set.of("UNKNOWN", "NOT_SENT").contains(e.state())) {
            if (!matches(e.errorCode(), "(?:[A-Z][A-Z0-9_]{0,63}|CORE_-[0-9]{1,10})") || e.response() != null) invalid();
        } else if (e.errorCode() != null) invalid();
        if ("SUCCEEDED".equals(e.state())) {
            ResponseSpec response = e.response();
            if (response == null) invalid();
            if (plan) {
                if (!text(response.promptText(), 8000) || !matches(response.sha256(), "[a-f0-9]{64}")
                        || response.width() != null || response.height() != null || response.mimeType() != null
                        || response.sizeBytes() != null) invalid();
            } else if (response.promptText() != null || !matches(response.sha256(), "[a-f0-9]{64}")
                    || (response.width() == null) != (response.height() == null)
                    || (response.width() != null && (response.width() < 1 || response.height() < 1))
                    || !Set.of("image/png", "image/jpeg").contains(response.mimeType() == null ? "" : response.mimeType())
                    || response.sizeBytes() == null || response.sizeBytes() < 1 || response.sizeBytes() > 20L * 1024 * 1024) invalid();
        } else if (e.response() != null) invalid();
    }

    private static int rank(String state) {
        return switch (state) { case "REQUESTED" -> 0; case "DISPATCHED" -> 1; case "UNKNOWN", "NOT_SENT" -> 2; default -> 3; };
    }
    private static boolean matches(String value, String pattern) { return value != null && value.matches(pattern); }
    private static boolean text(String value, int max) { return value != null && !value.isBlank() && value.length() <= max; }
    private static void invalid() { throw new IllegalArgumentException("RUNTIME_TRACE_INVALID"); }
    private static String json(Object value) {
        try { return JSON.writeValueAsString(value); } catch (Exception ignored) { throw new IllegalArgumentException("RUNTIME_TRACE_INVALID"); }
    }
    private static JsonNode tree(String value) {
        if (value == null) return null;
        try { return JSON.readTree(value); } catch (Exception ignored) { throw new IllegalStateException("RUNTIME_TRACE_INVALID"); }
    }
}
