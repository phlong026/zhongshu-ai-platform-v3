package cn.iocoder.yudao.designprovider;

import cn.iocoder.yudao.module.aiorchestration.job.AiJobOrchestrationService;
import cn.iocoder.yudao.module.aiorchestration.job.AiJobSettlementService;
import cn.iocoder.yudao.module.design.project.DesignProjectService;
import cn.iocoder.yudao.module.infra.zhongshu.delivery.OutboxDispatcherService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntSupplier;

/** B-only job progression. No A payment order or personal-wallet recovery is present here. */
@Slf4j
public class ProviderDesignJobDriver implements ProviderRuntimeDriver {
    private final JdbcTemplate jdbc;
    private final AiJobOrchestrationService orchestration;
    private final AiJobSettlementService settlement;
    private final DesignProjectService projects;
    private final OutboxDispatcherService outbox;
    private final String workerId = "design-provider-" + UUID.randomUUID();

    @Value("${zhongshu.design.driver-enabled:false}")
    private boolean enabled;
    @Value("${zhongshu.ai.internal-secret:}")
    private String internalSecret;
    @Value("${zhongshu.design.cancel-grace-minutes:10}")
    private int cancelGraceMinutes;
    @Value("${zhongshu.design.no-runtime-grace-minutes:30}")
    private int noRuntimeGraceMinutes;

    public ProviderDesignJobDriver(DataSource dataSource, AiJobOrchestrationService orchestration,
                                   AiJobSettlementService settlement, DesignProjectService projects,
                                   OutboxDispatcherService outbox) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.orchestration = orchestration;
        this.settlement = settlement;
        this.projects = projects;
        this.outbox = outbox;
    }

    @Override
    public void verifyReady() {
        if (!enabled) throw new IllegalStateException("DESIGN_PROVIDER_JOB_DRIVER_DISABLED");
        if (internalSecret == null || internalSecret.isBlank()) {
            throw new IllegalStateException("DESIGN_PROVIDER_RUNTIME_SECRET_MISSING");
        }
        if (cancelGraceMinutes < 1 || noRuntimeGraceMinutes < 1) {
            throw new IllegalStateException("DESIGN_PROVIDER_DRIVER_GRACE_INVALID");
        }
        Integer ready = jdbc.queryForObject("SELECT CASE WHEN to_regclass('ai_job') IS NOT NULL "
                + "AND to_regclass('ai_job_result') IS NOT NULL AND to_regclass('outbox_event') IS NOT NULL "
                + "AND to_regclass('design_provider_output_ticket') IS NOT NULL "
                + "AND to_regclass('design_provider_prompt_event_receipt') IS NOT NULL "
                + "THEN 1 ELSE 0 END", Integer.class);
        if (!Integer.valueOf(1).equals(ready)) throw new IllegalStateException("DESIGN_PROVIDER_JOB_SCHEMA_NOT_READY");
    }

    @Scheduled(fixedDelayString = "${zhongshu.design.driver-delay-ms:10000}",
            initialDelayString = "${zhongshu.design.driver-initial-delay-ms:20000}")
    public void validationTick() { if (enabled) step("validate", this::validateQuarantined); }

    @Scheduled(fixedDelayString = "${zhongshu.design.driver-delay-ms:10000}",
            initialDelayString = "${zhongshu.design.driver-initial-delay-ms:20000}")
    public void settlementTick() { if (enabled) step("settle", this::settleReady); }

    @Scheduled(fixedDelayString = "${zhongshu.design.driver-delay-ms:10000}",
            initialDelayString = "${zhongshu.design.driver-initial-delay-ms:20000}")
    public void promotionTick() { if (enabled) step("promote", this::promoteSettled); }

    @Scheduled(fixedDelayString = "${zhongshu.design.driver-delay-ms:10000}",
            initialDelayString = "${zhongshu.design.driver-initial-delay-ms:20000}")
    public void outboxTick() {
        if (enabled) step("outbox", () -> {
            int count = outbox.dispatchOnce("design-provider", workerId, 30, 50, 60);
            outbox.heartbeat("design-provider", workerId, 60);
            return count;
        });
    }

    int validateQuarantined() {
        List<Long> ids = jdbc.queryForList("SELECT DISTINCT r.job_id FROM ai_job_result r JOIN ai_job j ON j.id=r.job_id "
                + "WHERE r.validation_state='OUTPUT_QUARANTINED' AND r.validation_due_at<=now() "
                + "AND j.status IN ('RUNNING','VALIDATING','CANCEL_REQUESTED') AND j.deleted=FALSE "
                + "ORDER BY r.job_id LIMIT 20", Long.class);
        int processed = 0;
        for (Long id : ids) {
            try { processed += orchestration.validateQuarantinedResults(id); }
            catch (RuntimeException failure) { warnFailure("validate", id, failure); }
        }
        return processed;
    }

    int settleReady() {
        List<Long> ids = jdbc.queryForList("SELECT id FROM ai_job WHERE deleted=FALSE "
                + "AND status IN ('QUEUED','RUNNING','VALIDATING','CANCEL_REQUESTED') AND ("
                + "(SELECT count(*) FROM ai_job_result r WHERE r.job_id=ai_job.id AND r.validation_state='ACCEPTED')>=requested_count "
                + "OR (runtime_completed_at IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ai_job_result r WHERE r.job_id=ai_job.id AND r.validation_state='OUTPUT_QUARANTINED')) "
                + "OR (status='CANCEL_REQUESTED' AND cancel_requested_at < now()-(? * interval '1 minute')) "
                + "OR create_time < now()-(? * interval '1 minute')) ORDER BY create_time,id LIMIT 20",
                Long.class, cancelGraceMinutes, noRuntimeGraceMinutes);
        int processed = 0;
        for (Long id : ids) {
            try { if (settlement.settleIfReady(id, noRuntimeGraceMinutes, cancelGraceMinutes)) processed++; }
            catch (RuntimeException failure) { warnFailure("settle", id, failure); }
        }
        return processed;
    }

    int promoteSettled() {
        List<Map<String, Object>> pending = jdbc.queryForList("SELECT j.id,j.user_id,j.project_ref FROM ai_job j "
                + "WHERE j.status IN ('SUCCEEDED','PARTIALLY_SUCCEEDED') AND j.deleted=FALSE "
                + "AND EXISTS (SELECT 1 FROM ai_job_result r WHERE r.job_id=j.id AND r.validation_state='ACCEPTED') "
                + "AND (SELECT count(*) FROM design_candidate c WHERE c.job_id=j.id AND c.deleted=FALSE)<"
                + "(SELECT count(*) FROM ai_job_result r WHERE r.job_id=j.id AND r.validation_state='ACCEPTED') "
                + "ORDER BY j.update_time,j.id LIMIT 20");
        int processed = 0;
        for (Map<String, Object> row : pending) {
            long jobId = ((Number) row.get("id")).longValue();
            try {
                projects.promoteCandidatesForSettledJob(((Number) row.get("user_id")).longValue(),
                        Long.parseLong(String.valueOf(row.get("project_ref"))), jobId);
                processed++;
            } catch (RuntimeException failure) {
                jdbc.update("UPDATE ai_job SET update_time=now() WHERE id=?", jobId);
                warnFailure("promote", jobId, failure);
            }
        }
        return processed;
    }

    private void step(String stage, IntSupplier operation) {
        try {
            int count = operation.getAsInt();
            if (count > 0) log.info("[designProviderDriver][stage={} processed={}]", stage, count);
        } catch (RuntimeException failure) {
            warnFailure(stage, null, failure);
        }
    }

    private static void warnFailure(String stage, Long jobId, RuntimeException failure) {
        Throwable cause = failure.getCause();
        log.warn("[designProviderDriver][stage={} job={} failureType={} causeType={}]", stage, jobId,
                failure.getClass().getSimpleName(), cause == null ? "none" : cause.getClass().getSimpleName());
    }
}
