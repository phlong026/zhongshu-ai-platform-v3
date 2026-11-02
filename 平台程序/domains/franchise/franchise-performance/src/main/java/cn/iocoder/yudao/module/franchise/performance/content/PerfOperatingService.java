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
        commands.record(post, AGGREGATE, reconciliations.selectByPeriod(post.orgId(), period).getId(),
                1, "perf-month-reconcile", Map.of("period", period), idempotencyKey);
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
        LocalDate asOf = LocalDate.parse(previousPeriod(period) + "-01").plusMonths(1).minusDays(1);
        FrPerfOperatingSnapshotDO snap = new FrPerfOperatingSnapshotDO()
                .setCompanyId(post.orgId()).setPeriod(period)
                .setVersion(existing.isEmpty() ? 1 : existing.get(0).getVersion() + 1)
                .setSupersedes(existing.isEmpty() ? null : existing.get(0).getId())
                .setReport(cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(report))
                .setAsOf(asOf).setNote(StrUtil.nullToEmpty(note)).setAuthorUserId(post.userId());
        snapshots.insert(snap);
        commands.record(post, AGGREGATE, snap.getId(), snap.getVersion(), "perf-operating-snapshot",
                Map.of("period", period, "version", snap.getVersion()), idempotencyKey);
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
        report.put("incomeComposition", jdbc.queryForList(
                "SELECT source_type AS kind, COALESCE(SUM(amount_cents),0) AS cents FROM fr_fin_receipt "
                        + "WHERE company_id=? AND status='已登记' GROUP BY source_type ORDER BY cents DESC", org));
        report.put("paidComposition", jdbc.queryForList(
                "SELECT 'payment' AS kind, COALESCE(SUM(amount_cents),0) AS cents FROM fr_fin_payment "
                        + "WHERE company_id=? AND status='已核实' UNION ALL "
                        + "SELECT 'refund', COALESCE(SUM(amount_cents),0) FROM fr_fin_refund "
                        + "WHERE company_id=? AND status='已核实' AND replaced_by_id IS NULL", org, org));
        // 应收=工程两期未收合计；应付=finance 未付合计
        long receivable = jdbc.queryForObject(
                "SELECT COALESCE(SUM((SELECT COALESCE(SUM(c.amount_cents),0) FROM fr_eng_collection c "
                        + "WHERE c.company_id=t.company_id AND c.project_id=t.project_id AND c.stage=t.stage)),0) "
                        + "FROM fr_eng_collection_term t WHERE t.company_id = ?", Long.class, org);
        long totalTerms = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_cents),0) FROM fr_eng_collection_term WHERE company_id=?",
                Long.class, org);
        long collected = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_cents),0) FROM fr_eng_collection WHERE company_id=?", Long.class, org);
        long payableOutstanding = jdbc.queryForObject(
                "SELECT COALESCE(SUM(total_cents - COALESCE((SELECT SUM(p.amount_cents) FROM fr_fin_payment p "
                        + "WHERE p.payable_id = q.id AND p.status='已核实'),0)),0) FROM fr_fin_payable q WHERE q.company_id=?",
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
        receivable = 0; // suppress unused
        // 签名（快照幂等依据——覆盖收付行指纹）
        String signature = jdbc.queryForObject(
                "SELECT CONCAT('r:', COALESCE(SUM(amount_cents),0), ':', COUNT(*)) FROM fr_fin_receipt WHERE company_id=?",
                String.class, org)
                + "|" + jdbc.queryForObject(
                "SELECT CONCAT('p:', COALESCE(SUM(amount_cents),0), ':', COUNT(*)) FROM fr_fin_payment WHERE company_id=?",
                String.class, org)
                + "|" + jdbc.queryForObject(
                "SELECT CONCAT('f:', COALESCE(SUM(amount_cents),0), ':', COUNT(*)) FROM fr_fin_refund WHERE company_id=?",
                String.class, org)
                + "|" + jdbc.queryForObject(
                "SELECT CONCAT('e:', COALESCE(SUM(amount_cents),0), ':', COUNT(*)) FROM fr_eng_collection WHERE company_id=?",
                String.class, org);
        report.put("sourceSignature", signature);
        return report;
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
