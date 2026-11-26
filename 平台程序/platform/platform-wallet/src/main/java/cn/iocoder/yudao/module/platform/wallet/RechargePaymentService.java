package cn.iocoder.yudao.module.platform.wallet;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** 移植 commerce RechargePaymentService：短事务/外呼/复验，支付事实与积分到账独立事务。 */
@Service
@Slf4j
@EnableConfigurationProperties(WechatXPayProperties.class)
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "platform.legacy-design-bridge.enabled", havingValue = "false")
public class RechargePaymentService {
    @org.springframework.beans.factory.annotation.Value(WalletRecoveryJob.ENABLED)
    private boolean recoveryEnabled = true;

    public record OrderResponse(String id, String orderNo, String status, String creditState,
                                long priceFen, long basePoints, long bonusPoints, Instant createTime) {
    }

    record OrderSnapshot(long id, long tenantId, long walletId, long orgId, long actorId, long postId,
                         long bindingId, long planId, String orderNo, String appid, String openid,
                         String channel, String offerId, int environment, long priceFen, long basePoints,
                         long bonusPoints, String productId, String paymentState, String creditState,
                         String requestHash, Instant createTime, boolean company, long planVersion) {
        PaymentPort.OrderIdentity identity() {
            return new PaymentPort.OrderIdentity(tenantId, walletId, id, orderNo, channel, appid, offerId,
                    environment, openid, priceFen);
        }

        OrderResponse response() {
            return new OrderResponse(Long.toString(id), orderNo, paymentState, creditState,
                    priceFen, basePoints, bonusPoints, createTime);
        }

        WalletScope.Wallet wallet() {
            return new WalletScope.Wallet(tenantId, walletId, orgId, actorId, postId, "PAYMENT_RECOVERY");
        }
    }

    private record Payer(long bindingId, String openid) {
    }

    private record PrepayClaim(OrderSnapshot order, long attemptId, String token) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final WalletScope scope;
    private final PointAccountService points;
    private final PaymentFactValidator validator;
    private final WechatXPayProperties properties;
    private final ObjectProvider<PaymentPort> ports;

    public RechargePaymentService(DataSource dataSource, PlatformTransactionManager transactionManager,
                                  WalletScope scope, PointAccountService points, PaymentFactValidator validator,
                                  WechatXPayProperties properties, ObjectProvider<PaymentPort> ports) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setTimeout(10);
        this.scope = scope;
        this.points = points;
        this.validator = validator;
        this.properties = properties;
        this.ports = ports;
    }

    public OrderResponse createOrder(String planId, String key) {
        return createOrder(parseId(planId), key);
    }

    public OrderResponse createOrder(long planId, String key) {
        return createOrder(planId,key,null);
    }

    public OrderResponse createCompanyOrder(String planId, String key, long version) {
        if (version < 1) throw WalletException.invalidRequest();
        requireCompanyRecovery();
        return createOrder(parseId(planId),key,version);
    }

    private OrderResponse createOrder(long planId, String key, Long version) {
        requireOnlineRecharge();
        requireChannel();
        PointAccountService.requireText(key, 128);
        return transaction("createOrder", () -> {
            var wallet = scope.require();
            var payer = requirePayer(wallet);
            String hash = DigestUtil.sha256Hex(JsonUtils.toJsonString(List.of(wallet.tenantId(), wallet.id(),
                    wallet.actorId(), wallet.postId(), planId, payer.bindingId(), properties.getAppid(), payer.openid())));
            if (version != null) hash = DigestUtil.sha256Hex("COMPANY:"+version+":"+hash);
            var replay = jdbc.query("SELECT * FROM pf_recharge_order WHERE tenant_id = ? AND wallet_id = ? AND idempotency_key = ?",
                    (rs, row) -> readOrder(rs), wallet.tenantId(), wallet.id(), key);
            if (!replay.isEmpty()) {
                if (!hash.equals(replay.get(0).requestHash())) throw WalletException.conflict();
                return replay.get(0).response();
            }
            var plans = jdbc.queryForList("SELECT * FROM pf_recharge_plan WHERE tenant_id = ? AND id = ? AND status = 'ACTIVE' FOR SHARE",
                    wallet.tenantId(), planId);
            if (version != null) {
                if (plans.isEmpty() || ((Number)plans.get(0).get("version")).longValue()!=version)
                    throw new ServiceException(409,"套餐已调整，请重新选择");
                requireActiveCompany(wallet.orgId());
                closeUnquotedOrders(wallet);
                if (Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS(SELECT 1 FROM pf_recharge_order o WHERE o.tenant_id=? AND org_id=? AND actor_id=? AND post_id=?
                          AND credit_target='COMPANY' AND credit_state <> 'BLOCKED'
                          AND (payment_state IN ('CREATED','PENDING','UNKNOWN')
                               OR (payment_state='SUCCEEDED' AND credit_state='PENDING')))
                        """,Boolean.class,wallet.tenantId(),wallet.orgId(),wallet.actorId(),wallet.postId())))
                    throw new ServiceException(409,"已有待处理的充值订单，请刷新");
            }
            if (plans.isEmpty()) throw WalletException.planUnavailable();
            var plan = plans.get(0);
            String productId = (String) plan.get("wx_product_id");
            if (productId == null || productId.isBlank()) throw WalletException.planUnavailable();
            long base = ((Number) plan.get("base_points")).longValue();
            long bonus = ((Number) plan.get("bonus_points")).longValue();
            Math.addExact(base, bonus);
            long id = IdWorker.getId();
            int inserted = jdbc.update("""
                    INSERT INTO pf_recharge_order (id, tenant_id, wallet_id, org_id, actor_id, post_id, binding_id,
                        order_no, plan_id, plan_snapshot, price_fen, base_points, bonus_points,
                        payer_appid, payer_openid, channel, offer_id, environment, idempotency_key, request_hash,credit_target)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, 'XPAY', ?, ?, ?, ?,?)
                    ON CONFLICT DO NOTHING
                    """, id, wallet.tenantId(), wallet.id(), wallet.orgId(), wallet.actorId(), wallet.postId(), payer.bindingId(),
                    "PFW" + id, planId, JsonUtils.toJsonString(Map.of("name", plan.get("name"), "version", plan.get("version"),
                            "priceFen", plan.get("price_fen"), "basePoints", base, "bonusPoints", bonus, "wxProductId", productId)),
                    plan.get("price_fen"), base, bonus, properties.getAppid(), payer.openid(), properties.getOfferId(),
                    properties.getEnvironment(), key, hash,version==null?"WALLET":"COMPANY");
            if (inserted != 1) throw WalletException.conflict();
            return order(wallet.tenantId(), wallet.id(), id).response();
        });
    }

    public OrderResponse getOrder(String id) {
        return transaction("getOrder", () -> {
            var wallet = scope.require();
            requirePermission(wallet, "platform:wallet:read");
            return order(wallet.tenantId(), wallet.id(), parseId(id)).response();
        });
    }

    public PageResult<OrderResponse> listOrders(int pageNo, int pageSize) {
        if (pageNo < 1 || pageSize < 1 || pageSize > 100) throw WalletException.invalidRequest();
        return transaction("listOrders", () -> {
            var wallet = scope.require();
            requirePermission(wallet, "platform:wallet:read");
            long total = jdbc.queryForObject("SELECT count(*) FROM pf_recharge_order WHERE tenant_id = ? AND wallet_id = ?",
                    Long.class, wallet.tenantId(), wallet.id());
            var list = jdbc.query("SELECT * FROM pf_recharge_order WHERE tenant_id = ? AND wallet_id = ? ORDER BY create_time DESC, id DESC LIMIT ? OFFSET ?",
                    (rs, row) -> readOrder(rs).response(), wallet.tenantId(), wallet.id(), pageSize, (long) (pageNo - 1) * pageSize);
            return new PageResult<>(list, total);
        });
    }

    public Map<String, String> payParams(String id, String code, String attemptKey) {
        return payParams(id,code,attemptKey,false);
    }

    public Map<String, String> payParams(String id, String code, String attemptKey, boolean company) {
        requireNoTransaction();
        requireOnlineRecharge();
        if (company) requireCompanyRecovery();
        PaymentPort port = requireChannel();
        PointAccountService.requireText(code, 512);
        PointAccountService.requireText(attemptKey, 128);
        long orderId = parseId(id);
        PrepayClaim claim = transaction("claimPrepay", () -> {
            var wallet = scope.require();
            var payer = requirePayer(wallet);
            var order = order(wallet.tenantId(), wallet.id(), orderId);
            if (order.company()!=company) throw WalletException.unavailable();
            requireCurrentQuote(order);
            requireOriginalPayer(wallet, payer, order);
            requirePayable(order);
            var attempts = jdbc.queryForList("""
                    SELECT id FROM pf_payment_attempt
                    WHERE tenant_id = ? AND wallet_id = ? AND order_id = ? AND attempt_key = ? FOR UPDATE
                    """, wallet.tenantId(), wallet.id(), orderId, attemptKey);
            if (!attempts.isEmpty()) {
                // 不保存一次码/其指纹，也不把同 attempt 的不同 code 误认作相同请求。
                throw new ServiceException(409, "支付尝试已使用或处理中，请使用新的登录码与尝试编号");
            }
            jdbc.update("""
                    UPDATE pf_payment_attempt SET state = 'UNKNOWN' WHERE tenant_id = ? AND wallet_id = ?
                    AND order_id = ? AND state = 'PROCESSING' AND lease_until <= clock_timestamp()
                    """, wallet.tenantId(), wallet.id(), orderId);
            long busy = jdbc.queryForObject("""
                    SELECT count(*) FROM pf_payment_attempt WHERE tenant_id = ? AND wallet_id = ? AND order_id = ?
                    AND state = 'PROCESSING' AND lease_until > clock_timestamp()
                    """, Long.class, wallet.tenantId(), wallet.id(), orderId);
            if (busy > 0) throw new ServiceException(409, "支付参数正在生成");
            long attemptId = IdWorker.getId();
            String token = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO pf_payment_attempt (id, tenant_id, wallet_id, order_id, attempt_key, state, lease_token, lease_until)
                    VALUES (?, ?, ?, ?, ?, 'PROCESSING', ?, clock_timestamp() + interval '60 seconds')
                    """, attemptId, wallet.tenantId(), wallet.id(), orderId, attemptKey, token);
            return new PrepayClaim(order, attemptId, token);
        });
        try {
            var result = port.createPrepay(claim.order().identity(), claim.order().productId(), code);
            if (result == null || result.callParams() == null) throw new ServiceException(503, "支付参数未就绪");
            var params = result.callParams();
            for (String field : List.of("signData", "mode", "paySig", "signature")) {
                if (params.get(field) == null || params.get(field).isBlank()) throw new ServiceException(503, "支付参数不完整");
            }
            Map<String, String> safeParams = Map.of("signData", params.get("signData"), "mode", params.get("mode"),
                    "paySig", params.get("paySig"), "signature", params.get("signature"));
            return transaction("completePrepay", () -> {
                var wallet = scope.require();
                var payer = requirePayer(wallet);
                var currentOrder = order(wallet.tenantId(), wallet.id(), orderId);
                requireCurrentQuote(currentOrder);
                requireOriginalPayer(wallet, payer, currentOrder);
                requirePayable(currentOrder);
                int updated = jdbc.update("""
                        UPDATE pf_payment_attempt SET state = 'SUCCEEDED'
                        WHERE tenant_id = ? AND wallet_id = ? AND order_id = ? AND id = ?
                          AND lease_token = ? AND state = 'PROCESSING' AND lease_until > clock_timestamp()
                        """, wallet.tenantId(), wallet.id(), orderId, claim.attemptId(), claim.token());
                if (updated != 1) throw new ServiceException(409, "支付尝试已失效，请重新获取登录码");
                jdbc.update("UPDATE pf_recharge_order SET payment_state = 'PENDING', update_time = now() WHERE tenant_id = ? AND wallet_id = ? AND id = ?",
                        wallet.tenantId(), wallet.id(), orderId);
                return safeParams;
            });
        } catch (RuntimeException failure) {
            // code2session 的 code 绝不重试，失败/超时只保留尝试状态，不保存 code/session_key。
            try {
                jdbc.update("""
                        UPDATE pf_payment_attempt SET state = 'UNKNOWN' WHERE tenant_id = ? AND wallet_id = ?
                        AND order_id = ? AND id = ? AND lease_token = ? AND state = 'PROCESSING'
                        """, claim.order().tenantId(), claim.order().walletId(), orderId, claim.attemptId(), claim.token());
            } catch (DataAccessException cleanupFailure) {
                log.error("[payParams][order={} attempt={}] 写入UNKNOWN失败，租约到期后可恢复", orderId, claim.attemptId(), cleanupFailure);
            }
            log.warn("[payParams][order={} failureType={}] 支付尝试未签发", orderId, failure.getClass().getSimpleName());
            if (failure instanceof ServiceException businessFailure) throw businessFailure;
            // 未知 SDK 异常可能携带请求 URL/code；不把原始异常交给全局 HTTP 异常日志。
            throw new ServiceException(503, "支付参数暂不可用，请重新获取登录码后重试");
        }
    }

    /** 仅由可信渠道查单驱动；通知体不能直接调用此方法构造事实。 */
    public void processPaymentFact(long tenantId, long walletId, long orderId, PaymentPort.ChannelQueryResult fact) {
        requireNoTransaction();
        requireChannel();
        try {
            transaction("paymentFact", () -> {
                lockWallet(tenantId, walletId);
                var order = order(tenantId, walletId, orderId);
                validator.validate(order.identity(), fact);
                jdbc.update("""
                        INSERT INTO pf_payment_transaction (id, tenant_id, wallet_id, order_id, channel, appid,
                            offer_id, environment, transaction_id, order_type, amount_fen, paid_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                        """, IdWorker.getId(), tenantId, walletId, orderId, fact.channel(), fact.appid(), fact.offerId(),
                        fact.environment(), fact.channelTransactionId(), fact.orderType(), fact.amountCents(), Timestamp.from(fact.paidAt()));
                var recorded = jdbc.queryForList("""
                        SELECT transaction_id FROM pf_payment_transaction WHERE tenant_id = ? AND wallet_id = ? AND order_id = ?
                        AND channel = ? AND appid = ? AND offer_id = ? AND environment = ? AND amount_fen = ? AND order_type = ?
                        """, String.class, tenantId, walletId, orderId, fact.channel(), fact.appid(), fact.offerId(), fact.environment(), fact.amountCents(), fact.orderType());
                if (recorded.size() != 1 || !recorded.get(0).equals(fact.channelTransactionId())) throw WalletException.conflict();
                jdbc.update("""
                        UPDATE pf_recharge_order SET payment_state = 'SUCCEEDED',
                            credit_state = CASE WHEN credit_state = 'NOT_READY' THEN 'PENDING' ELSE credit_state END, update_time = now()
                        WHERE tenant_id = ? AND wallet_id = ? AND id = ?
                        """, tenantId, walletId, orderId);
                return null;
            });
        } catch (RuntimeException failure) {
            recordAnomaly(tenantId, walletId, orderId, "PAYMENT_FACT_REJECTED");
            throw failure;
        }
    }

    /** 只有可信渠道明确关闭同一未收款公司订单，才释放其充值入口。 */
    boolean processClosedCompanyOrder(long tenantId, long walletId, long orderId, PaymentPort.ChannelQueryResult fact) {
        requireNoTransaction();
        requireChannel();
        return transaction("paymentClosed", () -> {
            lockWallet(tenantId, walletId);
            var order = order(tenantId, walletId, orderId);
            if (!order.company()) return false;
            validator.validateClosed(order.identity(), fact);
            jdbc.update("""
                    UPDATE pf_recharge_order o SET payment_state='CLOSED', update_time=now()
                    WHERE tenant_id=? AND wallet_id=? AND id=? AND credit_target='COMPANY'
                      AND payment_state IN ('CREATED','PENDING','UNKNOWN') AND credit_state='NOT_READY'
                      AND NOT EXISTS (SELECT 1 FROM pf_payment_transaction p WHERE p.tenant_id=o.tenant_id
                          AND p.wallet_id=o.wallet_id AND p.order_id=o.id)
                    """, tenantId, walletId, orderId);
            return true;
        });
    }

    /** 已验证支付事实的独立入账事务；原用户撤岗不阻断已付订单恢复。 */
    public boolean fulfillOrder(long tenantId, long walletId, long orderId) {
        requireNoTransaction();
        return transaction("creditOrder", () -> {
            lockWallet(tenantId, walletId);
            var order = order(tenantId, walletId, orderId);
            if ("CREDITED".equals(order.creditState())) return true;
            if (!"SUCCEEDED".equals(order.paymentState()) || !"PENDING".equals(order.creditState())) return false;
            long facts = jdbc.queryForObject("SELECT count(*) FROM pf_payment_transaction WHERE tenant_id = ? AND wallet_id = ? AND order_id = ?",
                    Long.class, tenantId, walletId, orderId);
            if (facts != 1) throw WalletException.invalidState();
            if (order.company()) {
                CompanyRechargeLedger.change(jdbc,order,"COMPANY_RECHARGE",orderId,Math.addExact(order.basePoints(),order.bonusPoints()));
            } else {
                long base = points.credit(order.wallet(), "RECHARGE_BASE_CREDIT", order.basePoints(), "RECHARGE", Long.toString(orderId),
                        "recharge:" + orderId + ":base", "充值基础点");
                Long bonus = order.bonusPoints() == 0 ? null : points.credit(order.wallet(), "RECHARGE_BONUS_CREDIT", order.bonusPoints(),
                        "RECHARGE", Long.toString(orderId), "recharge:" + orderId + ":bonus", "充值赠送点");
                jdbc.update("""
                        INSERT INTO pf_recharge_credit (id, tenant_id, wallet_id, order_id, base_points, bonus_points, ledger_base_id, ledger_bonus_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """, IdWorker.getId(), tenantId, walletId, orderId, order.basePoints(), order.bonusPoints(), base, bonus);
            }
            jdbc.update("UPDATE pf_recharge_order SET credit_state = 'CREDITED', update_time = now() WHERE tenant_id = ? AND wallet_id = ? AND id = ?",
                    tenantId, walletId, orderId);
            return true;
        });
    }

    boolean onlineRechargeEnabled() {
        return properties.isOnlineRechargeEnabled();
    }

    private void requireOnlineRecharge() {
        if (!onlineRechargeEnabled()) throw new ServiceException(403, "线上充值暂未开放");
    }

    boolean companyPaymentsEnabled() {
        return onlineRechargeEnabled() && recoveryEnabled && channelEnabled();
    }

    private void requireCompanyRecovery() {
        if (!recoveryEnabled) throw new ServiceException(503, "公司积分支付暂未开放");
    }

    private boolean channelEnabled() {
        return properties.isEnabled() && "FEN".equals(properties.getPaidFeeUnit()) && ports.getIfAvailable()!=null
                && properties.getAppid()!=null && !properties.getAppid().isBlank()
                && properties.getOfferId()!=null && !properties.getOfferId().isBlank();
    }

    PaymentPort requireChannel() {
        if (!channelEnabled()) {
            throw new ServiceException(503, "支付通道尚未启用");
        }
        return ports.getObject();
    }

    private void requireActiveCompany(long orgId) {
        if (jdbc.queryForList("SELECT id FROM org_point_account WHERE org_id=? AND status='ACTIVE' FOR SHARE",Long.class,orgId).isEmpty())
            throw new ServiceException(409,"当前公司积分池未启用");
    }

    private void requireCurrentQuote(OrderSnapshot order) {
        if (!order.company()) return;
        requireActiveCompany(order.orgId());
        if (jdbc.queryForList("SELECT id FROM pf_recharge_plan WHERE tenant_id=? AND id=? AND version=? AND status='ACTIVE' FOR SHARE",
                Long.class,order.tenantId(),order.planId(),order.planVersion()).isEmpty())
            throw new ServiceException(409,"套餐已调整，请重新选择");
    }

    private void closeUnquotedOrders(WalletScope.Wallet wallet) {
        jdbc.update("""
                UPDATE pf_recharge_order o SET payment_state='CLOSED',update_time=now()
                WHERE tenant_id=? AND wallet_id=? AND actor_id=? AND post_id=? AND credit_target='COMPANY'
                AND payment_state='CREATED' AND NOT EXISTS (SELECT 1 FROM pf_recharge_plan p WHERE p.tenant_id=o.tenant_id
                  AND p.id=o.plan_id AND p.status='ACTIVE' AND p.version=(o.plan_snapshot->>'version')::bigint)
                """,wallet.tenantId(),wallet.id(),wallet.actorId(),wallet.postId());
    }

    void lockWallet(long tenantId, long walletId) {
        WalletScope.requireTransaction();
        if (jdbc.queryForList("SELECT id FROM pf_wallet WHERE tenant_id = ? AND id = ? FOR UPDATE", Long.class, tenantId, walletId).isEmpty()) {
            throw WalletException.unavailable();
        }
    }

    OrderSnapshot order(long tenantId, long walletId, long orderId) {
        var rows = jdbc.query("SELECT * FROM pf_recharge_order WHERE tenant_id = ? AND wallet_id = ? AND id = ? FOR UPDATE",
                (rs, row) -> readOrder(rs), tenantId, walletId, orderId);
        if (rows.isEmpty()) throw WalletException.unavailable();
        return rows.get(0);
    }

    private Payer requirePayer(WalletScope.Wallet wallet) {
        requirePermission(wallet, "platform:wallet:recharge");
        var rows = jdbc.query("""
                SELECT b.id, b.openid, b.mobile_hash, u.mobile FROM pf_wechat_binding b JOIN system_users u ON u.id = b.user_id
                WHERE b.appid = ? AND b.user_id = ? AND b.status = 'ACTIVE'
                  AND u.tenant_id = ? AND u.status = 0 AND u.deleted = 0 FOR SHARE OF b, u
                """, (rs, row) -> {
                    String mobile = rs.getString("mobile");
                    if (mobile == null || mobile.isBlank() || !DigestUtil.sha256Hex(mobile.trim()).equals(rs.getString("mobile_hash"))) {
                        throw WalletException.unavailable();
                    }
                    return new Payer(rs.getLong("id"), rs.getString("openid"));
                }, properties.getAppid(), wallet.actorId(), wallet.tenantId());
        if (rows.size() != 1 || rows.get(0).openid() == null || rows.get(0).openid().isBlank()) throw WalletException.unavailable();
        return rows.get(0);
    }

    void requirePermission(WalletScope.Wallet wallet, String permission) {
        WalletScope.requireTransaction();
        // 不复用请求缓存或全部角色并集；共享锁使撤权与最终签发有清晰先后。
        var permissions = jdbc.queryForList("""
                SELECT rm.id FROM system_role r JOIN system_role_menu rm ON rm.role_id = r.id AND rm.tenant_id = r.tenant_id
                JOIN system_menu m ON m.id = rm.menu_id
                WHERE r.tenant_id = ? AND r.code = ? AND r.status = 0 AND r.deleted = 0
                  AND rm.deleted = 0 AND m.deleted = 0 AND m.status = 0 AND m.permission = ? FOR SHARE OF r, rm, m
                """, Long.class, wallet.tenantId(), wallet.roleCode(), permission);
        if (permissions.isEmpty()) throw new ServiceException(403, "当前岗位没有钱包操作权限");
    }

    private void requireOriginalPayer(WalletScope.Wallet wallet, Payer payer, OrderSnapshot order) {
        if (order.actorId() != wallet.actorId() || order.postId() != wallet.postId()
                || order.bindingId() != payer.bindingId() || !order.openid().equals(payer.openid())
                || !order.appid().equals(properties.getAppid()) || !order.offerId().equals(properties.getOfferId())
                || order.environment() != properties.getEnvironment()) throw WalletException.unavailable();
    }

    private static void requirePayable(OrderSnapshot order) {
        if (!List.of("CREATED", "PENDING", "UNKNOWN").contains(order.paymentState()) || "BLOCKED".equals(order.creditState())) {
            throw WalletException.invalidState();
        }
    }

    private void recordAnomaly(long tenantId, long walletId, long orderId, String type) {
        try {
            jdbc.update("""
                    INSERT INTO pf_payment_anomaly_audit (id, tenant_id, wallet_id, order_id, anomaly_type, detail)
                    SELECT ?, tenant_id, wallet_id, id, ?, '可信支付事实校验失败，未修改余额'
                    FROM pf_recharge_order WHERE tenant_id = ? AND wallet_id = ? AND id = ?
                    """, IdWorker.getId(), type, tenantId, walletId, orderId);
            log.warn("[walletPaymentAudit][tenant={} wallet={} order={} type={}] 支付事实拒绝", tenantId, walletId, orderId, type);
        } catch (DataAccessException auditFailure) {
            log.error("[walletPaymentAudit][tenant={} wallet={} order={}] 支付异常审计持久化失败", tenantId, walletId, orderId, auditFailure);
        }
    }

    private static void requireNoTransaction() {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Payment phases must start outside an existing transaction");
        }
    }

    private <T> T transaction(String operation, Supplier<T> action) {
        try {
            return tx.execute(status -> {
                jdbc.execute("SET LOCAL lock_timeout = '3s'");
                return action.get();
            });
        } catch (DataAccessException e) {
            log.error("[walletPayment][operation={}] 数据库事务失败", operation, e);
            throw e;
        }
    }

    private static long parseId(String value) {
        try {
            long id = Long.parseLong(value);
            if (id <= 0) throw WalletException.invalidRequest();
            return id;
        } catch (NumberFormatException e) {
            throw WalletException.invalidRequest();
        }
    }

    private static OrderSnapshot readOrder(ResultSet rs) throws SQLException {
        var snapshot = JsonUtils.parseObject(rs.getString("plan_snapshot"), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
        return new OrderSnapshot(rs.getLong("id"), rs.getLong("tenant_id"), rs.getLong("wallet_id"), rs.getLong("org_id"),
                rs.getLong("actor_id"), rs.getLong("post_id"), rs.getLong("binding_id"), rs.getLong("plan_id"), rs.getString("order_no"),
                rs.getString("payer_appid"), rs.getString("payer_openid"), rs.getString("channel"), rs.getString("offer_id"),
                rs.getInt("environment"), rs.getLong("price_fen"), rs.getLong("base_points"), rs.getLong("bonus_points"),
                (String) snapshot.get("wxProductId"), rs.getString("payment_state"), rs.getString("credit_state"),
                rs.getString("request_hash"), rs.getTimestamp("create_time").toInstant(),"COMPANY".equals(rs.getString("credit_target")),
                ((Number)snapshot.getOrDefault("version",0)).longValue());
    }
}
