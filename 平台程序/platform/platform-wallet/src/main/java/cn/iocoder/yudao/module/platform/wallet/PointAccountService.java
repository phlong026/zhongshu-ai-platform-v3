package cn.iocoder.yudao.module.platform.wallet;

import cn.hutool.crypto.digest.DigestUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.List;

/**
 * 移植 commerce PointAccountService 的条件更新 + RETURNING + 只追加流水。
 * 原 reserve/release 不幂等，B 只允许 WalletChargeService 在持有业务单的事务内调用。
 */
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "platform.legacy-design-bridge.enabled", havingValue = "false")
public class PointAccountService {

    private record Balance(long available, long reserved) {
    }

    private final JdbcTemplate jdbc;

    public PointAccountService(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    long credit(WalletScope.Wallet wallet, String type, long amount, String bizType, String bizId,
                String key, String reason) {
        WalletScope.requireTransaction();
        requirePositive(amount);
        requireText(type, 40);
        requireText(bizType, 64);
        requireText(bizId, 128);
        requireText(key, 128);
        requireText(reason, 512);
        String hash = requestHash(wallet, type, amount, bizType, bizId, reason);
        // scope.require 已锁钱包；跨钱包同键仍由数据库唯一键整体回滚余额更新。
        var previous = jdbc.query("SELECT id, wallet_id, actor_id, delta, request_hash FROM pf_point_ledger WHERE tenant_id = ? AND wallet_id = ? AND idempotency_key = ?",
                (rs, row) -> {
                    if (rs.getLong("wallet_id") != wallet.id() || rs.getLong("actor_id") != wallet.actorId()
                            || rs.getLong("delta") != amount || !hash.equals(rs.getString("request_hash"))) {
                        throw WalletException.conflict();
                    }
                    return rs.getLong("id");
                }, wallet.tenantId(), wallet.id(), key);
        if (!previous.isEmpty()) return previous.get(0);
        var balances = jdbc.query("""
                UPDATE pf_point_account SET available_points = available_points + ?, version = version + 1, update_time = now()
                WHERE tenant_id = ? AND wallet_id = ?
                  AND available_points::numeric + reserved_points::numeric + ? <= 9223372036854775807
                RETURNING available_points, reserved_points
                """, (rs, row) -> new Balance(rs.getLong(1), rs.getLong(2)), amount, wallet.tenantId(), wallet.id(), amount);
        if (balances.isEmpty()) throw WalletException.balanceLimit();
        Balance after = balances.get(0);
        try {
            return append(wallet, type, amount, after, bizType, bizId, key, hash, reason);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 不跨钱包重读命中行；异常必须穿出调用方事务，余额与流水一起回滚。
            throw WalletException.conflict();
        }
    }

    void reserve(WalletScope.Wallet wallet, long amount) {
        update(wallet, "available_points = available_points - ?, reserved_points = reserved_points + ?",
                amount, "available_points >= ?");
    }

    void releaseReserve(WalletScope.Wallet wallet, long amount) {
        update(wallet, "reserved_points = reserved_points - ?, available_points = available_points + ?",
                amount, "reserved_points >= ?");
    }

    long consumeReserve(WalletScope.Wallet wallet, long amount, String bizType, String bizId, String key) {
        return consumeReserve(wallet, "CHARGE", amount, bizType, bizId, key, "业务单确认消费");
    }

    /** 退款/人工审批使用各自业务单保护幂等；调用方持有钱包及业务单锁。 */
    long consumeReserve(WalletScope.Wallet wallet, String type, long amount, String bizType,
                        String bizId, String key, String reason) {
        requireText(type, 40);
        requireText(bizType, 64);
        requireText(bizId, 128);
        requireText(key, 128);
        requireText(reason, 512);
        Balance after = update(wallet, "reserved_points = reserved_points - ?", amount, "reserved_points >= ?");
        String hash = requestHash(wallet, type, -amount, bizType, bizId, reason);
        return append(wallet, type, -amount, after, bizType, bizId, key, hash, reason);
    }

    private Balance update(WalletScope.Wallet wallet, String set, long amount, String guard) {
        WalletScope.requireTransaction();
        requirePositive(amount);
        var args = new java.util.ArrayList<Object>();
        for (char character : set.toCharArray()) {
            if (character == '?') args.add(amount);
        }
        args.add(wallet.tenantId());
        args.add(wallet.id());
        if (guard != null) args.add(amount);
        var balances = jdbc.query("UPDATE pf_point_account SET " + set
                        + ", version = version + 1, update_time = now() WHERE tenant_id = ? AND wallet_id = ?"
                        + (guard == null ? "" : " AND " + guard) + " RETURNING available_points, reserved_points",
                (rs, row) -> new Balance(rs.getLong(1), rs.getLong(2)), args.toArray());
        if (balances.isEmpty()) throw WalletException.insufficient();
        return balances.get(0);
    }

    private long append(WalletScope.Wallet wallet, String type, long delta, Balance after,
                        String bizType, String bizId, String key, String hash, String reason) {
        long id = IdWorker.getId();
        jdbc.update("""
                INSERT INTO pf_point_ledger (id, tenant_id, wallet_id, actor_id, type, delta,
                    available_after, reserved_after, biz_type, biz_id, idempotency_key, request_hash, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, wallet.tenantId(), wallet.id(), wallet.actorId(), type, delta,
                after.available(), after.reserved(), bizType, bizId, key, hash, reason);
        return id;
    }

    static String requestHash(WalletScope.Wallet wallet, String type, long amount,
                              String bizType, String bizId, String reason) {
        // JSON 数组固定顺序与转义，避免拼接分隔符的歧义；SHA-256 仅用于请求比对。
        return DigestUtil.sha256Hex(JsonUtils.toJsonString(List.of(wallet.tenantId(), wallet.id(),
                wallet.actorId(), type, amount, bizType, bizId, reason)));
    }

    static void requirePositive(long amount) {
        if (amount <= 0) throw WalletException.invalidRequest();
    }

    static void requireText(String value, int limit) {
        if (value == null || value.isBlank() || value.length() > limit) throw WalletException.invalidRequest();
    }
}
