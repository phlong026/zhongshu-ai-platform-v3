package cn.iocoder.yudao.module.franchise.performance.content;

import cn.iocoder.yudao.framework.common.biz.system.logger.OperateLogCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.logger.dto.OperateLogCreateReqDTO;
import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil;
import cn.iocoder.yudao.framework.common.util.servlet.ServletUtils;
import cn.iocoder.yudao.module.franchise.performance.dal.FrPerfCommissionPolicyDO;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * P1 尾批③：绩效报表导出（服务层）。
 *
 * <p><b>用途</b>：月度工资核算——按「员工 × 月份」汇总提成应付及付款进度，供老板/财务导出 CSV。
 *
 * <p><b>权限（与 §八 通用条款区分）</b>：本报表使用
 * {@code franchise:performance:commission}（V857 授予 boss/owner/finance/finance-lead）；
 * §八通用条款的 {@code franchise:fin:query} 仅适用于财务账表导出（收款/付款/应付/退款/月度对账），
 * 不含本项。后端 {@code @post.has} 为真源，前端按 roleCode 显隐。
 *
 * <p><b>月份口径（唯一权威）</b>：{@code fr_perf_commission_release.release_at} 所在自然月，
 * 半开区间 {@code [YYYY-MM-01 00:00:00, 次月-01 00:00:00)}。边界字面量与
 * {@link PerfDashboardService} 的 monthStart/monthEndExclusive 同型，解释时区 =
 * <b>数据库会话时区</b>，不引入第二套时区约定；{@code release_at} 是生成应付的唯一业务时间，
 * {@code create_time} 与 insert 同刻，不进入月份口径。历史月份未付金额留在其归属月份报表，
 * 不顺延滚存。
 *
 * <p><b>部署前置（P1 门禁登记）</b>：部署时必须执行 {@code SHOW TimeZone} 核验并确认业务月份口径。
 * 中国区工资核算月应为 Asia/Shanghai；若数据库/会话时区为 UTC，月初 8 小时内的应付会落到上月。
 * 该结论在本机无法替代生产核验（本机 Testcontainers 为 UTC），故只登记为部署前置，不在代码内
 * 硬编码时区（避免与既有经营看板/月度对账口径分叉）。
 *
 * <p><b>已付口径</b>：该月 release 关联应付的 {@code fr_fin_payment.status='已核实'} 付款累计，
 * 与 {@link PerfCommissionService#row} 的 paid 完全一致；不按 pay_date 再切月（避免同一应付
 * 跨月付款被拆成两行）。未付 = max(0, 已生成应付 − 已付)，超付不倒扣（红线：无自动扣薪/追回）。
 *
 * <p><b>列口径</b>：
 * <ul>
 *   <li>涉及项目数/已生成应付/已付/未付/已付清项目数/待核对项目数 = 该月 release 口径；</li>
 *   <li>已生成应付以 {@code fr_fin_payable.total_cents} 为权威（应付生成时冻结，见
 *       PerformancePayablePort）；应付行缺失时回退当前规则阶段额并计入待核对，不静默少算；</li>
 *   <li>提成总额 = 该月涉及项目的提成规则总额（policy.total_cents，Σ 三期，项目级参考值）；</li>
 *   <li>未约定项目数 = 该月涉及项目中，三期里至少一期为 0（未约定）的项目数；</li>
 *   <li>待核对项目数 = 该月 release 中至少一条命中「待人工核对」（应付缺失、规则版本变化，
 *       或当前净实收事实与释放时签名不一致/未足额），与行视图 reviewDue 派生同源。</li>
 * </ul>
 *
 * <p><b>§8.1 通用导出决策</b>：CSV UTF-8 BOM；当前筛选（仅 month）全部结果；员工行数上限 5 万；
 * 金额分→元两位小数（BigDecimal，禁止 double）；文本列 = + - @ 前置单引号防公式注入，
 * 金额列（Number/BigDecimal）不加前缀；空结果仍输出表头；导出动作记审计
 * （actorUserId/orgId/month/行数，复用 system_operate_log 既有同步通道；首字节前记 attempt，
 * 完成后记 success，零迁移零 DDL）。
 *
 * <p><b>流式写出（Codex r1 P2-2 / r2 P2-1 / r3 P1+P2）</b>：Controller 返回 StreamingResponseBody；
 * BOM/表头先写。员工 ID 在 prepare 时一次取定（LIMIT 50001，快照语义；流式窗口内新增员工不纳入本次导出）。
 * 随后按 500 员工/批查询，<b>每批在只读小事务内（autoCommit=false + PreparedStatement.fetchSize=500）
 * 用 RowCallbackHandler 逐行消费</b>，PgJDBC 走游标而非整批缓冲；批内只聚合「当前一个员工」，
 * 员工切换立即写行并丢弃；每批结束 flush，下一批查询前客户端已收到本批字节。
 * 单员工 release 上限 5000，prepare 前置检查；流式窗口内超限在本行写出前以 500 中止。
 *
 * <p><b>组件复用</b>：CSV 行编码与 platform-wallet 的 {@code FinanceExportService.csvRow} 同型，
 * 但 franchise-performance 编译期不依赖 platform-wallet（避免跨域模块依赖），故在本模块保留
 * 同型小工具并以纯函数测试锁定形态；提成阶段金额映射复用
 * {@link PerfCommissionService#stageAmountOf}；待核对事实在每个员工批次 SQL 中集合计算，禁止流式回调内访问数据库。
 */
@Service
@ConditionalOnProperty(name = "platform.legacy-design-bridge.enabled", havingValue = "false")
public class PerfCommissionExportService {

    private static final Logger log = LoggerFactory.getLogger(PerfCommissionExportService.class);

    /** §8.1 导出专用员工行上限；与列表分页 pageSize 是完全不同的口径。 */
    public static final int EXPORT_MAX_ROWS = 50_000;
    /** 单员工当月 release 上限（防止单员工大量项目把单行聚合撑爆；超出 500 中止）。 */
    public static final int MAX_RELEASES_PER_EMPLOYEE = 5_000;
    /** 员工分批大小：每批查询 release 并逐行写出，单批内存只保留当前员工聚合。 */
    private static final int EMPLOYEE_BATCH_SIZE = 500;

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final Pattern MONTH_PATTERN = Pattern.compile("\\d{4}-(0[1-9]|1[0-2])");

    private static final List<String> HEADER = List.of(
            "员工ID", "员工姓名", "涉及项目数", "提成总额（元）", "已生成应付（元）",
            "已付（元）", "未付（元）", "已付清项目数", "待核对项目数", "未约定项目数");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate batchTransaction;
    private final OperateLogCommonApi operateLogApi;

    @Autowired
    public PerfCommissionExportService(DataSource source, OperateLogCommonApi operateLogApi) {
        this(newJdbcTemplate(source), newBatchTransaction(source), operateLogApi);
    }

    /** 测试/内部装配：直接注入 JdbcTemplate（生产用 DataSource 构造器；不改变生产装配）。 */
    PerfCommissionExportService(JdbcTemplate jdbc, OperateLogCommonApi operateLogApi) {
        this(jdbc, newBatchTransaction(jdbc.getDataSource()), operateLogApi);
    }

    private PerfCommissionExportService(JdbcTemplate jdbc, TransactionTemplate batchTransaction,
                                        OperateLogCommonApi operateLogApi) {
        this.jdbc = jdbc;
        this.jdbc.setQueryTimeout(15);
        // PgJDBC 仅在 autoCommit=false + fetchSize 下走游标；批次事务由 batchTransaction 提供
        this.jdbc.setFetchSize(EMPLOYEE_BATCH_SIZE);
        this.batchTransaction = batchTransaction;
        this.operateLogApi = operateLogApi;
    }

    private static JdbcTemplate newJdbcTemplate(DataSource source) {
        JdbcTemplate template = new JdbcTemplate(source);
        template.setQueryTimeout(15);
        template.setFetchSize(EMPLOYEE_BATCH_SIZE);
        return template;
    }

    /** 每批一个只读小事务：PgJDBC 游标（autoCommit=false）随事务结束释放。 */
    private static TransactionTemplate newBatchTransaction(DataSource source) {
        DataSourceTransactionManager manager = new DataSourceTransactionManager(source);
        TransactionTemplate template = new TransactionTemplate(manager);
        template.setReadOnly(true);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    /**
     * prepare 结果：头部提交前的校验产物（月份/半开区间/员工 ID 快照）。
     * 员工 ID 在 prepare 时一次取定（≤5 万），流式只遍历该快照；快照之后新增的员工
     * 不纳入本次导出（快照语义，避免并发新增绕过 5 万上限）。
     */
    public record ExportPlan(String month, String from, String to, List<Long> employeeIds) {

        public ExportPlan {
            employeeIds = List.copyOf(employeeIds);
        }

        public int employeeCount() {
            return employeeIds.size();
        }
    }

    // ===================== 静态纯函数（表头/月份/金额/CSV）：供纯单测与 PG 测试复用 =====================

    /** 表头（中文；金额列注明「（元）」）。 */
    public static List<String> header() {
        return HEADER;
    }

    /** 月份校验（{@code YYYY-MM}，前后空白容忍）；非法 → 400 可读提示。 */
    public static String validateMonth(String month) {
        String value = month == null ? "" : month.trim();
        if (!MONTH_PATTERN.matcher(value).matches()) {
            throw badRequest("月份格式须为 YYYY-MM");
        }
        return value;
    }

    /** 分→元，两位小数（文本形态，供表头/纯函数测试）。 */
    public static String moneyYuan(long cents) {
        return moneyValue(cents).toPlainString();
    }

    /** 分→元受控数值类型：CSV 金额单元格直接传 BigDecimal，不走公式注入前缀。 */
    public static BigDecimal moneyValue(long cents) {
        return BigDecimal.valueOf(cents).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    /**
     * 电子表格标识符不可按数值解析：前置单引号避免 16 位以上 Snowflake ID
     * 被 Excel 舍入；纯数字转换不引入公式。
     */
    public static String spreadsheetIdentifier(long identifier) {
        String value = Long.toString(identifier);
        return value.length() > 15 ? "'" + value : value;
    }

    /**
     * CSV 单行（UTF-8，字段全部双引号包裹 + 内部引号双写 + CRLF）。
     * 文本列 = + - @ / 制表符 / 回车 / 换行 前置单引号；Number（含 BigDecimal 负金额）不前缀。
     */
    public static String csvRow(List<?> cells) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                line.append(',');
            }
            Object cell = cells.get(i);
            String value = cell == null ? "" : cell.toString();
            if (!(cell instanceof Number)) {
                String trimmed = value.stripLeading();
                if (!trimmed.isEmpty()
                        && ("=+-@".indexOf(trimmed.charAt(0)) >= 0
                        || value.charAt(0) == '\t' || value.charAt(0) == '\r' || value.charAt(0) == '\n')) {
                    value = "'" + value;
                }
            }
            line.append('"').append(value.replace("\"", "\"\"")).append('"');
        }
        return line.append("\r\n").toString();
    }

    private static ServiceException badRequest(String message) {
        // exception0：自定义提示作为消息模板（无参数），保证「5 万行超限/月份格式」可读信息真实下发
        return ServiceExceptionUtil.exception0(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), message);
    }

    /** 缺规则行 = 数据完整性异常（非客户端可修复）；提示不含任何内部键/ID。 */
    private static ServiceException dataIncomplete() {
        return ServiceExceptionUtil.exception0(GlobalErrorCodeConstants.INTERNAL_SERVER_ERROR.getCode(),
                "提成规则数据不完整，导出已中止，请联系管理员核对");
    }

    /** 单员工明细超限 = 数据异常/需收窄月份，提示不含员工 ID。 */
    private static ServiceException dataTooManyReleases() {
        return ServiceExceptionUtil.exception0(GlobalErrorCodeConstants.INTERNAL_SERVER_ERROR.getCode(),
                "单员工提成明细超过 " + MAX_RELEASES_PER_EMPLOYEE + " 条，导出已中止，请联系管理员核对");
    }

    // ===================== 头部提交前校验 =====================

    /**
     * 校验月份/员工上限/单员工明细上限/规则完整性。必须在 Controller 返回 StreamingResponseBody
     * <b>之前</b>调用，保证 400/500 仍走生产异常链（HTTP 200 + CommonResult），不会出现
     * 「已提交 200 后才发现参数错误」。
     */
    public ExportPlan prepare(PostSnapshot post, String month) {
        String period = validateMonth(month);
        String from = period + "-01 00:00:00";
        String to = LocalDate.parse(period + "-01").plusMonths(1) + " 00:00:00";
        // Codex r3 P2-2：员工 ID 一次取定（LIMIT 50001），第 50001 个即 400；流式只遍历快照
        List<Long> employeeIds = loadEmployeeIdSnapshot(post.orgId(), from, to);
        if (employeeIds.size() > EXPORT_MAX_ROWS) {
            throw badRequest("导出结果超过 5 万行上限（当前 " + employeeIds.size() + " 行），请调整月份后重试");
        }
        if (countEmployeesOverReleaseLimit(post.orgId(), from, to) > 0) {
            // Codex r2 P2-1：单员工 release 上限前置；不产出 CSV、不写成功审计
            throw dataTooManyReleases();
        }
        if (countOrphanReleases(post.orgId(), from, to) > 0) {
            // Codex r1 P2-1：缺 policy 不得静默少算；显式失败、不产出 CSV、不写成功审计
            throw dataIncomplete();
        }
        return new ExportPlan(period, from, to, employeeIds);
    }

    /** 员工 ID 快照：一次取定、有序、最多 5 万+1（超限由 prepare 判 400）。 */
    private List<Long> loadEmployeeIdSnapshot(long orgId, String from, String to) {
        return jdbc.queryForList(
                "SELECT p.assignee_user_id FROM fr_perf_commission_release r "
                        + "JOIN fr_perf_commission_policy p ON p.id = r.policy_id AND p.company_id = r.company_id "
                        + "WHERE r.company_id = ? AND r.release_at >= ?::timestamptz "
                        + "AND r.release_at < ?::timestamptz "
                        + "GROUP BY p.assignee_user_id ORDER BY p.assignee_user_id LIMIT ?",
                Long.class, orgId, from, to, EXPORT_MAX_ROWS + 1);
    }

    /** 单员工当月 release 数 > 上限的员工数（前置；不返回员工 ID）。 */
    private long countEmployeesOverReleaseLimit(long orgId, String from, String to) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM (SELECT p.assignee_user_id "
                        + "FROM fr_perf_commission_release r "
                        + "JOIN fr_perf_commission_policy p ON p.id = r.policy_id AND p.company_id = r.company_id "
                        + "WHERE r.company_id = ? AND r.release_at >= ?::timestamptz "
                        + "AND r.release_at < ?::timestamptz "
                        + "GROUP BY p.assignee_user_id HAVING COUNT(*) > ?) overflow",
                Long.class, orgId, from, to, MAX_RELEASES_PER_EMPLOYEE);
        return count == null ? 0 : count;
    }

    /** 当月 release 中找不到同组织规则行的条数（完整性前置；不返回 ID 明细）。 */
    private long countOrphanReleases(long orgId, String from, String to) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fr_perf_commission_release r "
                        + "LEFT JOIN fr_perf_commission_policy p "
                        + "ON p.id = r.policy_id AND p.company_id = r.company_id "
                        + "WHERE r.company_id = ? AND r.release_at >= ?::timestamptz "
                        + "AND r.release_at < ?::timestamptz AND p.id IS NULL",
                Long.class, orgId, from, to);
        return count == null ? 0 : count;
    }

    // ===================== 流式写出（逐员工） =====================

    /**
     * 写出 CSV（UTF-8 BOM + 表头 + 员工行）。按员工分批（500/批）查询，批内逐行消费、
     * 只聚合当前一个员工，员工切换即写行并丢弃；每批结束 flush，保证增量下发。
     * 首字节前必须先持久化 attempt；失败时抛异常（已写字节无法撤回，
     * 但 attempt 保留且不会写 success）。
     */
    public void writeCsv(PostSnapshot post, ExportPlan plan, OutputStream output) throws IOException {
        // createOperateLog 是同步 API，且 StreamingResponseBody 运行时本方法无外层业务事务；
        // attempt 插入成功返回后才允许首字节下发。
        writeAudit(post.orgId(), post.userId(), plan.month(), "attempt", null);
        output.write(UTF8_BOM);
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        writer.write(csvRow(HEADER));

        List<Long> employeeIds = plan.employeeIds(); // 固定快照：流式窗口内新增员工不纳入本次导出
        long[] written = {0};
        try {
            for (int start = 0; start < employeeIds.size(); start += EMPLOYEE_BATCH_SIZE) {
                List<Long> batch = employeeIds.subList(start,
                        Math.min(start + EMPLOYEE_BATCH_SIZE, employeeIds.size()));
                String placeholders = String.join(",", Collections.nCopies(batch.size(), "?"));
                List<Object> args = new ArrayList<>();
                args.add(post.orgId());
                args.add(plan.from());
                args.add(plan.to());
                args.addAll(batch);

                // 每批一个只读事务：autoCommit=false + fetchSize=500 → PgJDBC 游标逐行返回；
                // RowCallbackHandler 只聚合当前员工，事务结束即释放游标。
                batchTransaction.executeWithoutResult(status -> {
                    EmployeeSink sink = new EmployeeSink(writer);
                    jdbc.query(batchSql(placeholders), (ResultSet rs) -> sink.accept(rs), args.toArray());
                    sink.finish();
                    written[0] += sink.written();
                });
                writer.flush(); // 本批字节在下一批查询前已下发（增量写出证据）
            }
        } catch (UncheckedIOException error) {
            throw error.getCause();
        }
        writer.flush();
        writeAudit(post.orgId(), post.userId(), plan.month(), "success", written[0]);
    }

    private static String batchSql(String placeholders) {
        String stage = "CASE r.stage WHEN 'START' THEN 'SIGN_AFTER' "
                + "WHEN 'TOPPING' THEN 'CAP_DONE' ELSE r.stage END";
        String required = "COALESCE(CASE WHEN contract.id IS NOT NULL THEN sale_stage.amount_cents ELSE term.amount_cents END, 0)";
        return "SELECT p.id AS policy_id, p.assignee_user_id, p.assignee_name, "
                + "p.update_time AS policy_update_time, p.total_cents, p.start_cents, p.base_done_cents, "
                + "p.topping_cents, p.version AS current_version, r.project_id, r.stage, r.policy_version, "
                + "r.receipt_signature, fp.total_cents AS payable_cents, "
                + "COALESCE((SELECT SUM(pm.amount_cents) FROM fr_fin_payment pm "
                + "WHERE pm.payable_id = r.payable_id AND pm.status = '已核实'), 0) AS paid_cents, "
                + required + " AS fact_required, "
                + "CASE WHEN contract.id IS NOT NULL THEN COALESCE(unified.received, 0) "
                + "ELSE COALESCE(engineering.received, 0) END AS fact_received, "
                + "'required:' || " + required + " || "
                + "CASE WHEN contract.id IS NOT NULL THEN '|unified:' || contract.id || ':' || " + stage
                + " || ':' || COALESCE(unified.received, 0) "
                + " || CASE WHEN unified.refunded > 0 THEN '|refunded:' || unified.refunded ELSE '' END "
                + "ELSE COALESCE('|' || engineering.signature_parts, '') END AS fact_signature "
                + "FROM fr_perf_commission_release r "
                + "LEFT JOIN fr_perf_commission_policy p "
                + "ON p.id = r.policy_id AND p.company_id = r.company_id "
                + "LEFT JOIN fr_fin_payable fp ON fp.id = r.payable_id AND fp.company_id = r.company_id "
                + "LEFT JOIN fr_eng_project project ON project.id = r.project_id "
                + "AND project.company_id = r.company_id "
                + "LEFT JOIN fr_eng_collection_term term ON term.project_id = r.project_id "
                + "AND term.company_id = r.company_id AND term.stage = " + stage + " "
                + "LEFT JOIN LATERAL (SELECT c.id FROM fr_sale_contract c "
                + "WHERE c.company_id = r.company_id AND c.customer_id = project.customer_id "
                + "AND c.status = 'SIGNED' LIMIT 1) contract ON TRUE "
                + "LEFT JOIN fr_sale_payment_stage sale_stage ON sale_stage.company_id = r.company_id "
                + "AND sale_stage.contract_id = contract.id AND sale_stage.stage = " + stage + " "
                + "LEFT JOIN LATERAL (SELECT COALESCE(SUM(sr.amount_cents), 0) "
                + "- COALESCE(SUM(refund.refunded), 0) AS received, COALESCE(SUM(refund.refunded), 0) AS refunded "
                + "FROM fr_sale_stage_receipt sr LEFT JOIN LATERAL ("
                + "SELECT COALESCE(SUM(g.amount_cents), 0) AS refunded FROM fr_fin_refund g "
                + "WHERE g.company_id = r.company_id AND g.receipt_id = sr.receipt_id "
                + "AND g.status = '已核实' AND g.replaced_by_id IS NULL) refund ON TRUE "
                + "WHERE sr.company_id = r.company_id AND sr.stage_id = sale_stage.id) unified ON contract.id IS NOT NULL "
                + "LEFT JOIN LATERAL (SELECT COALESCE(SUM(GREATEST(0, c.amount_cents "
                + "- COALESCE(refund.refunded, 0))), 0) AS received, "
                + "string_agg('c:' || c.id || ':' || c.amount_cents || ':' || COALESCE(refund.refunded, 0), "
                + "'|' ORDER BY c.id) AS signature_parts FROM fr_eng_collection c "
                + "LEFT JOIN LATERAL (SELECT COALESCE(SUM(g.amount_cents), 0) AS refunded "
                + "FROM fr_fin_refund g WHERE g.company_id = r.company_id AND g.receipt_id = c.receipt_id "
                + "AND g.status = '已核实' AND g.replaced_by_id IS NULL) refund ON c.receipt_id IS NOT NULL "
                + "WHERE c.company_id = r.company_id AND c.project_id = r.project_id "
                + "AND c.stage = " + stage + ") engineering ON contract.id IS NULL "
                + "WHERE r.company_id = ? AND r.release_at >= ?::timestamptz "
                + "AND r.release_at < ?::timestamptz "
                + "AND (p.assignee_user_id IN (" + placeholders + ") OR p.id IS NULL) "
                + "ORDER BY p.assignee_user_id NULLS LAST, r.project_id, r.stage";
    }

    /** 待核对与行视图 reviewDue 同源：规则版本变化，或批量收款事实与释放签名不一致/未足额。 */
    private static boolean reviewDue(MonthRelease release) {
        if (!Objects.equals(release.policyVersion(), release.currentVersion())) {
            return true;
        }
        return release.factRequired() <= 0 || release.factReceived() < release.factRequired()
                || !PerfCommissionService.sameStageSignature(
                        release.factSignature(), release.receiptSignature());
    }

    private static boolean anyStageUnagreed(MonthRelease release) {
        return release.startCents() <= 0 || release.baseDoneCents() <= 0 || release.toppingCents() <= 0;
    }

    /**
     * 批内游标：只保留「当前一个员工」的聚合；员工切换时立即写行并丢弃。
     * 单员工 release 数超上限时，在该员工行写出前抛出 500（prepare 已前置，此处为竞态兜底）。
     */
    private final class EmployeeSink {
        private final BufferedWriter writer;
        private Long currentEmployeeId;
        private EmployeeAcc current;
        private long currentReleases;
        private long written;

        private EmployeeSink(BufferedWriter writer) {
            this.writer = writer;
        }

        private void accept(ResultSet rs) throws SQLException {
            Long policyId = rs.getObject("policy_id", Long.class);
            if (policyId == null) {
                // prepare 已前置拦截；OR p.id IS NULL 使竞态孤儿行进入本批，显式失败而非静默少算
                throw dataIncomplete();
            }
            long employeeId = rs.getLong("assignee_user_id");
            if (currentEmployeeId == null || currentEmployeeId != employeeId) {
                flushCurrent();
                currentEmployeeId = employeeId;
                current = new EmployeeAcc(employeeId);
                currentReleases = 0;
            }
            if (++currentReleases > MAX_RELEASES_PER_EMPLOYEE) {
                throw dataTooManyReleases(); // 本行尚未写出；审计不会记成功
            }

            MonthRelease release = new MonthRelease(
                    policyId, employeeId, rs.getString("assignee_name"),
                    rs.getObject("policy_update_time", OffsetDateTime.class),
                    rs.getLong("total_cents"), rs.getLong("start_cents"),
                    rs.getLong("base_done_cents"), rs.getLong("topping_cents"),
                    rs.getObject("current_version", Integer.class),
                    rs.getLong("project_id"), rs.getString("stage"),
                    rs.getObject("policy_version", Integer.class), rs.getString("receipt_signature"),
                    rs.getObject("payable_cents", Long.class), rs.getLong("paid_cents"),
                    rs.getLong("fact_required"), rs.getLong("fact_received"), rs.getString("fact_signature"));

            current.acceptName(release.assigneeName(), release.policyUpdateTime());
            long amount = release.payableCents() != null
                    ? release.payableCents()
                    : PerfCommissionService.stageAmountOf(release.startCents(), release.baseDoneCents(),
                            release.toppingCents(), release.stage());
            ProjectAcc project = current.projects.computeIfAbsent(release.projectId(),
                    projectId -> new ProjectAcc(release.totalCents(), anyStageUnagreed(release)));
            project.generatedCents += amount;
            project.paidCents += release.paidCents();
            project.fullyPaid = project.fullyPaid && release.paidCents() >= amount;
            if (!project.reviewDue) {
                project.reviewDue = release.payableCents() == null || reviewDue(release);
            }
        }

        private void finish() {
            flushCurrent();
        }

        private void flushCurrent() {
            if (current == null) {
                return;
            }
            if (++written > EXPORT_MAX_ROWS) {
                throw badRequest("导出结果超过 5 万行上限，请调整月份后重试");
            }
            try {
                writer.write(csvRow(current.toRow().cells()));
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
            current = null;
            currentEmployeeId = null;
        }

        private long written() {
            return written;
        }
    }

    // ===================== 审计（§8.1：actor/筛选/行数；既有 system_operate_log 通道，零迁移） =====================

    private void writeAudit(long orgId, long actorUserId, String month, String status, Long rows) {
        try {
            OperateLogCreateReqDTO req = new OperateLogCreateReqDTO();
            req.setUserId(actorUserId);
            req.setUserType(UserTypeEnum.ADMIN.getValue());
            req.setType("加盟商绩效导出");
            req.setSubType("commission");
            req.setBizId(orgId);
            String rowText = rows == null ? "" : "；行数=" + rows;
            req.setAction("导出绩效提成=commission；状态=" + status
                    + "；筛选条件=month=" + month + rowText);
            req.setExtra("{\"report\":\"commission\",\"orgId\":" + orgId
                    + ",\"month\":\"" + month + "\",\"status\":\"" + status + "\""
                    + (rows == null ? "" : ",\"rows\":" + rows) + "}");
            req.setRequestMethod("GET");
            req.setRequestUrl("/franchise/performance/commission/export");
            // 与 P0 批3 导出同型：DTO 的 userIp/userAgent 带 @NotEmpty；无请求上下文用受控非空占位
            String ip = ServletUtils.getClientIP();
            String ua = ServletUtils.getUserAgent();
            req.setUserIp(ip == null || ip.isBlank() ? "unknown" : ip);
            req.setUserAgent(ua == null || ua.isBlank() ? "performance-export" : ua);
            operateLogApi.createOperateLog(req);
        } catch (RuntimeException error) {
            log.error("[perfCommissionExport][orgId={},month={},status={},rows={}] 导出审计写入失败",
                    orgId, month, status, rows, error);
            throw error;
        }
    }

    // ===================== 内部聚合结构 =====================

    private record MonthRelease(Long policyId, long assigneeUserId, String assigneeName,
                                OffsetDateTime policyUpdateTime, long totalCents, long startCents,
                                long baseDoneCents, long toppingCents, Integer currentVersion,
                                long projectId, String stage, Integer policyVersion,
                                String receiptSignature, Long payableCents, long paidCents,
                                long factRequired, long factReceived, String factSignature) {
    }

    private record EmployeeRow(long employeeId, String employeeName, int projectCount, long totalCents,
                               long generatedCents, long paidCents, long unpaidCents, int paidOffProjects,
                               int reviewDueProjects, int unagreedProjects) {

        List<Object> cells() {
            return List.of(
                    spreadsheetIdentifier(employeeId),
                    employeeName == null ? "" : employeeName,
                    projectCount,
                    moneyValue(totalCents),
                    moneyValue(generatedCents),
                    moneyValue(paidCents),
                    moneyValue(unpaidCents),
                    paidOffProjects,
                    reviewDueProjects,
                    unagreedProjects);
        }
    }

    private static final class EmployeeAcc {
        private final long userId;
        private final Map<Long, ProjectAcc> projects = new LinkedHashMap<>();
        private String name;
        private OffsetDateTime nameAt;

        private EmployeeAcc(long userId) {
            this.userId = userId;
        }

        /** 同名员工多项目可能来自不同规则快照；取最近更新的规则行姓名（确定性）。 */
        private void acceptName(String assigneeName, OffsetDateTime updatedAt) {
            if (name == null || (updatedAt != null && (nameAt == null || !updatedAt.isBefore(nameAt)))) {
                name = assigneeName;
                nameAt = updatedAt;
            }
        }

        private EmployeeRow toRow() {
            int projectCount = projects.size();
            long totalCents = 0;
            long generatedCents = 0;
            long paidCents = 0;
            int paidOff = 0;
            int reviewDue = 0;
            int unagreed = 0;
            for (ProjectAcc project : projects.values()) {
                totalCents += project.totalCents;
                generatedCents += project.generatedCents;
                paidCents += project.paidCents;
                paidOff += project.fullyPaid ? 1 : 0;
                reviewDue += project.reviewDue ? 1 : 0;
                unagreed += project.unagreed ? 1 : 0;
            }
            return new EmployeeRow(userId, name, projectCount, totalCents, generatedCents, paidCents,
                    Math.max(0, generatedCents - paidCents), paidOff, reviewDue, unagreed);
        }
    }

    private static final class ProjectAcc {
        private final long totalCents;
        private final boolean unagreed;
        private long generatedCents;
        private long paidCents;
        private boolean fullyPaid = true;
        private boolean reviewDue;

        private ProjectAcc(long totalCents, boolean unagreed) {
            this.totalCents = totalCents;
            this.unagreed = unagreed;
        }
    }
}
