package cn.iocoder.yudao.module.franchise.performance.content;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.franchise.performance.dal.FrPerfCycleDO;
import cn.iocoder.yudao.module.franchise.performance.dal.FrPerfCycleMapper;
import cn.iocoder.yudao.module.franchise.performance.dal.FrPerfGoalDO;
import cn.iocoder.yudao.module.franchise.performance.dal.FrPerfGoalMapper;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.performance.enums.FranchisePerformanceErrorCodes.*;

/**
 * 周期复核与申诉（F10/D-2）：submit（本人或 boss；冻结签名+submission；目标未设拒）→
 * review（不能复核本人；签名变化须退回）→ appeal-resolve（另一位老板；采纳→adjustments+
 * 受影响归档 supersedes 提示重开）。蓝本：performance.js apply 三动作。
 */
@Service
public class PerfCycleService {

    public static final String AGGREGATE = "fr-perf-cycle";

    private final FrPerfCycleMapper cycles;
    private final FrPerfGoalMapper goals;
    private final PerfMetrics metrics;
    private final PerfGoalService goalService;
    private final FranchiseCommands commands;
    private final JdbcTemplate jdbcTemplate;

    public PerfCycleService(FrPerfCycleMapper cycles, FrPerfGoalMapper goals, PerfMetrics metrics,
                            PerfGoalService goalService, FranchiseCommands commands, JdbcTemplate jdbcTemplate) {
        this.cycles = cycles;
        this.goals = goals;
        this.metrics = metrics;
        this.goalService = goalService;
        this.commands = commands;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** upsert 并返回行 id（jdbc 写后不走 MP 回读——同事务可见性） */
    private long upsertCycleJsonb(PostSnapshot post, FrPerfCycleDO cycle, long userId, String period,
                                  String status, String sourceSignature, String submissionJson) {
        if (cycle == null) {
            long id = cn.hutool.core.util.IdUtil.getSnowflakeNextId();
            jdbcTemplate.update("INSERT INTO fr_perf_cycle (id, company_id, period, user_id, status, "
                            + "source_signature, submission, revision, create_time, update_time) "
                            + "VALUES (?,?,?,?,?,?,CAST(? AS jsonb),1,now(),now())",
                    id, post.orgId(), period, userId, status, sourceSignature, submissionJson);
            return id;
        }
        jdbcTemplate.update("UPDATE fr_perf_cycle SET status = ?, source_signature = ?, "
                        + "submission = CAST(? AS jsonb), revision = revision + 1, update_time = now() "
                        + "WHERE id = ? AND company_id = ?",
                status, sourceSignature, submissionJson, cycle.getId(), post.orgId());
        return cycle.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> submit(PostSnapshot post, String period, long userId, String note,
                                      String idempotencyKey) {
        if (!PerfMetrics.validPeriod(period)) {
            throw exception(PERF_PERIOD_INVALID, "period 须为 YYYY-MM");
        }
        boolean manage = List.of("boss", "owner").contains(post.roleCode());
        long target = manage && userId > 0 ? userId : post.userId();
        if (!manage && target != post.userId()) {
            throw exception(PERFORMANCE_FORBIDDEN);
        }
        var replayed = commands.replayed(post, idempotencyKey,
                Map.of("period", period, "userId", target, "note", StrUtil.nullToEmpty(note)));
        if (replayed.isPresent()) {
            return view(post, period, target);
        }
        FrPerfGoalDO goal = goals.selectByPeriod(post.orgId(), period);
        FrPerfCycleDO cycle = cycles.selectByPeriodAndUser(post.orgId(), period, target);
        if (cycle != null && FrPerfCycleDO.STATUS_REVIEWED.equals(cycle.getStatus())) {
            throw exception(CYCLE_ACTION_INVALID, "本周期已复核通过");
        }
        List<PerfMetrics.SourceRow> allRows = metrics.sources(post, period, adjustmentsOf(post, period));
        // P1-10：actuals 按被考核人过滤（原实现冻结了全公司汇总）
        List<PerfMetrics.SourceRow> myRows = allRows.stream()
                .filter(r -> r.userId() != null && r.userId() == target).toList();
        Map<String, Map<String, Long>> dept = PerfMetrics.departmentMetrics(myRows);
        // P1-10：目标未设 → 001 拒绝（原实现只写事件标记不拦截）
        if (goal == null || goalService.targetForUser(goal, target, "business") == null
                && goalService.targetForUser(goal, target, "engineering") == null
                && goalService.targetForUser(goal, target, "finance") == null) {
            throw exception(PERF_INPUT_INVALID, "请先由老板设置本月目标后再提交复核");
        }
        if (StrUtil.isBlank(note)) {
            throw exception(PERF_INPUT_INVALID, "请填写提交说明");
        }
        Map<String, Object> submission = new LinkedHashMap<>();
        submission.put("actuals", dept);
        submission.put("userId", target);
        submission.put("sourceSignature", PerfMetrics.sourceSignature(myRows));
        submission.put("note", StrUtil.trim(note));
        submission.put("at", String.valueOf(java.time.OffsetDateTime.now()));
        String targetMissingFlag = "0";
        long cycleId = upsertCycleJsonb(post, cycle, target, period, FrPerfCycleDO.STATUS_PENDING_REVIEW,
                (String) submission.get("sourceSignature"),
                cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(submission));
        commands.record(post, AGGREGATE, cycleId,
                rowRevision(cycleId, post.orgId()), "perf-cycle-submit", Map.of("period", period, "userId", target,
                        "targetMissing", targetMissingFlag), idempotencyKey);
        return view(post, period, target);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> review(PostSnapshot post, String period, long userId, boolean approve,
                                      String note, String idempotencyKey) {
        PerfSupport.requireManage(post); // P1-4
        FrPerfCycleDO cycle = requireCycle(post, period, userId);
        var replayed = commands.replayed(post, idempotencyKey,
                Map.of("period", period, "userId", userId, "approve", approve, "note", StrUtil.nullToEmpty(note)));
        if (replayed.isPresent()) {
            return view(post, period, userId);
        }
        if (userId == post.userId()) {
            throw exception(PERFORMANCE_FORBIDDEN); // 不能复核本人周期（F10）
        }
        if (!FrPerfCycleDO.STATUS_PENDING_REVIEW.equals(cycle.getStatus())) {
            throw exception(CYCLE_ACTION_INVALID, "周期不在待复核状态");
        }
        // P1-10 伴生修复：submit 冻结的是被考核人过滤签名——review 校验须同基准
        List<PerfMetrics.SourceRow> reviewerRows = metrics.sources(post, period, adjustmentsOf(post, period)).stream()
                .filter(r -> r.userId() != null && r.userId() == cycle.getUserId()).toList();
        String currentSignature = PerfMetrics.sourceSignature(reviewerRows);
        boolean changed = !currentSignature.equals(cycle.getSourceSignature());
        boolean effectiveApprove = approve && !changed; // 签名变化必须退回重提（F10）
        Map<String, Object> review = new LinkedHashMap<>();
        review.put("result", effectiveApprove ? "通过" : "退回");
        review.put("reason", changed ? "提交后来源签名已变化，请退回重提" : StrUtil.nullToEmpty(note));
        review.put("reviewerUserId", post.userId());
        review.put("at", String.valueOf(java.time.OffsetDateTime.now()));
        JsonUtil.updateJsonb(jdbcTemplate, "fr_perf_cycle", Map.of("review",
                cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(review)),
                cycle.getId(), post.orgId());
        jdbcTemplate.update("UPDATE fr_perf_cycle SET status = ?, revision = revision + 1 WHERE id = ? AND company_id = ?",
                effectiveApprove ? FrPerfCycleDO.STATUS_REVIEWED : FrPerfCycleDO.STATUS_RETURNED,
                cycle.getId(), post.orgId());
        commands.record(post, AGGREGATE, cycle.getId(), rowRevision(cycle.getId(), post.orgId()), "perf-cycle-review",
                Map.of("period", period, "userId", userId, "approve", approve, "note", StrUtil.nullToEmpty(note)),
                idempotencyKey);
        return view(post, period, userId);
    }

    /** 申诉裁决（另一位老板：采纳→adjustments 追加；被影响归档提示 supersedes 重开） */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> appealResolve(PostSnapshot post, String period, long userId, boolean adopt,
                                             String sourceKey, Long personUserId, boolean excluded,
                                             String note, String idempotencyKey) {
        FrPerfCycleDO cycle = requireCycle(post, period, userId);
        Map<String, Object> appealPayload = Map.of("period", period, "userId", userId, "adopt", adopt,
                "sourceKey", StrUtil.nullToEmpty(sourceKey), "excluded", excluded, "note", StrUtil.nullToEmpty(note));
        var replayed = commands.replayed(post, idempotencyKey, appealPayload);
        if (replayed.isPresent()) {
            return view(post, period, userId);
        }
        if (userId == post.userId()) {
            throw exception(PERFORMANCE_FORBIDDEN); // 须另一位老板
        }
        if (StrUtil.isBlank(note) || StrUtil.isBlank(sourceKey)) {
            throw exception(PERF_INPUT_INVALID, "请填写裁决说明与争议来源");
        }
        if (adopt) {
            Map<String, Object> adjustment = new LinkedHashMap<>();
            adjustment.put("sourceKey", StrUtil.trim(sourceKey));
            adjustment.put("personUserId", personUserId);
            adjustment.put("excluded", excluded || personUserId == null);
            adjustment.put("appealNote", StrUtil.trim(note));
            adjustment.put("appealByUserId", post.userId());
            adjustment.put("at", String.valueOf(java.time.OffsetDateTime.now()));
            JsonUtil.updateJsonb(jdbcTemplate, "fr_perf_cycle", Map.of("adjustments",
                    JsonUtil.append(cycle.getAdjustments(), adjustment)), cycle.getId(), post.orgId());
        }
        // P1-1：采纳与否都 bump（不采纳重放也须走 command_event revision 递增）
        jdbcTemplate.update("UPDATE fr_perf_cycle SET revision = revision + 1, update_time = now() "
                + "WHERE id = ? AND company_id = ?", cycle.getId(), post.orgId());
        commands.record(post, AGGREGATE, cycle.getId(), rowRevision(cycle.getId(), post.orgId()), "perf-cycle-appeal-resolve",
                appealPayload, idempotencyKey);
        return view(post, period, userId);
    }

    public List<Map<String, Object>> list(PostSnapshot post, String period) {
        List<Map<String, Object>> rowsOut = new ArrayList<>();
        for (FrPerfCycleDO cycle : cycles.selectByPeriod(post.orgId(), period)) {
            rowsOut.add(view(post, period, cycle.getUserId()));
        }
        return rowsOut;
    }

    private Map<String, Object> view(PostSnapshot post, String period, long userId) {
        FrPerfCycleDO cycle = cycles.selectByPeriodAndUser(post.orgId(), period, userId);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("period", period);
        view.put("userId", userId);
        view.put("status", cycle == null ? null : cycle.getStatus());
        view.put("submission", cycle == null ? null : cycle.getSubmission());
        view.put("adjustments", cycle == null ? null : cycle.getAdjustments());
        view.put("review", cycle == null ? null : cycle.getReview());
        return view;
    }

    private List<PerfMetrics.Adjustment> adjustmentsOf(PostSnapshot post, String period) {
        List<PerfMetrics.Adjustment> all = new ArrayList<>();
        for (FrPerfCycleDO cycle : cycles.selectByPeriod(post.orgId(), period)) {
            for (Map<String, Object> row : JsonUtil.parseList(cycle.getAdjustments())) {
                Object key = row.get("sourceKey");
                if (key != null) {
                    Long person = row.get("personUserId") instanceof Number n ? n.longValue() : null;
                    all.add(new PerfMetrics.Adjustment(String.valueOf(key), person,
                            Boolean.TRUE.equals(row.get("excluded"))));
                }
            }
        }
        return all;
    }

    /** 该行当前 revision（jdbc 直读——command_event 防重须与行 revision 同步递增） */
    private long rowRevision(long cycleId, long orgId) {
        Long rev = jdbcTemplate.queryForObject(
                "SELECT revision FROM fr_perf_cycle WHERE id = ? AND company_id = ?",
                Long.class, cycleId, orgId);
        return rev == null ? 1 : rev;
    }



    private FrPerfCycleDO requireCycle(PostSnapshot post, String period, long userId) {
        FrPerfCycleDO cycle = cycles.selectByPeriodAndUser(post.orgId(), period, userId);
        if (cycle == null) {
            throw exception(CYCLE_ACTION_INVALID, "周期未提交复核");
        }
        return cycle;
    }
}
