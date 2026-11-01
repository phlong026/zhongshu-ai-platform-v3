package cn.iocoder.yudao.module.franchise.org.wallet;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 * 钱包会话桥接错误码（09 文档 §三，契约冻结；数值为全局注册表 §7.6 分配，本地仅声明常量）。
 * 1_112_000_004（服务账号停用）复用 {@code FranchiseDesignErrorCodes.SERVICE_ACCOUNT_INACTIVE}。
 */
public interface WalletErrorCodes {

    /** 微信登录凭证缺失/无效/已用——客户端重新 wx.login 后可恢复 */
    ErrorCode WECHAT_CODE_INVALID = new ErrorCode(1_104_000_001, "微信登录凭证已失效，请重新进入小程序");

    /** 微信侧不可用或服务端配置异常 */
    ErrorCode WECHAT_SERVICE_UNAVAILABLE = new ErrorCode(1_104_000_002, "微信服务暂不可用，请稍后重试");

}
