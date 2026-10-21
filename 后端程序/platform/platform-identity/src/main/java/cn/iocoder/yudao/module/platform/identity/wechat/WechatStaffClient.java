package cn.iocoder.yudao.module.platform.identity.wechat;

/**
 * 员工登录用到的两个微信接口。实现不得在日志或异常中带出 secret、session_key、access_token、code 或手机号。
 */
public interface WechatStaffClient {

    /** 用一次性登录 code 换 openid（code2session）；失败直接抛业务异常，不重试 */
    String openid(String appid, String loginCode);

    /** 用一次性手机号 code 换中国大陆手机号（不含区号）；非大陆号码按未开通处理 */
    String phoneNumber(String appid, String phoneCode);

}
