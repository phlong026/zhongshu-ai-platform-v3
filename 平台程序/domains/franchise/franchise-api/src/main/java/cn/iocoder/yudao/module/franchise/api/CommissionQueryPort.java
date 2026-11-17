package cn.iocoder.yudao.module.franchise.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * 设计委托费用查询（B1 财务域登记 design 收款时的服务端门禁数据源）。
 * 只读视图，跨域经本 Port 访问，财务域不直接依赖 commission 的表结构。
 */
public interface CommissionQueryPort {

    /** Only IDs already authorized by the CRM query may be passed; company scope is mandatory. */
    default java.util.Map<Long, String> customerDesignStatuses(long companyId, java.util.Collection<Long> customerIds) {
        return java.util.Map.of();
    }

    /** 按委托编号取当前提交版本的费用视图；不存在返回 null */
    CommissionFeeView findCurrentFee(long commissionId);

    /** 费用视图：门店 companyId 持有委托，总部 designOrgId 是收款记账归属 */
    record CommissionFeeView(long commissionId, long companyId, long designOrgId,
                             int submissionVersion, String status,
                             long totalCents, long firstCents,
                             DesignFeeSettlementPort.Mode mode, long customerId,
                             Integer budgetVersion, Integer pricingVersion, BigDecimal area,
                             Long rateCents, String recipient) {

        /** Existing snapshots are explicitly historical split mode, never implicitly settled. */
        public CommissionFeeView(long commissionId, long companyId, long designOrgId,
                                 int submissionVersion, String status, long totalCents, long firstCents) {
            this(commissionId, companyId, designOrgId, submissionVersion, status, totalCents,
                    firstCents, DesignFeeSettlementPort.Mode.LEGACY_SPLIT, 0, null, null, null, null, null);
        }

        public List<DesignFeeSettlementPort.Stage> stages() {
            return mode == DesignFeeSettlementPort.Mode.AREA_FULL
                    ? List.of(DesignFeeSettlementPort.Stage.FULL)
                    : List.of(DesignFeeSettlementPort.Stage.FIRST, DesignFeeSettlementPort.Stage.TAIL);
        }

        /** 委托费用是否已批准（免审批直进 DESIGNING 或走完 fee-approve 均算） */
        public boolean feeApproved() {
            return !"FEE_PENDING".equals(status) && !"FEE_RETURNED".equals(status);
        }

        /** 期款是否为该版本费用方案的合法阶段（first 或 tail） */
        public boolean validStage(String stage) {
            return stage != null && stages().stream().anyMatch(item -> item.name().equals(stage.toUpperCase(Locale.ROOT)));
        }

        /** 期款对应金额（分） */
        public long stageCents(String stage) {
            if (!validStage(stage)) throw new IllegalArgumentException("非法设计费用期次");
            if (mode == DesignFeeSettlementPort.Mode.AREA_FULL) return totalCents;
            return "first".equalsIgnoreCase(stage) ? firstCents : totalCents - firstCents;
        }
    }

}
