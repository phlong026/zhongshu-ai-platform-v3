package cn.iocoder.yudao.designprovider;

import cn.iocoder.yudao.module.aiorchestration.job.AiJobSettlementService;
import cn.iocoder.yudao.module.infra.zhongshu.api.PlatformCostPort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

/** B-owned durable ACK identity for prompt cost events. */
public class ProviderPromptEventService {
    private static final Set<String> OUTCOMES = Set.of("DISPATCHED", "SUCCEEDED", "UNKNOWN", "NOT_SENT");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final AiJobSettlementService settlement;
    private final PlatformCostPort platformCosts;

    ProviderPromptEventService(DataSource dataSource, PlatformTransactionManager transactionManager,
            AiJobSettlementService settlement, PlatformCostPort platformCosts) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.transaction = new TransactionTemplate(transactionManager);
        this.settlement = settlement;
        this.platformCosts = platformCosts;
    }

    public boolean confirm(Event event) {
        validate(event);
        String fingerprint = fingerprint(event);
        String callFingerprint = callFingerprint(event);
        return Boolean.TRUE.equals(transaction.execute(status -> {
            String identity = "prompt-event:" + event.jobId();
            jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", rs -> { }, identity);
            var receipts = jdbc.queryForList("SELECT attempt_no,fencing_token,call_id,call_sha256,event_sha256,state "
                            + "FROM design_provider_prompt_event_receipt WHERE job_id=? AND outcome=? FOR UPDATE",
                    event.jobId(), event.outcome());
            if (!receipts.isEmpty()) {
                var receipt = receipts.get(0);
                if (((Number) receipt.get("attempt_no")).intValue() != event.attemptNo()
                        || ((Number) receipt.get("fencing_token")).longValue() != event.fencingToken()
                        || !event.callId().equals(receipt.get("call_id"))
                        || !fingerprint.equals(receipt.get("event_sha256"))
                        || !"APPLIED".equals(receipt.get("state"))) {
                    throw conflict("RUNTIME_PROMPT_EVENT_CONFLICT");
                }
                // The event was already atomically applied. ACK only clears a lost response; it performs no job/cost work.
                return true;
            }
            HolderMode holder = requireHolderOrRecoverableTerminal(event, callFingerprint);
            jdbc.update("INSERT INTO design_provider_prompt_event_receipt(id,job_id,attempt_no,fencing_token,outcome,"
                            + "call_id,call_sha256,event_sha256,state) VALUES (?,?,?,?,?,?,?,?,'RECEIVING')",
                    UUID.randomUUID(), event.jobId(), event.attemptNo(), event.fencingToken(), event.outcome(),
                    event.callId(), callFingerprint, fingerprint);
            boolean applied = apply(event, holder);
            if (!applied && !("UNKNOWN".equals(event.outcome())
                    && matchingAppliedDispatch(event, callFingerprint))) {
                throw conflict("RUNTIME_PROMPT_EVENT_NOT_APPLIED");
            }
            jdbc.update("UPDATE design_provider_prompt_event_receipt SET state='APPLIED',applied_at=now(),update_time=now() "
                            + "WHERE job_id=? AND outcome=? AND state='RECEIVING'",
                    event.jobId(), event.outcome());
            return true;
        }));
    }

    private boolean apply(Event event, HolderMode holder) {
        try {
            return switch (holder) {
                case CURRENT -> settlement.finishPromptCall(event.jobId(), event.attemptNo(), event.fencingToken(),
                        event.outcome(), event.callId(), event.responseHash());
                case STALE_UNKNOWN_ACK -> true;
                case STALE_NOT_SENT_RELEASE -> platformCosts.finishPrompt(event.jobId(), event.fencingToken(),
                        event.outcome(), event.callId(), event.responseHash(), false);
            };
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            throw conflict("RUNTIME_PROMPT_EVENT_NOT_APPLIED");
        }
    }

    private HolderMode requireHolderOrRecoverableTerminal(Event event, String callFingerprint) {
        var job = lockJob(event.jobId());
        String status = (String) job.get("status");
        boolean terminal = Set.of("SUCCEEDED", "PARTIALLY_SUCCEEDED", "FAILED", "CANCELLED", "REFUNDED")
                .contains(status);
        Integer attempts = jdbc.queryForObject("SELECT count(*) FROM ai_job_attempt WHERE job_id=? AND attempt_no=? "
                        + "AND fencing_token=? AND deleted=FALSE",
                Integer.class, event.jobId(), event.attemptNo(), event.fencingToken());
        if (!Integer.valueOf(1).equals(attempts)) throw conflict("RUNTIME_PROMPT_EVENT_LEASE");
        boolean currentFence = ((Number) job.get("fencing_token")).longValue() == event.fencingToken();
        boolean live = currentFence && "RUNNING".equals(status) && job.get("claim_expires_at") != null
                && ((java.sql.Timestamp) job.get("claim_expires_at")).toInstant().isAfter(java.time.Instant.now())
                && Integer.valueOf(1).equals(jdbc.queryForObject("SELECT count(*) FROM ai_job_attempt "
                        + "WHERE job_id=? AND attempt_no=? AND fencing_token=? AND deleted=FALSE AND finished_at IS NULL",
                        Integer.class, event.jobId(), event.attemptNo(), event.fencingToken()));
        if (currentFence && (terminal || live)) {
            if (("NOT_SENT".equals(event.outcome()) && hasAppliedOutcome(event.jobId(), "DISPATCHED"))
                    || ("DISPATCHED".equals(event.outcome()) && hasAppliedOutcome(event.jobId(), "NOT_SENT"))) {
                throw conflict("RUNTIME_PROMPT_EVENT_CONFLICT");
            }
            return HolderMode.CURRENT;
        }

        // Runtime durably retries these two terminal facts without rewriting their original attempt/fence.
        if ("UNKNOWN".equals(event.outcome()) && matchingAppliedDispatch(event, callFingerprint)) {
            return HolderMode.STALE_UNKNOWN_ACK;
        }
        if ("NOT_SENT".equals(event.outcome()) && "SERVICE_COST".equals(job.get("billing_mode"))
                && !hasAppliedDispatch(event.jobId())) {
            requireOriginalNotSentReservation(event);
            return HolderMode.STALE_NOT_SENT_RELEASE;
        }
        throw conflict("RUNTIME_PROMPT_EVENT_LEASE");
    }

    private java.util.Map<String, Object> lockJob(long jobId) {
        var jobs = jdbc.queryForList("SELECT status,fencing_token,claim_expires_at,billing_mode FROM ai_job "
                + "WHERE id=? AND deleted=FALSE FOR UPDATE", jobId);
        if (jobs.size() != 1) throw conflict("RUNTIME_PROMPT_EVENT_LEASE");
        return jobs.get(0);
    }

    private boolean hasAppliedDispatch(long jobId) {
        return hasAppliedOutcome(jobId, "DISPATCHED");
    }

    private boolean hasAppliedOutcome(long jobId, String outcome) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM design_provider_prompt_event_receipt "
                + "WHERE job_id=? AND outcome=? AND state='APPLIED'", Integer.class, jobId, outcome);
        return !Integer.valueOf(0).equals(count);
    }

    private void requireOriginalNotSentReservation(Event event) {
        var rows = jdbc.queryForList("SELECT c.prompt_state,c.prompt_token,c.prompt_call_id "
                + "FROM ai_job j JOIN platform_cost_business c ON c.id=j.cost_business_id "
                + "WHERE j.id=? AND j.billing_mode='SERVICE_COST' FOR UPDATE OF c", event.jobId());
        if (rows.size() != 1) throw conflict("RUNTIME_PROMPT_EVENT_NOT_APPLIED");
        var cost = rows.get(0);
        if (!"RESERVED".equals(cost.get("prompt_state")) || cost.get("prompt_token") == null
                || ((Number) cost.get("prompt_token")).longValue() != event.fencingToken()
                || cost.get("prompt_call_id") != null
                || !("plan-" + event.jobId()).equals(event.callId())) {
            throw conflict("RUNTIME_PROMPT_EVENT_NOT_APPLIED");
        }
    }

    private boolean matchingAppliedDispatch(Event event, String callFingerprint) {
        var rows = jdbc.queryForList("SELECT attempt_no,fencing_token,call_id,call_sha256,state "
                        + "FROM design_provider_prompt_event_receipt WHERE job_id=? AND outcome='DISPATCHED'",
                event.jobId());
        if (rows.size() != 1) return false;
        var receipt = rows.get(0);
        return ((Number) receipt.get("attempt_no")).intValue() == event.attemptNo()
                && ((Number) receipt.get("fencing_token")).longValue() == event.fencingToken()
                && event.callId().equals(receipt.get("call_id"))
                && callFingerprint.equals(receipt.get("call_sha256"))
                && "APPLIED".equals(receipt.get("state"));
    }

    private static void validate(Event event) {
        boolean succeeded = "SUCCEEDED".equals(event.outcome());
        boolean validSuccess = succeeded && event.responseHash() != null
                && event.responseHash().matches("[a-f0-9]{64}")
                && event.promptText() != null && !event.promptText().isBlank()
                && event.promptText().length() <= 8000
                && event.responseHash().equals(sha256(event.promptText()))
                && (event.model() == null || event.model().matches("[A-Za-z0-9_.:/-]{1,64}"));
        boolean cleanNonSuccess = !succeeded && event.responseHash() == null
                && event.promptText() == null && event.model() == null;
        if (event.jobId() <= 0 || event.attemptNo() <= 0 || event.fencingToken() <= 0
                || !OUTCOMES.contains(event.outcome())
                || event.callId() == null || !event.callId().matches("[A-Za-z0-9:_-]{1,160}")
                || !(validSuccess || cleanNonSuccess)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "RUNTIME_PROMPT_EVENT_INVALID");
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String fingerprint(Event event) {
        return digest(event, true);
    }

    private static String callFingerprint(Event event) {
        return digest(event, false);
    }

    private static String digest(Event event, boolean includeOutcome) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            add(digest, String.valueOf(event.jobId()));
            add(digest, String.valueOf(event.attemptNo()));
            add(digest, String.valueOf(event.fencingToken()));
            if (includeOutcome) add(digest, event.outcome());
            add(digest, event.callId());
            if (includeOutcome) add(digest, event.responseHash());
            add(digest, event.promptText());
            add(digest, event.model());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void add(MessageDigest digest, String value) {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (value == null ? 0 : 1));
        digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static ResponseStatusException conflict(String code) {
        return new ResponseStatusException(HttpStatus.CONFLICT, code);
    }

    private enum HolderMode { CURRENT, STALE_UNKNOWN_ACK, STALE_NOT_SENT_RELEASE }

    public record Event(long jobId, int attemptNo, long fencingToken, String outcome, String callId,
                        String responseHash, String promptText, String model) { }
}
