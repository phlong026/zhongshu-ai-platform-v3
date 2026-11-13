package cn.iocoder.yudao.module.commerce.orgpoint;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.franchise.api.OrgPointPort;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.commerce.enums.ErrorCodeConstants.ORG_MEMBER_QUOTA_EXCEEDED;
import static cn.iocoder.yudao.module.commerce.enums.ErrorCodeConstants.ORG_NOT_ENABLED;
import static cn.iocoder.yudao.module.commerce.enums.ErrorCodeConstants.ORG_POINT_INSUFFICIENT;
import static cn.iocoder.yudao.module.commerce.enums.ErrorCodeConstants.ORG_POINT_REPLAY_CONFLICT;
import static cn.iocoder.yudao.module.commerce.enums.ErrorCodeConstants.ORG_POINT_SUSPENDED;

/**
 * 公司积分池账本（24 号任务书批 A / B-1~B-7）。
 *
 * <p>核心合同（R1~R4 评审收敛）：
 * <ul>
 *   <li>一条流水一行、只追加：RESERVE=-N；SETTLE=+max(N-M,0)；RELEASE=+N。可用余额=SUM(available_delta)，
 *       在途预留=未终结 operation 的 -RESERVE 汇总；</li>
 *   <li>终态行复制原 RESERVE 的 operation_time/billing_month/org/actor/subject 快照，<b>禁止按当前时间重算月份</b>；</li>
 *   <li>预扣短事务顺序：锁账户行 FOR UPDATE → biz_key 幂等查 → 额度聚合（三态）→ 校验 → 余额 CAS → 写 RESERVE；
 *       提交后调用方才可外呼（付费外呼绝不进事务）；</li>
 *   <li>终态互斥：同一 operation 只允许一条 SETTLE 或 RELEASE（部分唯一索引兜底），结算/释放同事务锁原 RESERVE 行；</li>
 *   <li>金额由服务端按目录快照换算，禁止信任调用方传入积分数（四原则①快照冻价）。</li>
 * </ul>
 */
@Slf4j
@Service
public class OrgPointService implements OrgPointPort {

    public static final String TYPE_RESERVE = "RESERVE";
    public static final String TYPE_SETTLE = "SETTLE";
    public static final String TYPE_RELEASE = "RELEASE";
    public static final String TYPE_MANUAL_ADJUST = "MANUAL_ADJUST";
    public static final String TYPE_MIGRATION = "MIGRATION";

    private static final ZoneId MONTH_ZONE = ZoneId.of("Asia/Shanghai");
    private static final long MICROS_PER_CREDIT = 1_000_000L;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public OrgPointService(DataSource dataSource, PlatformTransactionManager transactionManager) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(transactionManager);
    }

    // ==================== 开通/账户（B-1/B-8） ====================

    /** 建池（幂等）：已存在则原样返回，不重建、不清零。 */
    public OrgPointAccountRow ensurePoolAccount(long orgId) {
        OrgPointAccountRow existing = findAccount(orgId);
        if (existing != null) {
            return existing;
        }
        tx.executeWithoutResult(status -> jdbc.update(
                "INSERT INTO org_point_account (id, org_id) VALUES (?, ?) ON CONFLICT (org_id) DO NOTHING",
                IdWorker.getId(), orgId));
        return findAccount(orgId);
    }

    /**
     * 开通赠点（仅首次）：biz_key=onboard-grant:{orgId} 唯一键保证重放不重复赠点；
     * 已赠过则 no-op 返回 false。
     */
    public boolean grantOnboardOnce(long orgId, long credits, String note) {
        if (credits <= 0) {
            return false;
        }
        ensurePoolAccount(orgId);
        Boolean granted = tx.execute(status -> {
            String bizKey = "onboard-grant:" + orgId;
            if (findByBizKey(bizKey) != null) {
                return false;
            }
            lockAccount(orgId);
            jdbc.update("UPDATE org_point_account SET balance = balance + ?, version = version + 1, "
                    + "update_time = now() WHERE org_id = ?", credits, orgId);
            insertLedger(new LedgerInsert(orgId, null, bizKey, null, TYPE_MANUAL_ADJUST, null,
                    null, null, credits, null, null, null, null,
                    note == null ? "开通赠点" : note));
            return true;
        });
        return Boolean.TRUE.equals(granted);
    }

    /** 总部人工调点（B-9）：bizKey 幂等；负数不得致余额 < 0。 */
    public OrgPointAccountRow adjustBalance(long orgId, long credits, String bizKey, String note, Long adminUserId) {
        ensurePoolAccount(orgId);
        return tx.execute(status -> {
            if (findByBizKey(bizKey) != null) {
                return findAccount(orgId);
            }
            lockAccount(orgId);
            int updated = jdbc.update("UPDATE org_point_account SET balance = balance + ?, "
                    + "version = version + 1, update_time = now() WHERE org_id = ? AND balance + ? >= 0",
                    credits, orgId, credits);
            if (updated == 0) {
                throw exception(ORG_POINT_INSUFFICIENT);
            }
            insertLedger(new LedgerInsert(orgId, null, bizKey, null, TYPE_MANUAL_ADJUST, null,
                    adminUserId, null, credits, null, null, null, null, note));
            return findAccount(orgId);
        });
    }

    // ==================== 三态计费（B-3~B-7） ====================

    @Override
    public long reserve(ReserveCommand command) {
        BillingPriceCatalog.Subject subject = BillingPriceCatalog.subject(command.subject());
        long quantity = Math.max(command.quantity(), 1);
        long n = BillingPriceCatalog.creditsFor(subject, quantity);
        return tx.execute(status -> {
            lockAccount(command.orgId());
            OrgPointAccountRow account = findAccount(command.orgId());
            if (account == null) {
                throw exception(ORG_NOT_ENABLED);
            }
            if (!"ACTIVE".equals(account.status())) {
                throw exception(ORG_POINT_SUSPENDED);
            }
            LedgerRow replayed = findByBizKey(command.bizKey());
            if (replayed != null) {
                if (!sameReserveIntent(replayed, command, subject.key())) {
                    throw exception(ORG_POINT_REPLAY_CONFLICT);
                }
                return replayed.operationId();
            }
            enforceMemberQuota(command.orgId(), command.actorUserId(), n);
            int updated = jdbc.update("UPDATE org_point_account SET balance = balance - ?, "
                    + "version = version + 1, update_time = now() WHERE org_id = ? AND balance >= ? "
                    + "AND status = 'ACTIVE'", n, command.orgId(), n);
            if (updated == 0) {
                throw exception(ORG_POINT_INSUFFICIENT);
            }
            long operationId = IdWorker.getId();
            String snapshot = JsonUtils.toJsonString(snapshot(subject, quantity, n, null, null));
            insertLedger(new LedgerInsert(command.orgId(), operationId, command.bizKey(),
                    Instant.now(), TYPE_RESERVE, subject.key(), command.actorUserId(),
                    command.actorPostId(), -n, currentMonth(), snapshot, command.refType(),
                    command.refId(), null));
            return operationId;
        });
    }

    @Override
    public Settlement settle(long operationId, long actualQuantity) {
        return tx.execute(status -> {
            LedgerRow reserve = lockReserve(operationId);
            if (reserve == null) {
                throw new IllegalStateException("预扣流水不存在：" + operationId);
            }
            LedgerRow terminal = findTerminal(operationId);
            if (terminal != null) {
                if (!TYPE_SETTLE.equals(terminal.type())) {
                    throw exception(ORG_POINT_REPLAY_CONFLICT);
                }
                long charged = -reserve.availableDelta();
                long refunded = terminal.availableDelta();
                return new Settlement(operationId, charged, refunded, true);
            }
            long m = creditsFromSnapshot(reserve.priceSnapshot(), actualQuantity);
            long charged = -reserve.availableDelta();
            long refund = Math.max(charged - m, 0);
            if (refund > 0) {
                jdbc.update("UPDATE org_point_account SET balance = balance + ?, version = version + 1, "
                        + "update_time = now() WHERE org_id = ?", refund, reserve.orgId());
            }
            String snapshot = JsonUtils.toJsonString(snapshot(
                    BillingPriceCatalog.subject(reserve.subject()),
                    originalQuantity(reserve.priceSnapshot()), charged, actualQuantity, m));
            insertLedger(new LedgerInsert(reserve.orgId(), operationId,
                    reserve.bizKey() + ":SETTLE", reserve.operationTime(), TYPE_SETTLE,
                    reserve.subject(), reserve.actorUserId(), reserve.actorPostId(), refund,
                    reserve.billingMonth(), snapshot, reserve.refType(), reserve.refId(), null));
            return new Settlement(operationId, charged, refund, false);
        });
    }

    @Override
    public Settlement release(long operationId) {
        return tx.execute(status -> {
            LedgerRow reserve = lockReserve(operationId);
            if (reserve == null) {
                throw new IllegalStateException("预扣流水不存在：" + operationId);
            }
            LedgerRow terminal = findTerminal(operationId);
            if (terminal != null) {
                if (!TYPE_RELEASE.equals(terminal.type())) {
                    throw exception(ORG_POINT_REPLAY_CONFLICT);
                }
                return new Settlement(operationId, -reserve.availableDelta(),
                        terminal.availableDelta(), true);
            }
            long charged = -reserve.availableDelta();
            jdbc.update("UPDATE org_point_account SET balance = balance + ?, version = version + 1, "
                    + "update_time = now() WHERE org_id = ?", charged, reserve.orgId());
            insertLedger(new LedgerInsert(reserve.orgId(), operationId,
                    reserve.bizKey() + ":RELEASE", reserve.operationTime(), TYPE_RELEASE,
                    reserve.subject(), reserve.actorUserId(), reserve.actorPostId(), charged,
                    reserve.billingMonth(), reserve.priceSnapshot(), reserve.refType(),
                    reserve.refId(), null));
            return new Settlement(operationId, charged, charged, false);
        });
    }

    @Override
    public Settlement settleByBizKey(String reserveBizKey, long actualQuantity) {
        Long operationId = findOperationIdByBizKey(reserveBizKey);
        if (operationId == null) {
            return null; // 上线前已在途的存量任务：无预扣则不结算
        }
        return settle(operationId, actualQuantity);
    }

    @Override
    public Settlement releaseByBizKey(String reserveBizKey) {
        Long operationId = findOperationIdByBizKey(reserveBizKey);
        if (operationId == null) {
            return null;
        }
        return release(operationId);
    }

    // ==================== 查询（B-10/B-11 服务层） ====================

    @Override
    public PoolSummary summary(long orgId) {
        OrgPointAccountRow account = findAccount(orgId);
        if (account == null) {
            return null;
        }
        return new PoolSummary(orgId, account.balance(), inFlight(orgId), account.status());
    }

    @Override
    public List<LedgerEntry> ledger(long orgId, Long actorUserId, String subject, String billingMonth,
                                    int page, int size) {
        return ledgerRows(orgId, actorUserId, subject, billingMonth, size, Math.max(page, 1) * size - size)
                .stream().map(row -> new LedgerEntry(row.id(), row.operationId(), row.type(),
                        row.subject(), row.actorUserId(), row.availableDelta(), row.billingMonth(),
                        row.refType(), row.refId(), row.note(), row.createTime()))
                .toList();
    }

    /** 任务合并视图：同一 operation 的 RESERVE + 终态折叠为一行（分页前折叠，R3-P2 复刻项目教训）。 */
    @Override
    public List<OperationView> operations(long orgId, int page, int size) {
        int offset = Math.max(page, 1) * size - size;
        return jdbc.query(
                "SELECT r.operation_id, r.org_id, r.subject, r.actor_user_id, r.biz_key, r.operation_time, "
                        + "-r.available_delta AS requested, COALESCE(t.type, 'PENDING') AS state, "
                        + "COALESCE(t.available_delta, 0) AS refunded, t.create_time AS terminal_time "
                        + "FROM org_point_ledger r "
                        + "LEFT JOIN org_point_ledger t ON t.operation_id = r.operation_id "
                        + "  AND t.type IN ('SETTLE','RELEASE') "
                        + "WHERE r.org_id = ? AND r.type = 'RESERVE' "
                        + "ORDER BY r.create_time DESC, r.id DESC LIMIT ? OFFSET ?",
                (rs, i) -> {
                    long requested = rs.getLong("requested");
                    long refunded = rs.getLong("refunded");
                    String state = rs.getString("state");
                    return new OperationView(rs.getLong("operation_id"), rs.getLong("org_id"),
                            rs.getString("subject"), rs.getObject("actor_user_id", Long.class),
                            "PENDING".equals(state) ? "PENDING"
                                    : ("RELEASE".equals(state) ? "RELEASED" : "COMPLETED"),
                            requested, requested - refunded, refunded, rs.getString("biz_key"),
                            rs.getTimestamp("operation_time") == null ? null
                                    : rs.getTimestamp("operation_time").toInstant(),
                            rs.getTimestamp("terminal_time") == null ? null
                                    : rs.getTimestamp("terminal_time").toInstant());
                }, orgId, size, offset);
    }

    @Override
    public long monthUsed(long orgId, long userId, String billingMonth) {
        Long sum = jdbc.queryForObject(
                "SELECT COALESCE(-SUM(available_delta), 0) FROM org_point_ledger "
                        + "WHERE org_id = ? AND actor_user_id = ? AND billing_month = ? "
                        + "AND type IN ('RESERVE','SETTLE','RELEASE')",
                Long.class, orgId, userId, billingMonth);
        return sum == null ? 0 : sum;
    }

    @Override
    public Long memberQuotaOf(long orgId, long userId) {
        return memberMonthlyQuota(orgId, userId);
    }

    @Override
    public List<MemberQuota> memberQuotas(long orgId) {
        return jdbc.query("SELECT user_id, monthly_credits FROM org_member_quota WHERE org_id = ? "
                        + "ORDER BY update_time DESC",
                (rs, i) -> new MemberQuota(rs.getLong("user_id"), rs.getLong("monthly_credits")), orgId);
    }

    @Override
    public PoolSummary ensurePool(long orgId) {
        ensurePoolAccount(orgId);
        return summary(orgId);
    }

    @Override
    public PoolSummary adjust(long orgId, long credits, String bizKey, String note, Long adminUserId) {
        OrgPointAccountRow row = adjustBalance(orgId, credits, bizKey, note, adminUserId);
        return new PoolSummary(orgId, row.balance(), inFlight(orgId), row.status());
    }

    public OrgPointAccountRow findAccount(long orgId) {
        List<OrgPointAccountRow> rows = jdbc.query(
                "SELECT org_id, balance, status, version FROM org_point_account WHERE org_id = ?",
                (rs, i) -> new OrgPointAccountRow(rs.getLong("org_id"), rs.getLong("balance"),
                        rs.getString("status"), rs.getLong("version")), orgId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 在途预留（未终结 operation 的 -RESERVE 合计）。 */
    public long inFlight(long orgId) {
        Long sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(-r.available_delta), 0) FROM org_point_ledger r "
                        + "WHERE r.org_id = ? AND r.type = 'RESERVE' AND NOT EXISTS ("
                        + "  SELECT 1 FROM org_point_ledger t WHERE t.operation_id = r.operation_id "
                        + "  AND t.type IN ('SETTLE','RELEASE'))",
                Long.class, orgId);
        return sum == null ? 0 : sum;
    }

    public Long memberMonthlyQuota(long orgId, long userId) {
        List<Long> rows = jdbc.query(
                "SELECT monthly_credits FROM org_member_quota WHERE org_id = ? AND user_id = ?",
                (rs, i) -> rs.getLong(1), orgId, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void setMemberQuota(long orgId, long userId, long monthlyCredits) {
        jdbc.update("INSERT INTO org_member_quota (id, org_id, user_id, monthly_credits) "
                        + "VALUES (?, ?, ?, ?) ON CONFLICT (org_id, user_id) DO UPDATE "
                        + "SET monthly_credits = EXCLUDED.monthly_credits, update_time = now()",
                IdWorker.getId(), orgId, userId, monthlyCredits);
    }

    /** 删行=不限（唯一"不限"表达）。 */
    public void clearMemberQuota(long orgId, long userId) {
        jdbc.update("DELETE FROM org_member_quota WHERE org_id = ? AND user_id = ?", orgId, userId);
    }

    public List<LedgerRow> ledgerRows(long orgId, Long actorUserId, String subject, String billingMonth,
                                     int limit, int offset) {
        StringBuilder sql = new StringBuilder(
                "SELECT id, org_id, operation_id, biz_key, operation_time, type, subject, actor_user_id, "
                        + "actor_post_id, available_delta, billing_month, price_snapshot, ref_type, ref_id, "
                        + "note, create_time FROM org_point_ledger WHERE org_id = ?");
        List<Object> args = new java.util.ArrayList<>();
        args.add(orgId);
        if (actorUserId != null) {
            sql.append(" AND actor_user_id = ?");
            args.add(actorUserId);
        }
        if (subject != null) {
            sql.append(" AND subject = ?");
            args.add(subject);
        }
        if (billingMonth != null) {
            sql.append(" AND billing_month = ?");
            args.add(billingMonth);
        }
        sql.append(" ORDER BY create_time DESC, id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), (rs, i) -> new LedgerRow(
                rs.getLong("id"), rs.getLong("org_id"), rs.getObject("operation_id", Long.class),
                rs.getString("biz_key"),
                rs.getTimestamp("operation_time") == null ? null : rs.getTimestamp("operation_time").toInstant(),
                rs.getString("type"), rs.getString("subject"),
                rs.getObject("actor_user_id", Long.class), rs.getObject("actor_post_id", Long.class),
                rs.getLong("available_delta"), rs.getString("billing_month"),
                rs.getString("price_snapshot"), rs.getString("ref_type"),
                rs.getObject("ref_id", Long.class), rs.getString("note"),
                rs.getTimestamp("create_time").toInstant()), args.toArray());
    }

    public static String currentMonth() {
        return LocalDate.now(MONTH_ZONE).toString().substring(0, 7);
    }

    // ==================== 内部 ====================

    private void enforceMemberQuota(long orgId, long actorUserId, long n) {
        Long quota = memberMonthlyQuota(orgId, actorUserId);
        if (quota == null) {
            return;
        }
        long used = monthUsed(orgId, actorUserId, currentMonth());
        if (used + n > quota) {
            throw exception(ORG_MEMBER_QUOTA_EXCEEDED);
        }
    }

    private void lockAccount(long orgId) {
        jdbc.queryForList("SELECT org_id FROM org_point_account WHERE org_id = ? FOR UPDATE", orgId);
    }

    private LedgerRow lockReserve(long operationId) {
        List<LedgerRow> rows = jdbc.query(
                "SELECT id, org_id, operation_id, biz_key, operation_time, type, subject, actor_user_id, "
                        + "actor_post_id, available_delta, billing_month, price_snapshot, ref_type, ref_id, "
                        + "note, create_time FROM org_point_ledger "
                        + "WHERE operation_id = ? AND type = 'RESERVE' FOR UPDATE",
                (rs, i) -> new LedgerRow(rs.getLong("id"), rs.getLong("org_id"),
                        rs.getObject("operation_id", Long.class), rs.getString("biz_key"),
                        rs.getTimestamp("operation_time") == null ? null
                                : rs.getTimestamp("operation_time").toInstant(),
                        rs.getString("type"), rs.getString("subject"),
                        rs.getObject("actor_user_id", Long.class),
                        rs.getObject("actor_post_id", Long.class),
                        rs.getLong("available_delta"), rs.getString("billing_month"),
                        rs.getString("price_snapshot"), rs.getString("ref_type"),
                        rs.getObject("ref_id", Long.class), rs.getString("note"),
                        rs.getTimestamp("create_time").toInstant()), operationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private LedgerRow findTerminal(long operationId) {
        List<LedgerRow> rows = jdbc.query(
                "SELECT id, org_id, operation_id, biz_key, operation_time, type, subject, actor_user_id, "
                        + "actor_post_id, available_delta, billing_month, price_snapshot, ref_type, ref_id, "
                        + "note, create_time FROM org_point_ledger "
                        + "WHERE operation_id = ? AND type IN ('SETTLE','RELEASE')",
                (rs, i) -> new LedgerRow(rs.getLong("id"), rs.getLong("org_id"),
                        rs.getObject("operation_id", Long.class), rs.getString("biz_key"),
                        rs.getTimestamp("operation_time") == null ? null
                                : rs.getTimestamp("operation_time").toInstant(),
                        rs.getString("type"), rs.getString("subject"),
                        rs.getObject("actor_user_id", Long.class),
                        rs.getObject("actor_post_id", Long.class),
                        rs.getLong("available_delta"), rs.getString("billing_month"),
                        rs.getString("price_snapshot"), rs.getString("ref_type"),
                        rs.getObject("ref_id", Long.class), rs.getString("note"),
                        rs.getTimestamp("create_time").toInstant()), operationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Long findOperationIdByBizKey(String bizKey) {
        List<Long> rows = jdbc.query(
                "SELECT operation_id FROM org_point_ledger WHERE biz_key = ? AND type = 'RESERVE'",
                (rs, i) -> rs.getObject(1, Long.class), bizKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private boolean sameReserveIntent(LedgerRow row, ReserveCommand command, String subjectKey) {
        return subjectKey.equals(row.subject())
                && Long.valueOf(command.orgId()).equals(row.orgId())
                && Long.valueOf(command.actorUserId()).equals(row.actorUserId())
                && java.util.Objects.equals(command.refType(), row.refType())
                && java.util.Objects.equals(command.refId(), row.refId());
    }

    private long originalQuantity(String priceSnapshot) {
        Map<String, Object> snapshot = JsonUtils.parseObject(priceSnapshot, Map.class);
        return Math.max(asLong(snapshot.get("quantity")), 1);
    }

    private long creditsFromSnapshot(String priceSnapshot, long actualQuantity) {
        Map<String, Object> snapshot = JsonUtils.parseObject(priceSnapshot, Map.class);
        long priceMicros = asLong(snapshot.get("priceMicrosPerUnit"));
        long minCharge = asLong(snapshot.get("minCharge"));
        long q = Math.max(actualQuantity, 1);
        long credits = (q * priceMicros + MICROS_PER_CREDIT - 1) / MICROS_PER_CREDIT;
        return Math.max(credits, minCharge);
    }

    private Map<String, Object> snapshot(BillingPriceCatalog.Subject subject, long quantity,
                                         long n, Long actualQuantity, Long m) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("subject", subject.key());
        snapshot.put("unit", subject.unit().name());
        snapshot.put("priceMicrosPerUnit", subject.priceMicrosPerUnit());
        snapshot.put("priceVersion", subject.priceVersion());
        snapshot.put("minCharge", subject.minCharge());
        snapshot.put("quantity", quantity);
        snapshot.put("credits", n);
        if (actualQuantity != null) {
            snapshot.put("actualQuantity", actualQuantity);
        }
        if (m != null) {
            snapshot.put("actualCredits", m);
        }
        return snapshot;
    }

    private static long asLong(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    private LedgerRow findByBizKey(String bizKey) {
        List<LedgerRow> rows = jdbc.query(
                "SELECT id, org_id, operation_id, biz_key, operation_time, type, subject, actor_user_id, "
                        + "actor_post_id, available_delta, billing_month, price_snapshot, ref_type, ref_id, "
                        + "note, create_time FROM org_point_ledger WHERE biz_key = ?",
                (rs, i) -> new LedgerRow(rs.getLong("id"), rs.getLong("org_id"),
                        rs.getObject("operation_id", Long.class), rs.getString("biz_key"),
                        rs.getTimestamp("operation_time") == null ? null
                                : rs.getTimestamp("operation_time").toInstant(),
                        rs.getString("type"), rs.getString("subject"),
                        rs.getObject("actor_user_id", Long.class),
                        rs.getObject("actor_post_id", Long.class),
                        rs.getLong("available_delta"), rs.getString("billing_month"),
                        rs.getString("price_snapshot"), rs.getString("ref_type"),
                        rs.getObject("ref_id", Long.class), rs.getString("note"),
                        rs.getTimestamp("create_time").toInstant()), bizKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void insertLedger(LedgerInsert row) {
        jdbc.update("INSERT INTO org_point_ledger (id, org_id, operation_id, biz_key, operation_time, "
                        + "type, subject, actor_user_id, actor_post_id, available_delta, billing_month, "
                        + "price_snapshot, ref_type, ref_id, note) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)",
                IdWorker.getId(), row.orgId(), row.operationId(), row.bizKey(),
                row.operationTime() == null ? null : Timestamp.from(row.operationTime()),
                row.type(), row.subject(), row.actorUserId(), row.actorPostId(),
                row.availableDelta(), row.billingMonth(), row.priceSnapshot(),
                row.refType(), row.refId(), row.note());
    }

    public record OrgPointAccountRow(long orgId, long balance, String status, long version) {
    }

    public record LedgerRow(long id, long orgId, Long operationId, String bizKey, Instant operationTime,
                            String type, String subject, Long actorUserId, Long actorPostId,
                            long availableDelta, String billingMonth, String priceSnapshot,
                            String refType, Long refId, String note, Instant createTime) {
    }

    private record LedgerInsert(long orgId, Long operationId, String bizKey, Instant operationTime,
                                String type, String subject, Long actorUserId, Long actorPostId,
                                long availableDelta, String billingMonth, String priceSnapshot,
                                String refType, Long refId, String note) {
    }
}
