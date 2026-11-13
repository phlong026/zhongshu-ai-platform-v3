package cn.iocoder.yudao.module.commerce.orgpoint;

import cn.iocoder.yudao.module.infra.zhongshu.audit.AuditEventMessage;
import cn.iocoder.yudao.module.infra.zhongshu.audit.AuditPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 长期未终结预扣告警（24 号 D6 §2.4）：每轮扫描一次，超阈值 PENDING 汇总成**一条**审计事件，
 * 仅告警 + 人工核对通道，**绝不自动退款**（UNKNOWN/DISPATCHED 可能已被供应商受理）。
 *
 * <p>开关 `zhongshu.org-point.pending-alert-enabled`（默认 false；生产建议 true）。
 * 阈值 `zhongshu.org-point.pending-alert-hours`（默认 24）。
 */
@Slf4j
@Component
public class OrgPointPendingAlertJob {

    private final OrgPointService orgPointService;
    private final AuditPort auditPort;
    private final boolean enabled;
    private final long thresholdHours;

    public OrgPointPendingAlertJob(OrgPointService orgPointService, AuditPort auditPort,
                                   @Value("${zhongshu.org-point.pending-alert-enabled:false}") boolean enabled,
                                   @Value("${zhongshu.org-point.pending-alert-hours:24}") long thresholdHours) {
        this.orgPointService = orgPointService;
        this.auditPort = auditPort;
        this.enabled = enabled;
        this.thresholdHours = thresholdHours;
    }

    @Scheduled(fixedDelayString = "${zhongshu.org-point.pending-alert-interval-ms:3600000}", initialDelayString = "60000")
    public void sweep() {
        if (!enabled) {
            return;
        }
        List<OrgPointService.PendingOperation> pending;
        try {
            pending = orgPointService.findPendingOlderThan(thresholdHours, 100);
        } catch (RuntimeException e) {
            log.warn("[pendingAlert][扫描失败] {}", e.getMessage());
            return;
        }
        if (pending.isEmpty()) {
            return;
        }
        Map<String, Object> byOrg = new LinkedHashMap<>();
        for (OrgPointService.PendingOperation op : pending) {
            byOrg.put(String.valueOf(op.orgId()),
                    ((Number) byOrg.getOrDefault(String.valueOf(op.orgId()), 0)).intValue() + 1);
        }
        log.warn("[pendingAlert][长期未终结预扣] count={} thresholdHours={} byOrg={}",
                pending.size(), thresholdHours, byOrg);
        auditPort.record(AuditEventMessage.builder()
                .eventType("ORG_POINT_PENDING_ALERT").actorType(AuditEventMessage.ActorType.SYSTEM)
                .actorId("system").action("ALERT")
                .bizType("org_point_ledger").bizId("pending-" + thresholdHours + "h")
                .result(AuditEventMessage.AuditResult.SUCCESS)
                .detail(Map.of("count", pending.size(), "thresholdHours", thresholdHours,
                        "byOrg", byOrg,
                        "operationIds", pending.stream().limit(20)
                                .map(OrgPointService.PendingOperation::operationId).toList()))
                .build());
    }
}
