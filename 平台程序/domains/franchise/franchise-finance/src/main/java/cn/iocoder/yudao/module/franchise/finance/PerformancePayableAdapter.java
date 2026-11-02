package cn.iocoder.yudao.module.franchise.finance;

import cn.iocoder.yudao.module.franchise.api.PerformancePayablePort;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.springframework.stereotype.Component;

/**
 * 绩效提成应付适配器（阶段 3 / D-6②）：复用 FinPaymentService.registerPayable 幂等先例，
 * 独立命名空间键 "perf-commission:{sourceRef}"（EngineeringPayableAdapter 同款模式，零新迁移）。
 */
@Component
public class PerformancePayableAdapter implements PerformancePayablePort {

    private final FinPaymentService payments;

    public PerformancePayableAdapter(FinPaymentService payments) {
        this.payments = payments;
    }

    @Override
    public long registerPayable(PostSnapshot post, PayableCommand cmd) {
        FinPaymentService.PayableCommand finCmd = new FinPaymentService.PayableCommand(
                post.orgId(), cmd.sourceProjectId(), "performance", cmd.amountCents(),
                cmd.recipient(), cmd.agreementRef());
        return payments.registerPayable(post, "perf-commission:" + cmd.sourceRef(), finCmd);
    }
}
