package cn.iocoder.yudao.module.commerce.payment.xpay;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.commerce.payment.PaymentPort;
import cn.iocoder.yudao.module.infra.zhongshu.api.WechatPaySessionPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import me.chanjar.weixin.common.error.WxError;
import me.chanjar.weixin.common.error.WxErrorException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T-XPAY 隔离测试：{@link VirtualPaymentAdapter}。
 *
 * <p>测试策略：Mockito mock {@link WxMaXPayService}（B 端查单/退款）与 {@link JdbcTemplate}
 * （道具编号 / payer openid / 声明金额），不触达任何真实微信端点。覆盖：
 * C 端拉起签名（paySig/signature 与官方 HMAC-SHA256 规则逐字节核对）、缺码延后、
 * 道具未配置拒绝、跨账号 openid 拒绝、推送验签、发货/退款推送 JSON+XML 双格式解析、
 * 查单状态映射、退款受理与 UNKNOWN 收口。
 */
@ExtendWith(MockitoExtension.class)
class VirtualPaymentAdapterTest {

    private static final String APP_ID = "wx-test-appid";
    private static final String OFFER_ID = "offer-test-001";
    private static final String APP_KEY = "xpay-app-key-test";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock
    private cn.binarywang.wx.miniapp.api.WxMaXPayService xPayService;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private WechatPaySessionPort paySessionPort;

    private WechatXPayProperties props;

    private VirtualPaymentAdapter adapter;

    @BeforeEach
    void setUp() {
        props = new WechatXPayProperties();
        props.setOfferId(OFFER_ID);
        props.setAppKey(APP_KEY);
        props.setEnv(0);
        props.setPushToken("push-token-test");
        adapter = new VirtualPaymentAdapter(props, APP_ID, paySessionPort, jdbcTemplate, xPayService);
    }

    private static String hmacSha256Hex(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ========== channel / merchantId ==========

    @Test
    @DisplayName("channel() 固定返回 XPAY；merchantId() 即 OfferID")
    void channelAndMerchant() {
        assertThat(adapter.channel()).isEqualTo("XPAY");
        assertThat(adapter.merchantId()).isEqualTo(OFFER_ID);
    }

    // ========== createPrepay（C 端签名） ==========

    @Test
    @DisplayName("createPrepay 正常路径：signData 字段齐全、paySig/signature 与官方签名规则一致")
    void createPrepaySignsCorrectly() throws Exception {
        when(jdbcTemplate.queryForObject(contains("plan_snapshot"), eq(String.class), eq("R1001")))
                .thenReturn("product-50");
        when(paySessionPort.exchangeForPay(eq(APP_ID), eq("login-code-1")))
                .thenReturn(new WechatPaySessionPort.PaySession("openid-1", "session-key-1"));

        PaymentPort.PrepayResult result = adapter.createPrepay("R1001", 5000L, "充值", "openid-1", "login-code-1");

        Map<String, String> params = result.getCallParams();
        assertThat(params).containsKeys("signData", "mode", "paySig", "signature");
        assertThat(params.get("mode")).isEqualTo("short_series_goods");

        // signData：客户端原样透传，字段与金额（分）必须精确
        JsonNode signData = JSON.readTree(params.get("signData"));
        assertThat(signData.get("offerId").asText()).isEqualTo(OFFER_ID);
        assertThat(signData.get("buyQuantity").asInt()).isEqualTo(1);
        assertThat(signData.get("env").asInt()).isEqualTo(0);
        assertThat(signData.get("currencyType").asText()).isEqualTo("CNY");
        assertThat(signData.get("productId").asText()).isEqualTo("product-50");
        assertThat(signData.get("goodsPrice").asLong()).isEqualTo(5000L);
        assertThat(signData.get("outTradeNo").asText()).isEqualTo("R1001");

        // 官方签名规则：paySig = HMAC-SHA256(AppKey, "requestVirtualPayment&" + signData)；
        // signature = HMAC-SHA256(session_key, signData)；均为小写 hex
        String signDataText = params.get("signData");
        assertThat(params.get("paySig"))
                .isEqualTo(hmacSha256Hex(APP_KEY, "requestVirtualPayment&" + signDataText));
        assertThat(params.get("signature")).isEqualTo(hmacSha256Hex("session-key-1", signDataText));
    }

    @Test
    @DisplayName("缺一次性 wx.login code：返回 state=SESSION_CODE_REQUIRED（后台补偿延后重签）")
    void createPrepayWithoutLoginCodeDefers() {
        PaymentPort.PrepayResult result = adapter.createPrepay("R1001", 5000L, "充值", "openid-1", null);
        assertThat(result.getCallParams()).containsEntry("state", VirtualPaymentAdapter.SESSION_CODE_REQUIRED);
    }

    @Test
    @DisplayName("方案未配置道具编号：409 拒绝，不发签名")
    void createPrepayRejectsMissingProductId() {
        when(jdbcTemplate.queryForObject(contains("plan_snapshot"), eq(String.class), eq("R1001")))
                .thenThrow(new EmptyResultDataAccessException(1));
        assertThatThrownBy(() -> adapter.createPrepay("R1001", 5000L, "充值", "openid-1", "code-1"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("道具编号");
    }

    @Test
    @DisplayName("code2session openid 与订单会话不一致：拒绝签名（防跨账号）")
    void createPrepayRejectsOpenidMismatch() {
        when(jdbcTemplate.queryForObject(contains("plan_snapshot"), eq(String.class), eq("R1001")))
                .thenReturn("product-50");
        when(paySessionPort.exchangeForPay(eq(APP_ID), eq("login-code-1")))
                .thenReturn(new WechatPaySessionPort.PaySession("openid-OTHER", "session-key-1"));
        assertThatThrownBy(() -> adapter.createPrepay("R1001", 5000L, "充值", "openid-1", "login-code-1"))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("不一致");
    }

    // ========== 推送验签 ==========

    @Test
    @DisplayName("verifyPushSignature：官方规则 SHA-1(字典序(token,timestamp,nonce))，消息体/echostr 不参与")
    void verifyPushSignature() throws Exception {
        // 独立按官方文档计算期望值（不复用被测类的工具方法）：字典序排序后直接拼接再 SHA-1
        String[] parts = {"push-token-test", "1694400000", "nonce-1"};
        java.util.Arrays.sort(parts);
        String good = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1")
                .digest(String.join("", parts).getBytes(StandardCharsets.UTF_8)));
        assertThat(VirtualPaymentAdapter.verifyPushSignature("push-token-test", good, "1694400000", "nonce-1"))
                .isTrue();
        assertThat(VirtualPaymentAdapter.verifyPushSignature("push-token-test", good.toUpperCase(), "1694400000", "nonce-1"))
                .isTrue(); // 大写 hex 等价
        assertThat(VirtualPaymentAdapter.verifyPushSignature("push-token-test", good, "1694400001", "nonce-1"))
                .isFalse(); // 时间戳被改
        assertThat(VirtualPaymentAdapter.verifyPushSignature("push-token-test", "deadbeef", "1694400000", "nonce-1"))
                .isFalse();
        assertThat(VirtualPaymentAdapter.verifyPushSignature("", good, "1694400000", "nonce-1"))
                .isFalse(); // 未配置 Token 恒拒绝
        // verifyNotification：同一签名对任意非空消息体成立（明文模式消息体不受保护 → 必须查单确认）
        assertThat(adapter.verifyNotification(Map.of("signature", good, "timestamp", "1694400000", "nonce", "nonce-1"),
                "{\"any\":1}".getBytes(StandardCharsets.UTF_8))).isTrue();
        assertThat(adapter.verifyNotification(Map.of("signature", good, "timestamp", "1694400000", "nonce", "nonce-1"),
                new byte[0])).isFalse();
    }

    // ========== 发货/退款推送解析（JSON + XML） ==========

    private static final String DELIVER_JSON = "{\"ToUserName\":\"gh-1\",\"FromUserName\":\"openid-1\","
            + "\"CreateTime\":1694400000,\"MsgType\":\"event\",\"Event\":\"xpay_goods_deliver_notify\","
            + "\"OpenId\":\"openid-1\",\"OutTradeNo\":\"R1001\",\"Env\":0,"
            + "\"WeChatPayInfo\":{\"MchOrderNo\":\"WX-ORDER-9\"},"
            + "\"GoodsInfo\":{\"ProductId\":\"product-50\",\"Quantity\":1}}";

    private static final String DELIVER_XML = "<xml><ToUserName><![CDATA[gh-1]]></ToUserName>"
            + "<FromUserName><![CDATA[openid-1]]></FromUserName><CreateTime>1694400000</CreateTime>"
            + "<MsgType><![CDATA[event]]></MsgType><Event><![CDATA[xpay_goods_deliver_notify]]></Event>"
            + "<OpenId><![CDATA[openid-1]]></OpenId><OutTradeNo><![CDATA[R1001]]></OutTradeNo><Env>0</Env>"
            + "<WeChatPayInfo><MchOrderNo><![CDATA[WX-ORDER-9]]></MchOrderNo></WeChatPayInfo>"
            + "<GoodsInfo><ProductId><![CDATA[product-50]]></ProductId><Quantity>1</Quantity></GoodsInfo></xml>";

    private void stubOrderFacts(String payerOpenid, String productId) {
        var row = new java.util.HashMap<String, Object>();
        row.put("payer_openid", payerOpenid);
        row.put("product_id", productId);
        lenient().when(jdbcTemplate.queryForList(contains("plan_snapshot"), eq("R1001")))
                .thenReturn(java.util.List.of(row));
    }

    private void stubQueryStatus(int status) throws Exception {
        stubPayerOpenid();
        var response = new cn.binarywang.wx.miniapp.bean.xpay.WxMaXPayQueryOrderResponse();
        response.setOrder(orderInfo(status));
        when(xPayService.queryOrder(any(), any())).thenReturn(response);
    }

    @Test
    @DisplayName("发货推送 JSON：查单确认已支付后，金额/平台单号/支付时间取查单结果")
    void parseDeliverPushJson() throws Exception {
        stubOrderFacts("openid-1", "product-50");
        stubQueryStatus(cn.binarywang.wx.miniapp.constant.WxMaConstants.XPayOrderStatus.PAID);
        PaymentPort.NormalizedNotification notification =
                adapter.parseNotification(Map.of(), DELIVER_JSON.getBytes(StandardCharsets.UTF_8));
        assertThat(notification.getEventId()).isEqualTo("deliver:WX-ORDER-9");
        assertThat(notification.getOrderNo()).isEqualTo("R1001");
        assertThat(notification.getChannelTransactionId()).isEqualTo("WX-ORDER-9");
        assertThat(notification.getAmountCents()).isEqualTo(5000L);
        assertThat(notification.getPaidAt()).isEqualTo(Instant.ofEpochSecond(1694400000));
    }

    @Test
    @DisplayName("发货推送 XML：与 JSON 解析结果一致（消息推送格式二选一等价）")
    void parseDeliverPushXml() throws Exception {
        stubOrderFacts("openid-1", "product-50");
        stubQueryStatus(cn.binarywang.wx.miniapp.constant.WxMaConstants.XPayOrderStatus.PROVIDED);
        PaymentPort.NormalizedNotification notification =
                adapter.parseNotification(Map.of(), DELIVER_XML.getBytes(StandardCharsets.UTF_8));
        assertThat(notification.getEventId()).isEqualTo("deliver:WX-ORDER-9");
        assertThat(notification.getOrderNo()).isEqualTo("R1001");
        assertThat(notification.getChannelTransactionId()).isEqualTo("WX-ORDER-9");
        assertThat(notification.getAmountCents()).isEqualTo(5000L);
    }

    @Test
    @DisplayName("伪造发货推送：查单显示未支付/已关闭 → 拒绝，绝不按订单声明金额到账")
    void parseDeliverPushRejectsUnpaidOrder() throws Exception {
        stubOrderFacts("openid-1", "product-50");
        stubQueryStatus(cn.binarywang.wx.miniapp.constant.WxMaConstants.XPayOrderStatus.CLOSED);
        assertThatThrownBy(() -> adapter.parseNotification(Map.of(), DELIVER_JSON.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ServiceException.class);

        var created = new cn.binarywang.wx.miniapp.bean.xpay.WxMaXPayQueryOrderResponse();
        created.setOrder(orderInfo(cn.binarywang.wx.miniapp.constant.WxMaConstants.XPayOrderStatus.CREATED));
        when(xPayService.queryOrder(any(), any())).thenReturn(created);
        // 未支付态：非业务异常 → 5xx，微信退避重推 / 补偿扫描收口
        assertThatThrownBy(() -> adapter.parseNotification(Map.of(), DELIVER_JSON.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(ServiceException.class);
    }

    @Test
    @DisplayName("查单不可用：暂不发货（5xx 重推），不回退声明金额")
    void parseDeliverPushRequiresQuery() throws Exception {
        stubOrderFacts("openid-1", "product-50");
        stubPayerOpenid();
        when(xPayService.queryOrder(any(), any()))
                .thenThrow(new WxErrorException(WxError.builder().errorCode(-1).errorMsg("system busy").build()));
        assertThatThrownBy(() -> adapter.parseNotification(Map.of(), DELIVER_JSON.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("推送 OpenId / 道具编号与订单不符：拒绝（不查单、不到账）")
    void parseDeliverPushRejectsMismatchedFacts() {
        stubOrderFacts("openid-OTHER", "product-50");
        assertThatThrownBy(() -> adapter.parseNotification(Map.of(), DELIVER_JSON.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ServiceException.class);
        stubOrderFacts("openid-1", "product-99");
        assertThatThrownBy(() -> adapter.parseNotification(Map.of(), DELIVER_JSON.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    @DisplayName("发货推送指向未知订单：拒绝")
    void parseDeliverPushRejectsUnknownOrder() {
        when(jdbcTemplate.queryForList(contains("plan_snapshot"), eq("R1001"))).thenReturn(java.util.List.of());
        assertThatThrownBy(() -> adapter.parseNotification(Map.of(), DELIVER_JSON.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    @DisplayName("退款推送：终态以查单为准——REFUNDED → SUCCEEDED，REFUND_FAILED → FAILED，其余 PROCESSING")
    void parseRefundPush() throws Exception {
        String success = "{\"Event\":\"xpay_refund_notify\",\"OutTradeNo\":\"R1001\","
                + "\"MchRefundId\":\"refund-9\",\"RetCode\":0,\"RefundFee\":5000}";
        stubQueryStatus(cn.binarywang.wx.miniapp.constant.WxMaConstants.XPayOrderStatus.REFUNDED);
        PaymentPort.NormalizedRefundNotification notification =
                adapter.parseRefundNotification(Map.of(), success.getBytes(StandardCharsets.UTF_8));
        assertThat(notification.getEventId()).isEqualTo("refund:refund-9:0");
        assertThat(notification.getOrderNo()).isEqualTo("R1001");
        assertThat(notification.getChannelRefundId()).isEqualTo("refund-9");
        assertThat(notification.getState()).isEqualTo("SUCCEEDED");
        assertThat(notification.getRefundAmountCents()).isEqualTo(5000L);

        String failed = "{\"Event\":\"xpay_refund_notify\",\"OutTradeNo\":\"R1001\","
                + "\"MchRefundId\":\"refund-9\",\"RetCode\":5,\"RefundFee\":5000}";
        var refundFailed = new cn.binarywang.wx.miniapp.bean.xpay.WxMaXPayQueryOrderResponse();
        refundFailed.setOrder(orderInfo(cn.binarywang.wx.miniapp.constant.WxMaConstants.XPayOrderStatus.REFUND_FAILED));
        when(xPayService.queryOrder(any(), any())).thenReturn(refundFailed);
        assertThat(adapter.parseRefundNotification(Map.of(), failed.getBytes(StandardCharsets.UTF_8)).getState())
                .isEqualTo("FAILED");

        // 伪造 RetCode=0 但查单仍为已支付：不冲正，保持预留等查单收口
        var paid = new cn.binarywang.wx.miniapp.bean.xpay.WxMaXPayQueryOrderResponse();
        paid.setOrder(orderInfo(cn.binarywang.wx.miniapp.constant.WxMaConstants.XPayOrderStatus.PAID));
        when(xPayService.queryOrder(any(), any())).thenReturn(paid);
        assertThat(adapter.parseRefundNotification(Map.of(), success.getBytes(StandardCharsets.UTF_8)).getState())
                .isEqualTo("PROCESSING");
    }

    @Test
    @DisplayName("非 xpay 事件解析被拒（控制器 5xx → 微信重试）")
    void parseRejectsUnknownEvent() {
        String other = "{\"Event\":\"xpay_complaint_notify\",\"OutTradeNo\":\"R1001\"}";
        assertThatThrownBy(() -> adapter.parseNotification(Map.of(), other.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalStateException.class);
    }

    // ========== 查单 / 退款（B 端） ==========

    private cn.binarywang.wx.miniapp.bean.xpay.WxMaXPayQueryOrderResponse.OrderInfo orderInfo(int status) {
        var info = new cn.binarywang.wx.miniapp.bean.xpay.WxMaXPayQueryOrderResponse.OrderInfo();
        info.setStatus(status);
        info.setPaidFee(5000L);
        info.setWxOrderId("WX-ORDER-9");
        info.setPaidTime(1694400000L);
        return info;
    }

    private void stubPayerOpenid() {
        when(jdbcTemplate.queryForObject(contains("payer_openid"), eq(String.class), eq("R1001")))
                .thenReturn("openid-1");
    }

    @Test
    @DisplayName("queryOrder：PAID → SUCCEEDED 且透传实付金额与平台单号")
    void queryOrderPaid() throws Exception {
        stubPayerOpenid();
        var response = new cn.binarywang.wx.miniapp.bean.xpay.WxMaXPayQueryOrderResponse();
        response.setOrder(orderInfo(cn.binarywang.wx.miniapp.constant.WxMaConstants.XPayOrderStatus.PAID));
        when(xPayService.queryOrder(any(), any())).thenReturn(response);

        PaymentPort.ChannelQueryResult result = adapter.queryOrder("R1001");
        assertThat(result.getState()).isEqualTo("SUCCEEDED");
        assertThat(result.getChannelTransactionId()).isEqualTo("WX-ORDER-9");
        assertThat(result.getAmountCents()).isEqualTo(5000L);
    }

    @Test
    @DisplayName("queryOrder 状态映射：CLOSED/REFUNDED → CLOSED，INIT/CREATED → PENDING，REFUND_FAILED → SUCCEEDED")
    void mapOrderStatusTable() {
        assertThat(VirtualPaymentAdapter.mapOrderStatus(cn.binarywang.wx.miniapp.constant.WxMaConstants.XPayOrderStatus.PROVIDED)).isEqualTo("SUCCEEDED");
        assertThat(VirtualPaymentAdapter.mapOrderStatus(cn.binarywang.wx.miniapp.constant.WxMaConstants.XPayOrderStatus.PROVIDING)).isEqualTo("SUCCEEDED");
        assertThat(VirtualPaymentAdapter.mapOrderStatus(cn.binarywang.wx.miniapp.constant.WxMaConstants.XPayOrderStatus.INIT)).isEqualTo("PENDING");
        assertThat(VirtualPaymentAdapter.mapOrderStatus(cn.binarywang.wx.miniapp.constant.WxMaConstants.XPayOrderStatus.CREATED)).isEqualTo("PENDING");
        assertThat(VirtualPaymentAdapter.mapOrderStatus(cn.binarywang.wx.miniapp.constant.WxMaConstants.XPayOrderStatus.CLOSED)).isEqualTo("CLOSED");
        assertThat(VirtualPaymentAdapter.mapOrderStatus(cn.binarywang.wx.miniapp.constant.WxMaConstants.XPayOrderStatus.REFUNDED)).isEqualTo("CLOSED");
        assertThat(VirtualPaymentAdapter.mapOrderStatus(cn.binarywang.wx.miniapp.constant.WxMaConstants.XPayOrderStatus.REFUND_FAILED)).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("queryOrder 微信报错：归 UNKNOWN（不中断补偿扫描），日志不含 errmsg")
    void queryOrderWxError() throws Exception {
        stubPayerOpenid();
        when(xPayService.queryOrder(any(), any()))
                .thenThrow(new WxErrorException(WxError.builder().errorCode(-1).errorMsg("system busy").build()));
        assertThat(adapter.queryOrder("R1001").getState()).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("requestRefund：受理返回 PROCESSING；微信拒绝返回 UNKNOWN（红线5 保持预留）")
    void requestRefundStates() throws Exception {
        stubPayerOpenid();
        when(xPayService.refundOrder(any(), any()))
                .thenReturn(new cn.binarywang.wx.miniapp.bean.xpay.WxMaXPayRefundOrderResponse());
        assertThat(adapter.requestRefund("R1001", "refund-9", 5000L).getState()).isEqualTo("PROCESSING");

        when(xPayService.refundOrder(any(), any()))
                .thenThrow(new WxErrorException(WxError.builder().errorCode(40226666).errorMsg("rejected").build()));
        assertThat(adapter.requestRefund("R1001", "refund-9", 5000L).getState()).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("queryRefund：按订单状态位映射退款终态；无退款痕迹 → NOT_FOUND")
    void queryRefundStates() throws Exception {
        stubPayerOpenid();
        var refunded = new cn.binarywang.wx.miniapp.bean.xpay.WxMaXPayQueryOrderResponse();
        refunded.setOrder(orderInfo(cn.binarywang.wx.miniapp.constant.WxMaConstants.XPayOrderStatus.REFUNDED));
        when(xPayService.queryOrder(any(), any())).thenReturn(refunded);
        assertThat(adapter.queryRefund("R1001", "refund-9").getState()).isEqualTo("SUCCEEDED");

        var failed = new cn.binarywang.wx.miniapp.bean.xpay.WxMaXPayQueryOrderResponse();
        failed.setOrder(orderInfo(cn.binarywang.wx.miniapp.constant.WxMaConstants.XPayOrderStatus.REFUND_FAILED));
        when(xPayService.queryOrder(any(), any())).thenReturn(failed);
        assertThat(adapter.queryRefund("R1001", "refund-9").getState()).isEqualTo("FAILED");

        var paid = new cn.binarywang.wx.miniapp.bean.xpay.WxMaXPayQueryOrderResponse();
        paid.setOrder(orderInfo(cn.binarywang.wx.miniapp.constant.WxMaConstants.XPayOrderStatus.PAID));
        when(xPayService.queryOrder(any(), any())).thenReturn(paid);
        assertThat(adapter.queryRefund("R1001", "refund-9").getState()).isEqualTo("NOT_FOUND");
    }
}
