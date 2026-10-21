package cn.iocoder.yudao.module.commerce.payment.xpay;

import cn.binarywang.wx.miniapp.api.WxMaService;
import cn.binarywang.wx.miniapp.api.impl.WxMaServiceImpl;
import cn.binarywang.wx.miniapp.api.impl.WxMaXPayServiceImpl;
import cn.binarywang.wx.miniapp.api.WxMaXPayService;
import cn.binarywang.wx.miniapp.bean.xpay.WxMaXPayQueryOrderRequest;
import cn.binarywang.wx.miniapp.bean.xpay.WxMaXPayQueryOrderResponse;
import cn.binarywang.wx.miniapp.bean.xpay.WxMaXPayRefundOrderRequest;
import cn.binarywang.wx.miniapp.bean.xpay.WxMaXPaySigParams;
import cn.binarywang.wx.miniapp.config.impl.WxMaDefaultConfigImpl;
import cn.iocoder.yudao.module.commerce.payment.PaymentPort;
import cn.iocoder.yudao.module.infra.zhongshu.api.WechatPaySessionPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.common.error.WxErrorException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 微信小程序虚拟支付适配器（provider=virtual）——「设计点充值」合规支付通道。
 *
 * <p>背景：设计点属虚拟商品，微信审核要求购买/支付必须接入官方「小程序虚拟支付」
 * （{@code wx.requestVirtualPayment}，OfferID + AppKey 签名），与商户号微信支付
 * （{@link cn.iocoder.yudao.module.commerce.payment.wechat.WechatPaymentAdapter}，provider=real）
 * 是两套独立链路。本类复用 {@link PaymentPort} 全部既有合同（Inbox 幂等、独立事务、
 * 查单兜底、整单退款），业务侧零改动。
 *
 * <p>通道语义映射（T-XPAY）：
 * <ul>
 *   <li><b>预下单</b>：虚拟支付无服务端 prepay 下单——{@code createPrepay} 纯本地构造 signData，
 *       paySig = HMAC-SHA256(AppKey, "requestVirtualPayment&" + signData)，signature =
 *       HMAC-SHA256(session_key, signData)；session_key 由客户端支付前临时 wx.login code 经
 *       {@link WechatPaySessionPort} 换取，用后即弃不落库。返回的 callParams
 *       （signData/mode/paySig/signature）原样透传 {@code wx.requestVirtualPayment}。</li>
 *   <li><b>发货</b>：收到 {@code xpay_goods_deliver_notify} 推送 → 既有 Inbox 幂等到账链路；
 *       前端 success 回调不作发货依据（分派表红线 3）。明文模式验签只覆盖 token/timestamp/nonce，
 *       <b>消息体不受签名保护</b>，因此推送只作「触发信号」：OpenId/ProductId 与订单交叉核对后，
 *       支付状态、实付金额、平台单号一律以 B 端 {@code query_order} 查单结果为准。</li>
 *   <li><b>查单兜底</b>：B 端 {@code /xpay/query_order}（pay_sig 签名，需 payer openid），
 *       状态映射见 {@link #mapOrderStatus}。</li>
 *   <li><b>退款</b>：B 端 {@code /xpay/refund_order} 受理（无同步终态，返回 PROCESSING）；
 *       {@code xpay_refund_notify} 推送同样只作触发信号，终态以查单（refund 状态位）为准，
 *       查单未到终态时按 PROCESSING 保持预留（红线 5）。
 *       iOS 订单不可服务端退款（用户经 App Store 发起，平台推 {@code xpay_refund_notify}）。</li>
 * </ul>
 *
 * <p>安全边界：AppKey/pushToken 只在服务端；日志/异常只含微信公开错误码，绝不携带
 * AppKey/session_key/推送原文；{@code signData} 为服务端构造的确定性 JSON 字符串，
 * 客户端必须原样透传（先签名后下发，禁止客户端重排/重序列化）。
 *
 * @see WechatXPayProperties
 * @see cn.iocoder.yudao.module.commerce.controller.notify.XpayNotifyController
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "zhongshu.commerce.payment", name = "provider", havingValue = "virtual")
@EnableConfigurationProperties(WechatXPayProperties.class)
public class VirtualPaymentAdapter implements PaymentPort {

    /** 渠道标识，写入 payment_notification_inbox.channel / payment_transaction.channel */
    private static final String CHANNEL = "XPAY";

    /** C 端拉起固定签名 uri（官方规则：paySig 消息体 = "requestVirtualPayment&" + signData） */
    static final String CLIENT_PAY_URI = "requestVirtualPayment";

    /** 道具直购固定 mode */
    static final String MODE_SHORT_SERIES_GOODS = "short_series_goods";

    /** 后台补偿无一次性 code 时无法重签：以 state 标记返回，服务层按「参数未就绪」处理（既有合同） */
    static final String SESSION_CODE_REQUIRED = "SESSION_CODE_REQUIRED";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final WechatXPayProperties properties;
    private final String wechatAppId;
    private final WechatPaySessionPort paySessionPort;
    private final JdbcTemplate jdbcTemplate;
    private final WxMaXPayService xPayService;

    /**
     * Spring 装配构造器：以 identity 已登记的 appid/secret 构建 {@link WxMaService}
     * （access_token 内存缓存，供 B 端查单/退款），再包出 {@link WxMaXPayService}。
     */
    public VirtualPaymentAdapter(WechatXPayProperties properties,
                                 @Value("${zhongshu.identity.wechat-appid}") String wechatAppId,
                                 @Value("${zhongshu.identity.wechat-appsecret}") String wechatAppSecret,
                                 WechatPaySessionPort paySessionPort,
                                 DataSource dataSource) {
        this.properties = properties;
        this.wechatAppId = wechatAppId;
        this.paySessionPort = paySessionPort;
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        WxMaDefaultConfigImpl config = new WxMaDefaultConfigImpl();
        config.setAppid(wechatAppId);
        config.setSecret(wechatAppSecret);
        WxMaService maService = new WxMaServiceImpl();
        maService.setWxMaConfig(config);
        this.xPayService = new WxMaXPayServiceImpl(maService);
    }

    /** 测试构造器：注入替身 {@link WxMaXPayService} 与 {@link JdbcTemplate}，不触达真实微信。 */
    @VisibleForTesting
    VirtualPaymentAdapter(WechatXPayProperties properties, String wechatAppId,
                          WechatPaySessionPort paySessionPort, JdbcTemplate jdbcTemplate,
                          WxMaXPayService xPayService) {
        this.properties = properties;
        this.wechatAppId = wechatAppId;
        this.paySessionPort = paySessionPort;
        this.jdbcTemplate = jdbcTemplate;
        this.xPayService = xPayService;
    }

    @Override
    public String channel() {
        return CHANNEL;
    }

    @Override
    public String merchantId() {
        return properties.getOfferId();
    }

    // ========== 预下单（纯本地签名） ==========

    @Override
    public PrepayResult createPrepay(String orderNo, long amountCents, String description, String openid,
                                     String wxLoginCode) {
        if (wxLoginCode == null || wxLoginCode.isBlank()) {
            // 一次性 code 是 session_key 的唯一来源；后台补偿路径缺码无法重签，
            // 以 state 标记交服务层按既有「参数未就绪」语义处理（等用户回来自取）。
            log.info("[createPrepay][orderNo={} 缺一次性 wx.login code，虚拟支付签名延后至用户支付时]", orderNo);
            return new PrepayResult(null, Map.of("state", SESSION_CODE_REQUIRED));
        }
        String productId = queryProductId(orderNo);
        if (productId == null || productId.isBlank()) {
            throw new cn.iocoder.yudao.framework.common.exception.ServiceException(409,
                    "充值方案未配置虚拟支付道具编号，请在管理后台补齐后重试");
        }
        // 用一次性 code 换当前 session_key（code 不重试）；openid 必须与订单会话一致，防跨账号签名
        // 实现方在 code 无效/微信侧失败时直接抛业务异常（一次性 code 不重试）
        WechatPaySessionPort.PaySession paySession = paySessionPort.exchangeForPay(wechatAppId, wxLoginCode);
        if (openid != null && !openid.isBlank() && !openid.equals(paySession.openid())) {
            log.warn("[createPrepay][orderNo={} code2session openid 与会话不一致，拒绝签名]", orderNo);
            throw new cn.iocoder.yudao.framework.common.exception.ServiceException(409,
                    "微信登录态与当前账号不一致，请重新进入小程序后重试");
        }
        // signData 键序固定；该字符串即签名原文，客户端必须原样透传给 wx.requestVirtualPayment
        String signData = buildSignData(orderNo, amountCents, productId);
        WxMaXPaySigParams sigParams = new WxMaXPaySigParams();
        sigParams.setAppKey(properties.getAppKey());
        sigParams.setSessionKey(paySession.sessionKey());
        Map<String, String> params = new LinkedHashMap<>();
        params.put("signData", signData);
        params.put("mode", MODE_SHORT_SERIES_GOODS);
        params.put("paySig", sigParams.calcPaySig(CLIENT_PAY_URI, signData));
        params.put("signature", sigParams.calcSig(signData));
        return new PrepayResult("xpay-" + orderNo, params);
    }

    /** signData：官方字段集；金额单位全程「分」，env 固定取配置（生产 0=现网）。 */
    private String buildSignData(String orderNo, long amountCents, String productId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("offerId", properties.getOfferId());
        data.put("buyQuantity", 1);
        data.put("env", properties.getEnv() == null ? 0 : properties.getEnv());
        data.put("currencyType", "CNY");
        data.put("productId", productId);
        data.put("goodsPrice", amountCents);
        data.put("outTradeNo", orderNo);
        data.put("attach", orderNo);
        try {
            return JSON.writeValueAsString(data);
        } catch (Exception e) {
            throw new IllegalStateException("signData 序列化失败", e);
        }
    }

    private String queryProductId(String orderNo) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT plan_snapshot->>'wx_product_id' FROM recharge_order "
                            + "WHERE order_no = ? AND deleted = FALSE", String.class, orderNo);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    // ========== xpay_* 消息推送（验签 + 规范化，XpayNotifyController 调用） ==========

    /**
     * URL 验证 / 消息验签共用的签名核验（微信消息推送官方规则，明文模式与 URL 验证一致）：
     * signature = SHA-1(字典序(token, timestamp, nonce))——echostr 与消息体<b>均不参与</b>签名。
     * 因此本校验只能证明「请求来自持有 Token 的一方」，不能证明消息体未被篡改/重放，
     * 业务事实必须另经 B 端查单确认（见 {@link #parseNotification}）。
     * 静态无状态：{@code XpayNotifyController} 在 stub/real profile 下无本类 Bean，经注入的
     * push-token 配置调用；{@link #verifyNotification} 亦复用。
     */
    public static boolean verifyPushSignature(String pushToken, String signature, String timestamp,
                                              String nonce) {
        if (isBlank(signature) || isBlank(timestamp) || isBlank(nonce) || isBlank(pushToken)) {
            return false;
        }
        String computed = sha1Hex(sortedJoin(pushToken, timestamp, nonce));
        return MessageDigest.isEqual(
                computed.getBytes(StandardCharsets.UTF_8),
                signature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
    }

    /** 消息推送验签（明文模式）：见 {@link #verifyPushSignature}；空消息体直接拒绝。 */
    @Override
    public boolean verifyNotification(Map<String, String> headers, byte[] body) {
        if (body == null || body.length == 0) {
            return false;
        }
        return verifyPushSignature(properties.getPushToken(), headers.get("signature"),
                headers.get("timestamp"), headers.get("nonce"));
    }

    /**
     * 发货推送 xpay_goods_deliver_notify → 规范化支付事实。
     *
     * <p>明文推送体不受签名保护（截获一组签名参数即可伪造任意消息体），故推送仅作触发信号：
     * <ol>
     *   <li>OpenId / GoodsInfo.ProductId 存在即与订单交叉核对，不符直接拒绝；</li>
     *   <li>B 端 {@code query_order} 必须确认已支付——查单不可用/未支付态抛非业务异常（5xx，微信退避重推，
     *       补偿扫描亦会收口），已关闭/失败拒绝；</li>
     *   <li>实付金额、平台单号、支付时间取查单结果，金额一致性由既有 PaymentFactValidator 硬校验。</li>
     * </ol>
     */
    @Override
    public NormalizedNotification parseNotification(Map<String, String> headers, byte[] body) {
        JsonNode node = parsePushBody(body);
        String event = node.path("Event").asText("");
        if (!"xpay_goods_deliver_notify".equals(event)) {
            throw new IllegalStateException("非发货推送事件: " + event);
        }
        String orderNo = requiredText(node, "OutTradeNo");
        JsonNode payInfo = node.path("WeChatPayInfo");
        JsonNode goodsInfo = node.path("GoodsInfo");
        String mchOrderNo = payInfo.path("MchOrderNo").asText("");
        String eventId = "deliver:" + (mchOrderNo.isBlank() ? orderNo : mchOrderNo);

        OrderFacts order = orderFacts(orderNo);
        String pushOpenid = node.path("OpenId").asText("");
        if (!pushOpenid.isBlank() && !pushOpenid.equals(order.payerOpenid())) {
            log.warn("[parseNotification][orderNo={} 推送 OpenId 与订单支付者不符，拒绝]", orderNo);
            throw rejected("发货推送与订单支付者不符");
        }
        String pushProductId = goodsInfo.path("ProductId").asText("");
        if (!pushProductId.isBlank() && order.productId() != null && !pushProductId.equals(order.productId())) {
            log.warn("[parseNotification][orderNo={} 推送道具编号与订单快照不符，拒绝]", orderNo);
            throw rejected("发货推送道具与订单不符");
        }

        WxMaXPayQueryOrderResponse.OrderInfo info = queryOrderInfo(orderNo);
        if (info == null || info.getStatus() == null) {
            throw new IllegalStateException("xpay 查单不可用，暂不发货（等待重推/补偿扫描）");
        }
        String state = mapOrderStatus(info.getStatus());
        if ("CLOSED".equals(state) || "FAILED".equals(state)) {
            log.warn("[parseNotification][orderNo={} 查单状态 {}，拒绝发货]", orderNo, state);
            throw rejected("订单未支付或已关闭，拒绝发货");
        }
        if (!"SUCCEEDED".equals(state)) {
            throw new IllegalStateException("xpay 查单尚未确认支付: " + state);
        }
        Long paidFee = info.getPaidFee();
        if (paidFee == null && goodsInfo.hasNonNull("ActualPrice")) {
            paidFee = goodsInfo.get("ActualPrice").asLong();
        }
        if (paidFee == null) {
            throw new IllegalStateException("xpay 查单未返回实付金额");
        }
        String transactionId = firstNonBlank(info.getWxOrderId(),
                payInfo.path("TransactionId").asText(""), mchOrderNo);
        Instant paidAt = info.getPaidTime() != null && info.getPaidTime() > 0
                ? Instant.ofEpochSecond(info.getPaidTime())
                : payInfo.hasNonNull("PaidTime") ? Instant.ofEpochSecond(payInfo.get("PaidTime").asLong())
                : node.hasNonNull("CreateTime") ? Instant.ofEpochSecond(node.get("CreateTime").asLong()) : null;
        return new NormalizedNotification(eventId, orderNo, transactionId, paidFee, paidAt);
    }

    /**
     * 退款推送 xpay_refund_notify → 规范化退款事实。推送体同样不受签名保护，
     * 终态以查单为准：查单 SUCCEEDED/FAILED 才推进冲正/释放，其余一律 PROCESSING 保持预留（红线 5）。
     */
    @Override
    public NormalizedRefundNotification parseRefundNotification(Map<String, String> headers, byte[] body) {
        JsonNode node = parsePushBody(body);
        String event = node.path("Event").asText("");
        if (!"xpay_refund_notify".equals(event)) {
            throw new IllegalStateException("非退款推送事件: " + event);
        }
        String orderNo = requiredText(node, "OutTradeNo");
        String mchRefundId = requiredText(node, "MchRefundId");
        int retCode = node.path("RetCode").asInt(-1);
        long refundFee = node.path("RefundFee").asLong(0L);
        String eventId = "refund:" + mchRefundId + ":" + retCode;
        String confirmed = queryRefund(orderNo, mchRefundId).getState();
        String state = "SUCCEEDED".equals(confirmed) || "FAILED".equals(confirmed) ? confirmed : "PROCESSING";
        if ((retCode == 0) != "SUCCEEDED".equals(state)) {
            log.warn("[parseRefundNotification][orderNo={} 推送 RetCode={} 与查单 {} 不一致，以查单为准]",
                    orderNo, retCode, confirmed);
        }
        return new NormalizedRefundNotification(eventId, orderNo, mchRefundId, state, refundFee, Instant.now());
    }

    /** 兼容 JSON 与 XML 两种推送格式（MP 后台消息推送配置二选一），统一为 JsonNode 访问。 */
    public static JsonNode parsePushBody(byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8).trim();
        try {
            if (text.startsWith("<")) {
                return JSON.valueToTree(xmlToMap(text));
            }
            return JSON.readTree(text);
        } catch (Exception e) {
            // 推送原文绝不进日志（含用户 openid 等隐私），只记解析失败
            throw new IllegalStateException("xpay 推送体解析失败: " + e.getClass().getSimpleName(), e);
        }
    }

    /** 简易 XML → 嵌套 Map（无属性/命名空间处理——微信明文推送为纯元素文本）。 */
    private static Map<String, Object> xmlToMap(String xml) throws Exception {
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        // 防外部实体注入：禁 DTD 与外部实体（微信推送不需要它们）
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute("http://xml.org/sax/features/external-general-entities", false);
        factory.setAttribute("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var document = factory.newDocumentBuilder()
                .parse(new org.xml.sax.InputSource(new java.io.StringReader(xml)));
        return elementToMap(document.getDocumentElement());
    }

    private static Map<String, Object> elementToMap(org.w3c.dom.Element element) {
        Map<String, Object> map = new LinkedHashMap<>();
        var children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) {
                continue;
            }
            var child = (org.w3c.dom.Element) children.item(i);
            boolean hasElementChildren = false;
            var grandChildren = child.getChildNodes();
            for (int j = 0; j < grandChildren.getLength(); j++) {
                if (grandChildren.item(j).getNodeType() == org.w3c.dom.Node.ELEMENT_NODE) {
                    hasElementChildren = true;
                    break;
                }
            }
            map.put(child.getTagName(), hasElementChildren ? elementToMap(child) : child.getTextContent());
        }
        return map;
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isBlank()) {
            throw new IllegalStateException("xpay 推送缺少必填字段: " + field);
        }
        return value;
    }

    private record OrderFacts(String payerOpenid, String productId) { }

    private OrderFacts orderFacts(String orderNo) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT payer_openid, plan_snapshot->>'wx_product_id' AS product_id FROM recharge_order "
                        + "WHERE order_no = ? AND deleted = FALSE", orderNo);
        if (rows.isEmpty()) {
            throw rejected("发货推送指向未知订单");
        }
        return new OrderFacts((String) rows.get(0).get("payer_openid"), (String) rows.get(0).get("product_id"));
    }

    private static cn.iocoder.yudao.framework.common.exception.ServiceException rejected(String message) {
        return new cn.iocoder.yudao.framework.common.exception.ServiceException(409, message);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    // ========== B 端查单 / 退款（access_token + pay_sig，经 WxMaXPayService） ==========

    @Override
    public ChannelQueryResult queryOrder(String orderNo) {
        WxMaXPayQueryOrderResponse.OrderInfo info = queryOrderInfo(orderNo);
        if (info == null || info.getStatus() == null) {
            return new ChannelQueryResult("UNKNOWN", null, null);
        }
        return new ChannelQueryResult(mapOrderStatus(info.getStatus()), info.getWxOrderId(), info.getPaidFee());
    }

    /** 虚拟支付订单状态 → 本系统状态（SUCCEEDED/PENDING/CLOSED/FAILED/UNKNOWN） */
    static String mapOrderStatus(int status) {
        if (status == cn.binarywang.wx.miniapp.constant.WxMaConstants.XPayOrderStatus.PAID
                || status == cn.binarywang.wx.miniapp.constant.WxMaConstants.XPayOrderStatus.PROVIDING
                || status == cn.binarywang.wx.miniapp.constant.WxMaConstants.XPayOrderStatus.PROVIDED) {
            return "SUCCEEDED";
        }
        if (status == cn.binarywang.wx.miniapp.constant.WxMaConstants.XPayOrderStatus.INIT
                || status == cn.binarywang.wx.miniapp.constant.WxMaConstants.XPayOrderStatus.CREATED) {
            return "PENDING";
        }
        if (status == cn.binarywang.wx.miniapp.constant.WxMaConstants.XPayOrderStatus.CLOSED) {
            return "CLOSED";
        }
        if (status == cn.binarywang.wx.miniapp.constant.WxMaConstants.XPayOrderStatus.REFUNDED) {
            // 已退款：未到账订单不再发货；本地若已到账由退款链路冲正
            return "CLOSED";
        }
        if (status == cn.binarywang.wx.miniapp.constant.WxMaConstants.XPayOrderStatus.REFUND_FAILED) {
            // 退款失败：订单仍有效，视同已支付
            return "SUCCEEDED";
        }
        return "UNKNOWN";
    }

    @Override
    public ChannelRefundResult requestRefund(String orderNo, String channelRefundId, long amountCents) {
        try {
            String openid = requirePayerOpenid(orderNo);
            WxMaXPayRefundOrderRequest request = WxMaXPayRefundOrderRequest.builder()
                    .openid(openid)
                    .env(properties.getEnv())
                    .orderId(orderNo)
                    .refundOrderId(channelRefundId)
                    .refundFee(amountCents)
                    .refundReason("设计点整单退款")
                    .build();
            xPayService.refundOrder(request, appKeySigParams());
            // 受理即返回：同步响应无终态，收口靠 xpay_refund_notify 推送与 queryRefund 查单
            return new ChannelRefundResult("PROCESSING");
        } catch (WxErrorException e) {
            log.warn("[requestRefund][orderNo={} 微信拒绝 errCode={}]", orderNo, wxErrCode(e));
            return new ChannelRefundResult("UNKNOWN");
        }
    }

    @Override
    public ChannelRefundResult queryRefund(String orderNo, String channelRefundId) {
        WxMaXPayQueryOrderResponse.OrderInfo info = queryOrderInfo(orderNo);
        if (info == null || info.getStatus() == null) {
            return new ChannelRefundResult("UNKNOWN");
        }
        int status = info.getStatus();
        if (status == cn.binarywang.wx.miniapp.constant.WxMaConstants.XPayOrderStatus.REFUNDED) {
            return new ChannelRefundResult("SUCCEEDED");
        }
        if (status == cn.binarywang.wx.miniapp.constant.WxMaConstants.XPayOrderStatus.REFUND_FAILED) {
            return new ChannelRefundResult("FAILED");
        }
        if (info.getRefundFee() != null && info.getRefundFee() > 0) {
            return new ChannelRefundResult("PROCESSING");
        }
        return new ChannelRefundResult("NOT_FOUND");
    }

    private WxMaXPayQueryOrderResponse.OrderInfo queryOrderInfo(String orderNo) {
        try {
            String openid = requirePayerOpenid(orderNo);
            WxMaXPayQueryOrderRequest request = WxMaXPayQueryOrderRequest.builder()
                    .openid(openid)
                    .env(properties.getEnv())
                    .orderId(orderNo)
                    .build();
            WxMaXPayQueryOrderResponse response = xPayService.queryOrder(request, appKeySigParams());
            return response == null ? null : response.getOrder();
        } catch (WxErrorException e) {
            // 只记公开错误码（如 ORDER_NOT_EXIST）；errmsg 不入日志
            log.warn("[queryOrder][orderNo={} 微信查单失败 errCode={}]", orderNo, wxErrCode(e));
            return null;
        }
    }

    private String requirePayerOpenid(String orderNo) {
        try {
            String openid = jdbcTemplate.queryForObject(
                    "SELECT payer_openid FROM recharge_order WHERE order_no = ? AND deleted = FALSE",
                    String.class, orderNo);
            if (openid == null || openid.isBlank()) {
                throw new cn.iocoder.yudao.framework.common.exception.ServiceException(409,
                        "订单缺少支付者标识，无法调用虚拟支付接口");
            }
            return openid;
        } catch (EmptyResultDataAccessException e) {
            throw new cn.iocoder.yudao.framework.common.exception.ServiceException(409,
                    "订单不存在，无法调用虚拟支付接口");
        }
    }

    private WxMaXPaySigParams appKeySigParams() {
        WxMaXPaySigParams sigParams = new WxMaXPaySigParams();
        sigParams.setAppKey(properties.getAppKey());
        return sigParams;
    }

    private static String wxErrCode(WxErrorException e) {
        try {
            return e.getError() == null ? "unknown" : String.valueOf(e.getError().getErrorCode());
        } catch (Exception ex) {
            return "unknown";
        }
    }

    // ========== 工具 ==========

    static String sortedJoin(String... values) {
        List<String> list = new java.util.ArrayList<>(List.of(values));
        java.util.Collections.sort(list);
        return String.join("", list);
    }

    static String sha1Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
