package cn.iocoder.yudao.module.platform.identity.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 * 平台员工登录错误码，段 1-104-000-xxx。消息面向员工，不含任何凭据或微信原始报错。
 */
public interface PlatformIdentityErrorCodes {

    ErrorCode WECHAT_APP_NOT_ALLOWED = new ErrorCode(1_104_000_000, "小程序未接入员工登录");
    ErrorCode WECHAT_CODE_INVALID = new ErrorCode(1_104_000_001, "登录凭证已失效，请重新进入小程序");
    ErrorCode WECHAT_SERVICE_UNAVAILABLE = new ErrorCode(1_104_000_002, "微信服务暂不可用，请稍后重试");
    ErrorCode STAFF_NOT_ENROLLED = new ErrorCode(1_104_000_003, "未找到该手机号对应的在岗员工，请联系门店负责人开通");
    ErrorCode STAFF_PHONE_AMBIGUOUS = new ErrorCode(1_104_000_004, "该手机号对应多名员工，请联系管理员核对");

}
