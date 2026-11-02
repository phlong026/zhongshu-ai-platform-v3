package cn.iocoder.yudao.module.franchise.api;

import cn.iocoder.yudao.module.platform.org.PostSnapshot;

/**
 * 绩效提成应付登记端口（阶段 3 / D-6②）：franchise-performance 提成 release 写通 finance 应付。
 * franchise-finance 以 PerformancePayableAdapter 实现（复用 FinPaymentService.registerPayable 幂等先例，
 * 零新迁移）；字段映射 supplier→recipient、agreementRef=提成标识（P2-7 兜底：perf-commission:{policyId}:v{version}）。
 * 付款本体在 finance 侧办理（D-6④ 降维：停付由本域 reconcile 状态机承担）。
 */
public interface PerformancePayablePort {

    /** @return 应付 id（重放返回既有） */
    long registerPayable(PostSnapshot post, PayableCommand command);

    record PayableCommand(long sourceProjectId, String sourceRef, String recipient, long amountCents,
                          String agreementRef, String stageLabel) {
    }
}
