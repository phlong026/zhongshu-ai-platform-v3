package cn.iocoder.yudao.module.franchise.performance.content;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.platform.org.SystemDirectory;
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
    @jakarta.annotation.Resource private SystemDirectory directory;

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
        return reconcile(post, period, null, idempotencyKey);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> reconcile(PostSnapshot post, String period, String expectedSignature,
                                          String idempotencyKey) {
        requirePeriod(period);
        // Serialize confirmations for one company/month, including the first insert and weak-network replay.
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                Object.class, "fr-perf-reconcile:" + post.orgId() + ":" + period);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("period", period);
        input.put("action", "month-reconcile");
        if (StrUtil.isNotBlank(expectedSignature)) input.put("sourceSignature", expectedSignature);
        FrPerfReconciliationDO existing = reconciliations.selectByPeriod(post.orgId(), period);
        if (existing != null) {
            var replayed = commands.previous(post, idempotencyKey, "perf-month-reconcile", existing.getId());
            if (replayed.isPresent()) {
                Map<String, Object> payload = JsonUtil.parseMap(replayed.get().getPayload());
                if (payload != null && input.equals(payload.get("request"))
                        && payload.get("confirmation") instanceof Map<?, ?> confirmation) {
                    // The monthly row may have been confirmed again. Its old request still gets its own receipt.
                    @SuppressWarnings("unchecked")
                    Map<String, Object> original = (Map<String, Object>) confirmation;
                    return new LinkedHashMap<>(original);
                }
                // Pre-upgrade events contained only the period and have no detailed receipt to recover.
                if (StrUtil.isBlank(expectedSignature) && Map.of("period", period).equals(payload)) {
                    return reconciliationView(post, period);
                }
                throw exception(PERF_INPUT_INVALID, "该请求的对账版本不一致，请重新读取对账记录");
            }
        }
        Map<String, Object> current = currentReconciliation(post.orgId(), period);
        if (StrUtil.isNotBlank(expectedSignature)
                && !expectedSignature.equals(current.get("sourceSignature"))) {
            throw exception(PERF_INPUT_INVALID, "账有变化，请刷新核对后再确认");
        }
        Map<String, Object> snapshot = new LinkedHashMap<>(current);
        snapshot.put("byName", userName(post.userId()));
        FrPerfReconciliationDO row = new FrPerfReconciliationDO().setCompanyId(post.orgId()).setPeriod(period)
                .setIncomeCents(cents(current, "incomeCents")).setPaidCents(cents(current, "paidCents"))
                .setNetCents(cents(current, "netCents")).setReceiptCount(((Number)current.get("receiptCount")).intValue())
                .setPaymentCount(((Number)current.get("paymentCount")).intValue()).setByUserId(post.userId())
                .setSourceSnapshot(JsonUtils.toJsonString(snapshot)).setUpdateTime(java.time.OffsetDateTime.now());
        if (existing == null) reconciliations.insert(row);
        else reconciliations.updateById(row.setId(existing.getId()));
        long recId = reconciliations.selectByPeriod(post.orgId(), period).getId();
        Map<String, Object> saved = reconciliationView(post, period);
        commands.record(post, AGGREGATE, recId,
                PerfSupport.nextAggregateRevision(jdbc, AGGREGATE, recId), "perf-month-reconcile",
                Map.of("request", input, "confirmation", saved), idempotencyKey);
        return saved;
    }

    /** Current ledger and the saved confirmation remain separate until finance explicitly confirms again. */
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String, Object> monthReconciliation(PostSnapshot post, String period) {
        requirePeriod(period);
        Map<String, Object> current = currentReconciliation(post.orgId(), period);
        Map<String, Object> saved = reconciliationView(post, period);
        boolean confirmed = saved.containsKey("incomeCents");
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("period", period);
        view.put("current", current);
        view.put("confirmed", confirmed ? saved : null);
        view.put("needsReview", confirmed && !current.get("sourceSignature").equals(saved.get("sourceSignature")));
        return view;
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
        if (row != null) {
            Map<String, Object> snapshot = JsonUtil.parseMap(row.getSourceSnapshot());
            if (snapshot != null) view.putAll(snapshot);
            view.put("incomeCents", row.getIncomeCents());
            view.put("paidCents", row.getPaidCents());
            view.put("netCents", row.getNetCents());
            view.put("receiptCount", row.getReceiptCount());
            view.put("paymentCount", row.getPaymentCount());
            view.put("byUserId", String.valueOf(row.getByUserId()));
            if (!view.containsKey("byName")) view.put("byName", userName(row.getByUserId()));
            view.put("updateTime", row.getUpdateTime().toString());
            view.put("legacy", snapshot == null);
        }
        view.put("period", period);
        return view;
    }

    private String userName(long userId) {
        if (directory != null) {
            var users = directory.usersByIds(List.of(userId));
            if (!users.isEmpty() && StrUtil.isNotBlank(users.get(0).name())) return users.get(0).name();
        }
        return "员工 " + userId;
    }

    private static long cents(Map<String, Object> values, String key) {
        return ((Number) values.get(key)).longValue();
    }

    private Map<String, Object> currentReconciliation(long companyId, String period) {
        // One statement sees a coherent set of source rows. Counts and amounts use the same facts.
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT 'receipt' AS kind,r.id::text AS id,r.amount_cents,'COMPANY' AS funding,r.received_date::text AS business_date,
                EXISTS (SELECT 1 FROM pf_command_event e WHERE e.domain='franchise' AND e.org_id=r.company_id
                    AND e.aggregate_type='fin-employee-recovery' AND e.aggregate_id=r.id
                    AND e.revision=1 AND e.command='fin-employee-recovery') AS employee_recovery
            FROM fr_fin_receipt r WHERE r.company_id=? AND r.status='已登记'
                AND r.received_date>=?::date AND r.received_date<?::date
            UNION ALL
            SELECT 'payment',id::text,amount_cents,funding_type,pay_date::text,false
            FROM fr_fin_payment WHERE company_id=? AND status='已核实' AND funding_type IN ('COMPANY','EMPLOYEE')
                AND pay_date>=?::date AND pay_date<?::date
            UNION ALL
            SELECT refund_direction,id::text,amount_cents,funding_type,date::text,false
            FROM fr_fin_refund WHERE company_id=? AND status='已核实' AND replaced_by_id IS NULL
                AND (refund_direction='client' OR funding_type IN ('COMPANY','EMPLOYEE'))
                AND date>=?::date AND date<?::date
            ORDER BY kind,id
            """, companyId, period + "-01", nextMonth(period), companyId, period + "-01", nextMonth(period),
                companyId, period + "-01", nextMonth(period));
        Map<String, Object> out = new LinkedHashMap<>();
        for (String name : List.of("grossReceipts", "employeeRecovery", "clientRefunds", "companyPaid", "companyReturned", "employeeAdvance", "employeeReturned")) {
            out.put(name + "Cents", 0L);
            out.put(name + "Count", 0);
        }
        for (Map<String, Object> row : rows) {
            String kind = String.valueOf(row.get("kind"));
            boolean employee = "EMPLOYEE".equals(row.get("funding"));
            String name = switch (kind) {
                case "receipt" -> "grossReceipts";
                case "client" -> "clientRefunds";
                case "payment" -> employee ? "employeeAdvance" : "companyPaid";
                case "expense" -> employee ? "employeeReturned" : "companyReturned";
                default -> throw new IllegalStateException("Unexpected reconciliation source " + kind);
            };
            long amount = ((Number)row.get("amount_cents")).longValue();
            // This is a subset of company cash receipts, not a new receipt or a supplier refund to an employee.
            // Keep the original five source fields for existing reconciliation signatures.
            if (Boolean.TRUE.equals(row.remove("employee_recovery"))) {
                out.put("employeeRecoveryCents", cents(out, "employeeRecoveryCents") + amount);
                out.put("employeeRecoveryCount", ((Number)out.get("employeeRecoveryCount")).intValue() + 1);
            }
            out.put(name + "Cents", cents(out, name + "Cents") + amount);
            out.put(name + "Count", ((Number)out.get(name + "Count")).intValue() + 1);
        }
        long gross = cents(out, "grossReceiptsCents"), refunds = cents(out, "clientRefundsCents");
        long companyPaid = cents(out, "companyPaidCents") - cents(out, "companyReturnedCents");
        out.put("netReceivedCents", gross - refunds);
        out.put("netCompanyPaidCents", companyPaid);
        out.put("employeeNetAdvanceCents", cents(out, "employeeAdvanceCents") - cents(out, "employeeReturnedCents"));
        // Retain existing reporting semantics: paid includes client refunds and nets supplier returns.
        out.put("incomeCents", gross);
        out.put("paidCents", companyPaid + refunds);
        out.put("netCents", gross - refunds - companyPaid);
        out.put("receiptCount", out.get("grossReceiptsCount"));
        out.put("paymentCount", ((Number)out.get("companyPaidCount")).intValue() + ((Number)out.get("clientRefundsCount")).intValue());
        out.put("sourceSignature", PerfSupport.signature(rows.stream().map(JsonUtils::toJsonString).toList()));
        return out;
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
            // M08-T08/R1 P1：趋势支出同 reconcile 口径——client 退款流出计入，expense 退回回流冲减
            long paid = monthSum(org, "SELECT COALESCE(SUM(amount_cents),0) FROM fr_fin_payment "
                    + "WHERE company_id=? AND status='已核实' AND funding_type='COMPANY' AND pay_date >= ?::date AND pay_date < ?::date", p)
                    + monthSum(org, "SELECT COALESCE(SUM(amount_cents),0) FROM fr_fin_refund "
                    + "WHERE company_id=? AND status='已核实' AND replaced_by_id IS NULL AND refund_direction = 'client' "
                    + "AND date >= ?::date AND date < ?::date", p)
                    - monthSum(org, "SELECT COALESCE(SUM(amount_cents),0) FROM fr_fin_refund "
                    + "WHERE company_id=? AND status='已核实' AND replaced_by_id IS NULL AND refund_direction = 'expense' AND funding_type='COMPANY' "
                    + "AND date >= ?::date AND date < ?::date", p);
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
                        + "WHERE company_id=? AND status='已核实' AND funding_type='COMPANY' AND pay_date <= ?::date UNION ALL "
                        + "SELECT 'refund', COALESCE(SUM(amount_cents),0) FROM fr_fin_refund "
                        + "WHERE company_id=? AND status='已核实' AND replaced_by_id IS NULL AND refund_direction = 'client' "
                        + "AND date <= ?::date UNION ALL "
                        // R3 P2：expense 回流按减项语义返回负值——构成项合计=净支出（与 trend/reconcile 同口径）
                        + "SELECT 'expense-refund', -COALESCE(SUM(amount_cents),0) FROM fr_fin_refund "
                        + "WHERE company_id=? AND status='已核实' AND replaced_by_id IS NULL AND refund_direction = 'expense' AND funding_type='COMPANY' "
                        + "AND date <= ?::date",
                org, asOfDate, org, asOfDate, org, asOfDate));
        // 应收按期次汇总：统一销售合同优先，未迁移项目使用工程约定。
        var receiptBalances=receiptBalances(org,asOfDate);var paymentBalances=paymentBalances(org,asOfDate);
        long totalTerms=receiptBalances.stream().mapToLong(r->((Number)r.get("amount_cents")).longValue()).sum();
        long collected=receiptBalances.stream().mapToLong(r->((Number)r.get("collected_cents")).longValue()).sum();
        long receivable=receiptBalances.stream().mapToLong(r->Math.max(0,((Number)r.get("amount_cents")).longValue()-((Number)r.get("collected_cents")).longValue())).sum();
        long payableOutstanding=paymentBalances.stream().mapToLong(r->Math.max(0,((Number)r.get("due")).longValue())).sum();
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("receivableCents", receivable);
        totals.put("collectedCents", collected);
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
        for(var row:receiptBalances){long due=Math.max(0,((Number)row.get("amount_cents")).longValue()-((Number)row.get("collected_cents")).longValue());if(due>0)bucketize(receivableAging,due,row.get("due_date")==null?null:((java.sql.Date)row.get("due_date")).toLocalDate(),today);}
        Map<String,Long> payableAging=emptyBuckets.get();
        for(var row:paymentBalances){long due=((Number)row.get("due")).longValue();if(due>0)bucketize(payableAging,due,row.get("due_date")==null?null:((java.sql.Date)row.get("due_date")).toLocalDate(),today);}
        report.put("receivableAging", receivableAging);
        report.put("payableAging", payableAging);
        // M10-T04/R1 P1-8：签名=完整 report JSON 的 SHA 摘要——覆盖全部报表依赖（构成/账龄/截面），
        // 历史修订改变任一维度都会生成新版本（旧按月分桶签名漏构成/账龄变更）
        report.put("sourceSignature", PerfSupport.signature(
                java.util.List.of(cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(report))));
        return report;
    }

    // A recorded signing date and dated cash corrections can affect past months. Contract
    // changes take effect on their recorded business day and voids on the command day.
    // Once a project moves to sales terms, voiding those terms does not revive legacy debt.
    private List<Map<String,Object>> receiptBalances(long org, String asOf) {
        return jdbc.queryForList("""
            WITH cutoff AS (SELECT ?::bigint AS org, ?::date AS day),
            signed_history AS (
                SELECT c.id,c.company_id,c.customer_id,
                    (v.occurred_at AT TIME ZONE 'Asia/Shanghai')::date AS void_date
                FROM cutoff d JOIN fr_sale_contract c ON c.company_id=d.org
                    AND NOT c.deleted AND c.signed_date<=d.day
                LEFT JOIN LATERAL (
                    SELECT e.revision,e.occurred_at FROM pf_command_event e
                    WHERE c.status='VOIDED' AND e.domain='franchise' AND e.aggregate_type='fr-sale-contract'
                      AND e.aggregate_id=c.id AND e.org_id=c.company_id AND e.command='sale-contract-void'
                    ORDER BY e.revision LIMIT 1
                ) v ON true
                WHERE c.status='SIGNED' OR (v.revision IS NOT NULL AND EXISTS (
                    SELECT 1 FROM pf_command_event e
                    WHERE e.domain='franchise' AND e.aggregate_type='fr-sale-contract'
                      AND e.aggregate_id=c.id AND e.org_id=c.company_id AND e.command='sale-contract-sign'
                      AND e.revision<v.revision
                ))
            ),
            sale_terms AS (
                SELECT s.id,s.company_id,s.contract_id,
                    CASE WHEN h.stage_id IS NULL THEN s.amount_cents ELSE h.amount_cents END AS amount_cents,
                    CASE WHEN h.stage_id IS NULL THEN s.due_date ELSE h.due_date END AS due_date
                FROM cutoff d
                JOIN signed_history c ON c.company_id=d.org AND (c.void_date IS NULL OR c.void_date>d.day)
                JOIN fr_sale_payment_stage s ON s.contract_id=c.id AND s.company_id=c.company_id
                LEFT JOIN LATERAL (
                    SELECT h.stage_id,h.amount_cents,h.due_date,h.deleted
                    FROM fr_sale_stage_before_change h
                    WHERE h.company_id=s.company_id AND h.stage_id=s.id AND h.effective_on>d.day
                    ORDER BY h.effective_on,h.contract_revision LIMIT 1
                ) h ON true
                -- Voiding only soft-deletes pending stages. Prior months use the stage before that deletion.
                WHERE NOT COALESCE(h.deleted, CASE WHEN c.void_date IS NOT NULL THEN false ELSE s.deleted END)
            ),
            receipts AS (
                SELECT r.id,r.company_id,r.amount_cents-COALESCE((
                    SELECT sum(f.amount_cents) FROM fr_fin_refund f
                    WHERE f.company_id=r.company_id AND f.receipt_id=r.id AND f.status='已核实'
                      AND f.replaced_by_id IS NULL AND f.date<=d.day
                ),0) AS collected_cents
                FROM cutoff d JOIN fr_fin_receipt r ON r.company_id=d.org
                    AND r.status='已登记' AND r.received_date<=d.day
            ),
            legacy_projects AS (
                SELECT p.id,p.company_id
                FROM cutoff d JOIN fr_eng_project p ON p.company_id=d.org
                WHERE NOT EXISTS (
                    SELECT 1 FROM signed_history c WHERE c.company_id=p.company_id
                      AND (c.id=p.sale_contract_id OR (p.sale_contract_id IS NULL AND c.customer_id=p.customer_id))
                )
                  AND NOT EXISTS (
                      SELECT 1 FROM fr_eng_collection x
                      JOIN fr_sale_stage_receipt a ON a.receipt_id=x.receipt_id AND a.company_id=x.company_id
                      JOIN sale_terms s ON s.id=a.stage_id AND s.company_id=a.company_id
                      WHERE x.project_id=p.id AND x.company_id=p.company_id
                        AND x.occurred_at<=d.day
                  )
            )
            SELECT s.amount_cents,s.due_date,COALESCE((
                SELECT sum(r.collected_cents) FROM fr_sale_stage_receipt a
                JOIN receipts r ON r.id=a.receipt_id AND r.company_id=a.company_id
                WHERE a.stage_id=s.id AND a.company_id=s.company_id
            ),0) AS collected_cents
            FROM sale_terms s
            UNION ALL
            SELECT t.amount_cents,NULL::date AS due_date,COALESCE((
                SELECT sum(CASE WHEN x.receipt_id IS NULL THEN x.amount_cents ELSE r.collected_cents END)
                FROM fr_eng_collection x
                LEFT JOIN receipts r ON r.id=x.receipt_id AND r.company_id=x.company_id
                WHERE x.company_id=t.company_id AND x.project_id=t.project_id AND x.stage=t.stage
                  AND x.occurred_at<=d.day
            ),0) AS collected_cents
            FROM cutoff d JOIN legacy_projects p ON p.company_id=d.org
            JOIN fr_eng_collection_term t ON t.project_id=p.id AND t.company_id=p.company_id
            WHERE (t.create_time AT TIME ZONE 'Asia/Shanghai')::date<=d.day
            """, org, asOf);
    }
    private List<Map<String,Object>> paymentBalances(long org,String asOf){return jdbc.queryForList("""
        SELECT q.due_date,
         CASE WHEN q.source_type='reimbursement' THEN COALESCE((SELECT m.amount_cents-COALESCE((SELECT sum(f.amount_cents) FROM fr_fin_refund f WHERE f.payment_id=m.id AND f.status='已核实' AND f.funding_type='EMPLOYEE' AND f.date<=?::date),0) FROM fr_fin_payment m WHERE m.id::text=q.source_ref AND m.company_id=q.company_id AND m.status='已核实'),0) ELSE q.total_cents END
         -COALESCE((SELECT sum(LEAST(p.amount_cents-COALESCE(p.pending_link_cents,0),p.amount_cents-COALESCE((SELECT sum(f.amount_cents) FROM fr_fin_refund f WHERE f.payment_id=p.id AND f.status='已核实' AND f.date<=?::date),0))) FROM fr_fin_payment p WHERE p.payable_id=q.id AND p.company_id=q.company_id AND p.status='已核实' AND p.pay_date<=?::date),0)
         +CASE WHEN q.source_type='reimbursement' THEN COALESCE((SELECT sum(r.amount_cents) FROM fr_fin_receipt r WHERE r.company_id=q.company_id AND r.source_ref='employee-recovery:'||q.id AND r.status='已登记' AND r.received_date<=?::date),0) ELSE 0 END AS due
         FROM fr_fin_payable q WHERE q.company_id=? AND COALESCE(q.business_date,q.create_time::date)<=?::date
        """,asOf,asOf,asOf,asOf,org,asOf);}

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
