package cn.iocoder.yudao.designprovider;

import cn.iocoder.yudao.module.aiorchestration.controller.internal.vo.InternalEventReqVO;
import cn.iocoder.yudao.module.aiorchestration.job.AiJobOrchestrationService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/** Persists the exact upload grant and consumes it atomically with the result event. */
public class ProviderOutputTicketService {
    private static final Duration TICKET_TTL = Duration.ofMinutes(15);
    private final JdbcTemplate jdbc;
    private final AiJobOrchestrationService orchestration;

    ProviderOutputTicketService(JdbcTemplate jdbc, AiJobOrchestrationService orchestration) {
        this.jdbc = jdbc;
        this.orchestration = orchestration;
    }

    @Transactional
    public Ticket issue(long jobId, int attemptNo, long fencingToken, int slot, String mimeType) {
        var leases = jdbc.queryForList("SELECT requested_count FROM ai_job WHERE id=? AND fencing_token=? "
                        + "AND status='RUNNING' AND claim_expires_at>now() AND deleted=FALSE "
                        + "AND EXISTS (SELECT 1 FROM ai_job_attempt a WHERE a.job_id=ai_job.id AND a.attempt_no=? "
                        + "AND a.fencing_token=? AND a.finished_at IS NULL AND a.deleted=FALSE) FOR UPDATE",
                jobId, fencingToken, attemptNo, fencingToken);
        if (leases.size() != 1 || slot > ((Number) leases.get(0).get("requested_count")).intValue()) {
            throw conflict("RUNTIME_TICKET_LEASE_INVALID");
        }
        String extension = "image/png".equals(mimeType) ? ".png" : ".jpg";
        String key = "ai-quarantine/" + jobId + "/" + fencingToken + "/" + slot + "/" + UUID.randomUUID() + extension;
        jdbc.update("INSERT INTO design_provider_output_ticket(id,job_id,attempt_no,fencing_token,"
                        + "candidate_slot_no,object_key,mime_type,expires_at) VALUES (?,?,?,?,?,?,?,now()+(? * interval '1 second')) "
                        + "ON CONFLICT (job_id,attempt_no,fencing_token,candidate_slot_no) DO NOTHING",
                UUID.randomUUID(), jobId, attemptNo, fencingToken, slot, key, mimeType, TICKET_TTL.toSeconds());
        var rows = jdbc.queryForList("SELECT object_key,mime_type,state FROM design_provider_output_ticket "
                        + "WHERE job_id=? AND attempt_no=? AND fencing_token=? AND candidate_slot_no=? FOR UPDATE",
                jobId, attemptNo, fencingToken, slot);
        if (rows.size() != 1) throw conflict("RUNTIME_TICKET_ISSUE_FAILED");
        Map<String, Object> row = rows.get(0);
        if ("CONSUMED".equals(row.get("state"))) throw conflict("RUNTIME_TICKET_ALREADY_CONSUMED");
        if (!mimeType.equals(row.get("mime_type"))) throw conflict("RUNTIME_TICKET_MIME_CONFLICT");
        jdbc.update("UPDATE design_provider_output_ticket SET expires_at=now()+(? * interval '1 second'),"
                        + "update_time=now() WHERE job_id=? AND attempt_no=? AND fencing_token=? AND candidate_slot_no=?",
                TICKET_TTL.toSeconds(), jobId, attemptNo, fencingToken, slot);
        return new Ticket((String) row.get("object_key"), mimeType);
    }

    @Transactional
    public String consume(long jobId, InternalEventReqVO event) {
        int slot = event.getCandidateSlotNo() == null ? 1 : event.getCandidateSlotNo();
        // Keep lock order consistent with issuance and orchestration: job first, then ticket.
        if (jdbc.queryForList("SELECT id FROM ai_job WHERE id=? FOR UPDATE", jobId).size() != 1) {
            throw conflict("RUNTIME_TICKET_JOB");
        }
        var tickets = jdbc.queryForList("SELECT job_id,attempt_no,fencing_token,candidate_slot_no,object_key,mime_type,state,"
                        + "source_event_id,content_sha256,size_bytes,expires_at FROM design_provider_output_ticket "
                        + "WHERE object_key=? FOR UPDATE", event.getObjectKey());
        if (tickets.size() != 1) throw conflict("RUNTIME_TICKET_NOT_ISSUED");
        Map<String, Object> ticket = tickets.get(0);
        long size = event.getSizeBytes() == null ? 0 : event.getSizeBytes();
        requireEqual(((Number) ticket.get("job_id")).longValue(), jobId, "RUNTIME_TICKET_JOB");
        requireEqual(((Number) ticket.get("attempt_no")).intValue(), event.getAttemptNo(), "RUNTIME_TICKET_ATTEMPT");
        requireEqual(((Number) ticket.get("fencing_token")).longValue(), event.getFencingToken(), "RUNTIME_TICKET_FENCE");
        requireEqual(((Number) ticket.get("candidate_slot_no")).intValue(), slot, "RUNTIME_TICKET_SLOT");
        requireEqual((String) ticket.get("mime_type"), event.getMimeType(), "RUNTIME_TICKET_MIME");
        if ("CONSUMED".equals(ticket.get("state"))) {
            requireEqual((String) ticket.get("source_event_id"), event.getSourceEventId(), "RUNTIME_TICKET_REPLAY_EVENT");
            requireEqual((String) ticket.get("content_sha256"), event.getSha256(), "RUNTIME_TICKET_REPLAY_SHA");
            requireEqual(((Number) ticket.get("size_bytes")).longValue(), size, "RUNTIME_TICKET_REPLAY_SIZE");
            return AiJobOrchestrationService.ReportOutcome.DUPLICATE_EVENT.name();
        }
        if (((java.sql.Timestamp) ticket.get("expires_at")).toInstant().isBefore(java.time.Instant.now())) {
            throw conflict("RUNTIME_TICKET_EXPIRED");
        }
        var outcome = orchestration.reportResult(jobId, event.getAttemptNo(), event.getFencingToken(),
                event.getProviderCode(), event.getSourceEventId(), slot, event.getObjectKey(), event.getSha256(),
                event.getMimeType(), size);
        if (outcome != AiJobOrchestrationService.ReportOutcome.QUARANTINED) {
            throw conflict("RUNTIME_TICKET_RESULT_" + outcome.name());
        }
        jdbc.update("UPDATE design_provider_output_ticket SET state='CONSUMED',source_event_id=?,content_sha256=?,"
                        + "size_bytes=?,consumed_at=now(),update_time=now() WHERE object_key=? AND state='ISSUED'",
                event.getSourceEventId(), event.getSha256(), size, event.getObjectKey());
        return outcome.name();
    }

    private static void requireEqual(Object expected, Object actual, String code) {
        if (!java.util.Objects.equals(expected, actual)) throw conflict(code);
    }

    private static ResponseStatusException conflict(String code) {
        return new ResponseStatusException(HttpStatus.CONFLICT, code);
    }

    public record Ticket(String objectKey, String mimeType) { }
}
