package cn.iocoder.yudao.module.platform.wallet;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import cn.iocoder.yudao.module.platform.test.PlatformPgIntegrationTest;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@TestPropertySource(properties = "platform.test.extra-flyway-locations=,classpath:db/migration/platform-wallet")
class WalletAccountingIntegrationTest extends PlatformPgIntegrationTest {

    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private final ThreadLocal<PostSnapshot> current = new ThreadLocal<>();
    private StaffPostService posts;
    private WalletScope scope;
    private PointAccountService points;
    private WalletChargeService charges;
    private PostSnapshot post;
    private long tenant;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(transactionManager);
        tenant = IdWorker.getId();
        TenantContextHolder.setTenantId(tenant);
        jdbc.execute("CREATE TABLE IF NOT EXISTS system_users (id BIGINT PRIMARY KEY, username VARCHAR(64), nickname VARCHAR(64), tenant_id BIGINT NOT NULL, mobile VARCHAR(32), status SMALLINT DEFAULT 0, deleted SMALLINT DEFAULT 0)");
        post = createPost();
        current.set(post);
        CurrentPost currentPost = () -> Optional.ofNullable(current.get());
        posts = mock(StaffPostService.class);
        when(posts.findActivePost(anyLong(), anyLong())).thenAnswer(invocation -> Optional.ofNullable(current.get()));
        scope = new WalletScope(dataSource, currentPost, posts);
        points = new PointAccountService(dataSource);
        charges = new WalletChargeService(dataSource, transactionManager, scope, points);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        current.remove();
    }

    @Test
    void sameCreditKeyChecksWalletAmountActorAndRequestHash() {
        long ledger = credit("opening", 100);
        assertThat(credit("opening", 100)).isEqualTo(ledger);
        assertThatThrownBy(() -> credit("opening", 101)).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        current.set(createPostInOrg(post.orgId()));
        assertThatThrownBy(() -> credit("opening", 100)).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        current.set(createPost());
        assertThatThrownBy(() -> credit("opening", 100)).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        current.set(post);
        assertBalance(100, 0, 1);
    }

    @Test
    void chargeReserveAndReleaseAreIdempotentAndTerminalStateIsExclusive() {
        credit("opening", 100);
        var charge = charges.reserve("ASSET", "asset-1", 40, "reserve-1");
        assertThat(charges.reserve("ASSET", "asset-1", 40, "reserve-1").id()).isEqualTo(charge.id());
        assertBalance(60, 40, 1);
        charges.release(charge.id());
        charges.release(charge.id());
        assertThatThrownBy(() -> charges.consume(charge.id())).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertBalance(100, 0, 1);
    }

    @Test
    void successfulConsumeAppendsExactlyOneLedgerAndCannotBeReleased() {
        credit("opening", 100);
        var charge = charges.reserve("ASSET", "asset-2", 40, "reserve-2");
        charges.consume(charge.id());
        charges.consume(charge.id());
        assertThatThrownBy(() -> charges.release(charge.id())).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertBalance(60, 0, 2);
        assertThat(jdbc.queryForObject("SELECT sum(delta) FROM pf_point_ledger WHERE tenant_id = ?", Long.class, tenant))
                .isEqualTo(60);
    }

    @Test
    void rejectedChargeRollsBackItsBusinessRowAndBalance() {
        credit("opening", 10);
        assertThatThrownBy(() -> charges.reserve("ASSET", "expensive", 11, "insufficient"))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_wallet_charge WHERE tenant_id = ?", Long.class, tenant))
                .isZero();
        assertBalance(10, 0, 1);
    }

    @Test
    void businessAndRequestKeysCannotChangeAmountOrActor() {
        credit("opening", 100);
        var charge = charges.reserve("ASSET", "same-business", 10, "same-key");
        assertThatThrownBy(() -> charges.reserve("ASSET", "same-business", 11, "same-key"))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThatThrownBy(() -> charges.reserve("ASSET", "same-business", 10, "new-key"))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        current.set(createPostInOrg(post.orgId()));
        assertThatThrownBy(() -> charges.consume(charge.id())).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        current.set(post);
        assertBalance(90, 10, 1);
    }

    @Test
    void concurrentReserveHasOneBusinessRowAndOneBalanceTransfer() throws Exception {
        credit("opening", 100);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var operation = (java.util.concurrent.Callable<String>) () -> {
                TenantContextHolder.setTenantId(tenant);
                current.set(post);
                try {
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                    return charges.reserve("ASSET", "concurrent", 30, "concurrent-key").id();
                } finally {
                    TenantContextHolder.clear();
                    current.remove();
                }
            };
            var first = pool.submit(operation);
            var second = pool.submit(operation);
            start.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(second.get(15, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertBalance(70, 30, 1);
    }

    @Test
    void revokedPostCannotWriteEvenWhenCurrentPostWasCached() {
        credit("opening", 100);
        doThrow(new IllegalStateException("revoked")).when(posts).revalidateActivePost(post.userId(), post.postId());
        assertThatThrownBy(() -> credit("after-revoke", 10)).isInstanceOf(IllegalStateException.class);
        assertBalance(100, 0, 1);
    }

    @Test
    void databaseRevocationOverridesCachedPostAndUserSnapshots() {
        credit("opening", 100);
        jdbc.update("UPDATE pf_staff_post SET status = 'REVOKED', revoked_at = now() WHERE id = ?", post.postId());
        // currentPost 与 mock findActivePost 仍返回旧 ACTIVE 快照，必须由锁后数据库复验拦截。
        assertThatThrownBy(() -> credit("stale-post", 1))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        jdbc.update("UPDATE pf_staff_post SET status = 'ACTIVE', revoked_at = NULL WHERE id = ?", post.postId());
        jdbc.update("UPDATE system_users SET status = 1 WHERE id = ?", post.userId());
        assertThatThrownBy(() -> credit("stale-user", 1))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertBalance(100, 0, 1);
    }

    @Test
    void disabledWalletAndForeignTenantCannotWrite() {
        credit("opening", 100);
        TenantContextHolder.setTenantId(tenant + 1);
        assertThatThrownBy(() -> credit("foreign-tenant", 10))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        TenantContextHolder.setTenantId(tenant);
        jdbc.update("UPDATE pf_wallet SET status = 'DISABLED' WHERE org_id = ?", post.orgId());
        assertThatThrownBy(() -> credit("disabled-wallet", 10))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertBalance(100, 0, 1);
    }

    @Test
    void localResourceMappingIsStableTenantBoundAndNeverReactivated() {
        var mappings = new LocalStoreServiceAccountService(dataSource, transactionManager, scope);
        long resourceId = mappings.requireServiceAccount(post.orgId());
        assertThat(mappings.requireServiceAccount(post.orgId())).isEqualTo(resourceId);
        assertThatThrownBy(() -> mappings.requireServiceAccount(post.orgId() + 1))
                .isInstanceOf(cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort.ServiceAccountInactiveException.class);
        TenantContextHolder.setTenantId(tenant + 1);
        assertThatThrownBy(() -> mappings.requireServiceAccount(post.orgId()))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        TenantContextHolder.setTenantId(tenant);
        jdbc.update("UPDATE pf_org_service_account SET status = 'DISABLED' WHERE id = ?", resourceId);
        assertThatThrownBy(() -> mappings.requireServiceAccount(post.orgId()))
                .isInstanceOf(cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort.ServiceAccountInactiveException.class);
        assertThat(jdbc.queryForObject("SELECT id FROM pf_org_service_account WHERE org_id = ?", Long.class, post.orgId()))
                .isEqualTo(resourceId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_wallet WHERE org_id = ?", Long.class, post.orgId())).isZero();
    }

    @Test
    void queriesAreBoundedAndReturnStringIds() {
        credit("opening", 100);
        var queries = new WalletQueryService(dataSource, transactionManager, scope);
        assertThat(queries.account().orgId()).isEqualTo(Long.toString(post.orgId()));
        assertThat(queries.ledger(1, 1).getTotal()).isEqualTo(1);
        assertThat(queries.ledger(1, 1).getList().get(0).actorId()).isEqualTo(Long.toString(post.userId()));
        assertThat(queries.ledger(2, 1).getList()).isEmpty();
        assertThatThrownBy(() -> queries.ledger(0, 20))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThatThrownBy(() -> queries.ledger(1, 101))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        jdbc.update("INSERT INTO pf_recharge_plan (id, tenant_id, name, price_fen, base_points, status) VALUES (?, ?, 'Visible plan', 100, 10, 'ACTIVE')", IdWorker.getId(), tenant);
        jdbc.update("INSERT INTO pf_recharge_plan (id, tenant_id, name, price_fen, base_points, status) VALUES (?, ?, 'Other tenant', 100, 10, 'ACTIVE')", IdWorker.getId(), tenant + 1);
        assertThat(queries.rechargePlans()).extracting(WalletQueryService.RechargePlan::name).containsExactly("Visible plan");
    }

    @Test
    void rechargeSnapshotsIdentityAndQuoteAndRejectsChangedIdempotentRequests() {
        var payment = paymentService();
        long planId = paymentFixture();
        var order = payment.createOrder(planId, "create-order");
        assertThat(order.orderNo()).startsWith("PFW");
        assertThat(payment.createOrder(planId, "create-order").id()).isEqualTo(order.id());
        jdbc.update("UPDATE pf_recharge_plan SET price_fen = 999 WHERE id = ?", planId);
        assertThat(payment.getOrder(order.id()).priceFen()).isEqualTo(100);
        jdbc.update("UPDATE pf_recharge_plan SET status = 'DISABLED' WHERE id = ?", planId);
        assertThat(payment.createOrder(planId, "create-order").id()).isEqualTo(order.id());
        assertThatThrownBy(() -> payment.createOrder(planId, "disabled-plan-new-intent"))
                .isInstanceOfSatisfying(cn.iocoder.yudao.framework.common.exception.ServiceException.class,
                        failure -> assertThat(failure.getCode()).isEqualTo(1_120_000_006));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_recharge_order WHERE tenant_id = ?", Long.class, tenant)).isEqualTo(1);
        assertThatThrownBy(() -> payment.createOrder(planId + 1, "create-order"))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        jdbc.update("UPDATE pf_wechat_binding SET status = 'REVOKED' WHERE user_id = ?", post.userId());
        assertThatThrownBy(() -> payment.createOrder(planId, "another-order"))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
    }

    @Test
    void verifiedPaymentAndCreditAreSeparateRecoverableTransactions() {
        var payment = paymentService();
        long planId = paymentFixture();
        var order = payment.createOrder(planId, "settle-order");
        long walletId = Long.parseLong(new WalletQueryService(dataSource, transactionManager, scope).account().walletId());
        current.remove();
        var fact = new PaymentPort.ChannelQueryResult("SUCCEEDED", order.orderNo(), "XPAY", "wallet-app", "wallet-offer", 0,
                "openid-" + post.userId(), "wx-transaction-" + order.id(), 100L, "FEN", java.time.Instant.now(), 0);
        payment.processPaymentFact(tenant, walletId, Long.parseLong(order.id()), fact);
        assertBalance(0, 0, 0);
        payment.fulfillOrder(tenant, walletId, Long.parseLong(order.id()));
        payment.fulfillOrder(tenant, walletId, Long.parseLong(order.id()));
        assertBalance(12, 0, 2);
    }

    @Test
    void payParamsConsumesCodeOutsideTransactionsAndNeverReusesAnAttemptKey() {
        PaymentPort channel = mock(PaymentPort.class);
        when(channel.createPrepay(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> {
                    assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    return new PaymentPort.PrepayResult(java.util.Map.of("signData", "signed-order", "mode", "short_series_goods", "paySig", "test-pay-signature", "signature", "test-session-signature"));
                });
        var payment = paymentService(channel);
        var order = payment.createOrder(paymentFixture(), "prepay-order");
        assertThat(payment.payParams(order.id(), "single-use-code", "attempt-1")).containsKey("signature");
        assertThatThrownBy(() -> payment.payParams(order.id(), "single-use-code", "attempt-1"))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThatThrownBy(() -> payment.payParams(order.id(), "different-code", "attempt-1"))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        org.mockito.Mockito.verify(channel, org.mockito.Mockito.times(1))
                .createPrepay(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        jdbc.update("UPDATE pf_wechat_binding SET status = 'REVOKED' WHERE user_id = ?", post.userId());
        assertThatThrownBy(() -> payment.payParams(order.id(), "single-use-code", "attempt-1"))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
    }

    @Test
    void revokedDuringCodeExchangeNeverReceivesPaymentParameters() {
        PaymentPort channel = mock(PaymentPort.class);
        when(channel.createPrepay(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> {
                    doThrow(new IllegalStateException("revoked during code exchange")).when(posts)
                            .revalidateActivePost(post.userId(), post.postId());
                    return new PaymentPort.PrepayResult(java.util.Map.of("signData", "signed-order", "mode", "short_series_goods", "paySig", "test-pay-signature", "signature", "test-session-signature"));
                });
        var payment = paymentService(channel);
        var order = payment.createOrder(paymentFixture(), "revoked-prepay-order");
        assertThatThrownBy(() -> payment.payParams(order.id(), "single-use-code", "revoked-attempt"))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThat(jdbc.queryForObject("SELECT state FROM pf_payment_attempt WHERE tenant_id = ? AND order_id = ?", String.class,
                tenant, Long.parseLong(order.id()))).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT to_jsonb(a)::text FROM pf_payment_attempt a WHERE tenant_id = ? AND order_id = ?", String.class,
                tenant, Long.parseLong(order.id()))).doesNotContain("single-use-code", "test-session-signature", "session_key");
    }

    @Test
    void prepayLeaseExpiryWhileWaitingForWalletLockPreventsIssuingParameters() throws Exception {
        PaymentPort channel = mock(PaymentPort.class);
        var payment = paymentService(channel);
        var order = payment.createOrder(paymentFixture(), "lease-order");
        long walletId = Long.parseLong(new WalletQueryService(dataSource, transactionManager, scope).account().walletId());
        var locked = new CountDownLatch(1);
        var finalTransactionStarted = new CountDownLatch(1);
        var validations = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
            if (validations.incrementAndGet() == 2) {
                jdbc.queryForObject("SELECT 1", Integer.class); // 固定 PostgreSQL 事务开始时刻，再让另一连接调整 lease。
                finalTransactionStarted.countDown();
            }
            return null;
        }).when(posts).revalidateActivePost(post.userId(), post.postId());
        var executor = Executors.newSingleThreadExecutor();
        var blocker = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>>();
        when(channel.createPrepay(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> {
                    blocker.set(executor.submit(() -> tx.execute(status -> {
                        jdbc.queryForObject("SELECT id FROM pf_wallet WHERE tenant_id = ? AND id = ? FOR UPDATE", Long.class, tenant, walletId);
                        locked.countDown();
                        awaitLatch(finalTransactionStarted);
                        jdbc.update("UPDATE pf_payment_attempt SET lease_until = clock_timestamp() + interval '100 milliseconds' WHERE tenant_id = ? AND wallet_id = ? AND order_id = ?",
                                tenant, walletId, Long.parseLong(order.id()));
                        jdbc.queryForObject("SELECT 1 FROM pg_sleep(0.2)", Integer.class);
                        return null;
                    })));
                    awaitLatch(locked);
                    return new PaymentPort.PrepayResult(java.util.Map.of("signData", "signed-order", "mode", "short_series_goods", "paySig", "test-pay-signature", "signature", "test-session-signature"));
                });
        try {
            assertThatThrownBy(() -> payment.payParams(order.id(), "single-use-code", "lease-attempt"))
                    .isInstanceOfSatisfying(cn.iocoder.yudao.framework.common.exception.ServiceException.class,
                            failure -> assertThat(failure.getCode()).isEqualTo(409));
            blocker.get().get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT state FROM pf_payment_attempt WHERE tenant_id = ? AND wallet_id = ? AND order_id = ?",
                String.class, tenant, walletId, Long.parseLong(order.id()))).isEqualTo("UNKNOWN");
    }

    @Test
    void oneChannelTransactionCannotBeRemappedToAnotherOrder() {
        var payment = paymentService();
        long planId = paymentFixture();
        var first = payment.createOrder(planId, "first-order");
        var second = payment.createOrder(planId, "second-order");
        long walletId = Long.parseLong(new WalletQueryService(dataSource, transactionManager, scope).account().walletId());
        String transactionId = "same-transaction-" + first.id();
        payment.processPaymentFact(tenant, walletId, Long.parseLong(first.id()), new PaymentPort.ChannelQueryResult("SUCCEEDED", first.orderNo(),
                "XPAY", "wallet-app", "wallet-offer", 0, "openid-" + post.userId(), transactionId, 100L, "FEN", java.time.Instant.now(), 0));
        assertThatThrownBy(() -> payment.processPaymentFact(tenant, walletId, Long.parseLong(second.id()),
                new PaymentPort.ChannelQueryResult("SUCCEEDED", second.orderNo(), "XPAY", "wallet-app", "wallet-offer", 0,
                        "openid-" + post.userId(), transactionId, 100L, "FEN", java.time.Instant.now(), 0)))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThat(payment.getOrder(second.id()).status()).isEqualTo("CREATED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_payment_anomaly_audit WHERE tenant_id = ? AND order_id = ?", Long.class,
                tenant, Long.parseLong(second.id()))).isEqualTo(1);
    }

    private RechargePaymentService paymentService() {
        return paymentService(mock(PaymentPort.class));
    }

    private RechargePaymentService paymentService(PaymentPort channel) {
        var properties = new WechatXPayProperties();
        properties.setEnabled(true);
        properties.setAppid("wallet-app");
        properties.setOfferId("wallet-offer");
        properties.setPaidFeeUnit("FEN");
        var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        beans.addBean("payment", channel);
        return new RechargePaymentService(dataSource, transactionManager, scope, points,
                new PaymentFactValidator(), properties, beans.getBeanProvider(PaymentPort.class));
    }

    @Test
    void companyPoolCreditsOnceAndKeepsHistoricWalletSeparate() {
        var payment = paymentService();
        long planId = paymentFixture();
        jdbc.update("INSERT INTO org_point_account(id,org_id,balance) VALUES(?,?,8)",IdWorker.getId(),post.orgId());
        var order = payment.createCompanyOrder(Long.toString(planId),"company-check",1);
        assertThat(payment.createCompanyOrder(Long.toString(planId),"company-check",1).id()).isEqualTo(order.id());
        long walletId=jdbc.queryForObject("SELECT id FROM pf_wallet WHERE org_id=?",Long.class,post.orgId());
        var fact=new PaymentPort.ChannelQueryResult("SUCCEEDED",order.orderNo(),"XPAY","wallet-app","wallet-offer",0,
                "openid-"+post.userId(),"fixture-"+order.id(),100L,"FEN",java.time.Instant.now(),0);
        for(int i=0;i<2;i++) {
            payment.processPaymentFact(tenant,walletId,Long.parseLong(order.id()),fact);
            payment.fulfillOrder(tenant,walletId,Long.parseLong(order.id()));
        }
        assertThat(jdbc.queryForObject("SELECT balance FROM org_point_account WHERE org_id=?",Long.class,post.orgId())).isEqualTo(20);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM org_point_ledger WHERE org_id=? AND type='COMPANY_RECHARGE'",Long.class,post.orgId())).isOne();
        assertBalance(0,0,0);
        var authorization=mock(cn.iocoder.yudao.module.platform.authz.PostPermissionEvaluator.class);
        when(authorization.has("platform:wallet:recharge")).thenReturn(true);
        var catalog=new CompanyRechargeCatalog(jdbc,scope,authorization,payment);
        assertThat(tx.execute(status->catalog.read()).currentOrder().status()).isEqualTo("CREDITED");
        var refund=new RefundPaymentService(dataSource,transactionManager,scope,points,payment,new PaymentFactValidator());
        var refunded=new PaymentPort.ChannelRefundResult("SUCCEEDED",order.orderNo(),"external-company-refund","wx-refund-"+order.id(),100L,"FEN",1,8);
        refund.processRefundFact(tenant,walletId,Long.parseLong(order.id()),refunded);
        refund.processRefundFact(tenant,walletId,Long.parseLong(order.id()),refunded);
        assertThat(tx.execute(status->catalog.read()).currentOrder().status()).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject("SELECT balance FROM org_point_account WHERE org_id=?",Long.class,post.orgId())).isEqualTo(8);
        assertBalance(0,0,0);
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="franchise.ui.runner",matches=".+")
    void miniProgramToSpringControllersOverHttp() throws Exception {
        long planId=paymentFixture();
        var provider=mock(PaymentPort.class);
        when(provider.createPrepay(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(new PaymentPort.PrepayResult(java.util.Map.of("signData","fixture","signature","fixture","paySig","fixture","mode","short_series_goods")));
        var payment=paymentService(provider);
        jdbc.update("INSERT INTO org_point_account(id,org_id,balance) VALUES(?,?,8)",IdWorker.getId(),post.orgId());
        PostSnapshot peer=createPost(), other=createPost();
        current.set(post);
        var pool = new cn.iocoder.yudao.module.platform.wallet.orgpoint.OrgPointService(dataSource, transactionManager,
                mock(cn.iocoder.yudao.module.infra.zhongshu.audit.AuditPort.class),
                new cn.iocoder.yudao.module.platform.wallet.orgpoint.OrgPointPriceService(dataSource, transactionManager));
        assertThat(pool.summary(post.orgId()).balance()).isEqualTo(8);
        new CompanyServicesHttpFixture(jdbc,transactionManager,scope,payment,provider,current,tenant,post,peer,other)
                .run(planId,System.getProperty("franchise.ui.runner"));
        assertThat(pool.summary(post.orgId()).balance()).isEqualTo(20);
        assertThat(pool.summary(post.orgId()).monthUsedTotal()).isZero();
    }

    @Test
    void revisedQuoteDoesNotHideOrReplaceAnUnconfirmedCompanyOrder() {
        var gateway = mock(PaymentPort.class);
        when(gateway.createPrepay(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(new PaymentPort.PrepayResult(java.util.Map.of(
                "signData", "fixture", "signature", "fixture", "paySig", "fixture", "mode", "short_series_goods")));
        var payment = paymentService(gateway);
        long planId = paymentFixture();
        jdbc.update("INSERT INTO org_point_account(id,org_id,balance) VALUES(?,?,8)", IdWorker.getId(), post.orgId());
        var old = payment.createCompanyOrder(Long.toString(planId), "old-company", 1);
        long walletId = jdbc.queryForObject("SELECT id FROM pf_wallet WHERE org_id=?", Long.class, post.orgId());
        long oldId = Long.parseLong(old.id());
        payment.payParams(old.id(), "fixture-code", "old-attempt", true);
        jdbc.update("UPDATE pf_recharge_plan SET version=2, price_fen=200 WHERE id=?", planId);
        var permissions = mock(cn.iocoder.yudao.module.platform.authz.PostPermissionEvaluator.class);
        when(permissions.has("platform:wallet:recharge")).thenReturn(true);
        var catalog = new CompanyRechargeCatalog(jdbc, scope, permissions, payment);
        var displayed = tx.execute(status -> catalog.read()).currentOrder();
        assertThat(displayed.id()).isEqualTo(old.id());
        assertThat(displayed.status()).isEqualTo("PROCESSING");
        assertThat(displayed.quoteCurrent()).isFalse();
        assertThatThrownBy(() -> payment.createCompanyOrder(Long.toString(planId), "replacement", 2))
                .hasMessageContaining("已有待处理的充值订单");
        assertThatThrownBy(() -> payment.payParams(old.id(), "new-code", "new-attempt", true))
                .hasMessageContaining("套餐已调整");
        String payer = "openid-" + post.userId();
        var unrelated = new PaymentPort.ChannelQueryResult("CLOSED", "different-order", "XPAY", "wallet-app",
                "wallet-offer", 0, payer, null, 0L, "FEN", null, 0);
        assertThatThrownBy(() -> payment.processClosedCompanyOrder(tenant, walletId, oldId, unrelated))
                .isInstanceOf(IllegalArgumentException.class);
        var unknown = new PaymentPort.ChannelQueryResult("UNKNOWN", old.orderNo(), "XPAY", "wallet-app",
                "wallet-offer", 0, payer, null, null, "UNVERIFIED", null, 0);
        assertThatThrownBy(() -> payment.processClosedCompanyOrder(tenant, walletId, oldId, unknown))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_recharge_order WHERE wallet_id=?", Long.class, walletId)).isOne();
        var latePayment = new PaymentPort.ChannelQueryResult("SUCCEEDED", old.orderNo(), "XPAY", "wallet-app",
                "wallet-offer", 0, payer, "fixture-old-payment", 100L, "FEN", java.time.Instant.now(), 0);
        payment.processPaymentFact(tenant, walletId, oldId, latePayment);
        assertThatThrownBy(() -> payment.createCompanyOrder(Long.toString(planId), "replacement", 2))
                .hasMessageContaining("已有待处理的充值订单");
        assertThat(payment.fulfillOrder(tenant, walletId, oldId)).isTrue();
        assertThat(payment.fulfillOrder(tenant, walletId, oldId)).isTrue();
        var staleClose = new PaymentPort.ChannelQueryResult("CLOSED", old.orderNo(), "XPAY", "wallet-app",
                "wallet-offer", 0, payer, null, 0L, "FEN", null, 0);
        payment.processClosedCompanyOrder(tenant, walletId, oldId, staleClose);
        assertThat(tx.execute(status -> catalog.read()).currentOrder().status()).isEqualTo("CREDITED");
        assertThat(jdbc.queryForObject("SELECT balance FROM org_point_account WHERE org_id=?", Long.class, post.orgId()))
                .isEqualTo(20);
        assertThat(payment.createCompanyOrder(Long.toString(planId), "replacement", 2).priceFen()).isEqualTo(200);
    }

    @Test
    void channelClosedUnpaidCompanyOrderAllowsNewQuoteAndNeverCredits() {
        var gateway = mock(PaymentPort.class);
        when(gateway.createPrepay(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(new PaymentPort.PrepayResult(java.util.Map.of(
                "signData", "fixture", "signature", "fixture", "paySig", "fixture", "mode", "short_series_goods")));
        var payment = paymentService(gateway);
        long planId = paymentFixture();
        jdbc.update("INSERT INTO org_point_account(id,org_id,balance) VALUES(?,?,8)", IdWorker.getId(), post.orgId());
        var beforePay = payment.createCompanyOrder(Long.toString(planId), "old-unopened", 1);
        jdbc.update("UPDATE pf_recharge_plan SET version=2, price_fen=200 WHERE id=?", planId);
        var old = payment.createCompanyOrder(Long.toString(planId), "old-opened", 2);
        payment.payParams(old.id(), "fixture-code", "attempt-2", true);
        jdbc.update("UPDATE pf_recharge_plan SET version=3, price_fen=300 WHERE id=?", planId);
        long walletId = jdbc.queryForObject("SELECT id FROM pf_wallet WHERE org_id=?", Long.class, post.orgId());
        assertThat(jdbc.queryForObject("SELECT payment_state FROM pf_recharge_order WHERE id=?", String.class,
                Long.parseLong(beforePay.id()))).isEqualTo("CLOSED");
        assertThatThrownBy(() -> payment.createCompanyOrder(Long.toString(planId), "after-close", 3))
                .hasMessageContaining("已有待处理的充值订单");
        var closed = new PaymentPort.ChannelQueryResult("CLOSED", old.orderNo(), "XPAY", "wallet-app",
                "wallet-offer", 0, "openid-" + post.userId(), null, 0L, "FEN", null, 0);
        when(gateway.queryOrder(org.mockito.ArgumentMatchers.any())).thenReturn(closed);
        var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        beans.addBean("payment", gateway);
        var refunds = new RefundPaymentService(dataSource, transactionManager, scope, points, payment, new PaymentFactValidator());
        new WalletRecoveryJob(dataSource, transactionManager, payment, refunds, new WechatXPayProperties(),
                beans.getBeanProvider(PaymentPort.class)).runOnce(100);
        assertThat(jdbc.queryForObject("SELECT payment_state FROM pf_recharge_order WHERE id=?", String.class,
                Long.parseLong(old.id()))).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_payment_transaction WHERE wallet_id=?", Long.class, walletId)).isZero();
        assertThat(jdbc.queryForObject("SELECT balance FROM org_point_account WHERE org_id=?", Long.class, post.orgId()))
                .isEqualTo(8);
        assertThat(payment.createCompanyOrder(Long.toString(planId), "after-close", 3).priceFen()).isEqualTo(300);
    }

    private long paymentFixture() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS pf_wechat_binding (id BIGINT PRIMARY KEY, appid VARCHAR(64), openid VARCHAR(128), user_id BIGINT, mobile_hash CHAR(64), status VARCHAR(16))");
        jdbc.execute("ALTER TABLE system_role ADD COLUMN IF NOT EXISTS deleted SMALLINT NOT NULL DEFAULT 0");
        jdbc.execute("ALTER TABLE system_role_menu ADD COLUMN IF NOT EXISTS deleted SMALLINT NOT NULL DEFAULT 0");
        jdbc.execute("ALTER TABLE system_menu ADD COLUMN IF NOT EXISTS deleted SMALLINT NOT NULL DEFAULT 0");
        jdbc.update("UPDATE system_users SET mobile = '13800000000' WHERE id = ? AND tenant_id = ?", post.userId(), tenant);
        jdbc.update("INSERT INTO pf_wechat_binding (id, appid, openid, user_id, mobile_hash, status) VALUES (?, 'wallet-app', ?, ?, ?, 'ACTIVE')",
                IdWorker.getId(), "openid-" + post.userId(), post.userId(), cn.hutool.crypto.digest.DigestUtil.sha256Hex("13800000000"));
        long roleId = IdWorker.getId();
        jdbc.update("INSERT INTO system_role (id, code, status, tenant_id) VALUES (?, 'owner', 0, ?)", roleId, tenant);
        jdbc.update("INSERT INTO system_role_menu (id, role_id, menu_id, tenant_id) VALUES (?, ?, 910102, ?)", IdWorker.getId(), roleId, tenant);
        jdbc.update("INSERT INTO system_role_menu (id, role_id, menu_id, tenant_id) VALUES (?, ?, 910101, ?)", IdWorker.getId(), roleId, tenant);
        long planId = IdWorker.getId();
        jdbc.update("INSERT INTO pf_recharge_plan (id, tenant_id, name, price_fen, base_points, bonus_points, status, wx_product_id) VALUES (?, ?, 'Payment test', 100, 10, 2, 'ACTIVE', 'product')", planId, tenant);
        return planId;
    }

    @Test
    void cumulativeSettlementChargesOnlyTheDeltaAndReleasesOnlyAtTerminal() {
        credit("opening", 100);
        charges.reserve("DESIGN", "cumulative", 80, "cumulative-reserve");
        current.remove(); // 已发生的后台任务补偿不能依赖请求工作身份。
        charges.settleToCumulativeForTask(tenant, post.orgId(), "DESIGN", "cumulative", 20, false, 1);
        charges.settleToCumulativeForTask(tenant, post.orgId(), "DESIGN", "cumulative", 20, false, 1);
        assertBalance(20, 60, 2);
        charges.settleToCumulativeForTask(tenant, post.orgId(), "DESIGN", "cumulative", 50, true, 2);
        charges.settleToCumulativeForTask(tenant, post.orgId(), "DESIGN", "cumulative", 50, true, 2);
        assertBalance(50, 0, 3);
        assertThatThrownBy(() -> charges.settleToCumulativeForTask(tenant, post.orgId(), "DESIGN", "cumulative", 60, true, 3))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
    }

    @Test
    void releaseAfterPartialSettlementReplaysOnlyItsOwnTerminalOperation() {
        credit("opening", 100);
        var charge = charges.reserve("DESIGN", "partial-release", 80, "partial-release-key");
        charges.settleToCumulativeForTask(tenant, post.orgId(), "DESIGN", "partial-release", 20, false, 1);
        var released = charges.release(charge.id());
        assertThat(released.status()).isEqualTo("SETTLED");
        assertThat(charges.release(charge.id())).isEqualTo(released);
        assertThatThrownBy(() -> charges.consume(charge.id()))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        var taskCharge = charges.reserve("DESIGN", "task-terminal", 30, "task-terminal-key");
        charges.settleToCumulativeForTask(tenant, post.orgId(), "DESIGN", "task-terminal", 10, true, 1);
        assertThatThrownBy(() -> charges.release(taskCharge.id()))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertBalance(70, 0, 3);
    }

    @Test
    void cumulativeSettlementRejectsBackwardsConflictingOverdrawAndForeignScope() {
        credit("opening", 100);
        charges.reserve("DESIGN", "ordered", 80, "ordered-reserve");
        charges.settleToCumulativeForTask(tenant, post.orgId(), "DESIGN", "ordered", 20, false, 3);
        assertThatThrownBy(() -> charges.settleToCumulativeForTask(tenant, post.orgId(), "DESIGN", "ordered", 19, false, 4))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThatThrownBy(() -> charges.settleToCumulativeForTask(tenant, post.orgId(), "DESIGN", "ordered", 21, false, 2))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThatThrownBy(() -> charges.settleToCumulativeForTask(tenant, post.orgId(), "DESIGN", "ordered", 21, false, 3))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThatThrownBy(() -> charges.settleToCumulativeForTask(tenant, post.orgId(), "DESIGN", "ordered", 20, true, 3))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThatThrownBy(() -> charges.settleToCumulativeForTask(tenant, post.orgId(), "DESIGN", "ordered", 81, true, 4))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThatThrownBy(() -> charges.settleToCumulativeForTask(tenant + 1, post.orgId(), "DESIGN", "ordered", 20, true, 4))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertBalance(20, 60, 2);
    }

    @Test
    void totalBalanceUpperBoundProtectsFutureReserveRelease() {
        credit("maximum", Long.MAX_VALUE);
        var charge = charges.reserve("TEST", "upper-bound", 20, "upper-bound-reserve");
        assertThatThrownBy(() -> credit("overflow", 10))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE pf_point_account SET available_points = ? WHERE tenant_id = ?",
                Long.MAX_VALUE - 19, tenant)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertBalance(Long.MAX_VALUE - 20, 20, 1);
        charges.release(charge.id());
        assertBalance(Long.MAX_VALUE, 0, 1);
    }

    @Test
    void databaseRejectsNegativeBalancesAndLedgerMutation() {
        credit("opening", 100);
        assertThatThrownBy(() -> jdbc.update("UPDATE pf_point_account SET available_points = -1 WHERE tenant_id = ?", tenant))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE pf_point_ledger SET delta = 999 WHERE tenant_id = ?", tenant))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM pf_point_ledger WHERE tenant_id = ?", tenant))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertBalance(100, 0, 1);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test barrier timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test barrier interrupted", e);
        }
    }

    private long credit(String key, long amount) {
        return tx.execute(status -> points.credit(scope.require(), "MANUAL_CREDIT", amount,
                "TEST", "opening", key, "test opening balance"));
    }

    private void assertBalance(long available, long reserved, long ledgerCount) {
        var row = jdbc.queryForMap("SELECT available_points, reserved_points FROM pf_point_account WHERE tenant_id = ? AND wallet_id IN (SELECT id FROM pf_wallet WHERE org_id = ?)", tenant, post.orgId());
        assertThat(((Number) row.get("available_points")).longValue()).isEqualTo(available);
        assertThat(((Number) row.get("reserved_points")).longValue()).isEqualTo(reserved);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_point_ledger WHERE tenant_id = ? AND wallet_id IN (SELECT id FROM pf_wallet WHERE org_id = ?)", Long.class, tenant, post.orgId())).isEqualTo(ledgerCount);
    }

    private PostSnapshot createPost() {
        long orgId = IdWorker.getId();
        jdbc.update("INSERT INTO pf_org_type (code, domain, name) VALUES ('wallet-test', 'wallet', 'Wallet test') ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO system_dept (id, name, tenant_id) VALUES (?, 'Wallet test', ?)", orgId, tenant);
        jdbc.update("INSERT INTO pf_org_unit (dept_id, kind, org_id, org_type) VALUES (?, 'ORG', ?, 'wallet-test')", orgId, orgId);
        return createPostInOrg(orgId);
    }

    private PostSnapshot createPostInOrg(long orgId) {
        long userId = IdWorker.getId();
        long postId = IdWorker.getId();
        jdbc.update("INSERT INTO pf_post_template (role_code, domain, org_types) VALUES ('owner', 'wallet', 'wallet-test') ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO system_users (id, username, nickname, tenant_id, mobile) VALUES (?, ?, 'Wallet test', ?, '13800000000')",
                userId, "wallet-" + userId, tenant);
        jdbc.update("INSERT INTO pf_staff_post (id, user_id, org_id, role_code) VALUES (?, ?, ?, 'owner')", postId, userId, orgId);
        return new PostSnapshot(postId, userId, orgId, "store", "owner", null, false, Set.of());
    }
}
