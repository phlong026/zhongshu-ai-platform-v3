package cn.iocoder.yudao.module.platform.wallet;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.function.Supplier;

/** 服务端业务计费；金额来自已验证报价，不映射为任意扣点 HTTP 接口。 */
@Service
@Slf4j
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "platform.legacy-design-bridge.enabled", havingValue = "false")
public class WalletChargeService {

    public record Charge(String id, String status, long amount, String ledgerId,
                         long chargedPoints, long settlementVersion) {
    }

    private record StoredCharge(long id, long walletId, long actorId, long amount, String bizType,
                                String bizId, String key, String hash, String status, Long ledgerId,
                                long chargedPoints, long version, String terminalOperation) {
        Charge response() {
            return new Charge(Long.toString(id), status, amount, ledgerId == null ? null : ledgerId.toString(),
                    chargedPoints, version);
        }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final WalletScope scope;
    private final PointAccountService points;

    public WalletChargeService(DataSource dataSource, PlatformTransactionManager transactionManager,
                               WalletScope scope, PointAccountService points) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(transactionManager);
        this.scope = scope;
        this.points = points;
    }

    public Charge reserve(String bizType, String bizId, long amount, String idempotencyKey) {
        PointAccountService.requireText(bizType, 64);
        PointAccountService.requireText(bizId, 128);
        PointAccountService.requireText(idempotencyKey, 128);
        PointAccountService.requirePositive(amount);
        return execute("reserve", () -> {
            var wallet = scope.require();
            if (Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT spending_frozen FROM pf_wallet WHERE tenant_id = ? AND id = ?", Boolean.class,
                    wallet.tenantId(), wallet.id()))) throw WalletException.unavailable();
            String hash = PointAccountService.requestHash(wallet, "RESERVE", amount, bizType, bizId, "");
            long id = IdWorker.getId();
            int inserted = jdbc.update("""
                    INSERT INTO pf_wallet_charge (id, tenant_id, wallet_id, actor_id, biz_type, biz_id,
                        amount, idempotency_key, request_hash, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'RESERVED') ON CONFLICT DO NOTHING
                    """, id, wallet.tenantId(), wallet.id(), wallet.actorId(), bizType, bizId, amount,
                    idempotencyKey, hash);
            if (inserted == 1) {
                points.reserve(wallet, amount);
                return new Charge(Long.toString(id), "RESERVED", amount, null, 0, -1);
            }
            var existing = jdbc.query("""
                    SELECT * FROM pf_wallet_charge WHERE tenant_id = ? AND wallet_id = ?
                    AND (idempotency_key = ? OR (biz_type = ? AND biz_id = ?)) FOR UPDATE
                    """, (rs, row) -> read(rs), wallet.tenantId(), wallet.id(), idempotencyKey, bizType, bizId);
            if (existing.size() != 1) throw WalletException.conflict();
            var charge = existing.get(0);
            if (charge.walletId() != wallet.id() || charge.actorId() != wallet.actorId()
                    || charge.amount() != amount || !charge.hash().equals(hash)
                    || !charge.key().equals(idempotencyKey)) throw WalletException.conflict();
            return charge.response();
        });
    }

    public Charge consume(String chargeId) {
        return finish(chargeId, true);
    }

    public Charge release(String chargeId) {
        return finish(chargeId, false);
    }

    private Charge finish(String chargeId, boolean consume) {
        long id;
        try {
            id = Long.parseLong(chargeId);
        } catch (NumberFormatException e) {
            throw WalletException.invalidRequest();
        }
        return execute(consume ? "consume" : "release", () -> {
            var wallet = scope.require();
            var rows = jdbc.query("SELECT * FROM pf_wallet_charge WHERE tenant_id = ? AND wallet_id = ? AND id = ? FOR UPDATE",
                    (rs, row) -> read(rs), wallet.tenantId(), wallet.id(), id);
            if (rows.isEmpty()) throw WalletException.unavailable();
            var charge = rows.get(0);
            if (charge.actorId() != wallet.actorId()) throw WalletException.conflict();
            String operation = consume ? "CONSUME" : "RELEASE";
            if (operation.equals(charge.terminalOperation())) return charge.response();
            return settleLocked(wallet, charge, consume ? charge.amount() : charge.chargedPoints(), true,
                    Math.addExact(charge.version(), 1), operation);
        });
    }

    /**
     * 可信持久任务的累计结算入口。原业务单必须已预留；不接收客户端任意扣点请求。
     * 结果补偿不依赖 CurrentPost 或原操作人在岗，使用原单 actor、tenant 与钱包归属记账。
     */
    public Charge settleToCumulativeForTask(long tenantId, long orgId, String bizType, String bizId,
                                           long cumulativeChargedPoints, boolean terminal, long version) {
        PointAccountService.requireText(bizType, 64);
        PointAccountService.requireText(bizId, 128);
        if (version < 0 || cumulativeChargedPoints < 0) throw WalletException.invalidRequest();
        return execute("settleTask", () -> {
            var walletIds = jdbc.queryForList("""
                    SELECT w.id FROM pf_wallet w JOIN system_dept d ON d.id = w.org_id
                    WHERE w.tenant_id = ? AND w.org_id = ? AND d.tenant_id = w.tenant_id FOR UPDATE OF w
                    """, Long.class, tenantId, orgId);
            if (walletIds.isEmpty()) throw WalletException.unavailable();
            long walletId = walletIds.get(0);
            var charges = jdbc.query("""
                    SELECT * FROM pf_wallet_charge
                    WHERE tenant_id = ? AND wallet_id = ? AND biz_type = ? AND biz_id = ? FOR UPDATE
                    """, (rs, row) -> read(rs), tenantId, walletId, bizType, bizId);
            if (charges.isEmpty()) throw WalletException.unavailable();
            var charge = charges.get(0);
            var wallet = new WalletScope.Wallet(tenantId, walletId, orgId, charge.actorId(), 0, "TASK_RECOVERY");
            return settleLocked(wallet, charge, cumulativeChargedPoints, terminal, version, "TASK_SETTLEMENT");
        });
    }

    private Charge settleLocked(WalletScope.Wallet wallet, StoredCharge charge, long cumulative,
                                boolean terminal, long version, String operation) {
        boolean alreadyTerminal = !"RESERVED".equals(charge.status());
        if (version == charge.version()) {
            if (cumulative == charge.chargedPoints() && terminal == alreadyTerminal
                    && (!terminal || operation.equals(charge.terminalOperation()))) return charge.response();
            throw WalletException.conflict();
        }
        if (version < charge.version() || cumulative < charge.chargedPoints() || cumulative > charge.amount()) {
            throw WalletException.conflict();
        }
        if (alreadyTerminal) throw WalletException.invalidState();
        long delta = cumulative - charge.chargedPoints();
        Long ledgerId = charge.ledgerId();
        if (delta > 0) {
            ledgerId = points.consumeReserve(wallet, delta, charge.bizType(), charge.bizId(),
                    "charge:" + charge.id() + ":v:" + version);
        }
        long remaining = charge.amount() - cumulative;
        if (terminal && remaining > 0) points.releaseReserve(wallet, remaining);
        String status = !terminal ? "RESERVED" : cumulative == 0 ? "RELEASED"
                : cumulative == charge.amount() ? "CONSUMED" : "SETTLED";
        jdbc.update("""
                UPDATE pf_wallet_charge SET status = ?, charged_points = ?, settlement_version = ?,
                    ledger_id = ?, terminal_operation = ?, update_time = now() WHERE tenant_id = ? AND wallet_id = ? AND id = ?
                """, status, cumulative, version, ledgerId, terminal ? operation : null, wallet.tenantId(), wallet.id(), charge.id());
        return new Charge(Long.toString(charge.id()), status, charge.amount(), ledgerId == null ? null : ledgerId.toString(),
                cumulative, version);
    }

    private Charge execute(String operation, Supplier<Charge> action) {
        try {
            return tx.execute(status -> action.get());
        } catch (DataAccessException e) {
            log.error("[walletCharge][operation={}] 数据库事务失败，余额与业务单整体回滚", operation, e);
            throw e;
        }
    }

    private static StoredCharge read(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new StoredCharge(rs.getLong("id"), rs.getLong("wallet_id"), rs.getLong("actor_id"),
                rs.getLong("amount"), rs.getString("biz_type"), rs.getString("biz_id"),
                rs.getString("idempotency_key"), rs.getString("request_hash"), rs.getString("status"),
                rs.getObject("ledger_id", Long.class), rs.getLong("charged_points"), rs.getLong("settlement_version"),
                rs.getString("terminal_operation"));
    }
}
