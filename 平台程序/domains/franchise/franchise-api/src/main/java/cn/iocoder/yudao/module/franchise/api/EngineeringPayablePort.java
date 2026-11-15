package cn.iocoder.yudao.module.franchise.api;

import cn.iocoder.yudao.module.platform.org.PostSnapshot;

/**
 * 工程材料应付登记端口（阶段 2 / D-7①）：franchise-engineering 材料采购金额（terms）写通 finance 应付。
 * franchise-finance 以 EngineeringPayableAdapter 实现（复用 FinPaymentService.registerPayable 幂等先例，
 * **零新迁移**）；字段映射 supplier→recipient、basis→agreementRef、sourceRef 作幂等源。
 * 付款本体工程端不经手（finance 既有 FinPaymentService 办理）。
 */
public interface EngineeringPayablePort {

    /** @return 应付 id（重放返回既有） */
    long registerPayable(PostSnapshot post, PayableCommand command);

    /** 原供应事项无已核实付款时修订同一应付；全额退回不解锁，未融合实现必须拒绝。 */
    default void updateUnpaid(PostSnapshot post,long payableId,PayableCommand command,String idempotencyKey) {
        throw new UnsupportedOperationException("当前财务实现不支持未付款应付修订");
    }

    record PayableCommand(long sourceProjectId, String sourceRef, String supplier,
                          long amountCents, String basis) {
    }
}
