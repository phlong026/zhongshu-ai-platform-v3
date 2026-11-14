package cn.iocoder.yudao.module.platform.wallet.orgpoint;

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
import static cn.iocoder.yudao.module.platform.wallet.orgpoint.OrgPointErrors.ORG_MEMBER_QUOTA_EXCEEDED;
import static cn.iocoder.yudao.module.platform.wallet.orgpoint.OrgPointErrors.ORG_NOT_ENABLED;
import static cn.iocoder.yudao.module.platform.wallet.orgpoint.OrgPointErrors.ORG_POINT_INSUFFICIENT;
import static cn.iocoder.yudao.module.platform.wallet.orgpoint.OrgPointErrors.ORG_POINT_REPLAY_CONFLICT;
import static cn.iocoder.yudao.module.platform.wallet.orgpoint.OrgPointErrors.ORG_POINT_SUSPENDED;
import static cn.iocoder.yudao.module.platform.wallet.orgpoint.OrgPointErrors.ORG_SUBJECT_FORBIDDEN;

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

    /** D8 表化价格（服务必须注入；科目无 ACTIVE 配置行时才回退 BillingPriceCatalog 默认价）。 */
    private final OrgPointPriceService priceService;
    /** 只读一致快照（REPEATABLE READ）：告警汇总的 total/byOrg/样本必须来自同一时点。 */
    private final TransactionTemplate snapshotTx;
    /** 配置变更业务审计（任务书 §106/§180）：构造器必传，生产缺失 Bean 即启动失败。 */
    private final cn.iocoder.yudao.module.infra.zhongshu.audit.AuditPort auditPort;

    public OrgPointService(DataSource dataSource, PlatformTransactionManager transactionManager,
                           cn.iocoder.yudao.module.infra.zhongshu.audit.AuditPort auditPort,
                           OrgPointPriceService priceService) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(transactionManager);
        this.snapshotTx = new TransactionTemplate(transactionManager);
        // REQUIRES_NEW：不加入外层事务，保证 RR+只读真正生效（否则外层 READ_COMMITTED 会忽略内层设置）
        this.snapshotTx.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.snapshotTx.setIsolationLevel(
                org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.snapshotTx.setReadOnly(true);
        this.auditPort = java.util.Objects.requireNonNull(auditPort, "AuditPort");
        this.priceService = java.util.Objects.requireNonNull(priceService, "OrgPointPriceService");
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
            if (findByBizKey(bizKey) != null) {
                return false;
            }
            int updated = jdbc.update("UPDATE org_point_account SET balance = balance + ?, version = version + 1, "
                    + "update_time = now() WHERE org_id = ?", credits, orgId);
            if (updated != 1) {
                throw new IllegalStateException("开通赠点账户不存在或更新异常：orgId=" + orgId);
            }
            insertLedger(new LedgerInsert(orgId, null, bizKey, null, TYPE_MANUAL_ADJUST, null,
                    0L, null, credits, null, null, null, null,
                    note == null ? "开通赠点（系统）" : note));
            return true;
        });
        return Boolean.TRUE.equals(granted);
    }

    /** 总部人工调点（B-9）：bizKey 幂等；负数不得致余额 < 0。 */
    public OrgPointAccountRow adjustBalance(long orgId, long credits, String bizKey, String note, Long adminUserId) {
        // 评审 P2-4：调点审计必填（管理员 + 说明），拒绝无主调点
        if (adminUserId == null) {
            throw new IllegalArgumentException("调点必须记录操作管理员");
        }
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("调点必须填写说明");
        }
        return tx.execute(status -> {
            LedgerRow replayed = findByBizKey(bizKey);
            if (replayed != null) {
                // 评审 P1-3：同键必须同组织同金额，否则拒绝
                if (!Long.valueOf(orgId).equals(replayed.orgId())
                        || replayed.availableDelta() != credits) {
                    throw exception(ORG_POINT_REPLAY_CONFLICT);
                }
                return findAccount(orgId);
            }
            // 评审 P1-4：调点要求池已开通，不得隐式建 ACTIVE 池
            if (findAccount(orgId) == null) {
                throw exception(ORG_NOT_ENABLED);
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
        PriceSpec spec = resolvePrice(command.subject());
        long quantity = Math.max(command.quantity(), 1);
        long n = creditsFor(spec, quantity);
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
                if (!sameReserveIntent(replayed, command, spec.subject())) {
                    throw exception(ORG_POINT_REPLAY_CONFLICT);
                }
                return replayed.operationId();
            }
            // 评审 P2-3：账期与时间锚点一次获取，校验与入账共用同一个月界
            String month = currentMonth();
            Instant now = Instant.now();
            enforceSubjectPermission(command.orgId(), command.actorUserId(), spec.subject());
            enforceMemberQuota(command.orgId(), command.actorUserId(), n, month);
            int updated = jdbc.update("UPDATE org_point_account SET balance = balance - ?, "
                    + "version = version + 1, update_time = now() WHERE org_id = ? AND balance >= ? "
                    + "AND status = 'ACTIVE'", n, command.orgId(), n);
            if (updated == 0) {
                throw exception(ORG_POINT_INSUFFICIENT);
            }
            long operationId = IdWorker.getId();
            String snapshot = JsonUtils.toJsonString(snapshot(spec, quantity, n, null, null));
            insertLedger(new LedgerInsert(command.orgId(), operationId, command.bizKey(),
                    now, TYPE_RESERVE, spec.subject(), command.actorUserId(),
                    command.actorPostId(), -n, month, snapshot, command.refType(),
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
                // R1-P2（评审 P1-2）：同键改实际量必须拒绝——否则会静默沿用首次结算结果
                long settledQuantity = actualQuantityOf(terminal.priceSnapshot());
                if (Math.max(actualQuantity, 1) != settledQuantity) {
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
            String snapshot = terminalSnapshot(reserve.priceSnapshot(), actualQuantity, m);
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
        String month = currentMonth();
        return new PoolSummary(orgId, account.balance(), inFlight(orgId), account.status(),
                month, orgMonthUsed(orgId, month));
    }

    @Override
    public List<LedgerEntry> ledger(long orgId, Long actorUserId, String subject, String billingMonth,
                                    int page, int size) {
        return ledgerRows(orgId, actorUserId, subject, billingMonth, pageSize(size), offsetOf(page, size))
                .stream().map(row -> new LedgerEntry(row.id(), row.operationId(), row.type(),
                        row.subject(), row.actorUserId(), row.availableDelta(), row.billingMonth(),
                        row.refType(), row.refId(), row.note(), row.createTime()))
                .toList();
    }

    /** 任务合并视图：同一 operation 的 RESERVE + 终态折叠为一行（分页前折叠，R3-P2 复刻项目教训）。 */
    @Override
    public List<OperationView> operations(long orgId, int page, int size) {
        long offset = offsetOf(page, size);
        int safeSize = pageSize(size);
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
                }, orgId, safeSize, offset);
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
    public MyUsage myUsage(long orgId, long userId, String billingMonth, int page, int size) {
        String month = billingMonth == null || billingMonth.isBlank() ? currentMonth() : billingMonth;
        long used = monthUsed(orgId, userId, month);
        List<LedgerEntry> entries = ledgerRows(orgId, userId, null, month,
                pageSize(size), offsetOf(page, size)).stream()
                .map(row -> new LedgerEntry(row.id(), row.operationId(), row.type(), row.subject(),
                        row.actorUserId(), row.availableDelta(), row.billingMonth(), row.refType(),
                        row.refId(), row.note(), row.createTime()))
                .toList();
        return new MyUsage(orgId, userId, month, used, memberMonthlyQuota(orgId, userId), entries);
    }

    /** 组织当月全员用量（三态口径）。 */
    public long orgMonthUsed(long orgId, String billingMonth) {
        Long sum = jdbc.queryForObject(
                "SELECT COALESCE(-SUM(available_delta), 0) FROM org_point_ledger "
                        + "WHERE org_id = ? AND billing_month = ? "
                        + "AND type IN ('RESERVE','SETTLE','RELEASE')",
                Long.class, orgId, billingMonth);
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
        String month = currentMonth();
        return new PoolSummary(orgId, row.balance(), inFlight(orgId), row.status(),
                month, orgMonthUsed(orgId, month));
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

    public void setMemberQuota(long orgId, long userId, long monthlyCredits, long operatorUserId) {
        if (monthlyCredits <= 0 || monthlyCredits > 1_000_000_000L) {
            throw new IllegalArgumentException("月度额度必须在 1..1e9 之间：" + monthlyCredits);
        }
        tx.executeWithoutResult(status -> {
            Long previous = memberMonthlyQuota(orgId, userId);
            jdbc.update("INSERT INTO org_member_quota (id, org_id, user_id, monthly_credits) "
                            + "VALUES (?, ?, ?, ?) ON CONFLICT (org_id, user_id) DO UPDATE "
                            + "SET monthly_credits = EXCLUDED.monthly_credits, update_time = now()",
                    IdWorker.getId(), orgId, userId, monthlyCredits);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("monthlyCredits", monthlyCredits);
            if (previous != null) {
                detail.put("previousMonthlyCredits", previous);
            }
            auditConfig("ORG_MEMBER_QUOTA", "SET", orgId, userId, operatorUserId, detail);
        });
    }

    // ==================== 员工业务权限矩阵（B-12） ====================

    /** 无行=全允许（fail-open，存量零回填）；空数组=全禁。 */
    @Override
    public List<MemberPermission> memberPermissions(long orgId) {
        return jdbc.query("SELECT user_id, allowed_subjects FROM org_member_permission WHERE org_id = ? "
                        + "ORDER BY update_time DESC",
                (rs, i) -> new MemberPermission(rs.getLong("user_id"),
                        parseSubjects(rs.getString("allowed_subjects"))), orgId);
    }

    @Override
    public void setMemberPermission(long orgId, long userId, List<String> allowedSubjects,
                                    long operatorUserId) {
        // 评审 P2：null 语义必须显式——拒绝缺省，全禁用空数组表达
        if (allowedSubjects == null) {
            throw new IllegalArgumentException("allowedSubjects 不能为空数组以外的 null（全禁请传 []）");
        }
        List<String> normalized = allowedSubjects.stream().filter(s -> s != null && !s.isBlank())
                .distinct().sorted().toList();
        tx.executeWithoutResult(status -> {
            List<String> previous = findPermissionSubjects(orgId, userId);
            String serialized = normalized.isEmpty() ? "[]" : JsonUtils.toJsonString(normalized);
            jdbc.update("INSERT INTO org_member_permission (id, org_id, user_id, allowed_subjects) "
                            + "VALUES (?, ?, ?, ?) ON CONFLICT (org_id, user_id) DO UPDATE "
                            + "SET allowed_subjects = EXCLUDED.allowed_subjects, update_time = now()",
                    IdWorker.getId(), orgId, userId, serialized);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("allowedSubjects", normalized);
            if (previous != null) {
                detail.put("previousAllowedSubjects", previous);
            }
            auditConfig("ORG_MEMBER_PERMISSION", "SET", orgId, userId, operatorUserId, detail);
        });
    }

    @Override
    public void clearMemberPermission(long orgId, long userId, long operatorUserId) {
        tx.executeWithoutResult(status -> {
            List<String> previous = findPermissionSubjects(orgId, userId);
            jdbc.update("DELETE FROM org_member_permission WHERE org_id = ? AND user_id = ?", orgId, userId);
            auditConfig("ORG_MEMBER_PERMISSION", "CLEAR", orgId, userId, operatorUserId,
                    previous == null ? Map.of() : Map.of("previousAllowedSubjects", previous));
        });
    }

    @Override
    public void assertSubjectAllowed(long orgId, long userId, String subject) {
        enforceSubjectPermission(orgId, userId, subject);
    }

    @Override
    public long quoteCredits(String subject, long quantity) {
        return creditsFor(resolvePrice(subject), Math.max(quantity, 1));
    }

    /** 生效价规格（DB 配置优先，否则代码默认价）。 */
    private record PriceSpec(String subject, String unit, long priceMicros, long minCharge,
                             String versionText) {
    }

    private PriceSpec resolvePrice(String subjectKey) {
        BillingPriceCatalog.Subject base = BillingPriceCatalog.subject(subjectKey);
        OrgPointPriceService.ResolvedPrice resolved = priceService.resolve(subjectKey);
        if (resolved != null) {
            return new PriceSpec(subjectKey, resolved.unit(), resolved.priceMicros(),
                    resolved.minCharge(), resolved.versionText());
        }
        return new PriceSpec(subjectKey, base.unit().name(), base.priceMicrosPerUnit(),
                base.minCharge(), base.priceVersion());
    }

    /** 数量 → 积分（向上取整，不低于最低收费；上限防溢出）。 */
    private static long creditsFor(PriceSpec spec, long quantity) {
        long q = Math.max(quantity, 1);
        if (q > BillingPriceCatalog.MAX_QUANTITY) {
            throw new IllegalArgumentException("计量数量超出上限：" + q);
        }
        if (spec.priceMicros() <= 0 || spec.priceMicros() > BillingPriceCatalog.MAX_PRICE_MICROS) {
            // 写入侧已校验；此处兜底防脏数据使乘法溢出后静默少扣积分
            throw new IllegalArgumentException("单价超出合法范围（微积分）：" + spec.priceMicros());
        }
        long credits = (q * spec.priceMicros() + MICROS_PER_CREDIT - 1) / MICROS_PER_CREDIT;
        return Math.max(credits, spec.minCharge());
    }

    @Override
    public void bindOperation(String origin, long orgId, long actorUserId, Long actorPostId,
                              String refType, long refId, String subject, long operationId,
                              String bizKey) {
        tx.executeWithoutResult(status -> {
            LedgerRow reserve = lockReserve(operationId);
            if (reserve == null || reserve.orgId() != orgId
                    || !java.util.Objects.equals(reserve.actorUserId(), actorUserId)
                    || !java.util.Objects.equals(reserve.actorPostId(), actorPostId)
                    || !java.util.Objects.equals(reserve.subject(), subject)
                    || !java.util.Objects.equals(reserve.refType(), refType)
                    || !java.util.Objects.equals(reserve.refId(), refId)
                    || !java.util.Objects.equals(reserve.bizKey(), bizKey)) {
                throw exception(ORG_POINT_REPLAY_CONFLICT);
            }
            jdbc.update("INSERT INTO design_billing_binding (id, origin, org_id, actor_user_id, "
                            + "actor_post_id, ref_type, ref_id, subject, operation_id, biz_key) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (ref_type, ref_id, subject) "
                            + "DO NOTHING",
                    IdWorker.getId(), origin, orgId, actorUserId, actorPostId, refType, refId,
                    subject, operationId, bizKey);
            List<BindingIntent> rows = jdbc.query(
                    "SELECT origin,org_id,actor_user_id,actor_post_id,operation_id,biz_key "
                            + "FROM design_billing_binding WHERE ref_type=? AND ref_id=? AND subject=? FOR UPDATE",
                    (rs, i) -> new BindingIntent(rs.getString("origin"), rs.getLong("org_id"),
                            rs.getLong("actor_user_id"), rs.getObject("actor_post_id", Long.class),
                            rs.getLong("operation_id"), rs.getString("biz_key")), refType, refId, subject);
            BindingIntent saved = rows.get(0);
            if (!saved.equals(new BindingIntent(origin, orgId, actorUserId, actorPostId, operationId, bizKey))) {
                throw exception(ORG_POINT_REPLAY_CONFLICT);
            }
        });
    }

    private record BindingIntent(String origin, long orgId, long actorUserId, Long actorPostId,
                                 long operationId, String bizKey) {
    }

    @Override
    public Long findOperationByBizKey(String bizKey) {
        return findOperationIdByBizKey(bizKey);
    }

    @Override
    public PoolBinding findBinding(String refType, long refId, String subject) {
        List<PoolBinding> rows = jdbc.query(
                "SELECT org_id, actor_user_id, actor_post_id, operation_id FROM design_billing_binding "
                        + "WHERE ref_type = ? AND ref_id = ? AND subject = ?",
                (rs, i) -> new PoolBinding(rs.getLong("org_id"), rs.getLong("actor_user_id"),
                        rs.getObject("actor_post_id", Long.class), rs.getLong("operation_id")),
                refType, refId, subject);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public List<PromptOperation> promptOperations(long jobId) {
        return jdbc.query(
                "SELECT r.operation_id, r.biz_key, "
                        + "  CASE WHEN t.id IS NULL THEN 'PENDING' WHEN t.type = 'SETTLE' THEN 'SETTLED' "
                        + "       ELSE 'RELEASED' END AS state "
                        + "FROM org_point_ledger r LEFT JOIN org_point_ledger t "
                        + "  ON t.operation_id = r.operation_id AND t.type IN ('SETTLE','RELEASE') "
                        + "WHERE r.type = 'RESERVE' AND r.biz_key LIKE ? ORDER BY r.id",
                (rs, i) -> new PromptOperation(rs.getLong("operation_id"), rs.getString("biz_key"),
                        rs.getString("state")),
                "design:prompt:" + jobId + ":%");
    }

    @Override
    public PromptReceipt findPromptReceipt(long jobId, int attemptNo) {
        List<PromptReceipt> rows = jdbc.query(
                "SELECT call_id, response_hash, outcome, operation_id FROM design_prompt_receipt "
                        + "WHERE job_id = ? AND attempt_no = ?",
                (rs, i) -> new PromptReceipt(rs.getString("call_id"), rs.getString("response_hash"),
                        rs.getString("outcome"), rs.getObject("operation_id", Long.class)),
                jobId, attemptNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public void savePromptReceipt(long jobId, int attemptNo, String callId, String responseHash,
                                  String outcome, Long operationId) {
        if (!List.of("NOT_SENT", "DISPATCHED", "UNKNOWN", "SUCCEEDED").contains(outcome)) {
            throw new IllegalArgumentException("非法 prompt 收据状态：" + outcome);
        }
        tx.executeWithoutResult(status -> {
            PromptReceipt requested = new PromptReceipt(callId, responseHash, outcome, operationId);
            // 先竞争唯一键：同参首写并发时，败方等待胜方提交后再校验已有行。
            int inserted = jdbc.update("INSERT INTO design_prompt_receipt "
                            + "(id,job_id,attempt_no,call_id,response_hash,outcome,operation_id) "
                            + "VALUES (?,?,?,?,?,?,?) ON CONFLICT (job_id,attempt_no) DO NOTHING",
                    IdWorker.getId(), jobId, attemptNo, callId, responseHash, outcome, operationId);
            if (inserted == 1) {
                return;
            }
            List<PromptReceipt> existing = jdbc.query(
                    "SELECT call_id,response_hash,outcome,operation_id FROM design_prompt_receipt "
                            + "WHERE job_id=? AND attempt_no=? FOR UPDATE",
                    (rs, i) -> new PromptReceipt(rs.getString("call_id"), rs.getString("response_hash"),
                            rs.getString("outcome"), rs.getObject("operation_id", Long.class)), jobId, attemptNo);
            if (existing.isEmpty()) {
                throw exception(ORG_POINT_REPLAY_CONFLICT);
            }
            PromptReceipt current = existing.get(0);
            if (current.equals(requested)) {
                return;
            }
            boolean maySucceed = !"SUCCEEDED".equals(current.outcome()) && "SUCCEEDED".equals(outcome)
                    && compatible(current.callId(), callId)
                    && compatible(current.responseHash(), responseHash)
                    // NOT_SENT 时旧预留已释放，同 attempt 重试会产生新 operation。
                    && ("NOT_SENT".equals(current.outcome())
                    || compatible(current.operationId(), operationId));
            boolean mayBecomeUnknown = "DISPATCHED".equals(current.outcome()) && "UNKNOWN".equals(outcome)
                    && compatible(current.callId(), callId)
                    && compatible(current.responseHash(), responseHash)
                    && compatible(current.operationId(), operationId);
            if (!maySucceed && !mayBecomeUnknown) {
                throw exception(ORG_POINT_REPLAY_CONFLICT);
            }
            jdbc.update("UPDATE design_prompt_receipt SET call_id=?,response_hash=?,outcome=?,"
                            + "operation_id=?,update_time=now() WHERE job_id=? AND attempt_no=?",
                    callId, responseHash, outcome, operationId, jobId, attemptNo);
        });
    }

    private static boolean compatible(Object existing, Object requested) {
        return existing == null || java.util.Objects.equals(existing, requested);
    }

    @Override
    public Long findOperationByRef(String refType, long refId, String subject) {
        List<Long> rows = jdbc.query(
                "SELECT operation_id FROM design_billing_binding "
                        + "WHERE ref_type = ? AND ref_id = ? AND subject = ?",
                (rs, i) -> rs.getLong(1), refType, refId, subject);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 配置变更审计（§106/§180：删除/变更另记审计，含操作者、组织、目标成员与前值）。 */
    private void auditConfig(String bizType, String action, long orgId, long userId,
                             long operatorUserId, Map<String, Object> detail) {
        Map<String, Object> payload = new LinkedHashMap<>(detail);
        payload.put("orgId", orgId);
        payload.put("targetUserId", userId);
        auditPort.record(cn.iocoder.yudao.module.infra.zhongshu.audit.AuditEventMessage.builder()
                .eventType(bizType).actorType(
                        cn.iocoder.yudao.module.infra.zhongshu.audit.AuditEventMessage.ActorType.ADMIN)
                .actorId(String.valueOf(operatorUserId)).action(action)
                .bizType(bizType.toLowerCase()).bizId(orgId + ":" + userId)
                .result(cn.iocoder.yudao.module.infra.zhongshu.audit.AuditEventMessage.AuditResult.SUCCESS)
                .detail(payload).build());
    }

    /** 准入强制：有权限行且科目不在允许集 → 1_072_000_015（写事务内、外呼之前）。 */
    private void enforceSubjectPermission(long orgId, long actorUserId, String subjectKey) {
        List<String> allowed = findPermissionSubjects(orgId, actorUserId);
        if (allowed != null && !allowed.contains(subjectKey)) {
            throw exception(ORG_SUBJECT_FORBIDDEN);
        }
    }

    private List<String> findPermissionSubjects(long orgId, long actorUserId) {
        List<String> rows = jdbc.query(
                "SELECT allowed_subjects FROM org_member_permission WHERE org_id = ? AND user_id = ?",
                (rs, i) -> rs.getString(1), orgId, actorUserId);
        return rows.isEmpty() ? null : parseSubjects(rows.get(0));
    }

    private static List<String> parseSubjects(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        try {
            List<?> raw = JsonUtils.parseObject(text, List.class);
            return raw == null ? List.of() : raw.stream().map(String::valueOf).toList();
        } catch (RuntimeException e) {
            // 评审 P2：脏数据受控拒绝（按全禁处理并告警），绝不回退全允许
            log.warn("[parseSubjects][脏权限数据按全禁处理] raw={}", text);
            return List.of();
        }
    }

    /** 删行=不限（唯一"不限"表达）。 */
    public void clearMemberQuota(long orgId, long userId, long operatorUserId) {
        tx.executeWithoutResult(status -> {
            Long previous = memberMonthlyQuota(orgId, userId);
            jdbc.update("DELETE FROM org_member_quota WHERE org_id = ? AND user_id = ?", orgId, userId);
            auditConfig("ORG_MEMBER_QUOTA", "CLEAR", orgId, userId, operatorUserId,
                    previous == null ? Map.of() : Map.of("previousMonthlyCredits", previous));
        });
    }

    public List<LedgerRow> ledgerRows(long orgId, Long actorUserId, String subject, String billingMonth,
                                     int limit, long offset) {
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

    /** 长期未终结的预扣（D6 §2.4）：仅告警与人工核对用，绝不自动退款。 */
    public record PendingOperation(long operationId, long orgId, String subject, Long actorUserId,
                                   long requested, java.time.Instant operationTime) {
    }

    public static final long PENDING_ALERT_MIN_HOURS = 1;
    public static final long PENDING_ALERT_MAX_HOURS = 24 * 30;

    /** 告警汇总：total 与实际按组织计数均覆盖**全部**超阈值记录（不截断），样本仅用于定位。 */
    public record PendingAlertSummary(long total, long requestedTotal,
                                      List<OrgPendingCount> byOrg, List<PendingOperation> oldestSample) {
    }

    public record OrgPendingCount(long orgId, long count, long requested) {
    }

    /**
     * 长期未终结预扣汇总（D6 §2.4）：按组织全量聚合，避免"最旧 N 条占满窗口导致后续组织永不告警"。
     * `sampleLimit` 只限制返回的定位样本，不影响计数。
     */
    public PendingAlertSummary pendingAlertSummary(long thresholdHours, int sampleLimit) {
        if (thresholdHours < PENDING_ALERT_MIN_HOURS || thresholdHours > PENDING_ALERT_MAX_HOURS) {
            throw new IllegalArgumentException("告警阈值必须在 " + PENDING_ALERT_MIN_HOURS + ".."
                    + PENDING_ALERT_MAX_HOURS + " 小时之间：" + thresholdHours);
        }
        int limit = Math.min(Math.max(sampleLimit, 1), 100);
        return snapshotTx.execute(status -> pendingAlertSummaryInSnapshot(thresholdHours, limit));
    }

    private PendingAlertSummary pendingAlertSummaryInSnapshot(long thresholdHours, int limit) {
        String base = " FROM org_point_ledger r WHERE r.type = 'RESERVE' "
                + "AND r.operation_time < now() - (? * interval '1 hour') "
                + "AND NOT EXISTS (SELECT 1 FROM org_point_ledger t "
                + "  WHERE t.operation_id = r.operation_id AND t.type IN ('SETTLE','RELEASE'))";
        Map<String, Object> totals = jdbc.queryForMap(
                "SELECT count(*) AS total, COALESCE(SUM(-r.available_delta), 0) AS requested" + base,
                thresholdHours);
        List<OrgPendingCount> byOrg = jdbc.query(
                "SELECT r.org_id, count(*) AS cnt, COALESCE(SUM(-r.available_delta), 0) AS requested"
                        + base + " GROUP BY r.org_id ORDER BY cnt DESC, r.org_id",
                (rs, i) -> new OrgPendingCount(rs.getLong("org_id"), rs.getLong("cnt"),
                        rs.getLong("requested")), thresholdHours);
        List<PendingOperation> sample = jdbc.query(
                "SELECT r.operation_id, r.org_id, r.subject, r.actor_user_id, "
                        + "-r.available_delta AS requested, r.operation_time"
                        + base + " ORDER BY r.operation_time, r.id LIMIT ?",
                (rs, i) -> new PendingOperation(rs.getLong("operation_id"), rs.getLong("org_id"),
                        rs.getString("subject"), rs.getObject("actor_user_id", Long.class),
                        rs.getLong("requested"),
                        rs.getTimestamp("operation_time") == null ? null
                                : rs.getTimestamp("operation_time").toInstant()),
                thresholdHours, limit);
        return new PendingAlertSummary(((Number) totals.get("total")).longValue(),
                ((Number) totals.get("requested")).longValue(), byOrg, sample);
    }

    public static String currentMonth() {
        return LocalDate.now(MONTH_ZONE).toString().substring(0, 7);
    }

    // ==================== 内部 ====================

    private void enforceMemberQuota(long orgId, long actorUserId, long n, String billingMonth) {
        Long quota = memberMonthlyQuota(orgId, actorUserId);
        if (quota == null) {
            return;
        }
        long used = monthUsed(orgId, actorUserId, billingMonth);
        // 不用 used+n > quota：n 异常巨大时求和会溢出成负数而放行（等价变形，避免溢出）
        if (n > quota - used) {
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
                && java.util.Objects.equals(command.actorPostId(), row.actorPostId())
                && java.util.Objects.equals(command.refType(), row.refType())
                && java.util.Objects.equals(command.refId(), row.refId())
                // R1-P2（评审 P1-2）：数量变化必须拒绝，否则同键改量会静默沿用首次预扣额
                && originalQuantity(row.priceSnapshot()) == Math.max(command.quantity(), 1);
    }

    private long originalQuantity(String priceSnapshot) {
        Map<String, Object> snapshot = JsonUtils.parseObject(priceSnapshot, Map.class);
        return Math.max(asLong(snapshot.get("quantity")), 1);
    }

    private long actualQuantityOf(String priceSnapshot) {
        Map<String, Object> snapshot = JsonUtils.parseObject(priceSnapshot, Map.class);
        Object actual = snapshot.get("actualQuantity");
        return actual == null ? originalQuantity(priceSnapshot) : Math.max(asLong(actual), 1);
    }

    /**
     * 终态快照（评审 P2-1）：**复制原 RESERVE 的全部价格字段**（subject/unit/priceMicrosPerUnit/
     * priceVersion/minCharge/quantity），只追加 actualQuantity/actualCredits——调价后审计不得自相矛盾。
     */
    private String terminalSnapshot(String reserveSnapshot, long actualQuantity, long m) {
        Map<String, Object> snapshot = JsonUtils.parseObject(reserveSnapshot, Map.class);
        snapshot.put("actualQuantity", Math.max(actualQuantity, 1));
        snapshot.put("actualCredits", m);
        return JsonUtils.toJsonString(snapshot);
    }

    private long creditsFromSnapshot(String priceSnapshot, long actualQuantity) {
        Map<String, Object> snapshot = JsonUtils.parseObject(priceSnapshot, Map.class);
        long priceMicros = asLong(snapshot.get("priceMicrosPerUnit"));
        long minCharge = asLong(snapshot.get("minCharge"));
        long q = Math.max(actualQuantity, 1);
        if (q > BillingPriceCatalog.MAX_QUANTITY) {
            throw new IllegalArgumentException("计量数量超出上限：" + q);
        }
        long credits = (q * priceMicros + MICROS_PER_CREDIT - 1) / MICROS_PER_CREDIT;
        return Math.max(credits, minCharge);
    }

    private Map<String, Object> snapshot(PriceSpec subject, long quantity,
                                         long n, Long actualQuantity, Long m) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("subject", subject.subject());
        snapshot.put("unit", subject.unit());
        snapshot.put("priceMicrosPerUnit", subject.priceMicros());
        snapshot.put("priceVersion", subject.versionText());
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

    /** 评审 P2-1：分页钳制 + long 偏移，防 int 溢出/负 OFFSET。 */
    private static int pageSize(int size) {
        return Math.min(Math.max(size, 1), 100);
    }

    private static long offsetOf(int page, int size) {
        return (long) Math.max(Math.min(page, 1_000_000), 1) * pageSize(size) - pageSize(size);
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
