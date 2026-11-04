package cn.iocoder.yudao.module.franchise.performance.content;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.franchise.performance.dal.FrPerfOperatingSnapshotDO;
import cn.iocoder.yudao.module.franchise.performance.dal.FrPerfOperatingSnapshotMapper;
import cn.iocoder.yudao.module.franchise.performance.dal.FrPerfReconciliationDO;
import cn.iocoder.yudao.module.franchise.performance.dal.FrPerfReconciliationMapper;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.performance.enums.FranchisePerformanceErrorCodes.*;

/**
 * 经营视图（3.10 / D-7）：月末对账（按月覆盖写；口径=fr_fin 当月已登记回款/已核实付退）+
 * 经营快照（只允许过去月 008；sourceSignature 内嵌 report；变化 version+1 supersedes）+
 * 实时 analytics（近6月趋势/收支构成/应收应付合计/回款率/资金覆盖——账龄 6 桶并入 report）。
 */
@Service
public class PerfOperatingService {

    public static final String AGGREGATE = "fr-perf-operating";

    private final FrPerfReconciliationMapper reconciliations;
    private final FrPerfOperatingSnapshotMapper snapshots;
    private final FranchiseCommands commands;
    private final JdbcTemplate jdbc;

    public PerfOperatingService(FrPerfReconciliationMapper reconciliations,
                                FrPerfOperatingSnapshotMapper snapshots,
                                FranchiseCommands commands, JdbcTemplate jdbc) {
        this.reconciliations = reconciliations;
        this.snapshots = snapshots;
        this.commands = commands;
        this.jdbc = jdbc;
    }

    // ===================== 月末对账 =====================

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> reconcile(PostSnapshot post, String period, String idempotencyKey) {
        requirePeriod(period);
        var replayed = commands.replayed(post, idempotencyKey, Map.of("period", period, "action", "month-reconcile"));
        if (replayed.isPresent()) {
            return reconciliationView(post, period);
        }
        long income = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_cents), 0) FROM fr_fin_receipt WHERE company_id = ? "
                        + "AND status = '已登记' AND received_date >= ?::date AND received_date < ?::date",
                Long.class, post.orgId(), period + "-01", nextMonth(period));
        long paid = jdbc.queryForObject(
                "SELECT (SELECT COALESCE(SUM(amount_cents), 0) FROM fr_fin_payment WHERE company_id = ? "
                        + "AND status = '已核实' AND pay_date >= ?::date AND pay_date < ?::date) "
                        + "+ (SELECT COALESCE(SUM(amount_cents), 0) FROM fr_fin_refund WHERE company_id = ? "
                        + "AND status = '已核实' AND replaced_by_id IS NULL AND date >= ?::date AND date < ?::date)",
                Long.class, post.orgId(), period + "-01", nextMonth(period),
                post.orgId(), period + "-01", nextMonth(period));
        int receiptCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fr_fin_receipt WHERE company_id = ? AND status = '已登记' "
                        + "AND received_date >= ?::date AND received_date < ?::date",
                Integer.class, post.orgId(), period + "-01", nextMonth(period));
        int paymentCount = jdbc.queryForObject(
                "SELECT (SELECT COUNT(*) FROM fr_fin_payment WHERE company_id = ? AND status = '已核实' "
                        + "AND pay_date >= ?::date AND pay_date < ?::date) "
                        + "+ (SELECT COUNT(*) FROM fr_fin_refund WHERE company_id = ? AND status = '已核实' "
                        + "AND replaced_by_id IS NULL AND date >= ?::date AND date < ?::date)",
                Integer.class, post.orgId(), period + "-01", nextMonth(period),
                post.orgId(), period + "-01", nextMonth(period));
        FrPerfReconciliationDO existing = reconciliations.selectByPeriod(post.orgId(), period);
        if (existing == null) {
            reconciliations.insert(new FrPerfReconciliationDO().setCompanyId(post.orgId()).setPeriod(period)
                    .setIncomeCents(income).setPaidCents(paid).setNetCents(income - paid)
                    .setReceiptCount(receiptCount).setPaymentCount(paymentCount).setByUserId(post.userId()));
        } else {
            reconciliations.updateById(new FrPerfReconciliationDO().setId(existing.getId())
                    .setIncomeCents(income).setPaidCents(paid).setNetCents(income - paid)
                    .setReceiptCount(receiptCount).setPaymentCount(paymentCount).setByUserId(post.userId()));
        }
        long recId = reconciliations.selectByPeriod(post.orgId(), period).getId();
        commands.record(post, AGGREGATE, recId,
                PerfSupport.nextAggregateRevision(jdbc, AGGREGATE, recId), "perf-month-reconcile",
                Map.of("period", period), idempotencyKey);
        return reconciliationView(post, period);
    }

    public List<Map<String, Object>> reconciliationList(PostSnapshot post) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (FrPerfReconciliationDO row : reconciliations.selectAll(post.orgId())) {
            out.add(reconciliationView(post, row.getPeriod()));
        }
        return out;
    }

    private Map<String, Object> reconciliationView(PostSnapshot post, String period) {
        FrPerfReconciliationDO row = reconciliations.selectByPeriod(post.orgId(), period);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("period", period);
        if (row != null) {
            view.put("incomeCents", row.getIncomeCents());
            view.put("paidCents", row.getPaidCents());
            view.put("netCents", row.getNetCents());
            view.put("receiptCount", row.getReceiptCount());
            view.put("paymentCount", row.getPaymentCount());
            view.put("byUserId", row.getByUserId());
            view.put("updateTime", row.getUpdateTime());
        }
        return view;
    }

    // ===================== 经营快照（只允许过去月） =====================

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> snapshot(PostSnapshot post, String period, String note, String idempotencyKey) {
        requirePeriod(period);
        if (!period.equals(previousPeriod(currentPeriod()) ) && period.compareTo(currentPeriod()) >= 0) {
            throw exception(PERF_PERIOD_INVALID, "只能归档过去月份");
        }
        var replayed = commands.replayed(post, idempotencyKey, Map.of("period", period, "action", "snapshot"));
        if (replayed.isPresent()) {
            return snapshotView(post, period);
        }
        Map<String, Object> report = analytics(post, period);
        String signature = String.valueOf(report.get("sourceSignature"));
        List<FrPerfOperatingSnapshotDO> existing = snapshots.selectByPeriod(post.orgId(), period);
        if (!existing.isEmpty() && signature.equals(
                JsonUtil.parseMap(existing.get(0).getReport()) == null ? ""
                        : String.valueOf(JsonUtil.parseMap(existing.get(0).getReport()).get("sourceSignature")))) {
            throw exception(GOAL_OR_SNAPSHOT_LOCKED); // 签名未变化（002 幂等拒绝）
        }
        LocalDate asOf = LocalDate.parse(nextMonth(period)).minusDays(1); // P1-13：period 月末（原式早一月）
        long snapId = cn.hutool.core.util.IdUtil.getSnowflakeNextId();
        int snapVersion = existing.isEmpty() ? 1 : existing.get(0).getVersion() + 1;
        jdbc.update("INSERT INTO fr_perf_operating_snapshot (id, company_id, period, version, supersedes, "
                        + "report, as_of, note, status, author_user_id, create_time) "
                        + "VALUES (?,?,?,?,?,CAST(? AS jsonb),?,?,'FINAL',?,now())",
                snapId, post.orgId(), period, snapVersion,
                existing.isEmpty() ? null : existing.get(0).getId(),
                cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(report),
                asOf, StrUtil.nullToEmpty(note), post.userId());
        commands.record(post, AGGREGATE, snapId, snapVersion, "perf-operating-snapshot",
                Map.of("period", period, "version", snapVersion), idempotencyKey);
        return snapshotView(post, period);
    }

    public Map<String, Object> snapshotView(PostSnapshot post, String period) {
        List<FrPerfOperatingSnapshotDO> rows = snapshots.selectByPeriod(post.orgId(), period);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("period", period);
        view.put("snapshots", rows.stream().map(s -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", String.valueOf(s.getId()));
            row.put("version", s.getVersion());
            row.put("supersedes", s.getSupersedes() == null ? null : String.valueOf(s.getSupersedes()));
            row.put("report", s.getReport());
            row.put("asOf", s.getAsOf());
            row.put("note", s.getNote());
            return row;
        }).toList());
        return view;
    }

    // ===================== 实时 analytics =====================

    /** 近6月趋势/收支构成/应收应付合计/回款率/资金覆盖；sourceSignature 内嵌（快照幂等依据） */
    public Map<String, Object> analytics(PostSnapshot post, String period) {
        long org = post.orgId();
        Map<String, Object> report = new LinkedHashMap<>();
        // 近 6 月趋势（含当月）
        List<Map<String, Object>> trend = new ArrayList<>();
        for (int i = 5; i >= 0; i--) {
            String p = shift(period, -i);
            long income = monthSum(org, "SELECT COALESCE(SUM(amount_cents),0) FROM fr_fin_receipt "
                    + "WHERE company_id=? AND status='已登记' AND received_date >= ?::date AND received_date < ?::date", p);
            long paid = monthSum(org, "SELECT COALESCE(SUM(amount_cents),0) FROM fr_fin_payment "
                    + "WHERE company_id=? AND status='已核实' AND pay_date >= ?::date AND pay_date < ?::date", p)
                    + monthSum(org, "SELECT COALESCE(SUM(amount_cents),0) FROM fr_fin_refund "
                    + "WHERE company_id=? AND status='已核实' AND replaced_by_id IS NULL AND date >= ?::date AND date < ?::date", p);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("period", p);
            row.put("incomeCents", income);
            row.put("paidCents", paid);
            row.put("netCents", income - paid);
            trend.add(row);
        }
        report.put("trend", trend);
        // 收/支构成（按来源类型）
        // M10-T04：构成/截面补 period 月末截止（11 月收付不污染 10 月快照）
        String asOfDate = java.time.LocalDate.parse(nextMonth(period)).minusDays(1).toString();
        report.put("incomeComposition", jdbc.queryForList(
                "SELECT source_type AS kind, COALESCE(SUM(amount_cents),0) AS cents FROM fr_fin_receipt "
                        + "WHERE company_id=? AND status='已登记' AND received_date <= ?::date "
                        + "GROUP BY source_type ORDER BY cents DESC", org, asOfDate));
        report.put("paidComposition", jdbc.queryForList(
                "SELECT 'payment' AS kind, COALESCE(SUM(amount_cents),0) AS cents FROM fr_fin_payment "
                        + "WHERE company_id=? AND status='已核实' AND pay_date <= ?::date UNION ALL "
                        + "SELECT 'refund', COALESCE(SUM(amount_cents),0) FROM fr_fin_refund "
                        + "WHERE company_id=? AND status='已核实' AND replaced_by_id IS NULL AND date <= ?::date",
                org, asOfDate, org, asOfDate));
        // 应收=工程两期未收合计；应付=finance 未付合计
        long receivable = jdbc.queryForObject(
                "SELECT COALESCE(SUM((SELECT COALESCE(SUM(c.amount_cents),0) FROM fr_eng_collection c "
                        + "WHERE c.company_id=t.company_id AND c.project_id=t.project_id AND c.stage=t.stage)),0) "
                        + "FROM fr_eng_collection_term t WHERE t.company_id = ?", Long.class, org);
        long totalTerms = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_cents),0) FROM fr_eng_collection_term WHERE company_id=?",
                Long.class, org);
        // M10-T04/R1 P1-6：collected 按业务日期 occurred_at 截止（历史补录进正确月份——
        // 与 trend 的 received_date 口径一致）；fr_eng_collection.occurred_at DATE NOT NULL
        long collected = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_cents),0) FROM fr_eng_collection WHERE company_id=? "
                        + "AND occurred_at <= ?::date", Long.class, org, asOfDate);
        // M10-T04/R1 P1-7：应付余额按 as_of 截止（11 月付款不减少 10 月余额）
        long payableOutstanding = jdbc.queryForObject(
                "SELECT COALESCE(SUM(total_cents - COALESCE((SELECT SUM(p.amount_cents) FROM fr_fin_payment p "
                        + "WHERE p.payable_id = q.id AND p.status='已核实' AND p.pay_date <= DATE '" + asOfDate + "'),0)),0) "
                        + "FROM fr_fin_payable q WHERE q.company_id=?",
                Long.class, org);
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("receivableCents", Math.max(0, totalTerms - collected));
        totals.put("payableOutstandingCents", Math.max(0, payableOutstanding));
        totals.put("collectionRate", totalTerms == 0 ? null : (double) collected / totalTerms);
        // 资金覆盖=近6月净流入合计/未付应付
        long net6 = trend.stream().mapToLong(r -> ((Number) r.get("netCents")).longValue()).sum();
        totals.put("net6MonthsCents", net6);
        totals.put("coverage", payableOutstanding == 0 ? null : (double) net6 / payableOutstanding);
        report.put("totals", totals);
        // P1-14：应收/应付账龄六桶（未到期/逾期1-30/31-60/61-90/90+/未约定——约定日期=收款 occurred_at 应期）
        // M10-T04/R1 P1-7：账龄基准截止 as_of（period 月末）——晚一个月重算不改变历史快照桶位
        LocalDate today = java.time.LocalDate.parse(nextMonth(period)).minusDays(1);
        java.util.function.Supplier<Map<String, Long>> emptyBuckets = () -> {
            Map<String, Long> m0 = new LinkedHashMap<>();
            for (String b : java.util.List.of("notDue", "d1_30", "d31_60", "d61_90", "d90Plus", "undated")) {
                m0.put(b, 0L);
            }
            return m0;
        };
        Map<String, Long> receivableAging = emptyBuckets.get();
        jdbc.query("SELECT t.stage, t.amount_cents - COALESCE((SELECT SUM(c.amount_cents) FROM fr_eng_collection c "
                        + "WHERE c.company_id = t.company_id AND c.project_id = t.project_id AND c.stage = t.stage "
                        + "AND c.occurred_at <= DATE '" + asOfDate + "'), 0) AS due, "
                        + "t.project_id FROM fr_eng_collection_term t WHERE t.company_id = ?",
                rs -> {
                    long due = rs.getLong("due");
                    if (due <= 0) return;
                    LocalDate ref = jdbc.queryForObject(
                            "SELECT COALESCE(MAX(occurred_at), DATE '1900-01-01') FROM fr_eng_collection "
                                    + "WHERE company_id = ? AND project_id = ? AND stage = ?",
                            LocalDate.class, org, rs.getLong("project_id"), rs.getString("stage"));
                    bucketize(receivableAging, due, ref, today);
                }, org);
        Map<String, Long> payableAging = emptyBuckets.get();
        jdbc.query("SELECT q.id, q.total_cents - COALESCE((SELECT SUM(p.amount_cents) FROM fr_fin_payment p "
                        + "WHERE p.payable_id = q.id AND p.status = '已核实' AND p.pay_date <= DATE '" + asOfDate + "'), 0) AS due, "
                        + "COALESCE(MAX(CASE WHEN pay.pay_date <= DATE '" + asOfDate + "' THEN pay.pay_date END), DATE '1900-01-01') AS last_pay "
                        + "FROM fr_fin_payable q "
                        + "LEFT JOIN fr_fin_payment pay ON pay.payable_id = q.id AND pay.status = '已核实' "
                        + "WHERE q.company_id = ? GROUP BY q.id, q.total_cents",
                rs -> {
                    long due = rs.getLong("due");
                    if (due <= 0) return;
                    bucketize(payableAging, due, rs.getDate("last_pay") == null ? null
                            : rs.getDate("last_pay").toLocalDate(), today);
                }, org);
        report.put("receivableAging", receivableAging);
        report.put("payableAging", payableAging);
        receivable = 0; // suppress unused
        // M10-T04/R1 P1-8：签名=完整 report JSON 的 SHA 摘要——覆盖全部报表依赖（构成/账龄/截面），
        // 历史修订改变任一维度都会生成新版本（旧按月分桶签名漏构成/账龄变更）
        report.put("sourceSignature", PerfSupport.signature(
                java.util.List.of(cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(report))));
        return report;
    }

    /** 账龄入桶（基准日=该笔款项最近一次收付日；无日期=未约定桶） */
    private static void bucketize(Map<String, Long> buckets, long due, LocalDate ref, LocalDate today) {
        String bucket;
        if (ref == null || ref.getYear() < 1900) {
            bucket = "undated";
        } else {
            long days = java.time.temporal.ChronoUnit.DAYS.between(ref, today);
            bucket = days <= 0 ? "notDue" : days <= 30 ? "d1_30" : days <= 60 ? "d31_60"
                    : days <= 90 ? "d61_90" : "d90Plus";
        }
        buckets.merge(bucket, due, Long::sum);
    }

    private long monthSum(long org, String sql, String period) {
        return jdbc.queryForObject(sql, Long.class, org, period + "-01", nextMonth(period));
    }

    private static String currentPeriod() {
        LocalDate now = LocalDate.now();
        return String.format("%04d-%02d", now.getYear(), now.getMonthValue());
    }

    private static String previousPeriod(String period) {
        return shift(period, -1);
    }

    private static String shift(String period, int months) {
        LocalDate d = LocalDate.parse(period + "-01").plusMonths(months);
        return String.format("%04d-%02d", d.getYear(), d.getMonthValue());
    }

    private static String nextMonth(String period) {
        return shift(period, 1) + "-01";
    }

    private static void requirePeriod(String period) {
        if (!PerfMetrics.validPeriod(period)) {
            throw exception(PERF_PERIOD_INVALID, "period 须为 YYYY-MM");
        }
    }
}
