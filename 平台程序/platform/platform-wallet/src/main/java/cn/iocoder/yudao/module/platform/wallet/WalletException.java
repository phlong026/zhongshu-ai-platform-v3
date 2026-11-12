package cn.iocoder.yudao.module.platform.wallet;

import cn.iocoder.yudao.framework.common.exception.ServiceException;

/** 统一创建现有框架可识别的业务异常，不在错误中包含其他组织或支付凭据。 */
final class WalletException {

    private WalletException() {
    }

    static ServiceException unavailable() {
        return new ServiceException(1_120_000_001, "当前组织钱包不可用");
    }

    static ServiceException conflict() {
        return new ServiceException(1_120_000_002, "幂等请求与原请求不一致");
    }

    static ServiceException insufficient() {
        return new ServiceException(1_120_000_003, "钱包可用或预留点数不足");
    }

    static ServiceException invalidState() {
        return new ServiceException(1_120_000_004, "扣点业务单状态不允许此操作");
    }

    static ServiceException invalidRequest() {
        return new ServiceException(1_120_000_005, "钱包请求参数无效");
    }

    static ServiceException planUnavailable() {
        return new ServiceException(1_120_000_006, "当前充值套餐不可用，请重新选择");
    }

    static ServiceException balanceLimit() {
        return new ServiceException(1_120_000_007, "钱包总余额超出上限");
    }
}
