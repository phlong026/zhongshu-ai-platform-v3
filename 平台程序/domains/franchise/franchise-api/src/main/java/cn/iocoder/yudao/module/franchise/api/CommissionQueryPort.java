package cn.iocoder.yudao.module.franchise.api;

/**
 * 设计委托费用查询（B1 财务域登记 design 收款时的服务端门禁数据源）。
 * 只读视图，跨域经本 Port 访问，财务域不直接依赖 commission 的表结构。
 */
public interface CommissionQueryPort {

    /** 按委托编号取当前提交版本的费用视图；不存在返回 null */
    CommissionFeeView findCurrentFee(long commissionId);

    /** 费用视图：门店 companyId 持有委托，总部 designOrgId 是收款记账归属 */
    record CommissionFeeView(long commissionId, long companyId, long designOrgId,
                             int submissionVersion, String status,
                             long totalCents, long firstCents) {

        /** 委托费用是否已批准（免审批直进 DESIGNING 或走完 fee-approve 均算） */
        public boolean feeApproved() {
            return !"FEE_PENDING".equals(status) && !"FEE_RETURNED".equals(status);
        }

        /** 期款是否为该版本费用方案的合法阶段（first 或 tail） */
        public boolean validStage(String stage) {
            return "first".equals(stage) || "tail".equals(stage);
        }

        /** 期款对应金额（分） */
        public long stageCents(String stage) {
            return "first".equals(stage) ? firstCents : totalCents - firstCents;
        }
    }

}
