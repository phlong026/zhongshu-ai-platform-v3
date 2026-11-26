package cn.iocoder.yudao.module.franchise.performance.content;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.franchise.performance.dal.FrPerfCycleDO;
import cn.iocoder.yudao.module.franchise.performance.dal.FrPerfCycleMapper;
import cn.iocoder.yudao.module.franchise.performance.dal.FrPerfGoalDO;
import cn.iocoder.yudao.module.franchise.performance.dal.FrPerfGoalMapper;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
    private final PerfCycleAccess access;

    public PerfCycleService(FrPerfCycleMapper cycles, FrPerfGoalMapper goals, PerfMetrics metrics,
                            PerfGoalService goalService, FranchiseCommands commands, JdbcTemplate jdbcTemplate,
                            PerfCycleAccess access) {
        this.cycles = cycles;
        this.goals = goals;
        this.metrics = metrics;
        this.goalService = goalService;
        this.commands = commands;
        this.jdbcTemplate = jdbcTemplate;
        this.access = access;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> submit(PostSnapshot post, String period, long requestedUser, String note,
                                      Long expectedRevision, String idempotencyKey) {
        validatePeriod(period);
        long user = requestedUser == 0 ? post.userId() : requestedUser;
        boolean behalf = user != post.userId();
        boolean manager = access.require(post, behalf);
        if (behalf && !access.activeMember(post.orgId(), user)) throw exception(PERFORMANCE_FORBIDDEN);
        if (StrUtil.isBlank(note)) throw exception(PERF_INPUT_INVALID, "请填写提交说明");

        String key = commands.requireIdempotencyKey(idempotencyKey);
        lock(post.orgId(), period, user);
        FrPerfCycleDO cycle = find(post.orgId(), period, user);
        Map<String, Object> input = input(period, user, note, expectedRevision);
        if (replayed(post, cycle, key, "perf-cycle-submit", input)) {
            access.require(post, behalf);
            return view(cycle, post, manager);
        }
        checkRevision(cycle, expectedRevision, true);
        if (cycle != null && FrPerfCycleDO.STATUS_REVIEWED.equals(cycle.getStatus())) {
            throw exception(CYCLE_ACTION_INVALID, "本周期已复核通过");
        }
        FrPerfGoalDO goal = goals.selectByPeriod(post.orgId(), period);
        if (goal == null || goalService.targetForUser(goal, user, "business") == null
                && goalService.targetForUser(goal, user, "engineering") == null
                && goalService.targetForUser(goal, user, "finance") == null) {
            throw exception(PERF_INPUT_INVALID, "请先由老板设置本月目标后再提交复核");
        }
        List<PerfMetrics.SourceRow> facts = personalFacts(post, period, user);
        String signature = PerfMetrics.sourceSignature(facts);
        long revision = cycle == null ? 1 : cycle.getRevision() + 1;
        Map<String, Object> submission = new LinkedHashMap<>();
        submission.put("userId", String.valueOf(user));
        submission.put("revision", revision);
        submission.put("note", StrUtil.trim(note));
        submission.put("at", java.time.OffsetDateTime.now().toString());
        submission.put("actuals", PerfMetrics.departmentMetrics(facts));
        submission.put("sourceSignature", signature);
        submission.put("goalVersion", goal.getVersion());
        submission.put("goals", personalGoals(goal, user));
        submission.put("sources", PerfCycleEvidence.freeze(new NamedParameterJdbcTemplate(jdbcTemplate), post.orgId(), facts));
        submission.put("history", history(cycle));
        long id = cycle == null ? cn.hutool.core.util.IdUtil.getSnowflakeNextId() : cycle.getId();
        if (cycle == null) {
            jdbcTemplate.update("""
                    INSERT INTO fr_perf_cycle (id,company_id,period,user_id,status,source_signature,submission)
                    VALUES (?,?,?,?,'PENDING_REVIEW',?,CAST(? AS jsonb))
                    """, id, post.orgId(), period, user, signature, JsonUtils.toJsonString(submission));
        } else {
            jdbcTemplate.update("""
                    UPDATE fr_perf_cycle SET status='PENDING_REVIEW',source_signature=?,submission=CAST(? AS jsonb),
                      review=NULL,revision=revision+1,update_time=now() WHERE id=? AND company_id=?
                    """, signature, JsonUtils.toJsonString(submission), id, post.orgId());
        }
        commands.record(post, AGGREGATE, id, revision, "perf-cycle-submit", input, key);
        return readAfterWrite(post, period, user, behalf);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> review(PostSnapshot post, String period, long user, boolean approve,
                                      String note, Long expectedRevision, String idempotencyKey) {
        validatePeriod(period);
        access.require(post, true);
        if (user == post.userId()) throw exception(PERFORMANCE_FORBIDDEN);
        String key = commands.requireIdempotencyKey(idempotencyKey);
        lock(post.orgId(), period, user);
        FrPerfCycleDO cycle = requireCycle(post, period, user);
        Map<String, Object> input = input(period, user, note, expectedRevision);
        input.put("approve", approve);
        if (replayed(post, cycle, key, "perf-cycle-review", input)) {
            access.require(post, true);
            return view(cycle, post, true);
        }
        checkRevision(cycle, expectedRevision, false);
        if (!FrPerfCycleDO.STATUS_PENDING_REVIEW.equals(cycle.getStatus())) {
            throw exception(CYCLE_ACTION_INVALID, "周期不在待复核状态");
        }
        List<PerfMetrics.SourceRow> facts = personalFacts(post, period, user);
        Map<String, Object> submission = JsonUtil.parseMap(cycle.getSubmission());
        boolean changed = !PerfMetrics.sourceSignature(facts).equals(cycle.getSourceSignature());
        if (submission != null && submission.containsKey("sources")) {
            changed |= !Objects.equals(submission.get("sources"),
                    PerfCycleEvidence.freeze(new NamedParameterJdbcTemplate(jdbcTemplate), post.orgId(), facts));
        }
        boolean goalsChanged = submission != null && submission.containsKey("goals")
                && !Objects.equals(submission.get("goals"), personalGoals(goals.selectByPeriod(post.orgId(), period), user));
        boolean accepted = approve && !changed && !goalsChanged;
        String reason = changed ? "提交后来源已变化，请退回重提" : goalsChanged ? "提交后个人目标已变化，请退回重提" : StrUtil.nullToEmpty(note);
        Map<String, Object> review = new LinkedHashMap<>();
        review.put("result", accepted ? "通过" : "退回");
        review.put("reason", reason);
        review.put("reviewerUserId", String.valueOf(post.userId()));
        review.put("at", java.time.OffsetDateTime.now().toString());
        jdbcTemplate.update("""
                UPDATE fr_perf_cycle SET status=?, review=CAST(? AS jsonb), revision=revision+1,update_time=now()
                WHERE id=? AND company_id=?
                """, accepted ? FrPerfCycleDO.STATUS_REVIEWED : FrPerfCycleDO.STATUS_RETURNED,
                JsonUtils.toJsonString(review), cycle.getId(), post.orgId());
        commands.record(post, AGGREGATE, cycle.getId(), cycle.getRevision() + 1, "perf-cycle-review", input, key);
        return readAfterWrite(post, period, user, true);
    }

    /** 申诉裁决（另一位老板：采纳→adjustments 追加；被影响归档提示 supersedes 重开） */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> appealResolve(PostSnapshot post, String period, long userId, boolean adopt,
                                             String sourceKey, Long personUserId, boolean excluded,
                                             String note, String idempotencyKey) {
        validatePeriod(period);
        access.require(post, true);
        if (userId == post.userId()) throw exception(PERFORMANCE_FORBIDDEN);
        lock(post.orgId(), period, userId);
        FrPerfCycleDO cycle = requireCycle(post, period, userId);
        Map<String, Object> appealPayload = Map.of("period", period, "userId", userId, "adopt", adopt,
                "sourceKey", StrUtil.nullToEmpty(sourceKey), "excluded", excluded, "note", StrUtil.nullToEmpty(note));
        var replayed = commands.replayed(post, idempotencyKey, appealPayload);
        if (replayed.isPresent()) {
            return readAfterWrite(post, period, userId, true);
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
        return readAfterWrite(post, period, userId, true);
    }

    public List<Map<String, Object>> list(PostSnapshot post, String period) {
        validatePeriod(period);
        boolean manager = access.require(post, false);
        var result = rows(post.orgId(), period, manager ? null : post.userId()).stream()
                .map(cycle -> view(cycle, post, manager)).toList();
        access.require(post, manager);
        return result;
    }

    private Map<String, Object> readAfterWrite(PostSnapshot post, String period, long user, boolean management) {
        FrPerfCycleDO cycle = requireCycle(post, period, user);
        return view(cycle, post, access.require(post, management));
    }

    private Map<String, Object> view(FrPerfCycleDO row, PostSnapshot post, boolean manager) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("companyId", String.valueOf(row.getCompanyId()));
        view.put("period", row.getPeriod());
        view.put("userId", String.valueOf(row.getUserId()));
        view.put("revision", row.getRevision());
        view.put("status", row.getStatus());
        view.put("canReview", manager && row.getUserId() != post.userId() && FrPerfCycleDO.STATUS_PENDING_REVIEW.equals(row.getStatus()));
        view.put("submission", row.getSubmission());
        view.put("adjustments", row.getAdjustments());
        view.put("review", row.getReview());
        return view;
    }

    private List<FrPerfCycleDO> rows(long company, String period, Long user) {
        String sql = """
                SELECT id,company_id,period,user_id,status,revision,source_signature,submission::text,review::text,adjustments::text
                FROM fr_perf_cycle WHERE company_id=? AND period=?
                """ + (user == null ? "" : " AND user_id=?") + " ORDER BY user_id";
        return jdbcTemplate.query(sql, (rs, index) -> new FrPerfCycleDO().setId(rs.getLong("id"))
                .setCompanyId(company).setPeriod(period).setUserId(rs.getLong("user_id"))
                .setStatus(rs.getString("status")).setRevision(rs.getLong("revision")).setSourceSignature(rs.getString("source_signature"))
                .setSubmission(rs.getString("submission")).setReview(rs.getString("review")).setAdjustments(rs.getString("adjustments")),
                user == null ? new Object[]{company, period} : new Object[]{company, period, user});
    }

    private FrPerfCycleDO find(long company, String period, long user) {
        return rows(company, period, user).stream().findFirst().orElse(null);
    }

    private FrPerfCycleDO requireCycle(PostSnapshot post, String period, long user) {
        FrPerfCycleDO row = find(post.orgId(), period, user);
        if (row == null) throw exception(CYCLE_ACTION_INVALID, "周期未提交复核");
        return row;
    }

    private List<PerfMetrics.SourceRow> personalFacts(PostSnapshot post, String period, long user) {
        return metrics.sources(post, period, adjustmentsOf(post, period)).stream()
                .filter(fact -> Objects.equals(fact.userId(), user)).toList();
    }

    private static Object personalGoals(FrPerfGoalDO goal, long user) {
        if (goal == null) return null;
        String id = String.valueOf(user);
        Map<String, Object> targets = JsonUtil.parseMap(goal.getStaffDepartmentGoals());
        Map<String, Object> legacy = JsonUtil.parseMap(goal.getLegacyStaff());
        Map<String, Object> result = new LinkedHashMap<>();
        if (targets != null && targets.containsKey(id)) result.put("departments", exactGoalValues(targets.get(id)));
        if (legacy != null && legacy.containsKey(id)) result.put("legacy", exactGoalValues(legacy.get(id)));
        return result;
    }

    private static Object exactGoalValues(Object value) {
        if (value instanceof Number number) return number.toString();
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), exactGoalValues(item)));
            return result;
        }
        return value;
    }

    private static List<Object> history(FrPerfCycleDO cycle) {
        List<Object> result = new ArrayList<>();
        if (cycle == null) return result;
        Map<String, Object> prior = JsonUtil.parseMap(cycle.getSubmission());
        if (prior != null && prior.remove("history") instanceof List<?> older) result.addAll(older);
        Map<String, Object> previous = new LinkedHashMap<>();
        previous.put("revision", cycle.getRevision());
        previous.put("status", cycle.getStatus());
        previous.put("submission", prior);
        previous.put("review", JsonUtil.parseMap(cycle.getReview()));
        result.add(previous);
        return result;
    }

    private static void validatePeriod(String period) {
        if (!PerfMetrics.validPeriod(period)) throw exception(PERF_PERIOD_INVALID, "月份格式无效");
    }

    private void lock(long company, String period, long user) {
        jdbcTemplate.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", rs -> {},
                "perf-cycle:" + company + ":" + period + ":" + user);
    }

    /** A legacy review without a version can only refer to the first, never replaced, submission. */
    private static void checkRevision(FrPerfCycleDO cycle, Long expected, boolean submit) {
        long actual = cycle == null ? 0 : cycle.getRevision();
        if (expected == null ? (submit ? actual != 0 : actual != 1) : expected != actual) {
            throw new ServiceException(409, "周期版本已变化，请刷新后重新确认");
        }
    }

    private static Map<String, Object> input(String period, long user, String note, Long expected) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("period", period);
        input.put("userId", user);
        input.put("note", StrUtil.nullToEmpty(note));
        if (expected != null) input.put("expectedRevision", expected);
        return input;
    }

    private boolean replayed(PostSnapshot post, FrPerfCycleDO cycle, String key, String action, Map<String, Object> input) {
        var prior = commands.previous(post, key, action, cycle == null ? 0 : cycle.getId());
        if (prior.isEmpty()) return false;
        var saved = JsonUtil.parseMap(prior.get().getPayload());
        if (saved != null && saved.containsKey("targetMissing")) {
            // First-generation submit events omitted the note. Prove it from the matching archived submission.
            Object proven = null;
            for (Object version : history(cycle)) {
                if (!(version instanceof Map<?, ?> snapshot) || !(snapshot.get("submission") instanceof Map<?, ?> sub)) continue;
                Object submittedAt = sub.get("revision");
                if (submittedAt == null) {
                    submittedAt = jdbcTemplate.queryForObject("""
                            SELECT max(revision) FROM pf_command_event WHERE org_id=? AND aggregate_type=?
                              AND aggregate_id=? AND command='perf-cycle-submit' AND revision<=?
                            """, Long.class, post.orgId(), AGGREGATE, cycle.getId(), snapshot.get("revision"));
                }
                if (Objects.equals(String.valueOf(submittedAt), String.valueOf(prior.get().getRevision()))) {
                    proven = sub.get("note");
                    break;
                }
            }
            if (proven == null) throw new ServiceException(409, "旧回执未保留可核实的提交说明");
            saved.remove("targetMissing");
            saved.put("note", proven);
            input = new LinkedHashMap<>(input);
            input.put("note", String.valueOf(input.get("note")).trim());
        }
        if (saved == null || !saved.keySet().equals(input.keySet()) || !input.entrySet().stream().allMatch(
                field -> Objects.equals(String.valueOf(field.getValue()), String.valueOf(saved.get(field.getKey()))))) {
            throw new ServiceException(409, "该回执对应的周期操作已变化");
        }
        return true;
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
}
