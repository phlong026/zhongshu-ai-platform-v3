package cn.iocoder.yudao.module.commerce.payment.xpay;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 微信小程序虚拟支付配置（provider=virtual）。
 *
 * <p>来源：MP 后台 → 支付与交易 → 虚拟支付 → 基本配置（OfferID / 现网 AppKey），
 * 以及「消息推送」配置的 Token（发货推送 xpay_* 验签用）。机密 app-key/push-token 只经
 * 环境变量注入，绝不写入仓库与日志；启动守卫 {@code RealServiceWiringPolicy} 在
 * provider=virtual 时强制非空。
 *
 * <p>AppID/AppSecret 复用 identity 模块已登记的 {@code zhongshu.identity.wechat-appid}/
 * {@code zhongshu.identity.wechat-appsecret}（B 端查单/退款接口的 access_token 与 C 端
 * code2session 均依赖同一小程序身份），本类不重复配置。
 */
@Data
@ConfigurationProperties(prefix = "zhongshu.commerce.payment.virtual")
public class WechatXPayProperties {

    /**
     * 虚拟支付 OfferID（基本配置页）。消费点：signData.offerId 与 {@link VirtualPaymentAdapter#merchantId()}。
     */
    private String offerId;

    /**
     * 现网 AppKey（基本配置页）。pay_sig = HMAC-SHA256(AppKey, uri + '&' + post_body)。
     * 沙箱 AppKey 严禁配置到生产；本工程 provider=virtual 即现网链路，env 固定 0。
     */
    private String appKey;

    /**
     * 支付环境：0=现网（固定，虚拟支付文档约定）；沙箱（1）仅限开发者工具调试，本工程不使用。
     */
    private Integer env = 0;

    /**
     * MP 后台「消息推送」配置的 Token。xpay_* 发货/退款推送验签：
     * signature = SHA-1(字典序(token, timestamp, nonce, 消息体))。
     */
    private String pushToken;
}
