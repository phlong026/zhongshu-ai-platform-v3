package cn.iocoder.yudao.module.platform.wallet;

import cn.binarywang.wx.miniapp.api.WxMaService;
import cn.binarywang.wx.miniapp.api.WxMaXPayService;
import cn.binarywang.wx.miniapp.bean.xpay.WxMaXPayQueryOrderResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class VirtualPaymentAdapterSafetyTest {
    private final WxMaService wechat = mock(WxMaService.class);
    private final WxMaXPayService xpay = mock(WxMaXPayService.class);
    private final WechatXPayProperties properties = new WechatXPayProperties();
    private final PaymentPort.OrderIdentity order = new PaymentPort.OrderIdentity(
            1, 2, 3, "PFW3", "XPAY", "test-app", "test-offer", 0, "test-payer", 100);
    private VirtualPaymentAdapter adapter;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setAppid("test-app");
        properties.setOfferId("test-offer");
        adapter = new VirtualPaymentAdapter(properties, wechat, xpay);
    }

    @Test
    void unverifiedPaidFeeUnitNeverBecomesAnAmountFact() throws Exception {
        givenOrder(0, 2);
        var fact = adapter.queryOrder(order);
        assertThat(fact.amountCents()).isNull();
        assertThat(fact.amountUnit()).isEqualTo("UNVERIFIED");
        assertThatThrownBy(() -> new PaymentFactValidator().validate(order, fact))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void channelTransactionUsesWxpayIdAndNeverInternalOrMerchantId() throws Exception {
        givenOrder(0, 2);
        assertThat(adapter.queryOrder(order).channelTransactionId()).isEqualTo("payment-transaction");
    }

    @Test
    void refundAndUnknownTypesNeverBecomePaymentSuccess() throws Exception {
        for (int type : new int[]{1, 8, 9}) {
            givenOrder(type, 2);
            assertThat(adapter.queryOrder(order).state()).isNotEqualTo("SUCCEEDED");
        }
    }

    @Test
    void merchantDebitAndRefundFailureNeverBecomePaymentSuccess() throws Exception {
        for (int status : new int[]{5, 7, 8}) {
            givenOrder(0, status);
            assertThat(adapter.queryOrder(order).state()).isNotEqualTo("SUCCEEDED");
        }
    }

    @Test
    void refundNotificationUsesMchOrderIdAndDoesNotQueryBeforePersistence() {
        var body = "{\"Event\":\"xpay_refund_notify\",\"MchOrderId\":\"PFW3\",\"MchRefundId\":\"PFR4\",\"WxRefundId\":\"wx-refund\",\"RefundFee\":100,\"RetCode\":0}";
        var hint = adapter.parseNotification(Map.of(), body.getBytes(StandardCharsets.UTF_8));
        assertThat(hint.orderNo()).isEqualTo("PFW3");
        assertThat(hint.refundNo()).isEqualTo("PFR4");
        verifyNoInteractions(xpay, wechat);
    }

    @Test
    void encryptedNotificationIsRejectedUntilAppidCheckedDecryptIsImplemented() {
        var body = "{\"Encrypt\":\"ciphertext\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(adapter.verifyNotification(Map.of("encrypt_type", "aes"), body)).isFalse();
        assertThatThrownBy(() -> adapter.parseNotification(Map.of(), body))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void xmlExternalEntitiesAreRejectedWithoutLeakingPayload() {
        var body = "<!DOCTYPE xml [<!ENTITY secret SYSTEM 'file:///never-read'>]><xml><Event>&secret;</Event></xml>";
        assertThatThrownBy(() -> adapter.parseNotification(Map.of(), body.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("never-read");
        verifyNoInteractions(xpay, wechat);
    }

    @Test
    void anotherChannelConfigurationIsRejectedBeforeIo() {
        properties.setAppid("changed-app");
        assertThatThrownBy(() -> adapter.queryOrder(order)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(xpay, wechat);
    }

    @Test
    void prepayConsumesOneFreshCodeAndDoesNotExposeSessionKey() throws Exception {
        var users = mock(cn.binarywang.wx.miniapp.api.WxMaUserService.class);
        when(wechat.getUserService()).thenReturn(users);
        var session = new cn.binarywang.wx.miniapp.bean.WxMaJscode2SessionResult();
        session.setOpenid("test-payer");
        session.setSessionKey("fixture-session-secret");
        when(users.getSessionInfo("fixture-code")).thenReturn(session);
        properties.setAppKey("fixture-app-signing-key");
        var params = adapter.createPrepay(order, "fixture-product", "fixture-code").callParams();
        assertThat(params).containsOnlyKeys("signData", "mode", "paySig", "signature");
        assertThat(params.toString()).doesNotContain("fixture-session-secret", "fixture-code", "fixture-app-signing-key");
        assertThat(params.get("signData")).contains("\"goodsPrice\":100", "\"outTradeNo\":\"PFW3\"");
        verify(users, times(1)).getSessionInfo("fixture-code");
    }

    @Test
    void prepayRejectsAnotherPayerAndRedactsTheException() throws Exception {
        var users = mock(cn.binarywang.wx.miniapp.api.WxMaUserService.class);
        when(wechat.getUserService()).thenReturn(users);
        var session = new cn.binarywang.wx.miniapp.bean.WxMaJscode2SessionResult();
        session.setOpenid("other-payer");
        session.setSessionKey("fixture-secret");
        when(users.getSessionInfo("fixture-code")).thenReturn(session);
        properties.setAppKey("fixture-app-key");
        assertThatThrownBy(() -> adapter.createPrepay(order, "product", "fixture-code"))
                .isInstanceOf(IllegalStateException.class).hasNoCause().hasMessageNotContaining("fixture").hasMessageNotContaining("other-payer");
    }

    @Test
    void developerRefundGateMakesNoExternalRequest() {
        assertThatThrownBy(() -> adapter.requestRefund(new PaymentPort.RefundRequest(order, "PFR4", 100, 0, null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("disabled");
        verifyNoInteractions(wechat, xpay);
    }

    private void givenOrder(int type, int status) throws Exception {
        var info = new WxMaXPayQueryOrderResponse.OrderInfo();
        info.setOrderId("PFW3");
        info.setOrderType(type);
        info.setStatus(status);
        info.setEnvType(0L);
        info.setPaidFee(100L);
        info.setPaidTime(1700000000L);
        info.setWxpayOrderId("payment-transaction");
        info.setWxOrderId("internal-id");
        info.setChannelOrderId("merchant-id");
        var response = new WxMaXPayQueryOrderResponse();
        response.setOrder(info);
        when(xpay.queryOrder(any(), any())).thenReturn(response);
    }
}
