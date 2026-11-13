package cn.iocoder.yudao.module.commerce.orgpoint;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 存量个人点 → 公司池迁移（24 号任务书 B-16）。
 *
 * <p>严格前置（评审要求）：
 * <ol>
 *   <li>仅迁移**有 CONFIRMED 映射**的账号（B-15 人工确认清单）；</li>
 *   <li>仅迁移**可用点**（available）；存在在途预留（reserved&gt;0）的账号一律阻塞，先承接在途；</li>
 *   <li>存在未映射但有余额的账号时阻塞（不允许"悄悄漏迁"）；</li>
 *   <li>幂等：每账号一次 `migration:{accountId}` 业务键，重放不重复加点。</li>
 * </ol>
 * 守恒：迁出总额（个人账本负流水之和）== 迁入总额（公司池 MIGRATION 流水之和）。
 */
@Slf4j
@Service
public class DesignPointMigrationService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public DesignPointMigrationService(DataSource dataSource, PlatformTransactionManager transactionManager) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(transactionManager);
    }

    public record BlockedAccount(long accountId, long available, long reserved, String reason) {
    }

    public record OrgBucket(long orgId, int accountCount, long credits) {
    }

    public record MigrationReport(long totalAvailable, int eligibleAccounts, List<OrgBucket> byOrg,
                                  List<BlockedAccount> blocked, long alreadyMigrated) {
    }

    public record MigrationResult(long migratedAccounts, long migratedCredits,
                                  long personalLedgerSum, long orgLedgerSum, boolean balanced) {
    }

    /** 预检：可迁账号按组织聚合 + 阻塞清单（在途/未映射）。 */
    public MigrationReport dryRun() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT a.user_id, a.available_points, a.reserved_points, "
                        + "       m.matched_org_id, m.matched_user_id, m.status "
                        + "FROM design_point_account a "
                        + "LEFT JOIN design_identity_mapping m ON m.account_id = a.user_id "
                        + "WHERE a.deleted = FALSE AND (a.available_points > 0 OR a.reserved_points > 0) "
                        + "ORDER BY a.user_id");
        Map<Long, long[]> byOrg = new LinkedHashMap<>();
        List<BlockedAccount> blocked = new ArrayList<>();
        long total = 0;
        int eligible = 0;
        for (Map<String, Object> row : rows) {
            long accountId = ((Number) row.get("user_id")).longValue();
            long available = ((Number) row.get("available_points")).longValue();
            long reserved = ((Number) row.get("reserved_points")).longValue();
            Long orgId = row.get("matched_org_id") == null ? null
                    : ((Number) row.get("matched_org_id")).longValue();
            String status = (String) row.get("status");
            if (reserved > 0) {
                blocked.add(new BlockedAccount(accountId, available, reserved, "存在在途预留，需先承接在途"));
                continue;
            }
            if (orgId == null || !"CONFIRMED".equals(status)) {
                blocked.add(new BlockedAccount(accountId, available, reserved,
                        "无 CONFIRMED 身份映射（请先完成 B-15 人工确认）"));
                continue;
            }
            if (available <= 0) {
                continue;
            }
            long[] bucket = byOrg.computeIfAbsent(orgId, k -> new long[]{0, 0});
            bucket[0] += 1;
            bucket[1] += available;
            total += available;
            eligible++;
        }
        Long migrated = jdbc.queryForObject(
                "SELECT COALESCE(SUM(available_delta), 0) FROM org_point_ledger WHERE type = 'MIGRATION'",
                Long.class);
        List<OrgBucket> buckets = new ArrayList<>();
        byOrg.forEach((orgId, v) -> buckets.add(new OrgBucket(orgId, (int) v[0], v[1])));
        return new MigrationReport(total, eligible, buckets, blocked, migrated == null ? 0 : migrated);
    }

    /** 执行迁移：逐账号短事务（个人负流水 + 池加点 + 幂等键），最后守恒校验。 */
    public MigrationResult execute(String operator) {
        MigrationReport pre = dryRun();
        if (!pre.blocked().isEmpty()) {
            throw new IllegalStateException("存在阻塞账号，禁止迁移：共 " + pre.blocked().size()
                    + " 个（在途预留或未确认映射）");
        }
        long credited = 0;
        int accounts = 0;
        for (OrgBucket bucket : pre.byOrg()) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT a.user_id, a.available_points FROM design_point_account a "
                            + "JOIN design_identity_mapping m ON m.account_id = a.user_id "
                            + "WHERE m.matched_org_id = ? AND m.status = 'CONFIRMED' "
                            + "AND a.deleted = FALSE AND a.available_points > 0 AND a.reserved_points = 0 "
                            + "ORDER BY a.user_id", bucket.orgId());
            for (Map<String, Object> row : rows) {
                long accountId = ((Number) row.get("user_id")).longValue();
                long amount = ((Number) row.get("available_points")).longValue();
                long orgId = bucket.orgId();
                String bizKey = "migration:" + accountId;
                Long done = tx.execute(status -> {
                    if (existsOrgLedger(bizKey)) {
                        return 0L;
                    }
                    // 池侧：先确保池存在，再 MIGRATION 加点
                    jdbc.update("INSERT INTO org_point_account (id, org_id) VALUES (?, ?) "
                            + "ON CONFLICT (org_id) DO NOTHING", IdWorker.getId(), orgId);
                    jdbc.update("UPDATE org_point_account SET balance = balance + ?, "
                            + "version = version + 1, update_time = now() WHERE org_id = ?", amount, orgId);
                    jdbc.update("INSERT INTO org_point_ledger (id, org_id, operation_id, biz_key, type, "
                                    + "subject, actor_user_id, available_delta, price_snapshot, ref_type, ref_id, note) "
                                    + "VALUES (?, ?, NULL, ?, 'MIGRATION', NULL, ?, ?, NULL, 'design_account', ?, ?)",
                            IdWorker.getId(), orgId, bizKey, accountId, amount, accountId,
                            "存量个人点迁入（operator=" + operator + "）");
                    // 个人侧：负流水留痕后清零
                    jdbc.update("UPDATE design_point_account SET available_points = 0, "
                            + "version = version + 1, update_time = now() "
                            + "WHERE user_id = ? AND available_points = ?", accountId, amount);
                    jdbc.update("INSERT INTO design_point_ledger (id, user_id, type, delta, "
                                    + "available_after, reserved_after, biz_type, biz_id, idempotency_key, "
                                    + "operator_id, reason) "
                                    + "VALUES (?, ?, 'MIGRATION_OUT', ?, 0, 0, 'org_point_migration', ?, ?, ?, ?)",
                            IdWorker.getId(), accountId, -amount, String.valueOf(orgId),
                            bizKey + ":OUT", operator, "迁入公司池 orgId=" + orgId);
                    return amount;
                });
                if (done != null && done > 0) {
                    credited += done;
                    accounts++;
                }
            }
        }
        long personalOut = sumPersonalMigrationOut();
        long orgIn = sumOrgMigrationIn();
        long expectedAfter = pre.alreadyMigrated() + credited;
        boolean balanced = personalOut == orgIn && orgIn == expectedAfter;
        log.info("[execute][迁移完成] accounts={} credits={} personalOut={} orgIn={} balanced={}",
                accounts, credited, personalOut, orgIn, balanced);
        if (!balanced) {
            throw new IllegalStateException("迁移守恒校验失败：personalOut=" + personalOut
                    + " orgIn=" + orgIn);
        }
        return new MigrationResult(accounts, credited, personalOut, orgIn, true);
    }

    private boolean existsOrgLedger(String bizKey) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM org_point_ledger WHERE biz_key = ?", Integer.class, bizKey);
        return n != null && n > 0;
    }

    private long sumPersonalMigrationOut() {
        Long sum = jdbc.queryForObject(
                "SELECT COALESCE(-SUM(delta), 0) FROM design_point_ledger WHERE type = 'MIGRATION_OUT'",
                Long.class);
        return sum == null ? 0 : sum;
    }

    private long sumOrgMigrationIn() {
        return runMigrated();
    }

    private long runMigrated() {
        Long sum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(available_delta), 0) FROM org_point_ledger WHERE type = 'MIGRATION'",
                Long.class);
        return sum == null ? 0 : sum;
    }
}
