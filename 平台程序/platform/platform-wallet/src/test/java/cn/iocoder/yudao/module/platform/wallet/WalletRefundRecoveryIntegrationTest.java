package cn.iocoder.yudao.module.platform.wallet;

import cn.iocoder.yudao.module.platform.test.PlatformPgIntegrationTest;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@TestPropertySource(properties = "platform.test.extra-flyway-locations=,classpath:db/migration/platform-wallet")
class WalletRefundRecoveryIntegrationTest extends PlatformPgIntegrationTest {
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactionManager;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private WalletScope scope;
    private PointAccountService points;
    private PaymentPort port;
    private RechargePaymentService payment;
    private RefundPaymentService refunds;
    private ManualPointAdjustmentService adjustments;
    private WalletScope.Wallet wallet;
    private long orderId;
    private long tenant;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(transactionManager);
        tenant = IdWorker.getId();
        long org = IdWorker.getId();
        wallet = new WalletScope.Wallet(tenant, IdWorker.getId(), org, IdWorker.getId(), IdWorker.getId(), "refund-test");
        jdbc.update("INSERT INTO pf_org_type (code, domain, name) VALUES ('refund-test','wallet','Refund test') ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO system_dept (id, name, tenant_id) VALUES (?, 'Refund test', ?)", org, tenant);
        jdbc.update("INSERT INTO pf_org_unit (dept_id, kind, org_id, org_type) VALUES (?, 'ORG', ?, 'refund-test')", org, org);
        jdbc.update("INSERT INTO pf_wallet (id, org_id, tenant_id) VALUES (?, ?, ?)", wallet.id(), org, tenant);
        jdbc.update("INSERT INTO pf_point_account (wallet_id, tenant_id) VALUES (?, ?)", wallet.id(), tenant);
        scope = mock(WalletScope.class);
        when(scope.require()).thenAnswer(call -> {
            jdbc.queryForObject("SELECT id FROM pf_wallet WHERE tenant_id = ? AND id = ? FOR UPDATE", Long.class, tenant, wallet.id());
            return wallet;
        });
        points = new PointAccountService(dataSource);
        port = mock(PaymentPort.class);
        var properties = new WechatXPayProperties();
        properties.setEnabled(true);
        properties.setAppid("test-app");
        properties.setOfferId("test-offer");
        properties.setPaidFeeUnit("FEN");
        var beans = new StaticListableBeanFactory();
        beans.addBean("channel", port);
        payment = new RechargePaymentService(dataSource, transactionManager, scope, points,
                new PaymentFactValidator(), properties, beans.getBeanProvider(PaymentPort.class));
        refunds = new RefundPaymentService(dataSource, transactionManager, scope, points, payment, new PaymentFactValidator());
        adjustments = new ManualPointAdjustmentService(dataSource, transactionManager, scope, points, payment);
        permissions();
        long plan = IdWorker.getId();
        orderId = IdWorker.getId();
        jdbc.update("INSERT INTO pf_recharge_plan (id, tenant_id, name, price_fen, base_points, bonus_points) VALUES (?, ?, 'Test', 100, 10, 2)", plan, tenant);
        jdbc.update("""
                INSERT INTO pf_recharge_order (id, tenant_id, wallet_id, org_id, actor_id, post_id, binding_id,
                    order_no, plan_id, plan_snapshot, price_fen, base_points, bonus_points, payer_appid,
                    payer_openid, channel, offer_id, environment, idempotency_key, request_hash)
                VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?, '{"wxProductId":"product"}', 100, 10, 2,
                    'test-app', 'payer', 'XPAY', 'test-offer', 0, ?, ?)
                """, orderId, tenant, wallet.id(), org, wallet.actorId(), wallet.postId(), "PFW" + orderId,
                plan, "order-" + orderId, "a".repeat(64));
        when(port.requestRefund(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var request = call.getArgument(0, PaymentPort.RefundRequest.class);
            return new PaymentPort.ChannelRefundResult("UNKNOWN", request.order().orderNo(), request.refundNo(),
                    null, null, "UNVERIFIED", null, null);
        });
    }

    @Test
    void companyRefundReservesOnceRestoresOnFailureAndSettlesOnceOnSuccess() {
        jdbc.update("UPDATE pf_recharge_order SET credit_target='COMPANY' WHERE id=?", orderId);
        jdbc.update("INSERT INTO org_point_account(id,org_id,balance) VALUES(?,?,8)", IdWorker.getId(), wallet.orgId());
        paidAndCredited();
        var first = refunds.requestRefund(orderId, "company-first", 0);
        assertThat(refunds.requestRefund(orderId, "company-first", 0).id()).isEqualTo(first.id());
        for (int attempt = 0; attempt < 2; attempt++)
            refunds.processRefundFact(tenant, wallet.id(), orderId, fact(first, "FAILED", 7));
        assertThat(jdbc.queryForObject("SELECT balance FROM org_point_account WHERE org_id=?", Long.class, wallet.orgId())).isEqualTo(20);
        var second = refunds.requestRefund(orderId, "company-second", 0);
        for (int attempt = 0; attempt < 2; attempt++)
            refunds.processRefundFact(tenant, wallet.id(), orderId, fact(second, "SUCCEEDED", 8));
        assertThat(jdbc.queryForObject("SELECT balance FROM org_point_account WHERE org_id=?", Long.class, wallet.orgId())).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM org_point_ledger WHERE org_id=?", Long.class, wallet.orgId())).isEqualTo(4);
        balance(0, 0);
    }

    @Test
    void insufficientPointsCauseZeroExternalRefundsAndNoPlaceholder() {
        paidAndCredited();
        tx.executeWithoutResult(status -> points.reserve(wallet, 1));
        assertThatThrownBy(() -> refunds.requestRefund(orderId, "refund-1", 0)).isInstanceOf(RuntimeException.class);
        verify(port, never()).requestRefund(any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_refund_order WHERE tenant_id = ?", Long.class, tenant)).isZero();
        balance(11, 1);
    }

    @Test
    void unknownRefundKeepsReserveAndNewKeyCannotSendAgain() {
        paidAndCredited();
        var refund = refunds.requestRefund(orderId, "refund-1", 0);
        assertThat(refunds.requestRefund(orderId, "refund-1", 0).id()).isEqualTo(refund.id());
        assertThatThrownBy(() -> refunds.requestRefund(orderId, "refund-2", 0)).isInstanceOf(RuntimeException.class);
        balance(0, 12);
        verify(port, times(1)).requestRefund(any());
    }

    @Test
    void merchantDebitKeepsReserveAndUserReceiptReversesExactlyOnceWithoutCurrentPost() {
        paidAndCredited();
        var refund = refunds.requestRefund(orderId, "refund-1", 0);
        doThrow(new IllegalStateException("revoked")).when(scope).require();
        refunds.processRefundFact(tenant, wallet.id(), orderId, fact(refund, "PROCESSING", 5));
        balance(0, 12);
        refunds.processRefundFact(tenant, wallet.id(), orderId, fact(refund, "SUCCEEDED", 8));
        refunds.processRefundFact(tenant, wallet.id(), orderId, fact(refund, "SUCCEEDED", 8));
        balance(0, 0);
        assertThat(jdbc.queryForObject("SELECT sum(delta) FROM pf_point_ledger WHERE tenant_id = ?", Long.class, tenant)).isZero();
    }

    @Test
    void onlyTrustedPerRefundFailureReleasesAndSuccessCannotBeOverwritten() {
        paidAndCredited();
        var refund = refunds.requestRefund(orderId, "refund-1", 0);
        assertThatThrownBy(() -> refunds.processRefundFact(tenant, wallet.id(), orderId, fact(refund, "FAILED", 5)))
                .isInstanceOf(IllegalArgumentException.class);
        balance(0, 12);
        refunds.processRefundFact(tenant, wallet.id(), orderId, fact(refund, "FAILED", 7));
        refunds.processRefundFact(tenant, wallet.id(), orderId, fact(refund, "FAILED", 7));
        balance(12, 0);
    }

    @Test
    void externalRefundBeforeCreditBlocksLaterCredit() {
        paid();
        refunds.processRefundFact(tenant, wallet.id(), orderId, externalFact());
        assertThat(payment.fulfillOrder(tenant, wallet.id(), orderId)).isFalse();
        balance(0, 0);
        assertThat(jdbc.queryForObject("SELECT credit_state FROM pf_recharge_order WHERE tenant_id = ? AND wallet_id = ? AND id = ?",
                String.class, tenant, wallet.id(), orderId)).isEqualTo("BLOCKED");
    }

    @Test
    void externalRefundAfterSpendPersistsFactAndFreezesSpendingWithoutNegativeBalance() {
        paidAndCredited();
        tx.executeWithoutResult(status -> {
            points.reserve(wallet, 1);
            points.consumeReserve(wallet, 1, "TEST", "spent", "spent:" + orderId);
        });
        refunds.processRefundFact(tenant, wallet.id(), orderId, externalFact());
        balance(11, 0);
        assertThat(jdbc.queryForObject("SELECT spending_frozen FROM pf_wallet WHERE tenant_id = ? AND id = ?", Boolean.class, tenant, wallet.id())).isTrue();
        assertThat(jdbc.queryForObject("SELECT point_state FROM pf_refund_order WHERE tenant_id = ? AND wallet_id = ? AND order_id = ?",
                String.class, tenant, wallet.id(), orderId)).isEqualTo("DEBT");
    }

    @Test
    void reversalFailureKeepsRefundFactAndReservedPointsForRecovery() {
        paidAndCredited();
        var refund = refunds.requestRefund(orderId, "refund-1", 0);
        var failing = spy(points);
        doThrow(new IllegalStateException("ledger unavailable")).when(failing)
                .consumeReserve(any(), anyString(), anyLong(), anyString(), anyString(), anyString(), anyString());
        var service = new RefundPaymentService(dataSource, transactionManager, scope, failing, payment, new PaymentFactValidator());
        assertThatThrownBy(() -> service.processRefundFact(tenant, wallet.id(), orderId, fact(refund, "SUCCEEDED", 8)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT channel_state FROM pf_refund_order WHERE tenant_id = ? AND wallet_id = ? AND id = ?",
                String.class, tenant, wallet.id(), Long.parseLong(refund.id()))).isEqualTo("SUCCEEDED");
        balance(0, 12);
        refunds.processRefundFact(tenant, wallet.id(), orderId, fact(refund, "SUCCEEDED", 8));
        balance(0, 0);
    }

    @Test
    void foreignTenantFactCannotTouchTheOrder() {
        paidAndCredited();
        assertThatThrownBy(() -> refunds.processRefundFact(tenant + 1, wallet.id(), orderId, externalFact()))
                .isInstanceOf(RuntimeException.class);
        balance(12, 0);
    }

    @Test
    void manualAdjustmentRequiresDifferentReviewerAndBindsIdempotency() {
        var id = adjustments.submit(20, "correction", "manual-key");
        assertThat(adjustments.submit(20, "correction", "manual-key")).isEqualTo(id);
        assertThatThrownBy(() -> adjustments.submit(21, "correction", "manual-key")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> adjustments.review(id, true, "checked")).isInstanceOf(RuntimeException.class);
        wallet = new WalletScope.Wallet(tenant, wallet.id(), wallet.orgId(), IdWorker.getId(), IdWorker.getId(), "refund-test");
        var ledger = adjustments.review(id, true, "checked");
        assertThat(adjustments.review(id, true, "checked")).isEqualTo(ledger);
        balance(20, 0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_payment_transaction WHERE tenant_id = ?", Long.class, tenant)).isZero();
    }

    @Test
    void recoveryCreditsVerifiedFactsWithoutChannelOrCurrentPost() {
        paid();
        doThrow(new IllegalStateException("revoked")).when(scope).require();
        var job = recoveryJob();
        job.runOnce(100);
        balance(12, 0);
        verify(port, never()).queryOrder(argThat(identity -> identity.orderId() == orderId));
        verify(port, never()).requestRefund(any());
    }

    @Test
    void unknownInboxIsDurableBeforeAnyChannelQuery() {
        when(port.verifyNotification(any(), any())).thenReturn(true);
        when(port.parseNotification(any(), any())).thenReturn(new PaymentPort.NotificationHint("unknown", "PFWmissing", null, "xpay_goods_deliver_notify"));
        var job = recoveryJob();
        long id = job.acceptNotification(java.util.Map.of(), "notification".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        verify(port, never()).queryOrder(any());
        assertThat(jdbc.queryForObject("SELECT payload IS NOT NULL FROM pf_payment_notification_inbox WHERE id = ?", Boolean.class, id)).isTrue();
        job.runOnce(100);
        assertThat(jdbc.queryForObject("SELECT status FROM pf_payment_notification_inbox WHERE id = ?", String.class, id)).isEqualTo("UNKNOWN");
    }

    @Test
    void staleRecoveryLeaseCannotCompleteOrPostponeNewWorkerTask() {
        paid();
        var job = recoveryJob();
        job.seedTasks(100);
        var task = job.claimTasks(100).stream().filter(row -> row.tenant() == tenant).findFirst().orElseThrow();
        jdbc.update("UPDATE pf_wallet_recovery_task SET lease_token = 'new-worker', lease_until = now() + interval '60 seconds' WHERE id = ?", task.id());
        assertThat(job.finishTask(task, true)).isFalse();
        assertThat(job.finishTask(task, false)).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM pf_wallet_recovery_task WHERE id = ?", String.class, task.id())).isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT lease_token FROM pf_wallet_recovery_task WHERE id = ?", String.class, task.id())).isEqualTo("new-worker");
    }

    @Test
    void acceptedRefundIdSurvivesAnExpiredRequestLease() {
        lateRefundAcknowledgement(false);
    }

    @Test
    void acceptedRefundIdSurvivesLeaseTakeoverWithoutOverwritingNewOwner() {
        lateRefundAcknowledgement(true);
    }

    private void lateRefundAcknowledgement(boolean takeover) {
        paidAndCredited();
        doAnswer(call -> {
            var request = call.getArgument(0, PaymentPort.RefundRequest.class);
            if (takeover) jdbc.update("UPDATE pf_refund_order SET lease_token = 'new-owner', lease_until = clock_timestamp() + interval '60 seconds' WHERE tenant_id = ? AND wallet_id = ? AND refund_no = ?",
                    tenant, wallet.id(), request.refundNo());
            else jdbc.update("UPDATE pf_refund_order SET lease_until = clock_timestamp() - interval '1 second' WHERE tenant_id = ? AND wallet_id = ? AND refund_no = ?",
                    tenant, wallet.id(), request.refundNo());
            return new PaymentPort.ChannelRefundResult("PROCESSING", request.order().orderNo(), request.refundNo(), "accepted-refund-" + orderId,
                    null, "UNVERIFIED", null, null);
        }).when(port).requestRefund(any());
        var refund = refunds.requestRefund(orderId, "late-ack", 0);
        var row = jdbc.queryForMap("SELECT channel_refund_id, lease_token, channel_state FROM pf_refund_order WHERE tenant_id = ? AND wallet_id = ? AND id = ?",
                tenant, wallet.id(), Long.parseLong(refund.id()));
        assertThat(row.get("channel_refund_id")).isEqualTo("accepted-refund-" + orderId);
        if (takeover) assertThat(row.get("lease_token")).isEqualTo("new-owner");
        else assertThat((String) row.get("lease_token")).isNotBlank();
        assertThat(refunds.requestRefund(orderId, "late-ack", 0).id()).isEqualTo(refund.id());
        verify(port, times(1)).requestRefund(any());
        balance(0, 12);
    }

    @Test
    void databaseRejectsTerminalRefundsWithoutExplicitTypeAndStatus() {
        paidAndCredited();
        var refund = refunds.requestRefund(orderId, "bad-terminal", 0);
        for (String state : new String[]{"SUCCEEDED", "FAILED"}) {
            int status = "SUCCEEDED".equals(state) ? 8 : 7;
            assertThatThrownBy(() -> jdbc.update("UPDATE pf_refund_order SET channel_state = ?, channel_refund_id = ?, order_type = NULL, order_status = ? WHERE tenant_id = ? AND wallet_id = ? AND id = ?",
                    state, "invalid-" + orderId, status, tenant, wallet.id(), Long.parseLong(refund.id())))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThatThrownBy(() -> jdbc.update("UPDATE pf_refund_order SET channel_state = ?, channel_refund_id = ?, order_type = 1, order_status = NULL WHERE tenant_id = ? AND wallet_id = ? AND id = ?",
                    state, "invalid-" + orderId, tenant, wallet.id(), Long.parseLong(refund.id())))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
        balance(0, 12);
    }

    @Test
    void frozenWalletCannotApproveNewManualDebit() {
        paidAndCredited();
        long adjustment = adjustments.submit(-1, "new debit", "frozen-debit");
        jdbc.update("UPDATE pf_wallet SET spending_frozen = TRUE WHERE tenant_id = ? AND id = ?", tenant, wallet.id());
        wallet = new WalletScope.Wallet(tenant, wallet.id(), wallet.orgId(), IdWorker.getId(), IdWorker.getId(), "refund-test");
        assertThatThrownBy(() -> adjustments.review(adjustment, true, "checked"))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM pf_manual_point_adjustment WHERE tenant_id = ? AND wallet_id = ? AND id = ?", String.class,
                tenant, wallet.id(), adjustment)).isEqualTo("SUBMITTED");
        balance(12, 0);
    }

    @Test
    void inboxReplayUnderAnotherConfiguredTenantCannotReturnTheOriginalInbox() {
        when(port.verifyNotification(any(), any())).thenReturn(true);
        var props = new WechatXPayProperties();
        props.setAppid("test-app");
        props.setOfferId("test-offer");
        props.setNotificationTenantId(tenant);
        var beans = new StaticListableBeanFactory();
        beans.addBean("channel", port);
        var job = new WalletRecoveryJob(dataSource, transactionManager, payment, refunds, props, beans.getBeanProvider(PaymentPort.class));
        byte[] body = ("same-channel-body-" + orderId).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        long id = job.acceptNotification(java.util.Map.of(), body);
        props.setNotificationTenantId(tenant + 1);
        assertThatThrownBy(() -> job.acceptNotification(java.util.Map.of(), body)).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT tenant_id FROM pf_payment_notification_inbox WHERE id = ?", Long.class, id)).isEqualTo(tenant);
    }

    @Test
    void notificationForAnotherTenantOrderStaysUnknownWithoutBindingOrQuerying() {
        when(port.verifyNotification(any(), any())).thenReturn(true);
        var properties = new WechatXPayProperties();
        properties.setAppid("test-app");
        properties.setOfferId("test-offer");
        long configuredTenant = tenant + 1;
        properties.setNotificationTenantId(configuredTenant);
        var beans = new StaticListableBeanFactory();
        beans.addBean("channel", port);
        var job = new WalletRecoveryJob(dataSource, transactionManager, payment, refunds, properties, beans.getBeanProvider(PaymentPort.class));
        byte[] body = ("cross-tenant-" + orderId).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(port.parseNotification(any(), eq(body))).thenReturn(new PaymentPort.NotificationHint("event", "PFW" + orderId, null, "xpay_goods_deliver_notify"));
        long inbox = job.acceptNotification(java.util.Map.of(), body);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(job, "recoverInbox", 100);
        var row = jdbc.queryForMap("SELECT tenant_id, wallet_id, order_id, status FROM pf_payment_notification_inbox WHERE id = ?", inbox);
        assertThat(row.get("tenant_id")).isEqualTo(configuredTenant);
        assertThat(row.get("wallet_id")).isNull();
        assertThat(row.get("order_id")).isNull();
        assertThat(row.get("status")).isEqualTo("UNKNOWN");
        verify(port, never()).queryOrder(argThat(order -> order.orderId() == orderId));
        assertThat(jdbc.queryForObject("SELECT payment_state FROM pf_recharge_order WHERE id = ?", String.class, orderId)).isEqualTo("CREATED");
        balance(0, 0);
        assertThat(job.acceptNotification(java.util.Map.of(), body)).isEqualTo(inbox);
    }

    @Test
    void sameTenantNotificationCanBindAndReplayWithoutChangingScope() {
        when(port.verifyNotification(any(), any())).thenReturn(true);
        byte[] body = ("same-tenant-" + orderId).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(port.parseNotification(any(), eq(body))).thenReturn(new PaymentPort.NotificationHint("event", "PFW" + orderId, null, "xpay_goods_deliver_notify"));
        when(port.queryOrder(argThat(order -> order != null && order.orderId() == orderId))).thenReturn(
                new PaymentPort.ChannelQueryResult("SUCCEEDED", "PFW" + orderId, "XPAY", "test-app", "test-offer", 0,
                        "payer", "wxpay-" + orderId, 100L, "FEN", Instant.now(), 0));
        var job = recoveryJob();
        long inbox = job.acceptNotification(java.util.Map.of(), body);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(job, "recoverInbox", 100);
        var row = jdbc.queryForMap("SELECT tenant_id, wallet_id, order_id, status FROM pf_payment_notification_inbox WHERE id = ?", inbox);
        assertThat(row.get("tenant_id")).isEqualTo(tenant);
        assertThat(row.get("wallet_id")).isEqualTo(wallet.id());
        assertThat(row.get("order_id")).isEqualTo(orderId);
        assertThat(row.get("status")).isEqualTo("PROCESSED");
        assertThat(job.acceptNotification(java.util.Map.of(), body)).isEqualTo(inbox);
        verify(port, times(1)).queryOrder(argThat(order -> order.orderId() == orderId));
    }

    @Test
    void taskLeaseExpiringWhileWaitingForRowLockCannotBeCompleted() throws Exception {
        paid();
        var job = recoveryJob();
        job.seedTasks(100);
        var task = job.claimTasks(100).stream().filter(row -> row.tenant() == tenant).findFirst().orElseThrow();
        jdbc.update("UPDATE pf_wallet_recovery_task SET lease_until = clock_timestamp() + interval '1 second' WHERE id = ?", task.id());
        var locked = new java.util.concurrent.CountDownLatch(1);
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var holder = pool.submit(() -> tx.execute(status -> {
                jdbc.queryForObject("SELECT id FROM pf_wallet_recovery_task WHERE id = ? FOR UPDATE", Long.class, task.id());
                locked.countDown();
                await(release);
                return null;
            }));
            assertThat(locked.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var worker = pool.submit(() -> tx.execute(status -> {
                jdbc.queryForObject("SELECT now()", java.sql.Timestamp.class);
                started.countDown();
                return job.finishTask(task, true);
            }));
            assertThat(started.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
            while (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT lease_until <= clock_timestamp() FROM pf_wallet_recovery_task WHERE id = ?", Boolean.class, task.id()))) {
                if (System.nanoTime() > deadline) throw new IllegalStateException("lease did not expire");
                Thread.sleep(10);
            }
            release.countDown();
            holder.get(3, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(worker.get(3, java.util.concurrent.TimeUnit.SECONDS)).isFalse();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT status FROM pf_wallet_recovery_task WHERE id = ?", String.class, task.id())).isEqualTo("RUNNING");
    }

    private static void await(java.util.concurrent.CountDownLatch latch) {
        try {
            if (!latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("barrier timeout");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    @Test
    void concurrentSameKeyFreezesOnceAndCallsChannelOnce() throws Exception {
        paidAndCredited();
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<String> operation = () -> {
                if (!start.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                return refunds.requestRefund(orderId, "concurrent", 0).id();
            };
            var first = pool.submit(operation);
            var second = pool.submit(operation);
            start.countDown();
            assertThat(first.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(second.get(10, java.util.concurrent.TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
        balance(0, 12);
        verify(port, times(1)).requestRefund(any());
    }

    @Test
    void refundRecoveryNeverResubmitsAnUnknownRefund() {
        paidAndCredited();
        var refund = refunds.requestRefund(orderId, "unknown", 0);
        jdbc.update("UPDATE pf_refund_order SET next_attempt_at = now() WHERE tenant_id = ? AND wallet_id = ? AND id = ?", tenant, wallet.id(), Long.parseLong(refund.id()));
        when(port.queryRefund(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var request = call.getArgument(0, PaymentPort.RefundRequest.class);
            return new PaymentPort.ChannelRefundResult("UNKNOWN", request.order().orderNo(), request.refundNo(), null, null, "UNVERIFIED", null, null);
        });
        recoveryJob().runOnce(100);
        verify(port, times(1)).requestRefund(any());
        balance(0, 12);
    }

    @Test
    void failedNotificationSignatureCannotCreateInboxRows() {
        when(port.verifyNotification(any(), any())).thenReturn(false);
        assertThatThrownBy(() -> recoveryJob().acceptNotification(java.util.Map.of(), new byte[]{1}))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_payment_notification_inbox WHERE tenant_id = ?", Long.class, tenant)).isZero();
    }

    @Test
    void disabledChannelStillAllowsRefundPointRecovery() {
        paidAndCredited();
        var refund = refunds.requestRefund(orderId, "refund-1", 0);
        var failing = spy(points);
        doThrow(new IllegalStateException("ledger unavailable")).when(failing)
                .consumeReserve(any(), anyString(), anyLong(), anyString(), anyString(), anyString(), anyString());
        var service = new RefundPaymentService(dataSource, transactionManager, scope, failing, payment, new PaymentFactValidator());
        assertThatThrownBy(() -> service.processRefundFact(tenant, wallet.id(), orderId, fact(refund, "SUCCEEDED", 8)))
                .isInstanceOf(IllegalStateException.class);
        jdbc.update("UPDATE pf_refund_order SET next_attempt_at = now() WHERE tenant_id = ? AND wallet_id = ? AND id = ?", tenant, wallet.id(), Long.parseLong(refund.id()));
        var beans = new StaticListableBeanFactory();
        new WalletRecoveryJob(dataSource, transactionManager, payment, refunds, new WechatXPayProperties(), beans.getBeanProvider(PaymentPort.class)).runOnce(100);
        balance(0, 0);
    }

    @Test
    void migrationUndoRefusesNonEmptyFactsAndRollsBackEarlierSchemaChanges() {
        paidAndCredited();
        var refund = refunds.requestRefund(orderId, "undo-protected", 0);
        long refundId = Long.parseLong(refund.id());
        when(port.verifyNotification(any(), any())).thenReturn(true);
        byte[] body = ("undo-protected-" + orderId).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        long inboxId = recoveryJob().acceptNotification(java.util.Map.of(), body);
        String before = jdbc.queryForObject("SELECT to_jsonb(r)::text FROM pf_refund_order r WHERE id = ?", String.class, refundId);
        var indexes = jdbc.queryForList("SELECT tablename, indexname, indexdef FROM pg_indexes WHERE schemaname = current_schema() ORDER BY indexname");
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            for (String file : new String[]{"906__undo_pf_wallet_recovery", "905__undo_pf_wallet_manual_adjustment", "904__undo_pf_wallet_refund"}) {
                var resource = new org.springframework.core.io.ClassPathResource("db/migration-undo/platform-wallet/V20261111." + file + ".sql");
                var populator = new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(resource);
                // PostgreSQL DO块内部含分号；整份脚本交给PG解析，沿用调用方事务。
                populator.setSeparator(org.springframework.jdbc.datasource.init.ScriptUtils.EOF_STATEMENT_SEPARATOR);
                populator.execute(dataSource);
            }
        })).isInstanceOf(org.springframework.dao.DataAccessException.class)
                .rootCause().hasMessageContaining("Payment, refund or notification data exists; refund schema rollback refused");
        assertThat(jdbc.queryForObject("SELECT to_jsonb(r)::text FROM pf_refund_order r WHERE id = ?", String.class, refundId)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT payload FROM pf_payment_notification_inbox WHERE id = ?", byte[].class, inboxId)).isEqualTo(body);
        assertThat(jdbc.queryForList("SELECT tablename, indexname, indexdef FROM pg_indexes WHERE schemaname = current_schema() ORDER BY indexname")).isEqualTo(indexes);
        assertThat(jdbc.queryForObject("SELECT to_regclass('pf_manual_point_adjustment')::text", String.class)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT to_regclass('pf_wallet_recovery_task')::text", String.class)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = 'pf_payment_notification_inbox' AND column_name = 'next_attempt_at'", Long.class)).isEqualTo(1);
        balance(0, 12);
    }

    private WalletRecoveryJob recoveryJob() {
        var props = new WechatXPayProperties();
        props.setAppid("test-app");
        props.setOfferId("test-offer");
        props.setNotificationTenantId(tenant);
        var beans = new StaticListableBeanFactory();
        beans.addBean("channel", port);
        return new WalletRecoveryJob(dataSource, transactionManager, payment, refunds, props, beans.getBeanProvider(PaymentPort.class));
    }

    private void paid() {
        payment.processPaymentFact(tenant, wallet.id(), orderId, new PaymentPort.ChannelQueryResult("SUCCEEDED", "PFW" + orderId,
                "XPAY", "test-app", "test-offer", 0, "payer", "wxpay-" + orderId, 100L, "FEN", Instant.now(), 0));
    }

    private void paidAndCredited() {
        paid();
        payment.fulfillOrder(tenant, wallet.id(), orderId);
    }

    private PaymentPort.ChannelRefundResult fact(RefundPaymentService.RefundResponse refund, String state, int status) {
        return new PaymentPort.ChannelRefundResult(state, "PFW" + orderId, refund.refundNo(), "wx-refund-" + refund.id(), 100L, "FEN", 1, status);
    }

    private PaymentPort.ChannelRefundResult externalFact() {
        return new PaymentPort.ChannelRefundResult("SUCCEEDED", "PFW" + orderId, "external-" + orderId,
                "wx-external-" + orderId, 100L, "FEN", 8, 8);
    }

    private void balance(long available, long reserved) {
        var row = jdbc.queryForMap("SELECT available_points, reserved_points FROM pf_point_account WHERE tenant_id = ? AND wallet_id = ?", tenant, wallet.id());
        assertThat(((Number) row.get("available_points")).longValue()).isEqualTo(available);
        assertThat(((Number) row.get("reserved_points")).longValue()).isEqualTo(reserved);
    }

    private void permissions() {
        jdbc.execute("ALTER TABLE system_role ADD COLUMN IF NOT EXISTS deleted SMALLINT NOT NULL DEFAULT 0");
        jdbc.execute("ALTER TABLE system_role_menu ADD COLUMN IF NOT EXISTS deleted SMALLINT NOT NULL DEFAULT 0");
        jdbc.execute("ALTER TABLE system_menu ADD COLUMN IF NOT EXISTS deleted SMALLINT NOT NULL DEFAULT 0");
        long role = IdWorker.getId();
        jdbc.update("INSERT INTO system_role (id, code, status, tenant_id) VALUES (?, 'refund-test', 0, ?)", role, tenant);
        for (String permission : new String[]{"platform:wallet:refund", "platform:wallet:adjustment:submit", "platform:wallet:adjustment:review"}) {
            long menu = IdWorker.getId();
            jdbc.update("INSERT INTO system_menu (id, permission, status) VALUES (?, ?, 0)", menu, permission);
            jdbc.update("INSERT INTO system_role_menu (id, role_id, menu_id, tenant_id) VALUES (?, ?, ?, ?)", IdWorker.getId(), role, menu, tenant);
        }
    }
}
