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
    // M02-T04 批次#39：real 通道发送失败（腾讯云非 Ok——Message 摘要参数化）
    ErrorCode SMS_SEND_FAILED = new ErrorCode(1_104_000_009, "短信发送失败：{}");
    // M02-T04 批次#39：real 通道 SDK 异常（网络/凭据错误——不回显凭据，提示重试）
    ErrorCode SMS_SERVICE_UNAVAILABLE = new ErrorCode(1_104_000_010, "短信服务暂不可用，请稍后重试");
    ErrorCode ACCOUNT_CREDENTIAL_INVALID = new ErrorCode(1_104_000_011, "账号或密码不正确");
    ErrorCode ACCOUNT_LOGIN_LOCKED = new ErrorCode(1_104_000_012, "密码连续错误次数过多，请15分钟后再试");
    ErrorCode ACCOUNT_NEW_PASSWORD_INVALID = new ErrorCode(1_104_000_013, "新密码需为10至64位且同时包含英文字母和数字");
    ErrorCode WECHAT_ALREADY_BOUND_OTHER = new ErrorCode(1_104_000_014, "该微信已绑定其他账号，请先联系管理员处理");
    ErrorCode WECHAT_BINDING_CONFLICT = new ErrorCode(1_104_000_015, "微信绑定状态已变化，请重试");

}
