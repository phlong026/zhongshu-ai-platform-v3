package cn.iocoder.yudao.module.commerce.controller.notify;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.servlet.ServletUtils;
import cn.iocoder.yudao.framework.ratelimiter.core.annotation.RateLimiter;
import cn.iocoder.yudao.framework.ratelimiter.core.keyresolver.impl.ClientIpRateLimiterKeyResolver;
import cn.iocoder.yudao.module.commerce.payment.RechargePaymentService;
import cn.iocoder.yudao.module.commerce.payment.xpay.VirtualPaymentAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 微信小程序虚拟支付消息推送入口（xpay_*，provider=virtual）。
 *
 * <p><b>配置来源</b>：MP 后台「开发设置 → 消息推送」——URL 指向本端点
 * （{@code GET/POST /design/v1/payments/xpay/push}），Token 即
 * {@code zhongshu.commerce.payment.virtual.push-token}；数据格式 JSON/XML 均支持
 * （应答镜像请求格式），加密方式须选<b>明文模式</b>（本工程按 Token 验签，不做 AES 解密）。
 *
 * <p><b>事件路由</b>：{@code xpay_goods_deliver_notify} →
 * {@link RechargePaymentService#handleNotification}（Inbox 幂等到账）；
 * {@code xpay_refund_notify} → {@link RechargePaymentService#handleRefundNotification}
 * （冲正/释放）。其余事件（投诉/订阅问询等）记录后直接 ACK，避免微信无限重试。
 *
 * <p><b>响应契约</b>：成功应答 {@code <xml><ErrCode>0</ErrCode><ErrMsg><![CDATA[success]]></ErrMsg></xml>}
 * （XML 推送）或 {@code {"ErrCode":0,"ErrMsg":"success"}}（JSON 推送）；失败回 HTTP 5xx，
 * 微信对发货推送最多重试 15 次——重复推送由 Inbox (channel,event_id) 幂等吸收。
 *
 * <p><b>安全边界</b>：{@code @PermitAll} 放行 Spring Security 底座；真实性由
 * {@link VirtualPaymentAdapter#verifyNotification} 的 Token 签名校验
 * （signature = SHA-1(字典序(token, timestamp, nonce))，消息体不参与签名），验签失败即拒；
 * 因明文消息体不受签名保护，到账/退款事实一律由适配器经 B 端查单确认后才推进。
 * 推送原文（含 openid）绝不写入日志。
 *
 * @see VirtualPaymentAdapter
 * @see RechargePaymentService#handleNotification(Map, byte[])
 */
@Slf4j
@Tag(name = "虚拟支付消息推送入口（xpay_*）")
@RestController
@RequestMapping("/design/v1/payments/xpay")
@PermitAll
public class XpayNotifyController {

    /** 请求体大小上限（与微信支付通知入口一致的 64KB；xpay 推送远小于此） */
    static final int MAX_BODY_SIZE = 64 * 1024;

    private static final String XML_SUCCESS =
            "<xml><ErrCode>0</ErrCode><ErrMsg><![CDATA[success]]></ErrMsg></xml>";
    private static final String XML_FAIL =
            "<xml><ErrCode>1</ErrCode><ErrMsg><![CDATA[process failed]]></ErrMsg></xml>";
    private static final String JSON_SUCCESS = "{\"ErrCode\":0,\"ErrMsg\":\"success\"}";
    private static final String JSON_FAIL = "{\"ErrCode\":1,\"ErrMsg\":\"process failed\"}";

    @Resource
    private RechargePaymentService rechargePaymentService;

    /**
     * 推送验签 Token。经 @Value 注入而非注入适配器 Bean：本控制器在 stub/real profile 下也存在，
     * 而 VirtualPaymentAdapter 仅 provider=virtual 装配；Token 为空时验签恒失败（未配置即拒绝）。
     */
    @org.springframework.beans.factory.annotation.Value("${zhongshu.commerce.payment.virtual.push-token:}")
    private String pushToken;

    /**
     * MP 后台保存消息推送配置时的 URL 有效性验证（GET + echostr）：
     * 验签通过则原样返回 echostr（纯文本），微信才允许保存配置。
     */
    @GetMapping(value = "/push", produces = MediaType.TEXT_PLAIN_VALUE)
    @Operation(summary = "消息推送 URL 验证（echostr 回显）")
    public ResponseEntity<String> verifyUrl(@RequestParam(value = "signature", required = false) String signature,
                                            @RequestParam(value = "timestamp", required = false) String timestamp,
                                            @RequestParam(value = "nonce", required = false) String nonce,
                                            @RequestParam(value = "echostr", required = false) String echostr) {
        if (echostr == null || echostr.isBlank()
                || !VirtualPaymentAdapter.verifyPushSignature(pushToken, signature, timestamp, nonce)) {
            log.warn("[xpay推送] URL 验证失败（签名不匹配或参数缺失）");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("verify failed");
        }
        return ResponseEntity.ok(echostr);
    }

    /**
     * xpay_* 事件推送入口。发货/退款分流到既有 Inbox 幂等链路；处理失败回 5xx 触发微信重试
     * （Inbox 已留痕可重投）。
     */
    @PostMapping(value = "/push")
    @RateLimiter(time = 1, count = 1000, keyResolver = ClientIpRateLimiterKeyResolver.class)
    @Operation(summary = "虚拟支付发货/退款事件推送（xpay_goods_deliver_notify / xpay_refund_notify）")
    public ResponseEntity<String> handlePush(HttpServletRequest request) {
        long contentLength = request.getContentLengthLong();
        if (contentLength > MAX_BODY_SIZE) {
            log.warn("[xpay推送] 请求体超限（Content-Length）: size={}B", contentLength);
            return fail(request, HttpStatus.PAYLOAD_TOO_LARGE);
        }
        byte[] body = ServletUtils.getBodyBytes(request);
        if (body == null || body.length == 0 || body.length > MAX_BODY_SIZE) {
            log.warn("[xpay推送] 请求体为空或超限: contentType={}", request.getContentType());
            return fail(request, HttpStatus.BAD_REQUEST);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("signature", request.getParameter("signature"));
        headers.put("timestamp", request.getParameter("timestamp"));
        headers.put("nonce", request.getParameter("nonce"));

        boolean xml = isXmlBody(body);
        try {
            String event = readEventType(body);
            if ("xpay_refund_notify".equals(event)) {
                String result = rechargePaymentService.handleRefundNotification(headers, body);
                log.info("[xpay推送] 退款事件处理完成: result={}, bodySize={}B", result, body.length);
            } else if ("xpay_goods_deliver_notify".equals(event)) {
                String result = rechargePaymentService.handleNotification(headers, body);
                log.info("[xpay推送] 发货事件处理完成: result={}, bodySize={}B", result, body.length);
            } else {
                // 其余事件（投诉/订阅问询等）本期不消费：记录后直接 ACK，避免微信按失败无限重试
                log.info("[xpay推送] 未消费事件直接 ACK: event={}, bodySize={}B", event, body.length);
            }
            return ResponseEntity.ok().contentType(xml ? MediaType.APPLICATION_XML : MediaType.APPLICATION_JSON)
                    .body(xml ? XML_SUCCESS : JSON_SUCCESS);
        } catch (ServiceException e) {
            // 验签失败 / 订单状态冲突：不 ACK，让微信按退避重试（发货推送最多 15 次）
            log.warn("[xpay推送] 业务异常: code={}, msg={}, bodySize={}B", e.getCode(), e.getMessage(), body.length);
            return fail(request, HttpStatus.BAD_REQUEST);
        } catch (RuntimeException e) {
            log.error("[xpay推送] 系统异常: bodySize={}B, type={}", body.length, e.getClass().getSimpleName());
            return fail(request, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private ResponseEntity<String> fail(HttpServletRequest request, HttpStatus status) {
        boolean xml = false;
        try {
            byte[] body = ServletUtils.getBodyBytes(request);
            xml = body != null && isXmlBody(body);
        } catch (RuntimeException ignored) {
            // 读不到 body 时按 JSON 失败应答
        }
        return ResponseEntity.status(status)
                .contentType(xml ? MediaType.APPLICATION_XML : MediaType.APPLICATION_JSON)
                .body(xml ? XML_FAIL : JSON_FAIL);
    }

    static boolean isXmlBody(byte[] body) {
        String trimmed = new String(body, StandardCharsets.UTF_8).trim();
        return trimmed.startsWith("<");
    }

    /** 读取 Event 字段（JSON/XML 皆适配；缺失时抛出，走 5xx 重试路径） */
    static String readEventType(byte[] body) {
        JsonNode node = VirtualPaymentAdapter.parsePushBody(body);
        String event = node.path("Event").asText("");
        if (event.isBlank()) {
            throw new ServiceException(400, "xpay 推送缺少 Event 字段");
        }
        return event;
    }
}
