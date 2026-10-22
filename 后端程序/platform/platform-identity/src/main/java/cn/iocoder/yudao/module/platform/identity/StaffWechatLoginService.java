package cn.iocoder.yudao.module.platform.identity;

/**
 * 小程序员工微信登录。
 * <ol>
 *   <li>微信已绑定员工，且员工账号启用、手机号未变、仍有有效任职：直接签发令牌；</li>
 *   <li>未绑定（或绑定因改号、停用而失效）：需要微信手机号；手机号唯一对应一名启用且在岗的员工时绑定并签发令牌；</li>
 *   <li>同一小程序下一名员工只绑定一个微信：换微信绑定时旧绑定作废。</li>
 * </ol>
 */
public interface StaffWechatLoginService {

    /**
     * @param phoneCode 微信手机号授权 code；首次登录前可不传，此时返回 needPhone
     */
    WechatLoginResult login(String appid, String loginCode, String phoneCode);

}
