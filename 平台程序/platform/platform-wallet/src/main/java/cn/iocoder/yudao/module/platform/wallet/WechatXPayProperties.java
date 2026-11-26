package cn.iocoder.yudao.module.platform.wallet;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 从 commerce xpay 配置迁移；B 单独命名，默认关闭，敏感字段不生成 toString。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "platform.wallet.payment")
public class WechatXPayProperties {
    private boolean enabled;
    /** 独立控制新充值与签名。关闭后继续处理原订单通知、对账和退款。 */
    private boolean onlineRechargeEnabled;
    private String appid;
    private String appSecret;
    private String offerId;
    private String appKey;
    private String pushToken;
    private int environment;
    /** 只有取得官方单位证据后才可显式设置 FEN；缺失单位不得依据订单声明入账。 */
    private String paidFeeUnit = "UNVERIFIED";
    private long notificationTenantId;
    private int timeoutMillis = 10000;
}
