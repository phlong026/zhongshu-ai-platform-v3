package cn.iocoder.yudao.designprovider;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.aiorchestration.controller.internal.vo.InternalClaimReqVO;
import cn.iocoder.yudao.module.aiorchestration.controller.internal.vo.InternalClaimRespVO;
import cn.iocoder.yudao.module.aiorchestration.controller.internal.vo.InternalEventReqVO;
import cn.iocoder.yudao.module.aiorchestration.job.AiJobOrchestrationService;
import cn.iocoder.yudao.module.aiorchestration.job.AiJobSettlementService;
import jakarta.annotation.security.PermitAll;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/** Runtime contract exposed by B. Authentication is enforced by the dedicated servlet filter. */
@RestController
@PermitAll
@RequestMapping("/internal-api/design/v1/ai-jobs")
public class ProviderAiJobInternalController {
    private static final Logger LOG = LoggerFactory.getLogger(ProviderAiJobInternalController.class);
    private final AiJobOrchestrationService orchestration;
    private final AiJobSettlementService settlement;
    private final ProviderPromptEventService promptEvents;
    private final ProviderOutputTicketService tickets;
    private final String providerCode;

    public ProviderAiJobInternalController(AiJobOrchestrationService orchestration,
            AiJobSettlementService settlement, ProviderPromptEventService promptEvents,
            ProviderOutputTicketService tickets,
            @Value("${zhongshu.ai.provider-code:apilio}") String providerCode) {
        this.orchestration = orchestration;
        this.settlement = settlement;
        this.promptEvents = promptEvents;
        this.tickets = tickets;
        this.providerCode = providerCode;
    }

    @PostMapping("/claims")
    public CommonResult<List<InternalClaimRespVO>> claim(@Valid @RequestBody InternalClaimReqVO request) {
        List<InternalClaimRespVO> result = orchestration.claim(request.getWorkerId(),
                        request.getMaxJobs() == null ? 1 : request.getMaxJobs(), 60, providerCode)
                .stream().map(job -> {
                    var response = new InternalClaimRespVO();
                    response.setJobId(String.valueOf(job.jobId()));
                    response.setAttemptNo(job.attemptNo());
                    response.setFencingToken(job.fencingToken());
                    response.setPhase(job.phase());
                    response.setPayload(Map.of("requestedCount", job.requestedCount(), "outputPrefix", job.outputPrefix()));
                    return response;
                }).toList();
        return success(result);
    }

    @PostMapping("/{jobId}/lease-renewals")
    public CommonResult<Boolean> renew(@PathVariable String jobId, @Valid @RequestBody InternalEventReqVO event) {
        return success(orchestration.renewLease(jobId(jobId), event.getAttemptNo(), event.getFencingToken(), 60));
    }

    @PostMapping("/{jobId}/prompt-calls")
    public CommonResult<String> reservePrompt(@PathVariable String jobId, @RequestBody Map<String, Object> body) {
        return success(settlement.reservePromptCall(jobId(jobId), integer(body, "attemptNo"), number(body, "fencingToken")));
    }

    @PostMapping("/{jobId}/prompt-call-events")
    public CommonResult<Boolean> finishPrompt(@PathVariable String jobId, @RequestBody Map<String, Object> body) {
        long id = jobId(jobId);
        int attempt = integer(body, "attemptNo");
        long fence = number(body, "fencingToken");
        String outcome = string(body, "outcome");
        String callId = string(body, "callId");
        String responseHash = body.get("responseHash") instanceof String value ? value : null;
        String prompt = body.get("promptText") instanceof String value ? value : null;
        String model = body.get("model") instanceof String value ? value : null;
        boolean result = promptEvents.confirm(new ProviderPromptEventService.Event(id, attempt, fence, outcome,
                callId, responseHash, prompt, model));
        if ("SUCCEEDED".equals(outcome) && prompt != null) {
            try {
                settlement.recordPromptText(id, attempt, fence, prompt, model);
            } catch (RuntimeException failure) {
                LOG.warn("[runtimePromptRecord][job={} attempt={} failureType={}]", id, attempt,
                        failure.getClass().getSimpleName());
            }
        }
        return success(result);
    }

    @PostMapping("/{jobId}/progress-events")
    public CommonResult<Boolean> progress(@PathVariable String jobId, @Valid @RequestBody InternalEventReqVO event) {
        orchestration.reportProgress(jobId(jobId), event.getAttemptNo(), event.getFencingToken(),
                event.getProgress() == null ? 0 : event.getProgress(), event.getStage());
        return success(true);
    }

    @PostMapping("/{jobId}/result-events")
    public CommonResult<String> result(@PathVariable String jobId, @Valid @RequestBody InternalEventReqVO event) {
        validateResult(event);
        return success(tickets.consume(jobId(jobId), event));
    }

    @PostMapping("/{jobId}/failure-events")
    public CommonResult<Boolean> failure(@PathVariable String jobId, @Valid @RequestBody InternalEventReqVO event) {
        return success(orchestration.reportFailure(jobId(jobId), event.getAttemptNo(), event.getFencingToken(),
                event.getProviderCode(), event.getSourceEventId(), event.getErrorCode(), event.getMessage()));
    }

    @PostMapping("/{jobId}/completion-events")
    public CommonResult<Boolean> complete(@PathVariable String jobId, @Valid @RequestBody InternalEventReqVO event) {
        return success(orchestration.completeAttempt(jobId(jobId), event.getAttemptNo(), event.getFencingToken()));
    }

    private static long jobId(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "RUNTIME_JOB_ID_INVALID");
        }
    }

    private static int integer(Map<String, Object> body, String field) {
        long value = number(body, field);
        if (value > Integer.MAX_VALUE) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "RUNTIME_BODY_INVALID");
        return (int) value;
    }

    private static long number(Map<String, Object> body, String field) {
        if (!(body.get(field) instanceof Number number) || number.longValue() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "RUNTIME_BODY_INVALID");
        }
        return number.longValue();
    }

    private static String string(Map<String, Object> body, String field) {
        if (!(body.get(field) instanceof String value) || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "RUNTIME_BODY_INVALID");
        }
        return value;
    }

    private void validateResult(InternalEventReqVO event) {
        long size = event.getSizeBytes() == null ? -1 : event.getSizeBytes();
        int slot = event.getCandidateSlotNo() == null ? -1 : event.getCandidateSlotNo();
        if (!providerCode.equals(event.getProviderCode())
                || event.getSourceEventId() == null
                || !event.getSourceEventId().matches("[A-Za-z0-9:_-]{1,128}")
                || slot < 1 || slot > 4
                || event.getObjectKey() == null || event.getObjectKey().length() > 300
                || event.getSha256() == null || !event.getSha256().matches("[a-f0-9]{64}")
                || !("image/png".equals(event.getMimeType()) || "image/jpeg".equals(event.getMimeType()))
                || size < 1 || size > 20L * 1024 * 1024) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "RUNTIME_RESULT_INVALID");
        }
    }
}
