package cn.iocoder.yudao.module.franchise.performance.content;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.franchise.performance.dal.FrPerfGoalDO;
import cn.iocoder.yudao.module.franchise.performance.dal.FrPerfGoalMapper;
import cn.iocoder.yudao.module.franchise.performance.dal.FrPerfTaskArchiveDO;
import cn.iocoder.yudao.module.franchise.performance.dal.FrPerfTaskArchiveMapper;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.performance.enums.FranchisePerformanceErrorCodes.*;

/**
 * 月度目标（3.7 / D-5）：三级（公司级五指标列+部门级/人员部门级 JSONB+旧标量兼容）；
 * version+history；归档期拒改（锁信号=该 period 存在 fr_perf_task_archive，P3-13）。
 * 蓝本：owner-model.js（旧目标页已封口只读——职责移交任务机）。
 */
@Service
public class PerfGoalService {

    public static final String AGGREGATE = "fr-perf-goal";

    private final FrPerfGoalMapper goals;
    private final FrPerfTaskArchiveMapper archives;
    private final FranchiseCommands commands;
    private final JdbcTemplate jdbcTemplate;

    public PerfGoalService(FrPerfGoalMapper goals, FrPerfTaskArchiveMapper archives,
                           FranchiseCommands commands, JdbcTemplate jdbcTemplate) {
        this.goals = goals;
        this.archives = archives;
        this.commands = commands;
        this.jdbcTemplate = jdbcTemplate;
    }

    public record GoalCommand(String period, Long salesCents, Long collectionsCents, Integer effectiveCount,
                              Integer customersCount, Integer nodesCount,
                              Map<String, Object> departmentGoals, Map<String, Object> staffDepartmentGoals,
                              Map<String, Object> legacyStaff) {
    }

    public Map<String, Object> get(PostSnapshot post, String period) {
        requirePeriod(period);
        FrPerfGoalDO goal = goals.selectByPeriod(post.orgId(), period);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("period", period);
        view.put("salesCents", goal == null ? null : goal.getSalesCents());
        view.put("collectionsCents", goal == null ? null : goal.getCollectionsCents());
        view.put("effectiveCount", goal == null ? null : goal.getEffectiveCount());
        view.put("customersCount", goal == null ? null : goal.getCustomersCount());
        view.put("nodesCount", goal == null ? null : goal.getNodesCount());
        view.put("departmentGoals", goal == null ? null : goal.getDepartmentGoals());
        view.put("staffDepartmentGoals", goal == null ? null : goal.getStaffDepartmentGoals());
        view.put("legacyStaff", goal == null ? null : goal.getLegacyStaff());
        view.put("version", goal == null ? 0 : goal.getVersion());
        view.put("locked", !archives.selectByPeriod(post.orgId(), period).isEmpty());
        return view;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> save(PostSnapshot post, GoalCommand cmd, String idempotencyKey) {
        requirePeriod(cmd.period());
        var replayed = commands.replayed(post, idempotencyKey, payload(cmd));
        if (replayed.isPresent()) {
            return get(post, cmd.period());
        }
        if (!archives.selectByPeriod(post.orgId(), cmd.period()).isEmpty()) {
            throw exception(GOAL_OR_SNAPSHOT_LOCKED); // 归档期拒改（锁=task_archive 存在）
        }
        FrPerfGoalDO goal = goals.selectByPeriod(post.orgId(), cmd.period());
        int nextVersion = goal == null ? 1 : goal.getVersion() + 1;
        // 红线：JSONB 列（department_goals/staff_department_goals/legacy_staff/history）一律 JdbcTemplate CAST
        if (goal == null) {
            goals.insert(new FrPerfGoalDO().setCompanyId(post.orgId()).setPeriod(cmd.period())
                    .setSalesCents(cmd.salesCents()).setCollectionsCents(cmd.collectionsCents())
                    .setEffectiveCount(cmd.effectiveCount()).setCustomersCount(cmd.customersCount())
                    .setNodesCount(cmd.nodesCount())
                    .setVersion(nextVersion).setAuthorUserId(post.userId()));
            goal = goals.selectByPeriod(post.orgId(), cmd.period());
        } else {
            goals.updateById(new FrPerfGoalDO().setId(goal.getId())
                    .setSalesCents(cmd.salesCents()).setCollectionsCents(cmd.collectionsCents())
                    .setEffectiveCount(cmd.effectiveCount()).setCustomersCount(cmd.customersCount())
                    .setNodesCount(cmd.nodesCount())
                    .setVersion(nextVersion));
        }
        jdbcTemplate.update("UPDATE fr_perf_goal SET department_goals = CAST(? AS jsonb), "
                        + "staff_department_goals = CAST(? AS jsonb), legacy_staff = CAST(? AS jsonb), "
                        + "history = CAST(? AS jsonb), update_time = now() WHERE id = ? AND company_id = ?",
                json(cmd.departmentGoals()), json(cmd.staffDepartmentGoals()), json(cmd.legacyStaff()),
                JsonUtil.append(goal.getHistory(), Map.of("version", goal.getVersion(),
                        "at", String.valueOf(java.time.OffsetDateTime.now()), "actorUserId", post.userId())),
                goal.getId(), post.orgId());
        // command_event 按 (聚合, revision) 防重——revision 用目标 version（每次保存递增，阶段 2 同款教训）
        commands.record(post, AGGREGATE, goal.getId(), nextVersion,
                "perf-goal-save", payload(cmd), idempotencyKey);
        return get(post, cmd.period());
    }

    /** cycle submit 的 target 来源（P3-13）：本人部门级→旧标量回退 */
    Long targetForUser(FrPerfGoalDO goal, Long userId, String dept) {
        if (goal == null) {
            return null;
        }
        Map<String, Object> staff = JsonUtil.parseMap(goal.getStaffDepartmentGoals());
        if (staff != null && userId != null && dept != null) {
            Object row = staff.get(String.valueOf(userId));
            if (row instanceof Map<?, ?> byDept && byDept.get(dept) instanceof Map<?, ?> metrics) {
                Object target = metrics.get("collectionsAmount");
                if (target instanceof Number n) {
                    return n.longValue();
                }
            }
        }
        Map<String, Object> legacy = JsonUtil.parseMap(goal.getLegacyStaff());
        if (legacy != null && userId != null) {
            Object target = legacy.get(String.valueOf(userId));
            if (target instanceof Number n) {
                return n.longValue();
            }
        }
        return null;
    }

    private static void requirePeriod(String period) {
        if (!PerfMetrics.validPeriod(period)) {
            throw exception(PERF_PERIOD_INVALID, "period 须为 YYYY-MM");
        }
    }

    private static Map<String, Object> payload(GoalCommand cmd) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("period", cmd.period());
        m.put("salesCents", cmd.salesCents());
        m.put("collectionsCents", cmd.collectionsCents());
        m.put("nodesCount", cmd.nodesCount());
        m.put("deptGoals", cmd.departmentGoals() == null ? 0 : cmd.departmentGoals().size());
        m.put("staffDeptGoals", cmd.staffDepartmentGoals() == null ? 0 : cmd.staffDepartmentGoals().size());
        return m;
    }

    private static String json(Map<String, Object> value) {
        return value == null || value.isEmpty() ? null
                : cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(value);
    }
}
