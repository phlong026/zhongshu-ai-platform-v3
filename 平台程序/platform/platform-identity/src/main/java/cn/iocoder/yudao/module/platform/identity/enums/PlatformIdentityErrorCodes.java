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
    ErrorCode SMS_SEND_TOO_FREQUENT = new ErrorCode(1_104_000_005, "发送太频繁，请稍后再试");
    ErrorCode SMS_DAILY_LIMIT = new ErrorCode(1_104_000_006, "今日验证码发送次数已达上限，请明日再试");
    ErrorCode SMS_CAPTCHA_WRONG = new ErrorCode(1_104_000_007, "验证码不正确，请重新输入");
    ErrorCode SMS_CAPTCHA_EXPIRED = new ErrorCode(1_104_000_008, "验证码已失效，请重新获取");

}
