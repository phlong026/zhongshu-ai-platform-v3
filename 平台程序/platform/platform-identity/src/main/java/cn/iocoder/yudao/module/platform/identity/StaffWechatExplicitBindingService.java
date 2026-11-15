package cn.iocoder.yudao.module.platform.identity;

/** 把当前已认证后台用户与微信绑定，不通过手机号或别名创建、猜测用户。 */
public interface StaffWechatExplicitBindingService {
    void bind(String appid, String loginCode, Long currentUserId);
}
