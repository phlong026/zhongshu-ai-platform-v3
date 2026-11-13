package cn.iocoder.yudao.module.platform.wallet.export;

import cn.iocoder.yudao.framework.common.biz.system.logger.OperateLogCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.logger.dto.OperateLogCreateReqDTO;
import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P0 批3：加盟商财务 CSV 导出（§八）。B-only 装配：仅
 * {@code platform.legacy-design-bridge.enabled=false}（B 独立进程 application.properties:80）时注册，
 * 旧融合 yudao-server 资源目录无该属性 → 缺省 → 条件不装配 → 零可写端点注册（FROZEN 单调不可逆）。
 *
 * <p>数据访问用 JdbcTemplate 只读 SELECT（与 OperationsAuditService 同型，零新依赖/零新接口），
 * 筛选形态逐表对齐批2 FinanceController 五表 /page 契约（§三.1）。
 *
 * <p>口径来源（唯一）：
 * ① 明细列清单 = R5 §8.2 逐表表格逐字（收款17/付款13/应付8/退款14+更正标记），非 DO 全字段；
 * ② 更正链 R4 = replaced_by_id/replaces_id 仅退款表具备，统计口径对齐
 *    PerfOperatingService.paidComposition（replaced_by_id IS NULL AND status='已核实'）与
 *    FinReconciliationService.java L40-73（月度：毛额=已登记收款 received_date 月内；
 *    退款=client+已核实+replaced_by_id IS NULL+date 月内；净额=毛−退；
 *    异常=全量已核待关联 + business_date IS NULL 待归月子集）。
 *    注：platform-wallet 编译期不依赖 franchise-finance（零新依赖红线的直接后果），
 *    故月度汇总为同口径 SQL 落地，非调用 FinReconciliationService.monthReconciliation 方法本身；
 *    一致性由本模块真实 PG 集成测试 × franchise-finance 的 FinReconciliationIntegrationTest 双证。
 */
@Service
@ConditionalOnProperty(name = "platform.legacy-design-bridge.enabled", havingValue = "false")
public class FinanceExportService {

    private static final Logger log = LoggerFactory.getLogger(FinanceExportService.class);

    /** 导出专用 5 万行上限（§8.1）；与分页 pageSize=100 是完全不同的口径，独立常量。 */
    public static final int EXPORT_MAX_ROWS = 50_000;
    /** 内部分批大小（仅用于游标分页取数，不复用 pageSize 上限口径）。 */
    private static final int BATCH_SIZE = 500;
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** 逐表列定义：DB 列名 → §8.2 导出列名。金额列名带「（元）」。 */
    private static final Map<String, List<String[]>> COLUMNS = Map.of(
            "receipts", List.of(
                    col("id", "收款单号"), col("project_id", "项目"), col("customer_id", "客户"),
                    col("source_type", "来源类型"), col("source_ref", "来源单据"), col("name", "名目"),
                    col("amount_cents", "金额（元）"), col("status", "状态"), col("received_date", "业务日期"),
                    col("registered_at", "登记时间"), col("registrar_user_id", "登记人"), col("method", "收款方式"),
                    col("transaction_no", "交易号"), col("voucher_file_id", "凭证"),
                    col("revision_version", "修订版本"), col("void_reason", "作废原因"), col("void_by_user_id", "作废人")),
            "payments", List.of(
                    col("id", "付款单号"), col("payable_id", "关联应付单"), col("project_id", "项目"),
                    col("amount_cents", "金额（元）"), col("pending_link_cents", "待关联金额（元）"),
                    col("link_status", "关联状态"), col("status", "状态"), col("pay_date", "付款日期"),
                    col("operator_user_id", "经办人"), col("transaction_no", "交易号"),
                    col("voucher_file_id", "凭证"), col("void_reason", "作废原因"), col("voided_at", "作废时间")),
            "payables", List.of(
                    col("id", "应付单号"), col("project_id", "项目"), col("stage", "阶段"),
                    col("total_cents", "金额（元）"), col("recipient", "收款方"), col("status", "状态"),
                    col("agreement_ref", "协议依据"), col("create_time", "创建时间")),
            "refunds", List.of(
                    col("id", "退款单号"), col("receipt_id", "关联收款单"), col("refund_direction", "方向"),
                    col("payment_id", "费用回流关联付款单"), col("amount_cents", "金额（元）"),
                    col("status", "状态"), col("reason", "原因"), col("date", "日期"),
                    col("transaction_no", "交易号"), col("voucher_file_id", "凭证"),
                    col("replaces_id", "更正链（原单）"), col("replaced_by_id", "更正链（被更正标记）"),
                    col("__correction__", "更正"), col("void_reason", "作废原因"), col("voided_at", "作废时间")));

    private static final List<String> RECONCILIATION_HEADER = List.of("月份", "毛实收（元）", "客户退款（元）",
            "净实收（元）", "异常待关联金额（元）", "异常待关联笔数", "待归月金额（元）", "待归月笔数");

    private static final Map<String, String> TABLES = Map.of(
            "receipts", "fr_fin_receipt", "payments", "fr_fin_payment",
            "payables", "fr_fin_payable", "refunds", "fr_fin_refund");

    private static String[] col(String key, String header) {
        return new String[]{key, header};
    }

    @Resource
    private OperateLogCommonApi operateLogApi;
    private JdbcTemplate jdbc;

    public FinanceExportService(DataSource source) {
        this.jdbc = new JdbcTemplate(source);
        this.jdbc.setQueryTimeout(15);
    }

    // ===== 静态纯函数（列清单 / 金额 / CSV / 更正链 / 统计口径）：供纯单测与 PG 测试复用 =====

    public static List<String> headerFor(String table) {
        if ("reconciliation".equals(table)) {
            return RECONCILIATION_HEADER;
        }
        List<String[]> def = COLUMNS.get(table);
        if (def == null) {
            throw badRequest("不支持的导出账表：" + table);
        }
        List<String> headers = new ArrayList<>();
        for (String[] c : def) {
            headers.add(c[1]);
        }
        return headers;
    }

    /** 分→元，两位小数（§8.1「金额单位：全部金额列分→元，表头注明（元）」）。文本形态，用于表头/纯函数测试。 */
    public static String moneyYuan(long cents) {
        return moneyValue(cents).toPlainString();
    }

    /**
     * 金额受控数值类型（P1②）：CSV 金额单元格直接传 BigDecimal，走数值单元格，
     * 不经公式注入前缀（否则退款>毛实收时 -100.00 会被文本化成 '-100.00）。
     */
    public static BigDecimal moneyValue(long cents) {
        return BigDecimal.valueOf(cents).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    /** CSV 单行：UTF-8，字段双引号包裹 + 内部引号双写 + 公式注入前缀（沿用 ZhongshuExportWorker.appendRow 形态）。 */
    public static String csvRow(List<Object> cells) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                line.append(',');
            }
            Object cell = cells.get(i);
            String value = cell == null ? "" : (cell instanceof java.sql.Timestamp t
                    ? t.toInstant().toString() : cell.toString());
            String trimmed = value.stripLeading();
            if (!(cell instanceof Number) && !trimmed.isEmpty()
                    && ("=+-@".indexOf(trimmed.charAt(0)) >= 0
                    || value.charAt(0) == '\t' || value.charAt(0) == '\r' || value.charAt(0) == '\n')) {
                value = "'" + value;
            }
            line.append('"').append(value.replace("\"", "\"\"")).append('"');
        }
        return line.append("\r\n").toString();
    }

    /**
     * 更正标记列：被更正单 = 「已由 #新单号 替代」（replaced_by_id 非空）；新单/普通单为空。
     * 参数用 Object 以区分 SQL NULL（空串）与数字 0——不能先把 null 折成 0 再判。
     */
    public static String correctionMark(Object replacedById) {
        if (replacedById == null || replacedById.toString().isBlank()) {
            return "";
        }
        return "已由 #" + replacedById + " 替代";
    }

    /**
     * 汇总金额计入资格（R4 逐表限定）：作废单不计入；实收只计已登记；付款/退款只计已核实；
     * 退款另排除被替代旧单（replaced_by_id 非空）；保留有效更正新单（replaces_id 非空不影响）。
     * 应付表为经营口径、不并入现金汇总（§8.1 逐表口径仅实收/付款/退款）。
     */
    public static boolean summaryEligible(String table, Map<String, String> row) {
        String voidReason = text(row.get("void_reason"));
        String voidedAt = text(row.get("voided_at"));
        if (!voidReason.isEmpty() || !voidedAt.isEmpty()) {
            return false; // 作废单：明细行照出，不计入金额汇总
        }
        return switch (table) {
            case "receipts" -> "已登记".equals(row.get("status"));
            case "payments", "refunds" -> "已核实".equals(row.get("status"))
                    && text(row.get("replaced_by_id")).isEmpty(); // 退款/付款排除被替代旧单
            default -> false; // payables 不并入现金汇总
        };
    }

    private static String text(Object o) {
        return o == null ? "" : o.toString();
    }

    private static ServiceException badRequest(String message) {
        // 用 exception0 把自定义提示作为消息模板（无参数），避免 doFormat 因「参数过多」丢弃提示，
        // 保证 §8.1「5 万超限提示收窄筛选」等可读信息真实下发到前端。
        return ServiceExceptionUtil.exception0(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), message);
    }

    // ===== 实例入口：Controller 调用 =====

    /** 导出结果：CSV 字节 + 写出循环里真实累计的明细记录数（P1③，不从文本反推）。 */
    private record ExportResult(byte[] csv, long rows) {
    }

    /**
     * 导出当前筛选条件的全部结果（非当前页）。返回 UTF-8 BOM 开头的 CSV 字节。
     * 权限/组织隔离/参数校验在 Controller 层显式完成后调用本方法。
     */
    public byte[] export(String table, Map<String, String> filters, long orgId, long actorUserId) {
        validateFilters(table, filters);
        ExportResult result = "reconciliation".equals(table)
                ? buildReconciliation(filters, orgId)
                : buildDetailCsv(table, filters, orgId);
        // P1③：审计行数直接用写出循环的计数器，字段内嵌 CR/LF 不影响行数
        writeAudit(table, filters, orgId, actorUserId, result.rows());
        return result.csv();
    }

    /** 显式参数校验（O3：沿用批2 Controller 显式 ServiceException 路径，不依赖 PageParam @Max(200)）。 */
    public static void validateFilters(String table, Map<String, String> filters) {
        if (!TABLES.containsKey(table) && !"reconciliation".equals(table)) {
            throw badRequest("不支持的导出账表：" + table);
        }
        // 5 万行上限由 buildDetailCsv 的 count(*) 在写出任何字节前判定（O3 独立口径，不复用 pageSize）。
        // 日期 From>To 拒（逐表业务日期口径；payables 无日期字段，不提供日期参数）
        validateRange(filters.get("receivedDateFrom"), filters.get("receivedDateTo"));
        validateRange(filters.get("payDateFrom"), filters.get("payDateTo"));
        validateRange(filters.get("dateFrom"), filters.get("dateTo"));
        validateRange(filters.get("businessDateFrom"), filters.get("businessDateTo"));
        String direction = filters.get("refundDirection");
        if (direction != null && !direction.isBlank()
                && !"client".equals(direction) && !"expense".equals(direction)) {
            throw badRequest("非法的 refundDirection：" + direction);
        }
        if ("reconciliation".equals(table)) {
            String month = filters.get("month");
            if (month == null || !month.matches("\\d{4}-(0[1-9]|1[0-2])")) {
                throw badRequest("月份格式须为 YYYY-MM");
            }
        }
    }

    private static void validateRange(String from, String to) {
        if (from != null && !from.isBlank() && to != null && !to.isBlank()
                && LocalDate.parse(from).isAfter(LocalDate.parse(to))) {
            throw badRequest("日期区间起始晚于结束，请收窄筛选");
        }
    }

    // ===== 明细 CSV（4 表） =====

    private ExportResult buildDetailCsv(String table, Map<String, String> filters, long orgId) {
        // 5 万行上限：count(*) 前置短路（写出任何字节之前判定）
        long total = countDetail(table, filters, orgId);
        if (total > EXPORT_MAX_ROWS) {
            throw badRequest("导出结果超过 5 万行上限（当前 " + total + " 行），请收窄筛选条件后重试");
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(UTF8_BOM);
        appendRow(out, headerFor(table));

        List<String[]> def = COLUMNS.get(table);
        String selectColumns = selectColumns(def);
        StringBuilder where = new StringBuilder("company_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(orgId);
        appendFilters(table, filters, where, args);

        long cursor = 0;
        int written = 0;
        long summaryCents = 0;
        while (true) {
            List<Object> pageArgs = new ArrayList<>(args);
            pageArgs.add(cursor);
            pageArgs.add(BATCH_SIZE);
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT " + selectColumns + " FROM " + TABLES.get(table)
                            + " WHERE " + where + " AND id > ? ORDER BY id LIMIT ?", pageArgs.toArray());
            for (Map<String, Object> row : rows) {
                // 逐批累加双重判定（O3：不复用 pageSize 上限口径，独立 5 万上限）
                if (++written > EXPORT_MAX_ROWS) {
                    throw badRequest("导出结果超过 5 万行上限，请收窄筛选条件后重试");
                }
                List<Object> cells = new ArrayList<>();
                for (String[] c : def) {
                    Object value;
                    if ("__correction__".equals(c[0])) {
                        value = correctionMark(row.get("replaced_by_id"));
                    } else if (isMoneyColumn(c[0])) {
                        value = moneyValue(asLong(row.get(c[0]))); // P1②：受控数值单元格
                    } else {
                        value = row.get(c[0]);
                    }
                    cells.add(value);
                }
                appendRow(out, cells);
                if (summaryEligible(table, stringify(row))) {
                    summaryCents += asLong(row.get(amountColumn(table)));
                }
                cursor = asLong(row.get("id"));
            }
            if (rows.size() < BATCH_SIZE) {
                break;
            }
        }

        // 独立汇总行组（§8.1「汇总与对账统计口径」落地；不影响明细表头列清单）
        appendRow(out, List.of("明细行数", written));
        appendRow(out, List.of("有效汇总金额（元）", moneyValue(summaryCents)));
        return new ExportResult(out.toByteArray(), written);
    }

    private static String selectColumns(List<String[]> def) {
        List<String> cols = new ArrayList<>();
        for (String[] c : def) {
            if (!"__correction__".equals(c[0])) {
                cols.add(c[0]);
            }
        }
        return String.join(",", cols);
    }

    private void appendFilters(String table, Map<String, String> f, StringBuilder where, List<Object> args) {
        eqIfPresent(where, args, "status", f.get("status"));
        eqIfPresent(where, args, "project_id", f.get("projectId"));
        switch (table) {
            case "receipts" -> {
                range(where, args, "received_date", f.get("receivedDateFrom"), f.get("receivedDateTo"));
                eqIfPresent(where, args, "method", f.get("method"));
                eqIfPresent(where, args, "source_type", f.get("sourceType"));
                keywordReceipts(where, args, f.get("keyword"));
            }
            case "payments" -> {
                range(where, args, "pay_date", f.get("payDateFrom"), f.get("payDateTo"));
                keywordPayments(where, args, f.get("keyword"));
            }
            case "refunds" -> {
                range(where, args, "date", f.get("dateFrom"), f.get("dateTo"));
                eqIfPresent(where, args, "receipt_id", f.get("receiptId"));
                eqIfPresent(where, args, "refund_direction", f.get("refundDirection"));
                keywordRefunds(where, args, f.get("keyword"));
            }
            case "payables" -> keywordPayables(where, args, f.get("keyword"));
            default -> { }
        }
    }

    private static void eqIfPresent(StringBuilder where, List<Object> args, String column, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        where.append(" AND ").append(column).append(" = ?");
        // BIGINT 列（project_id / receipt_id）必须绑定 Long，否则 PG 报
        // "operator does not exist: bigint = character varying"。
        if ("project_id".equals(column) || "receipt_id".equals(column)) {
            args.add(Long.parseLong(value));
        } else {
            args.add(value);
        }
    }

    private static void range(StringBuilder where, List<Object> args, String column, String from, String to) {
        if (from != null && !from.isBlank()) {
            where.append(" AND ").append(column).append(" >= ?");
            args.add(LocalDate.parse(from));
        }
        if (to != null && !to.isBlank()) {
            where.append(" AND ").append(column).append(" <= ?");
            args.add(LocalDate.parse(to));
        }
    }

    // keyword 形态逐表对齐批2 Mapper（like + or）
    private static void keywordReceipts(StringBuilder where, List<Object> args, String kw) {
        String k = like(kw);
        if (k == null) return;
        where.append(" AND (name LIKE ? OR source_ref LIKE ? OR transaction_no LIKE ? OR method LIKE ?)");
        args.add(k); args.add(k); args.add(k); args.add(k);
    }

    private static void keywordPayments(StringBuilder where, List<Object> args, String kw) {
        String k = like(kw);
        if (k == null) return;
        where.append(" AND (transaction_no LIKE ? OR void_reason LIKE ?)");
        args.add(k); args.add(k);
    }

    private static void keywordRefunds(StringBuilder where, List<Object> args, String kw) {
        String k = like(kw);
        if (k == null) return;
        where.append(" AND (reason LIKE ? OR transaction_no LIKE ?)");
        args.add(k); args.add(k);
    }

    private static void keywordPayables(StringBuilder where, List<Object> args, String kw) {
        String k = like(kw);
        if (k == null) return;
        where.append(" AND (recipient LIKE ? OR stage LIKE ? OR agreement_ref LIKE ?)");
        args.add(k); args.add(k); args.add(k);
    }

    private static String like(String kw) {
        return kw == null || kw.isBlank() ? null : "%" + kw.trim() + "%";
    }

    private long countDetail(String table, Map<String, String> f, long orgId) {
        StringBuilder where = new StringBuilder("company_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(orgId);
        appendFilters(table, f, where, args);
        Long count = jdbc.queryForObject("SELECT count(*) FROM " + TABLES.get(table) + " WHERE " + where,
                Long.class, args.toArray());
        return count == null ? 0 : count;
    }

    // ===== 月度对账汇总（独立结构，口径逐字对齐 FinReconciliationService.java L40-73） =====

    private ExportResult buildReconciliation(Map<String, String> filters, long orgId) {
        String month = filters.get("month");
        LocalDate start = LocalDate.parse(month + "-01");
        LocalDate end = start.plusMonths(1);

        // 毛实收 = Σ 已登记收款（received_date 月内）
        Long gross = jdbc.queryForObject("SELECT COALESCE(SUM(amount_cents),0) FROM fr_fin_receipt "
                + "WHERE company_id=? AND status='已登记' AND received_date >= ? AND received_date < ?",
                Long.class, orgId, start, end);
        // 客户退款 = Σ client 方向、已核实、未被更正替代（date 月内）
        Long refunded = jdbc.queryForObject("SELECT COALESCE(SUM(amount_cents),0) FROM fr_fin_refund "
                + "WHERE company_id=? AND status='已核实' AND refund_direction='client' "
                + "AND replaced_by_id IS NULL AND date >= ? AND date < ?",
                Long.class, orgId, start, end);
        // 异常待关联（全量已核待关联）+ 待归月（business_date IS NULL 子集）
        Long pendingCents = jdbc.queryForObject("SELECT COALESCE(SUM(amount_cents),0) FROM fr_fin_abnormal_fund "
                + "WHERE company_id=? AND status='已核待关联'", Long.class, orgId);
        Integer pendingCount = jdbc.queryForObject("SELECT count(*) FROM fr_fin_abnormal_fund "
                + "WHERE company_id=? AND status='已核待关联'", Integer.class, orgId);
        Long datePendingCents = jdbc.queryForObject("SELECT COALESCE(SUM(amount_cents),0) FROM fr_fin_abnormal_fund "
                + "WHERE company_id=? AND status='已核待关联' AND business_date IS NULL", Long.class, orgId);
        Integer datePendingCount = jdbc.queryForObject("SELECT count(*) FROM fr_fin_abnormal_fund "
                + "WHERE company_id=? AND status='已核待关联' AND business_date IS NULL", Integer.class, orgId);

        long g = gross == null ? 0 : gross;
        long r = refunded == null ? 0 : refunded;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(UTF8_BOM);
        appendRow(out, RECONCILIATION_HEADER);
        // P1②：5 个金额列走受控 BigDecimal 数值单元格（负净实收 -100.00 不得被文本化）；
        // 2 个笔数列为 Integer（Number），同样不走公式注入前缀。
        appendRow(out, List.of(month, moneyValue(g), moneyValue(r), moneyValue(g - r),
                moneyValue(pendingCents == null ? 0 : pendingCents), pendingCount == null ? 0 : pendingCount,
                moneyValue(datePendingCents == null ? 0 : datePendingCents),
                datePendingCount == null ? 0 : datePendingCount));
        return new ExportResult(out.toByteArray(), 1);
    }

    // ===== 审计写入（§8.1 L351：actor/筛选条件/行数；既有 system_operate_log 表，零迁移零 DDL） =====

    private void writeAudit(String table, Map<String, String> filters, long orgId, long actorUserId, long rows) {
        try {
            OperateLogCreateReqDTO req = new OperateLogCreateReqDTO();
            req.setUserId(actorUserId);
            req.setUserType(UserTypeEnum.ADMIN.getValue());
            req.setType("加盟商财务导出");
            req.setSubType(table);
            req.setBizId(orgId);
            req.setAction("导出账表=" + table + "；筛选条件=" + new LinkedHashMap<>(filters)
                    + "；行数=" + rows);
            req.setExtra("{\"table\":\"" + table + "\",\"orgId\":" + orgId + ",\"rows\":" + rows + "}");
            req.setRequestMethod("POST");
            req.setRequestUrl("/franchise/finance/exports");
            // P1①：OperateLogCreateReqDTO.userIp/userAgent 带 @NotEmpty，真实 @Validated 代理会拒绝空值。
            // 从当前请求上下文取真实值；无请求上下文（内部/定时调用）用受控非空占位。
            String ip = cn.iocoder.yudao.framework.common.util.servlet.ServletUtils.getClientIP();
            String ua = cn.iocoder.yudao.framework.common.util.servlet.ServletUtils.getUserAgent();
            req.setUserIp(ip == null || ip.isBlank() ? "unknown" : ip);
            req.setUserAgent(ua == null || ua.isBlank() ? "finance-export" : ua);
            operateLogApi.createOperateLog(req);
        } catch (RuntimeException error) {
            log.error("[financeExport][orgId={},table={},rows={}] 导出审计写入失败", orgId, table, rows, error);
            throw error;
        }
    }

    // ===== 辅助 =====

    private static void appendRow(ByteArrayOutputStream out, List<?> cells) {
        byte[] bytes = csvRow(new ArrayList<>(cells)).getBytes(StandardCharsets.UTF_8);
        out.writeBytes(bytes);
    }

    private static long asLong(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.longValue();
        String s = o.toString();
        return s.isEmpty() ? 0 : Long.parseLong(s);
    }

    private static Map<String, String> stringify(Map<String, Object> row) {
        Map<String, String> result = new LinkedHashMap<>();
        row.forEach((k, v) -> result.put(k, v == null ? "" : v.toString()));
        return result;
    }

    private static boolean isMoneyColumn(String key) {
        return "amount_cents".equals(key) || "total_cents".equals(key) || "pending_link_cents".equals(key);
    }

    private static String amountColumn(String table) {
        return "payables".equals(table) ? "total_cents" : "amount_cents";
    }

    // ===== 测试专用入口：真实 PG 夹具用反射调用，绕过 Spring 装配 =====

    /**
     * 仅替换 JdbcTemplate 与审计依赖，其余 CSV/口径/5 万上限逻辑与生产完全同一条代码路径。
     * 构造期传入未连接 DataSource（不触发连接），随后把字段替换为真实 PG 的 JdbcTemplate。
     */
    private static byte[] exportForTest(JdbcTemplate template, String table, Map<String, String> filters,
                                        long orgId, long actorUserId) {
        return exportForTest(template, table, filters, orgId, actorUserId, req -> { });
    }

    /** 6 参重载：供审计写入单测注入 mock OperateLogCommonApi 并 verify。 */
    private static byte[] exportForTest(JdbcTemplate template, String table, Map<String, String> filters,
                                        long orgId, long actorUserId, OperateLogCommonApi audit) {
        FinanceExportService service = new FinanceExportService(
                new org.springframework.jdbc.datasource.SingleConnectionDataSource());
        service.jdbc = template;
        service.operateLogApi = audit;
        return service.export(table, filters, orgId, actorUserId);
    }
}
