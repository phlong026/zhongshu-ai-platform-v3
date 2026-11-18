package cn.iocoder.yudao.module.franchise.finance;

import cn.iocoder.yudao.module.franchise.api.EngineeringPayablePort;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.springframework.stereotype.Component;

/**
 * 工程材料应付适配器（阶段 2 / D-7①）：franchise-finance 实现 EngineeringPayablePort。
 * 复用 FinPaymentService.registerPayable 幂等先例（FranchiseCommands idempotencyKey），零新迁移。
 * 幂等键取独立命名空间 "eng-payable:{sourceRef}"（sales collect 的 biz-receipt 先例——
 * 不与工程域自身命令键相撞，工程侧 supply.payable_id 落锚）。
 */
@Component
public class EngineeringPayableAdapter implements EngineeringPayablePort {

    private final FinPaymentService payments;

    public EngineeringPayableAdapter(FinPaymentService payments) {
        this.payments = payments;
    }

    @Override
    public long registerPayable(PostSnapshot post, PayableCommand cmd) {
        FinPaymentService.PayableCommand finCmd = new FinPaymentService.PayableCommand(
                post.orgId(), cmd.sourceProjectId(), cmd.sourceRef().startsWith("engineering-expense:") ? "engineering_expense" : "engineering", cmd.amountCents(),
                cmd.supplier(), cmd.basis());
        return payments.registerPayable(post, "eng-payable:" + cmd.sourceRef(), finCmd);
    }

    @Override
    public void updateUnpaid(PostSnapshot post,long payableId,PayableCommand cmd,String idempotencyKey) {
        payments.updateUnpaidEngineeringPayable(post,idempotencyKey,
                new FinPaymentService.EngineeringPayableUpdateCommand(payableId,cmd.sourceProjectId(),
                        cmd.sourceRef(),cmd.supplier(),cmd.amountCents(),cmd.basis()));
    }
}
