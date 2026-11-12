package cn.iocoder.yudao.module.platform.wallet;

import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import cn.iocoder.yudao.framework.tenant.core.aop.TenantIgnore;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/** B独立钱包明文推送入口；ACK仅表示inbox事务已提交，业务事实由恢复任务查单。 */
@RestController
@ConditionalOnProperty(name = "platform.legacy-design-bridge.enabled", havingValue = "false")
@ConditionalOnProperty(name = "platform.wallet.payment.enabled", havingValue = "true")
@Slf4j
public class WalletXpayNotifyController {
    static final String PATH = "/app-api/platform/wallet/payments/xpay/push";
    private final WalletRecoveryJob recovery;
    private final WechatXPayProperties properties;

    public WalletXpayNotifyController(WalletRecoveryJob recovery, WechatXPayProperties properties) {
        this.recovery = recovery;
        this.properties = properties;
    }

    @PermitAll
    @TenantIgnore
    @ApiAccessLog(enable = false)
    @GetMapping(PATH)
    public ResponseEntity<String> verify(HttpServletRequest request) {
        var notification = WalletXpayNotifyConfiguration.notification(request);
        String echo = notification.parameters().get("echostr");
        boolean valid = echo != null && !echo.isBlank()
                && VirtualPaymentAdapter.verifyPushSignature(properties.getPushToken(), notification.parameters());
        if (!valid) log.warn("[walletXpayNotify][operation=verify] 推送地址验证失败");
        return ResponseEntity.status(valid ? 200 : 403).contentType(MediaType.TEXT_PLAIN)
                .body(valid ? echo : "verify failed");
    }

    @PermitAll
    @TenantIgnore
    @ApiAccessLog(enable = false)
    @PostMapping(PATH)
    public ResponseEntity<String> notify(HttpServletRequest request) {
        var notification = WalletXpayNotifyConfiguration.notification(request);
        boolean xml = new String(notification.body(), StandardCharsets.UTF_8).trim().startsWith("<");
        int status = 200;
        try {
            // acceptNotification在事务提交成功后才返回；此处没有查单、入账或退款外呼。
            recovery.acceptNotification(notification.parameters(), notification.body());
        } catch (cn.iocoder.yudao.framework.common.exception.ServiceException | IllegalArgumentException failure) {
            status = 400;
            log.warn("[walletXpayNotify][operation=accept failureType={}] 通知未接受", failure.getClass().getSimpleName());
        } catch (RuntimeException failure) {
            status = 503;
            log.error("[walletXpayNotify][operation=persist failureType={}] 通知未持久化，等待渠道重试", failure.getClass().getSimpleName());
        }
        return ResponseEntity.status(status).contentType(xml ? MediaType.APPLICATION_XML : MediaType.APPLICATION_JSON)
                .body(ack(xml, status == 200));
    }

    static String ack(boolean xml, boolean success) {
        int code = success ? 0 : 1;
        String message = success ? "success" : "process failed";
        return xml ? "<xml><ErrCode>" + code + "</ErrCode><ErrMsg><![CDATA[" + message + "]]></ErrMsg></xml>"
                : "{\"ErrCode\":" + code + ",\"ErrMsg\":\"" + message + "\"}";
    }
}
