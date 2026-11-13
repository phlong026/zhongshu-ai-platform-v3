package cn.iocoder.yudao.module.franchise.performance.content;

import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.performance.enums.FranchisePerformanceErrorCodes.PERF_PERIOD_INVALID;

/**
 * 经营看板（P1 批1）：跨域只读聚合，供 /fr/dashboard 使用。
 *
 * <p>口径基线（R5 §三.5/§5.3）：
 * <ul>
 *   <li>财务趋势/收支构成/余额/账龄由 {@code GET /franchise/performance/operating/analytics} 提供，
 *       部门实绩/任务统计由 {@code GET /franchise/performance/tasks/summary} 提供——本服务不重复聚合。</li>
 *   <li>本服务只补上述两端点不覆盖的三域“当月创建量”：客资（客户/线索）、工程（承接）、设计（服务委托）。</li>
 * </ul>
 *
 * <p><b>状态分组边界</b>：新增客户/承接工程的状态桶是<b>查询时刻的当前状态快照</b>，
 * 不是月末历史状态（三域均无状态流水表，无法还原历史）。前端 tooltip 同步登记该边界。
 *
 * <p><b>设计委托边界</b>：{@code serviceCommissions} 只统计经 B→A 服务能力发起、
 * {@code operation = 'CREATE_PROJECT'} 的命令。该列写入的是 {@code Operation.name()}
 * （全大写+下划线，见 RemoteDesignCommandStore 与 925 DDL），不是 {@code action()} 的
 * {@code create-project}，也不是 {@code portMethod()} 的 {@code createProject}。
 * 消费者在设计端自建的项目不计入。计数含 NEW/SENT/UNKNOWN/SUCCEEDED/REJECTED 全部状态，
 * 故它是<b>发起数</b>而非成功数（如需成功数须另立口径；仅计 SUCCEEDED 会漏掉 UNKNOWN 在途）。
 *
 * <p>跨域查询沿用 {@link PerfMetrics} 既有模式：JdbcTemplate 显式组织列（crm/eng 用 company_id，
 * 设计命令表用 org_id），不经 OrgScope，不引 crm/engineering/design 模块依赖。
 */
@Service
public class PerfDashboardService {

    /** 客户状态（CustomerDO / 802 DDL 实证；不引 franchise-crm 依赖） */
    private static final List<String> CUSTOMER_STATUSES = List.of("REGISTERED", "FOLLOWING");
    /** 工程状态（FrEngProjectDO / 814 DDL 实证；不引 franchise-engineering 依赖） */
    private static final List<String> ENGINEERING_STATUSES = List.of(
            "PENDING_START", "IN_PROGRESS", "READY_DELIVER", "DELIVERED", "ARCHIVED");
    /** 设计委托创建操作名（fr_design_remote_command.operation = Operation.name()） */
    private static final String DESIGN_CREATE_PROJECT = "CREATE_PROJECT";

    private final JdbcTemplate jdbc;

    public PerfDashboardService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 当月创建量聚合（period=YYYY-MM；组织隔离由 company_id/org_id 强制收敛）。 */
    public Map<String, Object> dashboard(PostSnapshot post, String period) {
        requirePeriod(period);
        long org = post.orgId();
        String from = monthStart(period);
        String to = monthEndExclusive(period);

        Map<String, Long> customerByStatus = countByStatus(
                "SELECT status, COUNT(*) AS cnt FROM fr_customer WHERE company_id = ? "
                        + "AND create_time >= ?::timestamptz AND create_time < ?::timestamptz GROUP BY status",
                CUSTOMER_STATUSES, org, from, to);
        long newLeads = count("SELECT COUNT(*) FROM fr_lead WHERE company_id = ? "
                + "AND create_time >= ?::timestamptz AND create_time < ?::timestamptz", org, from, to);
        Map<String, Long> engineeringByStatus = countByStatus(
                "SELECT status, COUNT(*) AS cnt FROM fr_eng_project WHERE company_id = ? "
                        + "AND create_time >= ?::timestamptz AND create_time < ?::timestamptz GROUP BY status",
                ENGINEERING_STATUSES, org, from, to);
        long serviceCommissions = count("SELECT COUNT(*) FROM fr_design_remote_command WHERE org_id = ? "
                + "AND operation = ? AND created_at >= ?::timestamptz AND created_at < ?::timestamptz",
                org, DESIGN_CREATE_PROJECT, from, to);

        Map<String, Object> crm = new LinkedHashMap<>();
        crm.put("newCustomers", sum(customerByStatus));
        crm.put("newCustomersByStatus", customerByStatus);
        crm.put("newLeads", newLeads);
        Map<String, Object> engineering = new LinkedHashMap<>();
        engineering.put("created", sum(engineeringByStatus));
        engineering.put("byStatus", engineeringByStatus);
        Map<String, Object> design = new LinkedHashMap<>();
        design.put("serviceCommissions", serviceCommissions);

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("period", period);
        view.put("crm", crm);
        view.put("engineering", engineering);
        view.put("design", design);
        return view;
    }

    /** 状态分桶：已知状态零填（前端稳定渲染），未知状态兜底追加不吞数。 */
    private Map<String, Long> countByStatus(String sql, List<String> zeroFill, Object... args) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String status : zeroFill) {
            out.put(status, 0L);
        }
        for (Map<String, Object> row : jdbc.queryForList(sql, args)) {
            out.put(String.valueOf(row.get("status")), ((Number) row.get("cnt")).longValue());
        }
        return out;
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    private static long sum(Map<String, Long> values) {
        long total = 0L;
        for (Long value : values.values()) {
            total += value == null ? 0L : value;
        }
        return total;
    }

    private static void requirePeriod(String period) {
        if (!PerfMetrics.validPeriod(period)) {
            // 错误码模板无占位符：不传 params，避免 doFormat 记录 ERROR 日志并丢自定义提示。
            throw exception(PERF_PERIOD_INVALID);
        }
    }

    /** 与 PerfMetrics.monthStart 同口径（本地月首日）。 */
    private static String monthStart(String period) {
        return period + "-01 00:00:00";
    }

    /** 与 PerfMetrics.monthEndExclusive 同口径（次月首日，半开区间）。 */
    private static String monthEndExclusive(String period) {
        return LocalDate.parse(period + "-01").plusMonths(1) + " 00:00:00";
    }
}
