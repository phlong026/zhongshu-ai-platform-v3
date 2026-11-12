package cn.iocoder.yudao.server.split;

import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.framework.web.core.handler.GlobalResponseBodyHandler;
import cn.iocoder.yudao.framework.web.core.util.WebFrameworkUtils;
import cn.iocoder.yudao.module.commerce.controller.admin.RechargeOrderAdminController;
import cn.iocoder.yudao.module.commerce.controller.admin.RechargePlanAdminController;
import cn.iocoder.yudao.module.commerce.controller.app.AppPointController;
import cn.iocoder.yudao.module.commerce.controller.app.AppRechargeOrderController;
import cn.iocoder.yudao.module.commerce.controller.notify.WechatPayNotifyController;
import cn.iocoder.yudao.module.commerce.controller.notify.XpayNotifyController;
import cn.iocoder.yudao.module.commerce.payment.PaymentFactValidator;
import cn.iocoder.yudao.module.commerce.payment.PaymentPort;
import cn.iocoder.yudao.module.commerce.payment.RechargePaymentService;
import cn.iocoder.yudao.module.commerce.points.PointAccountService;
import cn.iocoder.yudao.module.infra.zhongshu.api.AccountStatePort;
import cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort;
import cn.iocoder.yudao.module.infra.zhongshu.api.PointLedgerPort;
import cn.iocoder.yudao.module.infra.zhongshu.event.ReliableEventPort;
import cn.iocoder.yudao.module.identity.account.JdbcAccountStateAdapter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.testcontainers.containers.PostgreSQLContainer;

import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static cn.iocoder.yudao.server.split.LegacyPlatformWritePort.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * G2h commerce-payment：真实 MVC + 真实 PG（identity/commerce 源码迁移）验证支付链 9 写入口
 * 接入共用停写门禁（writer=commerce-payment）。P2/P3/P4 渠道回调为 CHANNEL_ACK 证据模式
 * （CommonResult判别器唯一逃逸证据；任意状态码有意ACK释放+非2xx warn，c31纠正版）。
 * commerce 模块生产代码零改动；新B platform-wallet 不在停写面。
 */
class LegacyPlatformPaymentInterceptorTest {
    private static final String SCOPE = "e".repeat(64);
    private static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final long USER_ID = 7777L;
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();

    private final ApiErrorLogCommonApi errorLogs = mock(ApiErrorLogCommonApi.class);
    private final PaymentPort paymentPort = mock(PaymentPort.class);
    private final PointLedgerPort ledgerPort = mock(PointLedgerPort.class);
    private final ReliableEventPort reliableEventPort = mock(ReliableEventPort.class);
    private final PaymentFactValidator factValidator = mock(PaymentFactValidator.class);
    private JdbcTemplate observer;
    private LegacyPlatformWriteGate gate;
    private LegacyPlatformWalletSessionInterceptorTest.MemoryStore store;
    private RechargePaymentService payments;
    private MockMvc orderMvc;
    private MockMvc wechatMvc;
    private MockMvc xpayMvc;
    private MockMvc adminOrderMvc;
    private MockMvc adminPlanMvc;
    private final AtomicInteger commitsWithPermit = new AtomicInteger();
    private volatile CountDownLatch commitEntered;
    private volatile CountDownLatch commitRelease;

    @BeforeAll static void startDatabase() {
        PG.start();
        var dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        // 版本序逐字执行：identity 101 → commerce 301..310（含 payment_notification_inbox/
        // payment_transaction/recharge_plan/recharge_order 全表；310与V20261003.310归一化元组不同无冲突）。
        var files = new java.util.TreeMap<String, org.springframework.core.io.Resource>();
        for (var path : List.of(
                "db/migration/identity/V20260905.101__p2a_identity_access.sql",
                "db/migration/commerce/V20260905.301__p4a_pricing_points.sql",
                "db/migration/commerce/V20260905.302__p8a_payment.sql",
                "db/migration/commerce/V20260911.301__p8b_payment_anomaly.sql",
                "db/migration/commerce/V20260911.303__t13_24_prepay_params.sql",
                "db/migration/commerce/V20260913.304__payment_recovery_leases.sql",
                "db/migration/commerce/V20260929.305__generation_price_resolution.sql",
                "db/migration/commerce/V20260930.306__usage_point_pricing.sql",
                "db/migration/commerce/V20261003.307__refund_recovery_identity.sql",
                "db/migration/commerce/V20261003.308__prompt_reservation.sql",
                "db/migration/commerce/V20261003.309__prompt_call_receipt.sql",
                "db/migration/commerce/V20261003.310__manual_adjustment_request_key.sql",
                "db/migration/commerce/V20261005.310__xpay_virtual_payment.sql")) {
            files.put(path, new ClassPathResource(path));
        }
        new ResourceDatabasePopulator(files.values().toArray(new org.springframework.core.io.Resource[0]))
                .execute(dataSource);
        new WebFrameworkUtils(new WebProperties());
    }

    @AfterAll static void stopDatabase() { PG.stop(); }

    @BeforeEach void fixture() {
        SecurityContextHolderClear();
        var dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        observer = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        observer.execute("TRUNCATE design_point_account, design_point_ledger, recharge_plan, recharge_order, "
                + "payment_notification_inbox, payment_transaction, account, refund_order");
        observer.update("INSERT INTO account (id, status) VALUES (?, 'ACTIVE')", USER_ID);
        observer.update("INSERT INTO recharge_plan (id, name, amount_cents, base_points, bonus_points) "
                + "VALUES (9001, 'probe-plan', 1000, 100, 0)");
        store = new LegacyPlatformWalletSessionInterceptorTest.MemoryStore();
        gate = new LegacyPlatformWriteGate("g2h", SCOPE, Set.of(), Set.of("commerce-payment"), store);
        gate.apply(new Control(1, State.OPEN, SCOPE), FreezeEvidence.missing());
        commitsWithPermit.set(0);
        commitEntered = null;
        commitRelease = null;
        reset(paymentPort);
        when(paymentPort.channel()).thenReturn("WECHAT");
        when(paymentPort.merchantId()).thenReturn("probe-merchant");
        when(paymentPort.createPrepay(any(), anyLong(), any(), any(), any()))
                .thenReturn(new PaymentPort.PrepayResult("probe-prepay-id", Map.of(
                        "appId", "wx-app", "timeStamp", "1", "nonceStr", "n",
                        "package", "prepay_id=probe", "signType", "RSA", "paySign", "s")));

        var transactions = counting(new DataSourceTransactionManager(dataSource));
        var points = new PointAccountService(dataSource, transactions);
        AccountStatePort guard = new JdbcAccountStateAdapter(dataSource);
        ReflectionTestUtils.setField(points, "accountStatePort", guard);
        payments = new RechargePaymentService(dataSource, transactions, paymentPort,
                ledgerPort, points, reliableEventPort, factValidator);

        var orderController = new AppRechargeOrderController();
        ReflectionTestUtils.setField(orderController, "rechargePaymentService", payments);
        IdentitySessionPort sessionStub = token -> Optional.of(
                new IdentitySessionPort.SessionContext(USER_ID, "wx-app", "wallet-openid", false));
        ReflectionTestUtils.setField(orderController, "identitySessionPort", sessionStub);
        ReflectionTestUtils.setField(orderController, "rechargeEnabled", true);
        // AppPointController 注册进 orderMvc：point-ledger GET 为非目标读直通用例的载体（纯读）。
        var pointController = new AppPointController();
        ReflectionTestUtils.setField(pointController, "pointAccountService", points);
        ReflectionTestUtils.setField(pointController, "identitySessionPort", sessionStub);
        ReflectionTestUtils.setField(pointController, "jdbcTemplate", new JdbcTemplate(dataSource));
        var wechat = wechatController();
        var xpay = xpayController();
        var orderAdmin = adminController();
        var planAdmin = planAdminController();
        // admin 与 app/notify 控制器存在同路由 GET，生产靠 /admin-api 与 /app-api 前缀分离；
        // standalone 无前缀，拆多 MockMvc 规避歧义映射。
        orderMvc = builder(orderController, pointController);
        wechatMvc = builder(wechat);
        xpayMvc = builder(xpay);
        adminOrderMvc = builder(orderAdmin);
        adminPlanMvc = builder(planAdmin);
    }

    private MockMvc builder(Object... controllers) {
        var setup = MockMvcBuilders.standaloneSetup(controllers)
                .setControllerAdvice(new Object[]{new GlobalExceptionHandler("g2h", errorLogs),
                        new GlobalResponseBodyHandler()})
                .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON))
                .addFilters(new cn.iocoder.yudao.framework.web.core.filter.CacheRequestBodyFilter())
                .addInterceptors(new LegacyPlatformHttpWriteInterceptor(gate, true));
        return setup.build();
    }

    private XpayNotifyController xpayController() {
        var xpay = new XpayNotifyController();
        ReflectionTestUtils.setField(xpay, "rechargePaymentService", payments);
        ReflectionTestUtils.setField(xpay, "pushToken", "probe-push-token");
        return xpay;
    }

    private RechargePlanAdminController planAdminController() {
        var planAdmin = new RechargePlanAdminController(payments);
        ReflectionTestUtils.setField(planAdmin, "jdbc",
                new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())));
        return planAdmin;
    }

    private void SecurityContextHolderClear() { }

    /** 真实事务管理器代理：物理提交前可挂门闩；commit 按 pending 非空计数（G2d/E 口径）。 */
    private PlatformTransactionManager counting(PlatformTransactionManager delegate) {
        return (PlatformTransactionManager) java.lang.reflect.Proxy.newProxyInstance(
                PlatformTransactionManager.class.getClassLoader(), new Class<?>[]{PlatformTransactionManager.class},
                (proxy, method, args) -> {
                    boolean physicalBoundary = method.getName().equals("commit")
                            && args != null && args.length == 1
                            && args[0] instanceof org.springframework.transaction.TransactionStatus status
                            && status.isNewTransaction();
                    if (commitEntered != null && physicalBoundary) {
                        commitEntered.countDown();
                        await(commitRelease);
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    } finally {
                        boolean inFlight = !gate.snapshot().checkpoint().pending().isEmpty();
                        if (physicalBoundary && inFlight) commitsWithPermit.incrementAndGet();
                    }
                });
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("synthetic commit timeout");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    private String mintOrder() throws Exception {
        var result = orderMvc.perform(post("/design/v1/recharge-orders?planId=9001")
                .header("Authorization", "Bearer probe-token")
                .header("Idempotency-Key", "mint-" + System.nanoTime())
                .header("X-WX-Login-Code", "probe-login")).andReturn();
        assertEquals(0, JSON.readTree(result.getResponse().getContentAsString()).get("code").intValue());
        return observer.queryForObject(
                "SELECT order_no FROM recharge_order WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                String.class, USER_ID);
    }

    private int code(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString()).get("code").intValue();
    }

    /** xpay 应答为字符串编码的内嵌JSON（ResponseEntity<String>经Jackson二次序列化），两层解析取ErrCode。 */
    private int xpayErrCode(MvcResult result) throws Exception {
        var outer = JSON.readTree(result.getResponse().getContentAsString());
        var node = outer.has("ErrCode") ? outer.get("ErrCode") : JSON.readTree(outer.asText()).get("ErrCode");
        return node.intValue();
    }

    private int rows(String table) {
        return observer.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private void unknownPending(String operation) {
        assertEquals(1, gate.snapshot().checkpoint().pending().size());
        for (Work work : gate.snapshot().checkpoint().pending().values()) {
            assertEquals("commerce-payment", work.writer());
            assertEquals(operation, work.operation());
            assertTrue(work.unknown());
        }
    }

    // ---------- DRAINING：9入口逐一拒绝，全部表零写 ----------

    @Test void drainingRejectsAllNinePaymentEntriesWithoutAnyWrite() throws Exception {
        gate.apply(new Control(2, State.DRAINING, SCOPE), FreezeEvidence.missing());
        assertEquals(503, code(orderMvc.perform(post("/design/v1/recharge-orders?planId=9001")
                .header("Authorization", "Bearer bogus")).andReturn()));
        assertEquals(503, code(wechatMvc.perform(post("/design/v1/payments/wechat/notify")
                .contentType("application/json").content("{}")).andReturn()));
        assertEquals(503, code(wechatMvc.perform(post("/design/v1/payments/wechat/refund-notify")
                .contentType("application/json").content("{}")).andReturn()));
        assertEquals(503, code(xpayMvc.perform(post("/design/v1/payments/xpay/push?signature=s&timestamp=t&nonce=n")
                .contentType("application/json").content("{\"Event\":\"xpay_goods_deliver_notify\"}")).andReturn()));
        assertEquals(503, code(adminOrderMvc.perform(post("/design/v1/recharge-orders/9001/refund-requests")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{}")).andReturn()));
        assertEquals(503, code(adminOrderMvc.perform(post("/design/v1/recharge-orders/9001/reconciliation")).andReturn()));
        assertEquals(503, code(adminOrderMvc.perform(post("/design/v1/recharge-orders/9001/reconciliation-actions")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"action\":\"QUERY_CHANNEL\"}")).andReturn()));
        assertEquals(503, code(adminPlanMvc.perform(post("/design/v1/recharge-plans")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"name\":\"p\",\"amountCents\":100,\"basePoints\":10}")).andReturn()));
        assertEquals(503, code(adminPlanMvc.perform(patch("/design/v1/recharge-plans/9001")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"enabled\":false}")).andReturn()));
        assertEquals(0, rows("recharge_order"));
        assertEquals(0, rows("payment_notification_inbox"));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    // ---------- P1 建单+预下单（probe 形态） ----------

    @Test void openCreateOrderWritesOrderAndPrepayWithoutPermit() throws Exception {
        assertEquals(0, rows("recharge_order"));
        var result = orderMvc.perform(post("/design/v1/recharge-orders?planId=9001")
                .header("Authorization", "Bearer probe-token")
                .header("Idempotency-Key", "probe-idem-1")
                .header("X-WX-Login-Code", "probe-login")).andReturn();
        assertEquals(0, code(result));
        assertEquals(1, rows("recharge_order"));
        var order = observer.queryForMap("SELECT * FROM recharge_order");
        assertEquals("PENDING", order.get("payment_state"));
        assertNotNull(order.get("plan_snapshot"));
        assertNotNull(order.get("prepay_params"));
        assertEquals(1000L, ((Number) order.get("amount_cents")).longValue());
        // P1 为多事务链（外层建单 + ensurePrepay 租约/参数各自提交），物理commit数>=1即证permit在途。
        assertTrue(commitsWithPermit.get() >= 1);
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
        orderMvc.perform(post("/design/v1/recharge-orders?planId=9001")
                .header("Authorization", "Bearer probe-token")
                .header("Idempotency-Key", "probe-idem-1")
                .header("X-WX-Login-Code", "probe-login")).andReturn();
        assertEquals(1, rows("recharge_order"));
    }

    // ---------- P2 微信通知（CHANNEL_ACK 双型 + intake 隔离） ----------

    @Test void wechatNotifyUnverifiedWithHeadersIsRefusedWithZeroWrites() throws Exception {
        when(paymentPort.verifyNotification(any(), any())).thenReturn(false);
        var missing = wechatMvc.perform(post("/design/v1/payments/wechat/notify")
                .contentType("application/json").content("{}")).andReturn();
        assertEquals("FAIL", JSON.readTree(missing.getResponse().getContentAsString()).get("code").asText());
        var withHeaders = wechatMvc.perform(post("/design/v1/payments/wechat/notify")
                .contentType("application/json")
                .header("Wechatpay-Timestamp", "1").header("Wechatpay-Nonce", "n")
                .header("Wechatpay-Signature", "bad").header("Wechatpay-Serial", "s")
                .content("{}")).andReturn();
        assertEquals("FAIL", JSON.readTree(withHeaders.getResponse().getContentAsString()).get("code").asText());
        assertEquals(0, rows("payment_notification_inbox"));
        verify(paymentPort, atLeastOnce()).verifyNotification(any(), any());
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void wechatNotifyUnknownOrderPersistsInboxAndReleasesOnInControllerAck() throws Exception {
        // 纠正判据形态：Phase2 失败被控制器内 catch(RuntimeException) 转 500 FAIL（有意ACK=渠道重试），
        // inbox Phase1 独立事务已持久化——释放+inbox保留（补偿=渠道重推+inbox扫描）。
        when(paymentPort.verifyNotification(any(), any())).thenReturn(true);
        when(paymentPort.parseNotification(any(), any())).thenReturn(new PaymentPort.NormalizedNotification(
                "evt-probe-1", "R-NO-SUCH-ORDER", "ch-txn-1", 500, Instant.now()));
        var result = wechatMvc.perform(post("/design/v1/payments/wechat/notify")
                .contentType("application/json")
                .header("Wechatpay-Timestamp", "1").header("Wechatpay-Nonce", "n")
                .header("Wechatpay-Signature", "bad").header("Wechatpay-Serial", "s")
                .content("{}")).andReturn();
        assertEquals("FAIL", JSON.readTree(result.getResponse().getContentAsString()).get("code").asText());
        assertEquals(1, rows("payment_notification_inbox"));
        assertEquals(0, rows("recharge_order"));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void wechatNotifySerializationFailureRetainsUnknown() throws Exception {
        // 逃逸面代表（确定性路径）：verify=false → 业务异常 400 FAIL 体 {"code":"FAIL"} →
        // converter 写 "FAIL" 即抛 → 异常逃出控制器 → advice 产 CommonResult 500 →
        // getCommonResult 非空 → UNKNOWN 保留（宁多不漏）。真订单 Phase2 失败被控制器内
        // catch(RuntimeException) 转有意500 ACK（渠道重试）——不产生逃逸（c31纠正判据，见合同v0.1）。
        when(paymentPort.verifyNotification(any(), any())).thenReturn(false);
        var module = new com.fasterxml.jackson.databind.module.SimpleModule();
        module.addSerializer(String.class, new com.fasterxml.jackson.databind.JsonSerializer<String>() {
            @Override public void serialize(String value, com.fasterxml.jackson.core.JsonGenerator output,
                                            com.fasterxml.jackson.databind.SerializerProvider provider)
                    throws java.io.IOException {
                if ("FAIL".equals(value)) throw new java.io.IOException("synthetic serialization failure");
                output.writeString(value);
            }
        });
        var escapeMvc = MockMvcBuilders.standaloneSetup(wechatController())
                .setControllerAdvice(new Object[]{new GlobalExceptionHandler("g2h", errorLogs)})
                .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON.copy().registerModule(module)))
                .addInterceptors(new LegacyPlatformHttpWriteInterceptor(gate, true))
                .build();
        var result = escapeMvc.perform(post("/design/v1/payments/wechat/notify")
                .contentType("application/json")
                .header("Wechatpay-Timestamp", "1").header("Wechatpay-Nonce", "n")
                .header("Wechatpay-Signature", "s").header("Wechatpay-Serial", "s")
                .content("{}")).andReturn();
        assertEquals(500, code(result));
        assertEquals(0, rows("payment_notification_inbox"));
        unknownPending("payment-notify");
    }

    // ---------- P3 退款通知（intake 代表） ----------

    @Test void refundNotifyVerifiedIntakePersistsInboxAndReleases() throws Exception {
        when(paymentPort.verifyNotification(any(), any())).thenReturn(true);
        when(paymentPort.parseRefundNotification(any(), any())).thenReturn(
                new PaymentPort.NormalizedRefundNotification("evt-refund-1", "R-NO-SUCH", "refund-x",
                        "SUCCEEDED", 500, Instant.now()));
        var result = wechatMvc.perform(post("/design/v1/payments/wechat/refund-notify")
                .contentType("application/json")
                .header("Wechatpay-Timestamp", "1").header("Wechatpay-Nonce", "n")
                .header("Wechatpay-Signature", "s").header("Wechatpay-Serial", "s")
                .content("{}")).andReturn();
        assertNotNull(JSON.readTree(result.getResponse().getContentAsString()).get("code"));
        assertTrue(rows("payment_notification_inbox") >= 1);
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    // ---------- P4 xpay 推送（500-ACK 持久化状态钉住 + 未消费事件直ACK） ----------

    @Test void xpayPushUnknownEventDirectAckWithZeroWrites() throws Exception {
        var result = xpayMvc.perform(post("/design/v1/payments/xpay/push?signature=s&timestamp=t&nonce=n")
                .contentType("application/json").content("{\"Event\":\"complaint_event\"}")).andReturn();
        assertEquals(0, xpayErrCode(result));
        assertEquals(0, rows("payment_notification_inbox"));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void xpayPushVerifiedIntakePersistsInboxAnd500AckReleasesOnPhase2Failure() throws Exception {
        // c31 ①：钉住 500-ACK 路径持久化状态——inbox Phase1 独立事务行存在（订单不存在 Phase2 失败被隔离），
        // 释放的确定性前提（任意状态码ACK）由此锁定。
        when(paymentPort.verifyNotification(any(), any())).thenReturn(true);
        when(paymentPort.parseNotification(any(), any())).thenReturn(new PaymentPort.NormalizedNotification(
                "evt-xpay-1", "R-NO-SUCH-ORDER", "ch-xpay-1", 500, Instant.now()));
        var result = xpayMvc.perform(post("/design/v1/payments/xpay/push?signature=s&timestamp=t&nonce=n")
                .contentType("application/json").content("{\"Event\":\"xpay_goods_deliver_notify\"}")).andReturn();
        assertEquals(1, xpayErrCode(result));
        assertEquals(1, rows("payment_notification_inbox"));
        assertEquals(0, rows("recharge_order"));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void xpayPushSignatureWallRefusedWithZeroWrites() throws Exception {
        when(paymentPort.verifyNotification(any(), any())).thenReturn(false);
        var result = xpayMvc.perform(post("/design/v1/payments/xpay/push?signature=bad&timestamp=t&nonce=n")
                .contentType("application/json").content("{\"Event\":\"xpay_goods_deliver_notify\"}")).andReturn();
        assertEquals(1, xpayErrCode(result));
        assertEquals(0, rows("payment_notification_inbox"));
        verify(paymentPort, atLeastOnce()).verifyNotification(any(), any());
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    // ---------- P5-P9 管理面（handled UNKNOWN / 正常收口代表） ----------

    @Test void refundRequestForUnpaidOrderIsHandledRetainingUnknown() throws Exception {
        String orderNo = mintOrder();
        long orderId = observer.queryForObject(
                "SELECT id FROM recharge_order WHERE order_no = ?", Long.class, orderNo);
        var result = adminOrderMvc.perform(post("/design/v1/recharge-orders/" + orderId + "/refund-requests")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"x\"}")).andReturn();
        // 未支付订单退款受理：业务拒绝（handled ServiceException）→ UNKNOWN 保留。
        assertNotEquals(0, code(result));
        assertEquals(0, rows("refund_order"));
        unknownPending("refund-request");
    }

    @Test void openReconcileReportsStateAndReleases() throws Exception {
        String orderNo = mintOrder();
        long orderId = observer.queryForObject(
                "SELECT id FROM recharge_order WHERE order_no = ?", Long.class, orderNo);
        when(paymentPort.queryOrder(orderNo)).thenReturn(new PaymentPort.ChannelQueryResult(
                "PENDING", "ch-recon", 1000L));
        var result = adminOrderMvc.perform(post("/design/v1/recharge-orders/" + orderId + "/reconciliation")).andReturn();
        assertEquals(0, code(result));
        assertNotNull(JSON.readTree(result.getResponse().getContentAsString()).get("data"));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void openReconciliationActionExecutesQueryAndReleases() throws Exception {
        String orderNo = mintOrder();
        long orderId = observer.queryForObject(
                "SELECT id FROM recharge_order WHERE order_no = ?", Long.class, orderNo);
        when(paymentPort.queryOrder(orderNo)).thenReturn(new PaymentPort.ChannelQueryResult(
                "PENDING", "ch-recon", 1000L));
        var result = adminOrderMvc.perform(post("/design/v1/recharge-orders/" + orderId + "/reconciliation-actions")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"action\":\"QUERY_CHANNEL\"}")).andReturn();
        assertEquals(0, code(result));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void openCreatePlanWritesRealRow() throws Exception {
        assertEquals(1, rows("recharge_plan"));
        var result = adminPlanMvc.perform(post("/design/v1/recharge-plans")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"name\":\"probe-plan-2\",\"amountCents\":500,\"basePoints\":50,"
                        + "\"deliveryMode\":\"INLINE\",\"validityDays\":30}")).andReturn();
        assertEquals(0, code(result));
        assertEquals(2, rows("recharge_plan"));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    @Test void openUpdatePlanDisablesRealRow() throws Exception {
        var result = adminPlanMvc.perform(patch("/design/v1/recharge-plans/9001")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}")).andReturn();
        assertEquals(0, code(result));
        assertEquals(Boolean.FALSE, observer.queryForObject(
                "SELECT enabled FROM recharge_plan WHERE id = 9001", Boolean.class));
        assertTrue(gate.snapshot().checkpoint().pending().isEmpty());
    }

    // ---------- 白名单合同：9入口精确 writer/operation ----------

    @Test void wiredPaymentHandlersEnterGateWithExactWriterAndOperation() throws Exception {
        var port = mock(LegacyPlatformWritePort.class);
        var permit = mock(Permit.class);
        when(port.enter(any(), any(), any())).thenReturn(permit);
        var selected = new LegacyPlatformHttpWriteInterceptor(port, true);
        var orderController = new AppRechargeOrderController();
        var wechat = new WechatPayNotifyController();
        var xpay = new XpayNotifyController();
        var orderAdmin = new RechargeOrderAdminController(payments);
        var planAdmin = new RechargePlanAdminController(payments);
        var calls = List.of(
                new HandlerCall(orderController, AppRechargeOrderController.class.getDeclaredMethod("createOrder",
                        String.class, String.class, String.class, String.class)),
                new HandlerCall(wechat, WechatPayNotifyController.class.getDeclaredMethod("handlePaymentNotify",
                        HttpServletRequest.class)),
                new HandlerCall(wechat, WechatPayNotifyController.class.getDeclaredMethod("handleRefundNotify",
                        HttpServletRequest.class)),
                new HandlerCall(xpay, XpayNotifyController.class.getDeclaredMethod("handlePush",
                        HttpServletRequest.class)),
                new HandlerCall(orderAdmin, RechargeOrderAdminController.class.getDeclaredMethod("createRefundRequest",
                        String.class, Map.class)),
                new HandlerCall(orderAdmin, RechargeOrderAdminController.class.getDeclaredMethod("reconcileOrder",
                        String.class)),
                new HandlerCall(orderAdmin, RechargeOrderAdminController.class.getDeclaredMethod("reconciliationAction",
                        String.class, Map.class)),
                new HandlerCall(planAdmin, RechargePlanAdminController.class.getDeclaredMethod("createPlan", Map.class)),
                new HandlerCall(planAdmin, RechargePlanAdminController.class.getDeclaredMethod("updatePlan",
                        String.class, Map.class)));
        for (var call : calls) {
            var handler = new HandlerMethod(call.controller(), call.method());
            assertTrue(selected.preHandle(new org.springframework.mock.web.MockHttpServletRequest(),
                    new org.springframework.mock.web.MockHttpServletResponse(), handler), call.method().getName());
        }
        var captor = ArgumentCaptor.forClass(String.class);
        verify(port, times(9)).enter(eq(Owner.B_LEGACY), eq("commerce-payment"), captor.capture());
        assertEquals(Set.of("recharge-order-create", "payment-notify", "refund-notify", "xpay-push",
                "refund-request", "order-reconcile", "reconciliation-action", "recharge-plan-create",
                "recharge-plan-update"), Set.copyOf(captor.getAllValues()));
    }

    private record HandlerCall(Object controller, java.lang.reflect.Method method) { }

    // ---------- 非目标读直通 ----------

    @Test void nonTargetLedgerGetDoesNotWaitForPaymentCheckpoint() throws Exception {
        store.entered = new CountDownLatch(1);
        store.release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var blocked = pool.submit(() -> gate.enter(Owner.B_LEGACY, "commerce-payment", "synthetic-block"));
            assertTrue(store.entered.await(3, TimeUnit.SECONDS));
            var response = pool.submit(() -> orderMvc.perform(get("/design/v1/point-ledger?pageNo=1"))
                    .andReturn()).get(1, TimeUnit.SECONDS);
            assertEquals(0, code(response));
            store.release.countDown();
            blocked.get(3, TimeUnit.SECONDS).close();
        } finally {
            store.release.countDown();
            pool.shutdown();
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow();
                fail("synthetic request did not terminate");
            }
        }
    }

    // ---------- 装配矩阵（P2 CHANNEL_ACK + P5 admin 代表） ----------

    @Test void bridgeFalseStillGuardsPaymentHandlers() throws Exception {
        configurationCase(Map.of("platform.legacy-b-write-control-directory", "synthetic-unused",
                "platform.legacy-design-bridge.enabled", "false"), true);
    }

    @Test void installedDefaultConfigurationGuardsPaymentHandlers() throws Exception {
        configurationCase(Map.of("platform.legacy-b-write-control-directory", "synthetic-unused"), true);
    }

    @Test void notInstalledLeavesPaymentBehaviorWithoutFreezeEvidence() throws Exception {
        configurationCase(Map.of(), false);
    }

    private void configurationCase(Map<String, Object> properties, boolean installed) throws Exception {
        gate.apply(new Control(2, State.DRAINING, SCOPE), FreezeEvidence.missing());
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(
                    new org.springframework.core.env.MapPropertySource("synthetic", properties));
            context.registerBean(LegacyPlatformWriteGate.class, () -> gate);
            context.register(LegacyPlatformHttpWriteWebConfiguration.class);
            context.refresh();
            var registry = new LegacyPlatformWalletSessionInterceptorTest.TestRegistry();
            context.getBeansOfType(WebMvcConfigurer.class).values().forEach(value -> value.addInterceptors(registry));
            assertEquals(installed ? 1 : 0, registry.handlers().size());
            var selected = registry.handlers().isEmpty() ? null : (HandlerInterceptor) registry.handlers().get(0);
            HandlerInterceptor[] interceptors = selected == null ? new HandlerInterceptor[0] : new HandlerInterceptor[]{selected};
            var wechatConfigured = MockMvcBuilders.standaloneSetup(wechatController())
                    .setControllerAdvice(new Object[]{new GlobalExceptionHandler("g2h", errorLogs)})
                    .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON))
                    .addInterceptors(interceptors)
                    .build();
            var planAdminConfigured = MockMvcBuilders.standaloneSetup(planAdminController())
                    .setControllerAdvice(new Object[]{new GlobalExceptionHandler("g2h", errorLogs)})
                    .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON))
                    .addInterceptors(interceptors)
                    .build();
            // bridge=false 只豁免钱包会话；commerce-payment 入口无论开关一律受控。
            // installed：DRAINING 503（CommonResult code 503）；notInstalled：无门禁→业务层
            // 验签墙 400 FAIL 体（{"code":"FAIL"}，notify控制器自有应答形态）。
            var notifyResult = wechatConfigured.perform(post("/design/v1/payments/wechat/notify")
                    .contentType("application/json").content("{}")).andReturn();
            if (installed) {
                assertEquals(503, code(notifyResult));
            } else {
                assertEquals("FAIL", JSON.readTree(notifyResult.getResponse().getContentAsString())
                        .get("code").asText());
            }
            assertEquals(installed ? 503 : 0, code(planAdminConfigured.perform(
                    post("/design/v1/recharge-plans").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"p\",\"amountCents\":100,\"basePoints\":10}")).andReturn()));
            assertEquals(installed ? 1 : 2, rows("recharge_plan"));
            assertThrows(IllegalStateException.class,
                    () -> gate.apply(new Control(3, State.FROZEN, SCOPE), FreezeEvidence.missing()));
        }
    }

    private WechatPayNotifyController wechatController() {
        var wechat = new WechatPayNotifyController();
        ReflectionTestUtils.setField(wechat, "rechargePaymentService", payments);
        return wechat;
    }

    private RechargeOrderAdminController adminController() {
        return new RechargeOrderAdminController(payments);
    }
}
