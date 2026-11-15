package cn.iocoder.yudao.module.franchise.api;

import cn.iocoder.yudao.module.platform.org.PostSnapshot;

/**
 * 设计委托首尾款进度，供财务域（M3）在核实到账的同一事务内调用；执行人为财务人员的岗位，
 * 命令事件按该岗位留痕。
 * 就绪口径来自小程序 transfers-v2.js 的 condition：门店实付 ≥ 应付，且总部实收 ≥ 应付；
 * 历史审计兼容接口；不再作为完成、下发或待办的资金授权依据。
 * 新调用方应使用 DesignFeeSettlementPort 的实时净额投影和事务锁门禁。
 */
public interface DesignFeeProgressApi {

    /** 首款：门店已付足且总部已核实到账 */
    void markFirstReady(long commissionId, int submissionVersion, PostSnapshot actor);

    /** 尾款：门店已付足 */
    void markTailPaid(long commissionId, int submissionVersion, PostSnapshot actor);

    /** 尾款：门店已付足且总部已核实到账 */
    void markTailReady(long commissionId, int submissionVersion, PostSnapshot actor);

}
