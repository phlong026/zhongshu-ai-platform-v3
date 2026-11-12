package cn.iocoder.yudao.module.platform.wallet;

import cn.binarywang.wx.miniapp.api.WxMaService;
import cn.binarywang.wx.miniapp.api.WxMaXPayService;
import cn.binarywang.wx.miniapp.api.impl.WxMaServiceImpl;
import cn.binarywang.wx.miniapp.api.impl.WxMaXPayServiceImpl;
import cn.binarywang.wx.miniapp.bean.xpay.WxMaXPayQueryOrderRequest;
import cn.binarywang.wx.miniapp.bean.xpay.WxMaXPaySigParams;
import cn.binarywang.wx.miniapp.config.impl.WxMaDefaultConfigImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.common.error.WxErrorException;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** commerce xpay 适配器的严格移植；通知仅为查单提示，不补造渠道金额或退款关联。 */
@Component
@ConditionalOnProperty(name = "platform.wallet.payment.enabled", havingValue = "true")
@ConditionalOnProperty(name = "platform.legacy-design-bridge.enabled", havingValue = "false")
@Slf4j
public class VirtualPaymentAdapter implements PaymentPort {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final WechatXPayProperties properties;
    private final WxMaService wechat;
    private final WxMaXPayService xpay;
    private final CloseableHttpClient ownedClient;

    @org.springframework.beans.factory.annotation.Autowired
    @SuppressWarnings("unchecked") // SDK BaseWxMaServiceImpl的泛型execute返回值声明与接口不一致。
    public VirtualPaymentAdapter(WechatXPayProperties properties) {
        this.properties = properties;
        if (properties.getTimeoutMillis() < 1 || properties.getTimeoutMillis() > 60000) throw new IllegalArgumentException("Invalid xpay timeout");
        var config = RequestConfig.custom().setConnectTimeout(properties.getTimeoutMillis())
                .setConnectionRequestTimeout(properties.getTimeoutMillis()).setSocketTimeout(properties.getTimeoutMillis()).build();
        ownedClient = HttpClients.custom().setDefaultRequestConfig(config).disableAutomaticRetries().build();
        // SDK 默认 builder 是全局单例。此处只隔离本钱包客户端，不改其他模块的超时或重试策略。
        var service = new WxMaServiceImpl() {
            @Override public void initHttp() { }
            @Override public CloseableHttpClient getRequestHttpClient() { return ownedClient; }
        };
        var wxConfig = new WxMaDefaultConfigImpl();
        wxConfig.setAppid(properties.getAppid());
        wxConfig.setSecret(properties.getAppSecret());
        wxConfig.setMaxRetryTimes(0);
        service.setWxMaConfig(wxConfig);
        service.setMaxRetryTimes(0);
        this.wechat = service;
        this.xpay = new WxMaXPayServiceImpl(service);
    }

    VirtualPaymentAdapter(WechatXPayProperties properties, WxMaService wechat, WxMaXPayService xpay) {
        this.properties = properties;
        this.wechat = wechat;
        this.xpay = xpay;
        this.ownedClient = null;
    }

    @Override
    public PrepayResult createPrepay(OrderIdentity order, String productId, String wxLoginCode) {
        requireContext(order);
        PointAccountService.requireText(productId, 128);
        PointAccountService.requireText(wxLoginCode, 512);
        PointAccountService.requireText(properties.getAppKey(), 512);
        try {
            var session = wechat.getUserService().getSessionInfo(wxLoginCode);
            if (session == null || !order.openid().equals(session.getOpenid())
                    || session.getSessionKey() == null || session.getSessionKey().isBlank()) throw new IllegalStateException("Payment payer session mismatch");
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("offerId", order.offerId());
            data.put("buyQuantity", 1);
            data.put("env", order.environment());
            data.put("currencyType", "CNY");
            data.put("productId", productId);
            data.put("goodsPrice", order.amountCents());
            data.put("outTradeNo", order.orderNo());
            data.put("attach", order.orderNo());
            String signData = JSON.writeValueAsString(data);
            var sig = signature();
            sig.setSessionKey(session.getSessionKey());
            return new PrepayResult(Map.of("signData", signData, "mode", "short_series_goods",
                    "paySig", sig.calcPaySig("requestVirtualPayment", signData), "signature", sig.calcSig(signData)));
        } catch (Exception failure) {
            throw sanitized("prepay", order.orderId(), failure);
        }
    }

    @Override
    public ChannelQueryResult queryOrder(OrderIdentity order) {
        requireContext(order);
        try {
            var result = xpay.queryOrder(WxMaXPayQueryOrderRequest.builder().openid(order.openid())
                    .env(order.environment()).orderId(order.orderNo()).build(), signature());
            var info = result == null ? null : result.getOrder();
            if (info == null) return new ChannelQueryResult("UNKNOWN", null, order.channel(), order.appid(), order.offerId(),
                    order.environment(), order.openid(), null, null, "UNVERIFIED", null, null);
            boolean paid = (Integer.valueOf(0).equals(info.getOrderType()) || Integer.valueOf(7).equals(info.getOrderType()))
                    && info.getStatus() != null && info.getStatus() >= 2 && info.getStatus() <= 4;
            // paid_fee 单位未取得官方证据。只有明确门禁设为FEN后才允许提供可被validator接受的金额。
            boolean verifiedUnit = "FEN".equals(properties.getPaidFeeUnit());
            return new ChannelQueryResult(paid ? "SUCCEEDED" : "UNKNOWN", info.getOrderId(), order.channel(), order.appid(), order.offerId(),
                    info.getEnvType() == null ? -1 : Math.toIntExact(info.getEnvType()), order.openid(), info.getWxpayOrderId(),
                    verifiedUnit ? info.getPaidFee() : null, verifiedUnit ? "FEN" : "UNVERIFIED",
                    info.getPaidTime() != null && info.getPaidTime() > 0 ? Instant.ofEpochSecond(info.getPaidTime()) : null, info.getOrderType());
        } catch (Exception failure) {
            throw sanitized("queryOrder", order.orderId(), failure);
        }
    }

    /** 未核实req_from/biz_meta以及退款逐笔原单关联前，在冻结积分之前拒绝真实主动退款。 */
    IllegalStateException refundSubmissionUnavailable() {
        return new IllegalStateException("Xpay developer refund remains disabled pending verified request metadata and per-refund association");
    }

    @Override
    public ChannelRefundResult requestRefund(RefundRequest request) {
        requireContext(request.order());
        throw refundSubmissionUnavailable();
    }

    @Override
    public ChannelRefundResult queryRefund(RefundRequest request) {
        requireContext(request.order());
        if (request.channelRefundId() == null || request.channelRefundId().isBlank()) return unknownRefund(request);
        try {
            // 必须使用持久的退款渠道单号，不退回查询原支付单。SDK未给出可核验原单关联，不能确认终态。
            xpay.queryOrder(WxMaXPayQueryOrderRequest.builder().openid(request.order().openid())
                    .env(request.order().environment()).orderId(request.channelRefundId()).build(), signature());
            log.warn("[walletXpay][order={}] 退款查单缺少已证实的原单关联合同，保持UNKNOWN", request.order().orderId());
            return unknownRefund(request);
        } catch (Exception failure) {
            throw sanitized("queryRefund", request.order().orderId(), failure);
        }
    }

    private static ChannelRefundResult unknownRefund(RefundRequest request) {
        return new ChannelRefundResult("UNKNOWN", request.order().orderNo(), request.refundNo(), request.channelRefundId(), null, "UNVERIFIED", null, null);
    }

    @Override
    public boolean verifyNotification(Map<String, String> headers, byte[] body) {
        return body != null && body.length > 0 && body.length <= 65536 && verifyPushSignature(properties.getPushToken(), headers);
    }

    /** GET地址验证与POST通知共用明文推送验签；签名不证明通知体内的支付事实。 */
    static boolean verifyPushSignature(String token, Map<String, String> headers) {
        if (headers == null || headers.containsKey("encrypt_type") || headers.containsKey("msg_signature")) return false;
        String signature = headers.get("signature");
        String timestamp = headers.get("timestamp");
        String nonce = headers.get("nonce");
        if (blank(token) || blank(signature) || blank(timestamp) || blank(nonce) || signature.length() != 40
                || timestamp.length() > 32 || nonce.length() > 128) return false;
        try {
            String[] values = {token, timestamp, nonce};
            Arrays.sort(values);
            String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                    .digest(String.join("", values).getBytes(StandardCharsets.UTF_8)));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), signature.getBytes(StandardCharsets.US_ASCII));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-1 is unavailable");
        }
    }

    @Override
    public NotificationHint parseNotification(Map<String, String> headers, byte[] body) {
        if (body == null || body.length == 0 || body.length > 65536) throw new IllegalArgumentException("Invalid notification size");
        try {
            var node = parseBody(body);
            if (node.has("Encrypt")) throw new IllegalArgumentException("Encrypted notification is not enabled");
            String event = required(node, "Event");
            if ("xpay_refund_notify".equals(event)) {
                String no = required(node, "MchRefundId");
                return new NotificationHint("refund:" + no, required(node, "MchOrderId"), no, event);
            }
            if ("xpay_goods_deliver_notify".equals(event)) {
                String no = required(node, "OutTradeNo");
                return new NotificationHint("deliver:" + no, no, null, event);
            }
            throw new IllegalArgumentException("Unsupported notification event");
        } catch (Exception failure) {
            // XML解析器/Jackson异常可能含原文；不挂cause、不回显payload。
            log.warn("[walletXpay][operation=parseNotification failureType={}] 通知解析失败", failure.getClass().getSimpleName());
            throw new IllegalArgumentException("Invalid xpay notification");
        }
    }

    private static JsonNode parseBody(byte[] body) throws Exception {
        String text = new String(body, StandardCharsets.UTF_8).trim();
        if (!text.startsWith("<")) return JSON.readTree(text);
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
            @Override public void fatalError(org.xml.sax.SAXParseException exception) throws org.xml.sax.SAXException { throw exception; }
        });
        var root = builder.parse(new java.io.ByteArrayInputStream(body)).getDocumentElement();
        var node = JSON.createObjectNode();
        var children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof org.w3c.dom.Element child) {
                if (node.has(child.getTagName())) throw new IllegalArgumentException("Duplicate notification field");
                node.put(child.getTagName(), child.getTextContent());
            }
        }
        return node;
    }

    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isBlank() || value.length() > 128) throw new IllegalArgumentException("Missing notification field");
        return value;
    }

    private void requireContext(OrderIdentity order) {
        RefundPaymentService.requireNoTransaction();
        if (!properties.isEnabled() || !"XPAY".equals(order.channel()) || !java.util.Objects.equals(properties.getAppid(), order.appid())
                || !java.util.Objects.equals(properties.getOfferId(), order.offerId()) || properties.getEnvironment() != order.environment()
                || blank(order.openid())) throw new IllegalStateException("Fixed order channel configuration mismatch");
    }

    private WxMaXPaySigParams signature() {
        var signature = new WxMaXPaySigParams();
        signature.setAppKey(properties.getAppKey());
        return signature;
    }

    private IllegalStateException sanitized(String operation, long orderId, Exception failure) {
        Integer code = failure instanceof WxErrorException wx && wx.getError() != null ? wx.getError().getErrorCode() : null;
        log.warn("[walletXpay][operation={} order={} errorCode={} failureType={}] 渠道调用失败", operation, orderId, code, failure.getClass().getSimpleName());
        return new IllegalStateException("Xpay operation failed; recover by querying persisted order");
    }

    private static boolean blank(String text) { return text == null || text.isBlank(); }

    @PreDestroy
    public void close() throws java.io.IOException {
        if (ownedClient != null) {
            try { ownedClient.close(); }
            catch (java.io.IOException failure) {
                log.warn("[walletXpay][operation=close] HTTP客户端关闭失败");
                throw failure;
            }
        }
    }
}
