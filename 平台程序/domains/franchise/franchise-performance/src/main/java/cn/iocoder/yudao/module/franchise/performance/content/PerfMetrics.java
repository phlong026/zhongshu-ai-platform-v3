package cn.iocoder.yudao.module.franchise.performance.content;

import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 绩效指标口径引擎（19 号 D-3/V2.1 定稿）：十类 auto 任务口径 + 三组指标口径。
 * 只读直查各域表（JdbcTemplate 显式 company_id，不经 OrgScope）；auto 进度实时派生不落源表。
 *
 * <p>落月口径（D-10/P2-C）：各业务 DATE 列直接取月——receipts.received_date / contracts.signed_date /
 * plan_node.end_date（节点按计划结束日落月，按期=completed_at<=end_date）/ publications.published_at /
 * supply_receipt.occurred_at / refunds.date / payments.pay_date；commission（委托提交/达成）与
 * customer_follow/lead 用当月（create_time/update_time 取本地月， zsdev 单机口径）。
 *
 * <p>归属列（P3-8）：节点=responsible_user_id、合同/发布=author_user_id、合格到货=收货登记人、
 * 财务登记=registrar/operator、回款=工程来源→fr_eng_project.manager_user_id、业务来源→fr_customer.owner_user_id。
 *
 * <p>adjustments 生效（P2-3）：聚合前按 source_key 命中→替换归属/剔除（excluded）。
 */
@Component
public class PerfMetrics {

    public static final List<String> AUTO_TYPES = List.of(
            "auto:collectionsAmount", "auto:contract", "auto:completedNodes", "auto:onTimeNodes",
            "auto:publications", "auto:effectiveLeads", "auto:qualifiedDeliveries",
            "auto:approvedDeliverables", "auto:financeRegistrations");

    private final JdbcTemplate jdbc;

    public PerfMetrics(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 单条源事实（金额类 value=分；数量类 value=100/个——P1-9 单位定稿 ×100 存储）。
     * businessId=业务事实 id（如 fin-receipt:123 的 123）：adjustments 按 businessId 命中可一次
     * 裁决覆盖同一事实的全部关联派生行（回款金额+登记笔数——P1-8 契约）。
     */
    public record SourceRow(String key, String type, long value, Long userId, LocalDate date, String businessId) {
    }

    /** 申诉裁决行（fr_perf_cycle.adjustments JSONB 元素） */
    public record Adjustment(String sourceKey, Long personUserId, boolean excluded) {
    }

    private static String monthStart(String period) {
        return period + "-01";
    }

    private static String monthEndExclusive(String period) {
        LocalDate start = LocalDate.parse(monthStart(period));
        return start.plusMonths(1).toString();
    }

    /** 十类源行全量（已应用 adjustments；金额净额在 SQL 内完成退款扣减） */
    public List<SourceRow> sources(PostSnapshot post, String period, List<Adjustment> adjustments) {
        List<SourceRow> rows = new ArrayList<>();
        long org = post.orgId();
        String from = monthStart(period), to = monthEndExclusive(period);

        // ① auto:collectionsAmount（P1-6：receipt 记原始收款不减退款——退款独立负值行按发生日落月，
        // 每笔退款只扣一次，跨月退款污染历史月问题随之消除）
        jdbc.query("SELECT r.id, r.amount_cents, r.received_date, r.source_ref, r.source_type, r.registrar_user_id "
                        + "FROM fr_fin_receipt r WHERE r.company_id = ? AND r.status = '已登记' "
                        + "AND r.received_date >= ?::date AND r.received_date < ?::date",
                (RowCallbackHandler) rs -> {
                    long id = rs.getLong("id");
                    String sourceRef = rs.getString("source_ref");
                    Long owner = ownerOfReceipt(org, sourceRef, rs.getString("source_type"), id);
                    String businessId = "fin-receipt:" + id;
                    rows.add(new SourceRow(businessId, "auto:collectionsAmount",
                            rs.getLong("amount_cents"), owner, rs.getDate("received_date").toLocalDate(), businessId));
                    // R2-2：登记实绩归收款登记人（SELECT 显式带列——上轮回归点）
                    rows.add(new SourceRow("fin-reg-receipt:" + id, "auto:financeRegistrations",
                            100L, getLong(rs, "registrar_user_id"), rs.getDate("received_date").toLocalDate(), businessId));
                }, org, from, to);
        jdbc.query("SELECT f.id, f.amount_cents, f.date, f.receipt_id, r.source_ref, r.registrar_user_id "
                        + "FROM fr_fin_refund f LEFT JOIN fr_fin_receipt r ON r.id = f.receipt_id "
                        + "WHERE f.company_id = ? AND f.status = '已核实' AND f.replaced_by_id IS NULL "
                        + "AND f.date >= ?::date AND f.date < ?::date",
                (RowCallbackHandler) rs -> {
                    long receiptId = rs.getLong("receipt_id");
                    long refundId = rs.getLong("id");
                    Long owner = receiptId > 0 ? receiptOwnerCached(org, rs.getString("source_ref"), receiptId) : null;
                    // R2-3：退款只扣回款金额，不计 financeRegistrations（任务书口径=receipt+payment 登记）
                    rows.add(new SourceRow("fin-refund:" + refundId, "auto:collectionsAmount",
                            -rs.getLong("amount_cents"), owner, rs.getDate("date").toLocalDate(),
                            "fin-receipt:" + receiptId));
                }, org, from, to);

        // ② auto:contract 签约金额
        jdbc.query("SELECT id, total_cents, signed_date, author_user_id FROM fr_sale_contract "
                        + "WHERE company_id = ? AND status = 'SIGNED' AND signed_date >= ?::date AND signed_date < ?::date",
                (RowCallbackHandler) rs -> rows.add(new SourceRow("sale-contract:" + rs.getLong("id"), "auto:contract",
                        rs.getLong("total_cents"), getLong(rs, "author_user_id"),
                        rs.getDate("signed_date").toLocalDate(), "sale-contract:" + rs.getLong("id"))), org, from, to);

        // ③④ auto:completedNodes/onTimeNodes（V2.1：end_date 落月；按期=completed_at<=end_date）
        jdbc.query("SELECT id, completed_at, end_date, responsible_user_id, "
                        + "(completed_at IS NOT NULL AND end_date IS NOT NULL AND completed_at <= end_date) AS on_time "
                        + "FROM fr_eng_plan_node WHERE company_id = ? AND status = 'DONE' "
                        + "AND end_date >= ?::date AND end_date < ?::date",
                (RowCallbackHandler) rs -> {
                    long id = rs.getLong("id");
                    Long owner = getLong(rs, "responsible_user_id");
                    LocalDate end = rs.getDate("end_date").toLocalDate();
                    rows.add(new SourceRow("perf-node:" + id, "auto:completedNodes", 100L, owner, end, "perf-node:" + id));
                    if (rs.getBoolean("on_time")) {
                        rows.add(new SourceRow("perf-node-ontime:" + id, "auto:onTimeNodes", 100L, owner, end, "perf-node:" + id));
                    }
                }, org, from, to);

        // ⑤ auto:publications
        jdbc.query("SELECT id, published_at, author_user_id FROM fr_content_publication "
                        + "WHERE company_id = ? AND status = 'published' AND published_at >= ?::timestamptz AND published_at < ?::timestamptz",
                (RowCallbackHandler) rs -> rows.add(new SourceRow("perf-publication:" + rs.getLong("id"), "auto:publications",
                        100L, getLong(rs, "author_user_id"),
                        rs.getDate("published_at").toLocalDate(),
                        "perf-publication:" + rs.getLong("id"))), org, from, to);

        // ⑥ auto:effectiveLeads（待决①：转化时间=update_time，归属=registrar_user_id）
        jdbc.query("SELECT id, registrar_user_id, update_time FROM fr_lead "
                        + "WHERE company_id = ? AND status = 'CONVERTED' "
                        + "AND update_time >= ?::date AND update_time < ?::date",
                (RowCallbackHandler) rs -> rows.add(new SourceRow("perf-lead:" + rs.getLong("id"), "auto:effectiveLeads",
                        100L, getLong(rs, "registrar_user_id"),
                        rs.getDate("update_time").toLocalDate(), "perf-lead:" + rs.getLong("id"))), org, from, to);

        // ⑦ auto:qualifiedDeliveries（§九：按合格收货行计数、归属=收货登记人）
        jdbc.query("SELECT id, occurred_at, author_user_id FROM fr_eng_supply_receipt "
                        + "WHERE company_id = ? AND kind = 'RECEIPT' AND actual_quantity IS NOT NULL "
                        + "AND (quality = 'OK' OR CAST(quality_resolution AS text) LIKE '%更换后合格%') "
                        + "AND occurred_at >= ?::date AND occurred_at < ?::date",
                (RowCallbackHandler) rs -> rows.add(new SourceRow("perf-material:" + rs.getLong("id"), "auto:qualifiedDeliveries",
                        100L, getLong(rs, "author_user_id"), rs.getDate("occurred_at").toLocalDate(),
                        "perf-material:" + rs.getLong("id"))), org, from, to);

        // ⑧ 业务组 commissionAmount（委托金额=最新提交版，提交当月）+ auto:approvedDeliverables
        // （P1-7：完成事件 fr_commission_progress(kind='COMPLETE') 落月——提交月≠完成月不再错计）
        jdbc.query("SELECT c.id, s.total_cents, s.submitted_by_user_id, s.create_time "
                        + "FROM fr_commission c JOIN LATERAL ("
                        + "  SELECT total_cents, submitted_by_user_id, create_time FROM fr_commission_submission s "
                        + "  WHERE s.commission_id = c.id ORDER BY s.version DESC LIMIT 1) s ON TRUE "
                        + "WHERE c.company_id = ? AND s.create_time >= ?::timestamptz AND s.create_time < ?::timestamptz",
                (RowCallbackHandler) rs -> {
                    long id = rs.getLong("id");
                    Long owner = getLong(rs, "submitted_by_user_id");
                    LocalDate date = rs.getDate("create_time").toLocalDate();
                    rows.add(new SourceRow("perf-commission:" + id, "metric:commissionAmount",
                            rs.getLong("total_cents"), owner, date, "perf-commission:" + id));
                }, org, from + " 00:00:00", to + " 00:00:00");
        jdbc.query("SELECT p.id, p.commission_id, p.actor_user_id, p.create_time, s.submitted_by_user_id "
                        + "FROM fr_commission_progress p JOIN fr_commission c ON c.id = p.commission_id "
                        + "JOIN LATERAL (SELECT submitted_by_user_id FROM fr_commission_submission s "
                        + "  WHERE s.commission_id = c.id ORDER BY s.version DESC LIMIT 1) s ON TRUE "
                        + "WHERE p.company_id = ? AND p.kind = 'COMPLETE' "
                        + "AND p.create_time >= ?::timestamptz AND p.create_time < ?::timestamptz",
                (RowCallbackHandler) rs -> {
                    long id = rs.getLong("id");
                    Long owner = getLong(rs, "submitted_by_user_id");
                    Long fallback = getLong(rs, "actor_user_id");
                    rows.add(new SourceRow("perf-commission-approved:" + id, "auto:approvedDeliverables",
                            100L, owner != null ? owner : fallback,
                            rs.getDate("create_time").toLocalDate(),
                            "perf-commission:" + rs.getLong("commission_id")));
                }, org, from + " 00:00:00", to + " 00:00:00");

        // ⑨ auto:financeRegistrations 付款侧（收款侧已并入 ① businessId 行）
        jdbc.query("SELECT id, operator_user_id, pay_date FROM fr_fin_payment "
                        + "WHERE company_id = ? AND status = '已核实' AND pay_date >= ?::date AND pay_date < ?::date",
                (RowCallbackHandler) rs -> rows.add(new SourceRow("fin-reg-payment:" + rs.getLong("id"),
                        "auto:financeRegistrations", 100L, getLong(rs, "operator_user_id"),
                        rs.getDate("pay_date").toLocalDate(), "fin-payment:" + rs.getLong("id"))), org, from, to);

        // 三组补充口径：effectiveCustomers（当月有跟进客户去重；归属=客户 owner）
        jdbc.query("SELECT DISTINCT f.customer_id, c.owner_user_id, MIN(f.create_time) AS first_at "
                        + "FROM fr_customer_follow f JOIN fr_customer c ON c.id = f.customer_id "
                        + "WHERE f.company_id = ? AND f.create_time >= ?::timestamptz AND f.create_time < ?::timestamptz "
                        + "GROUP BY f.customer_id, c.owner_user_id",
                (RowCallbackHandler) rs -> rows.add(new SourceRow("perf-follow-customer:" + rs.getLong("customer_id"),
                        "metric:effectiveCustomers", 100L, getLong(rs, "owner_user_id"),
                        rs.getDate("first_at").toLocalDate(),
                        "perf-follow-customer:" + rs.getLong("customer_id"))), org, from + " 00:00:00", to + " 00:00:00");

        return applyAdjustments(rows, adjustments);
    }

    private final Map<Long, Long> receiptOwnerCache = new java.util.concurrent.ConcurrentHashMap<>();

    private Long ownerOfReceipt(long org, String sourceRef, String sourceType, long receiptId) {
        if (sourceRef != null && sourceRef.startsWith("engineering:")) {
            // engineering:{projectId}:{stage}:{collectionId}
            String[] parts = sourceRef.split(":");
            if (parts.length >= 2) {
                try {
                    long projectId = Long.parseLong(parts[1]);
                    return receiptOwnerCache.computeIfAbsent(receiptId,
                            k -> queryManager(org, projectId));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        if (sourceRef != null && sourceRef.startsWith("sale:")) {
            return receiptOwnerCache.computeIfAbsent(receiptId, k -> querySaleOwner(org, sourceRef));
        }
        return null;
    }

    private Long receiptOwnerCached(long org, String sourceRef, long receiptId) {
        return ownerOfReceipt(org, sourceRef, null, receiptId);
    }

    private Long queryManager(long org, long projectId) {
        List<Long> rows = jdbc.queryForList(
                "SELECT manager_user_id FROM fr_eng_project WHERE company_id = ? AND id = ?",
                Long.class, org, projectId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Long querySaleOwner(long org, String sourceRef) {
        // sale:{contractId}:{stage} → Java 侧解析合同 id → 客户 owner
        String[] parts = sourceRef.split(":");
        if (parts.length < 2) {
            return null;
        }
        try {
            long contractId = Long.parseLong(parts[1]);
            List<Long> rows = jdbc.queryForList(
                    "SELECT c.owner_user_id FROM fr_sale_contract k JOIN fr_customer c ON c.id = k.customer_id "
                            + "WHERE k.company_id = ? AND k.id = ?", Long.class, org, contractId);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long getLong(java.sql.ResultSet rs, String column) {
        try {
            long v = rs.getLong(column);
            return rs.wasNull() ? null : v;
        } catch (java.sql.SQLException e) {
            return null;
        }
    }

    /** 聚合前应用申诉裁决（P2-3）：source_key 命中→剔除/改归属 */
    static List<SourceRow> applyAdjustments(List<SourceRow> rows, List<Adjustment> adjustments) {
        if (adjustments == null || adjustments.isEmpty()) {
            return rows;
        }
        Map<String, Adjustment> byKey = new LinkedHashMap<>();
        for (Adjustment a : adjustments) {
            byKey.put(a.sourceKey(), a);
        }
        List<SourceRow> out = new ArrayList<>(rows.size());
        for (SourceRow row : rows) {
            Adjustment hit = row.businessId() == null ? null : byKey.get(row.businessId());
            if (hit == null) {
                out.add(row);
            } else if (!hit.excluded() && hit.personUserId() != null) {
                out.add(new SourceRow(row.key(), row.type(), row.value(), hit.personUserId(), row.date(),
                        row.businessId()));
            }
        }
        return out;
    }

    /** 任务口径聚合（金额类求和；数量类计数；userId=null 计全员） */
    public static long taskValue(List<SourceRow> rows, String type, Long userId) {
        long sum = 0;
        for (SourceRow row : rows) {
            if (type.equals(row.type()) && (userId == null || userId.equals(row.userId()))) {
                sum += row.value();
            }
        }
        return sum;
    }

    /** 三组指标汇总（summary 端点；金额/数量单位随指标） */
    public static Map<String, Map<String, Long>> departmentMetrics(List<SourceRow> rows) {
        Map<String, Map<String, Long>> byDept = new LinkedHashMap<>();
        for (String dept : List.of("business", "engineering", "finance")) {
            byDept.put(dept, new LinkedHashMap<>());
        }
        for (SourceRow row : rows) {
            String metric = row.type();
            Long value = row.value();
            String dept = deptOfMetric(metric);
            if (dept != null) {
                byDept.get(dept).merge(metric, value, Long::sum);
            }
        }
        // 派生：activityTotal=publications+effectiveLeads；firstPassNodes=completedNodes（§九 简化）
        Map<String, Long> business = byDept.get("business");
        business.put("metric:activityTotal",
                business.getOrDefault("auto:publications", 0L) + business.getOrDefault("auto:effectiveLeads", 0L));
        byDept.get("engineering").put("metric:firstPassNodes",
                byDept.get("engineering").getOrDefault("auto:completedNodes", 0L));
        return byDept;
    }

    private static String deptOfMetric(String type) {
        return switch (type) {
            case "auto:collectionsAmount", "auto:contract", "auto:publications", "auto:effectiveLeads",
                 "metric:commissionAmount", "metric:effectiveCustomers", "auto:approvedDeliverables" -> "business";
            case "auto:completedNodes", "auto:onTimeNodes", "auto:qualifiedDeliveries" -> "engineering";
            case "auto:financeRegistrations" -> "finance";
            default -> null;
        };
    }

    /** 归档签名（P1-12：明细 SHA-256 摘要——固定 64 字符不撑 VARCHAR(2048)） */
    public static String sourceSignature(List<SourceRow> rows) {
        StringBuilder sb = new StringBuilder();
        rows.stream().sorted(java.util.Comparator.comparing(SourceRow::key)).forEach(row -> {
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append(row.key()).append(',').append(row.value()).append(',')
                    .append(row.userId() == null ? "" : row.userId());
        });
        return cn.hutool.crypto.digest.DigestUtil.sha256Hex(sb.toString());
    }

    /** period 合法性（'YYYY-MM'） */
    public static boolean validPeriod(String period) {
        return period != null && period.matches("^\\d{4}-(0[1-9]|1[0-2])$");
    }

    /** 上月 period */
    public static String previousPeriod(String period) {
        LocalDate d = LocalDate.parse(monthStart(period)).minusMonths(1);
        return String.format("%04d-%02d", d.getYear(), d.getMonthValue());
    }

    /** 金额类目标两位小数元→分（D-10 统一分） */
    public static long yuanToCents(BigDecimal yuan) {
        return yuan.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
    }
}
