package cn.iocoder.yudao.module.franchise.performance.content;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.franchise.performance.dal.FrPerfCycleDO;
import cn.iocoder.yudao.module.franchise.performance.dal.FrPerfCycleMapper;
import cn.iocoder.yudao.module.franchise.performance.dal.FrPerfTaskArchiveDO;
import cn.iocoder.yudao.module.franchise.performance.dal.FrPerfTaskArchiveMapper;
import cn.iocoder.yudao.module.franchise.performance.dal.FrPerfTaskDO;
import cn.iocoder.yudao.module.franchise.performance.dal.FrPerfTaskMapper;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.franchise.org.FranchiseRoles;
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
 * 绩效任务机（3.8 / D-2 单轨基准=performance-tasks.js）：派发（manual+十类 auto）、
 * 改派（manualDone 清空）、manualDone 本人补填、范围查询（mine/company/department/member）、
 * 三组 summary（实时派生）、上月初始化（suggested 幂等）、归档链（快照+sourceSignature+修订）。
 */
@Service
public class PerfTaskService {

    public static final String AGGREGATE = "fr-perf-task";
    /** 三组→岗位角色映射（蓝本 DEPARTMENTS；业务组归并 sales/operations/design） */
    private static final Map<String, List<String>> DEPT_ROLES = Map.of(
            "business", List.of("sales", "sales-lead", "hq-operations", "hq-operations-lead", "hq-design-admin"),
            "engineering", List.of("engineer", "project-manager"),
            "finance", List.of("finance", "finance-lead"));

    private final FrPerfTaskMapper tasks;
    private final FrPerfTaskArchiveMapper archives;
    private final FrPerfCycleMapper cycles;
    private final PerfGoalService goalService;
    private final PerfMetrics metrics;
    private final FranchiseCommands commands;
    private final JdbcTemplate jdbcTemplate;
    private final cn.iocoder.yudao.module.platform.org.SystemDirectory systemDirectory;

    public PerfTaskService(FrPerfTaskMapper tasks, FrPerfTaskArchiveMapper archives,
                           FrPerfCycleMapper cycles, PerfGoalService goalService,
                           PerfMetrics metrics, FranchiseCommands commands, JdbcTemplate jdbcTemplate,
                           cn.iocoder.yudao.module.platform.org.SystemDirectory systemDirectory) {
        this.tasks = tasks;
        this.archives = archives;
        this.cycles = cycles;
        this.goalService = goalService;
        this.metrics = metrics;
        this.commands = commands;
        this.jdbcTemplate = jdbcTemplate;
        this.systemDirectory = systemDirectory;
    }

    /** 任务落库（history JSONB 走 CAST——MP insert 禁写 jsonb 列） */
    private long insertTask(PostSnapshot post, FrPerfTaskDO t, Map<String, Object> historyRow) {
        long id = cn.hutool.core.util.IdUtil.getSnowflakeNextId();
        jdbcTemplate.update("INSERT INTO fr_perf_task (id, company_id, period, title, assignee_user_id, "
                        + "assignee_name, assignee_dept, type, target_cents, due, history, suggested, revision, "
                        + "create_time, update_time) VALUES (?,?,?,?,?,?,?,?,?,?,CAST(? AS jsonb),?,1,now(),now())",
                id, post.orgId(), t.getPeriod(), t.getTitle(), t.getAssigneeUserId(), t.getAssigneeName(),
                t.getAssigneeDept(), t.getType(), t.getTargetCents(), t.getDue(),
                JsonUtil.append(null, historyRow), t.getSuggested() != null && t.getSuggested());
        return id;
    }

    public record TaskCommand(String period, String title, long assigneeUserId, String assigneeName,
                              String assigneeDept, String type, Long targetCents, LocalDate due,
                              Map<String, Object> suggestion) {
    }

    public record ManualDoneCommand(String note, List<String> photos) {
    }

    // ===================== 派发/改派/补填 =====================

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> create(PostSnapshot post, TaskCommand cmd, String idempotencyKey) {
        requirePeriod(cmd.period());
        PerfSupport.requireManage(post); // P1-4：服务层 manage 校验（不依赖控制器菜单码）
        Map<String, Object> payload = payload(cmd);
        var replayed = commands.replayed(post, idempotencyKey, payload);
        if (replayed.isPresent()) {
            return detail(post, String.valueOf(replayed.get().getAggregateId()), null);
        }
        if (StrUtil.isBlank(cmd.title()) || cmd.title().length() > 60 || cmd.assigneeUserId() <= 0
                || cmd.due() == null || cmd.targetCents() == null || cmd.targetCents() < 0) {
            throw exception(PERF_INPUT_INVALID, "请填写任务标题、责任人、目标与截止日");
        }
        boolean manual = FrPerfTaskDO.TYPE_MANUAL.equals(cmd.type());
        if (!manual && !PerfMetrics.AUTO_TYPES.contains(cmd.type())) {
            throw exception(PERF_INPUT_INVALID, "任务类型不合法（financeReviews 不建——19 §九）");
        }
        if (!dueInPeriod(cmd.due(), cmd.period())) {
            throw exception(PERF_PERIOD_INVALID, "截止日期须在任务月份内");
        }
        if (StrUtil.isBlank(cmd.assigneeDept()) || !DEPT_ROLES.containsKey(cmd.assigneeDept())) {
            throw exception(PERF_INPUT_INVALID, "分组须为 business/engineering/finance");
        }
        requireNotArchived(post, cmd.period()); // P1-11：归档期禁止新增任务
        if (manual && cmd.targetCents() != 0) {
            throw exception(PERF_INPUT_INVALID, "手动任务无目标值");
        }
        // P1-5：归属人须本组织在岗（职务真源），姓名快照服务端生成（客户端名不落库）
        var member = requireActiveMember(post, cmd.assigneeUserId());
        FrPerfTaskDO task = new FrPerfTaskDO().setPeriod(cmd.period())
                .setTitle(StrUtil.trim(cmd.title())).setAssigneeUserId(cmd.assigneeUserId())
                .setAssigneeName(member.name())
                .setAssigneeDept(cmd.assigneeDept()).setType(cmd.type())
                .setTargetCents(cmd.targetCents()).setDue(cmd.due())
                .setSuggested(cmd.suggestion() != null);
        long taskId = insertTask(post, task, Map.of("action", "create", "actorUserId", post.userId(),
                "at", String.valueOf(java.time.OffsetDateTime.now())));
        commands.record(post, AGGREGATE, taskId, 1, "perf-task-create", payload, idempotencyKey);
        return detail(post, String.valueOf(taskId), null);
    }

    /** 改派（蓝本：manualDone 清空+history 留痕；归档后禁改） */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> reassign(PostSnapshot post, long taskId, long toUserId, String toName,
                                        String idempotencyKey) {
        FrPerfTaskDO task = requireTask(post, taskId);
        requireNotArchived(post, task.getPeriod());
        PerfSupport.requireManage(post); // P1-4
        var replayed = commands.replayed(post, idempotencyKey, Map.of("taskId", taskId, "toUserId", toUserId));
        if (replayed.isPresent()) {
            return detail(post, String.valueOf(taskId), null);
        }
        // P1-5：改派目标须在岗，姓名服务端快照
        var member = requireActiveMember(post, toUserId);
        String history = JsonUtil.append(task.getHistory(), Map.of(
                "action", "reassign", "fromUserId", task.getAssigneeUserId(), "toUserId", toUserId,
                "actorUserId", post.userId(), "at", String.valueOf(java.time.OffsetDateTime.now())));
        boolean updated = tasks.updateByRevision(taskId, task.getRevision(), u -> u
                .set(FrPerfTaskDO::getAssigneeUserId, toUserId)
                .set(FrPerfTaskDO::getAssigneeName, member.name())) == 1;
        if (!updated) {
            throw exception(PERF_TASK_INVALID, "任务已被他人更新，请刷新后重试"); // P2-17 CAS 返回值
        }
        jdbcTemplate.update("UPDATE fr_perf_task SET manual_done = NULL WHERE id = ? AND company_id = ?",
                taskId, post.orgId()); // 蓝本 ：269 改派清空
        JsonUtil.updateJsonb(jdbcTemplate, "fr_perf_task", Map.of("history", history), taskId, post.orgId());
        commands.record(post, AGGREGATE, taskId, task.getRevision() + 1, "perf-task-reassign",
                Map.of("taskId", taskId, "toUserId", toUserId), idempotencyKey);
        return detail(post, String.valueOf(taskId), null);
    }

    /** manualDone 本人补填（photos 随 COS 批降维——字段建好；归档禁改） */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> completeManual(PostSnapshot post, long taskId, ManualDoneCommand cmd,
                                              String idempotencyKey) {
        FrPerfTaskDO task = requireTask(post, taskId);
        if (!FrPerfTaskDO.TYPE_MANUAL.equals(task.getType())) {
            throw exception(PERF_TASK_INVALID, "仅手动任务支持补填");
        }
        requireNotArchived(post, task.getPeriod());
        if (task.getAssigneeUserId() != post.userId()) {
            throw exception(PERFORMANCE_FORBIDDEN); // 仅被考核本人
        }
        var replayed = commands.replayed(post, idempotencyKey,
                Map.of("taskId", taskId, "note", StrUtil.nullToEmpty(cmd.note())));
        if (replayed.isPresent()) {
            return detail(post, String.valueOf(taskId), null);
        }
        if (StrUtil.isBlank(cmd.note()) || cmd.note().length() > 500) {
            throw exception(PERF_INPUT_INVALID, "完成说明必填且不超过 500 字");
        }
        if (cmd.photos() != null && cmd.photos().size() > 6) {
            throw exception(PERF_INPUT_INVALID, "凭证照片最多 6 张");
        }
        Map<String, Object> manualDone = new LinkedHashMap<>();
        manualDone.put("note", StrUtil.trim(cmd.note()));
        manualDone.put("photos", cmd.photos() == null ? List.of() : cmd.photos());
        manualDone.put("at", String.valueOf(java.time.OffsetDateTime.now()));
        manualDone.put("actorUserId", post.userId());
        String history = JsonUtil.append(task.getHistory(), Map.of(
                "action", "manual-complete", "previous", task.getManualDone() == null ? "" : task.getManualDone(),
                "actorUserId", post.userId(), "at", String.valueOf(java.time.OffsetDateTime.now())));
        tasks.updateByRevision(taskId, task.getRevision(), u -> {
        });
        JsonUtil.updateJsonb(jdbcTemplate, "fr_perf_task", new java.util.HashMap<>(Map.of(
                "manual_done", cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(manualDone),
                "history", history)), taskId, post.orgId());
        commands.record(post, AGGREGATE, taskId, task.getRevision() + 1, "perf-task-manual-complete",
                Map.of("taskId", taskId, "note", StrUtil.nullToEmpty(cmd.note())), idempotencyKey); // P1-2 与重放 payload 一致
        return detail(post, String.valueOf(taskId), null);
    }

    // ===================== 查询（实时派生） =====================

    /** 范围：mine=本人；company=manage 全组织；department=组；member=按人（manage/组长查成员——P3-14②） */
    public List<Map<String, Object>> list(PostSnapshot post, String period, String scope, String dept, Long userId) {
        requirePeriod(period);
        boolean manage = List.of(FranchiseRoles.BOSS, FranchiseRoles.OWNER).contains(post.roleCode());
        String effectiveScope = scope == null ? "mine" : scope;
        Long targetUser = post.userId();
        String targetDept = null;
        switch (effectiveScope) {
            case "company" -> {
                if (!manage) {
                    throw exception(PERFORMANCE_FORBIDDEN);
                }
                targetUser = null;
            }
            case "department" -> {
                if (StrUtil.isBlank(dept) || !DEPT_ROLES.containsKey(dept)) {
                    throw exception(PERF_INPUT_INVALID, "分组不合法");
                }
                targetDept = dept;
                targetUser = null;
                if (!manage && !DEPT_ROLES.get(dept).contains(post.roleCode())) {
                    throw exception(PERFORMANCE_FORBIDDEN); // 非本组岗位看组=越权
                }
            }
            case "member" -> {
                if (userId == null) {
                    throw exception(PERF_INPUT_INVALID, "member 范围须带 userId");
                }
                // P1-3/R2 P2-5：普通员工仅本人；组长（sales-lead/project-manager）可查本组成员（F6 蓝本语义，
                // 团队维度收紧随阶段 3 待决）；boss/owner 全量
                if (userId != post.userId() && !manage
                        && !List.of("sales-lead", "project-manager").contains(post.roleCode())) {
                    throw exception(PERFORMANCE_FORBIDDEN);
                }
                targetUser = userId;
            }
            case "mine" -> {
            }
            default -> throw exception(PERF_INPUT_INVALID, "scope 须为 mine/company/department/member");
        }
        List<PerfMetrics.SourceRow> sourceRows = rows(post, period);
        List<PerfMetrics.Adjustment> adjustments = adjustments(post, period);
        if (!adjustments.isEmpty()) {
            sourceRows = metrics.sources(post, period, adjustments);
        }
        List<Map<String, Object>> views = new ArrayList<>();
        for (FrPerfTaskDO task : tasks.selectByPeriod(post.orgId(), period)) {
            if (targetUser != null && !task.getAssigneeUserId().equals(targetUser)) {
                continue;
            }
            if (targetDept != null && !targetDept.equals(task.getAssigneeDept())) {
                continue;
            }
            views.add(taskView(task, sourceRows));
        }
        views.sort((a, b) -> String.valueOf(a.get("due")).compareTo(String.valueOf(b.get("due"))));
        return views;
    }

    /** 任务行视图（auto 进度实时派生+三态；归属待核对恒空——§九） */
    private Map<String, Object> taskView(FrPerfTaskDO task, List<PerfMetrics.SourceRow> sourceRows) {
        boolean manual = FrPerfTaskDO.TYPE_MANUAL.equals(task.getType());
        long collected = manual ? 0
                : PerfMetrics.taskValue(sourceRows, task.getType(), task.getAssigneeUserId());
        boolean completed = manual ? task.getManualDone() != null
                : task.getTargetCents() != null && task.getTargetCents() > 0 && collected >= task.getTargetCents();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", String.valueOf(task.getId()));
        view.put("period", task.getPeriod());
        view.put("title", task.getTitle());
        view.put("assigneeUserId", task.getAssigneeUserId());
        view.put("assigneeName", task.getAssigneeName());
        view.put("assigneeDept", task.getAssigneeDept());
        view.put("type", task.getType());
        view.put("targetCents", task.getTargetCents());
        view.put("due", task.getDue());
        view.put("manualDone", task.getManualDone());
        view.put("suggested", task.getSuggested());
        view.put("collected", collected);
        view.put("status", completed ? "已完成" : "进行中");
        view.put("unresolvedSources", List.of()); // 简化后恒空（§九）
        return view;
    }

    /** 三组汇总（summary；P1-3：非 manage 仅返回本人所在组；含目标对照——待决④映射） */
    public Map<String, Object> summary(PostSnapshot post, String period) {
        requirePeriod(period);
        boolean manage = List.of("boss", "owner").contains(post.roleCode());
        List<PerfMetrics.SourceRow> allRows = metrics.sources(post, period, adjustments(post, period));
        String myDept = DEPT_ROLE_OF.getOrDefault(post.roleCode(), null);
        Map<String, Map<String, Long>> byDept = PerfMetrics.departmentMetrics(
                manage || myDept == null ? allRows
                        : allRows.stream().filter(r -> deptOfSourceType(r.type()) == null
                                || deptOfSourceType(r.type()).equals(myDept)).toList());
        if (!manage && myDept != null) {
            byDept = Map.of(myDept, byDept.getOrDefault(myDept, Map.of()));
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("period", period);
        view.put("departments", byDept);
        List<FrPerfTaskDO> all = tasks.selectByPeriod(post.orgId(), period);
        view.put("taskStats", Map.of(
                "total", all.size(),
                "completed", all.stream()
                        .filter(t -> taskView(t, allRows).get("status").equals("已完成")).count()));
        return view;
    }

    // ===================== 上月初始化与归档链 =====================

    /** 按上月实绩生成建议任务（suggested 幂等键=period+userId+type；value=0 不建） */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> initializePrevious(PostSnapshot post, String period, String idempotencyKey) {
        requirePeriod(period);
        PerfSupport.requireManage(post); // P1-4
        requireNotArchived(post, period); // P1-11：归档期禁止初始化建议任务
        var replayed = commands.replayed(post, idempotencyKey, Map.of("period", period));
        if (replayed.isPresent()) {
            return Map.of("created", 0, "replayed", true);
        }
        String previous = PerfMetrics.previousPeriod(period);
        List<PerfMetrics.SourceRow> prevRows = metrics.sources(post, previous, List.of());
        // 上月在岗人员=上月有源归属的 user（简化：源行 userId 去重）
        java.util.Set<Long> people = new java.util.LinkedHashSet<>();
        for (PerfMetrics.SourceRow row : prevRows) {
            if (row.userId() != null) {
                people.add(row.userId());
            }
        }
        List<FrPerfTaskDO> existing = tasks.selectByPeriod(post.orgId(), period);
        int created = 0;
        for (Long userId : people) {
            for (String type : PerfMetrics.AUTO_TYPES) {
                long value = PerfMetrics.taskValue(prevRows, type, userId);
                boolean hasSource = prevRows.stream().anyMatch(r -> type.equals(r.type())
                        && userId.equals(r.userId()));
                if (value <= 0 || !hasSource) {
                    continue;
                }
                long target = "auto:collectionsAmount".equals(type) || "auto:contract".equals(type)
                        ? value : value; // 目标=上月实绩（金额类=分；数量类=个×100 存约定见 D-10）
                String dept = deptOfType(type);
                boolean exists = existing.stream().anyMatch(t ->
                        t.getAssigneeUserId().equals(userId) && t.getType().equals(type) && Boolean.TRUE.equals(t.getSuggested()));
                if (exists) {
                    continue;
                }
                Map<String, Object> suggestion = Map.of("period", previous, "value", value,
                        "unit", valueUnit(type));
                insertTask(post, new FrPerfTaskDO().setPeriod(period)
                        .setTitle(suggestTitle(type, previous)).setAssigneeUserId(userId)
                        .setAssigneeName("").setAssigneeDept(dept).setType(type)
                        .setTargetCents(target).setDue(LocalDate.parse(period + "-28"))
                        .setSuggested(true),
                        Map.of("action", "create", "suggestion", suggestion, "actorUserId", post.userId()));
                created++;
            }
        }
        // org 级聚合无行可 CAS——revision 取该聚合事件最大值+1（防 command_event 唯一键冲突）
        Long maxRev = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(revision),0) FROM pf_command_event WHERE aggregate_type = ? AND aggregate_id = ?",
                Long.class, AGGREGATE, post.orgId());
        commands.record(post, AGGREGATE, post.orgId(), (maxRev == null ? 0 : maxRev) + 1,
                "perf-task-initialize-previous",
                Map.of("period", period, "created", created), idempotencyKey);
        return Map.of("created", created, "replayed", false);
    }

    /** 归档：快照任务+sourceSignature（修订仅当签名变化——003） */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> archive(PostSnapshot post, String period, String note, boolean revise,
                                       String idempotencyKey) {
        requirePeriod(period);
        PerfSupport.requireManage(post); // P1-4
        var replayed = commands.replayed(post, idempotencyKey,
                Map.of("period", period, "revise", revise));
        if (replayed.isPresent()) {
            return archiveView(post, period);
        }
        List<FrPerfCycleDO> cycleRows = cycles.selectByPeriod(post.orgId(), period);
        if (revise) {
            List<FrPerfTaskArchiveDO> archives0 = archives.selectByPeriod(post.orgId(), period);
            if (archives0.isEmpty()) {
                throw exception(CYCLE_ACTION_INVALID, "本周期尚未归档，无需修订");
            }
        } else if (!archives.selectByPeriod(post.orgId(), period).isEmpty()) {
            throw exception(CYCLE_ACTION_INVALID, "本周期已归档（修订走 revise）");
        }
        // 有未决申诉阻断归档（F10）
        boolean pendingAppeal = cycleRows.stream().anyMatch(c ->
                JsonUtil.parseList(c.getAdjustments()).stream().anyMatch(a -> a.get("appealOpen") == Boolean.TRUE));
        if (!revise && pendingAppeal) {
            throw exception(CYCLE_ACTION_INVALID, "存在待复核申诉，阻断归档");
        }
        String signature = currentSignature(post, period);
        if (revise) {
            List<FrPerfTaskArchiveDO> existing = archives.selectByPeriod(post.orgId(), period);
            if (existing.isEmpty() || signature.equals(existing.get(0).getSourceSignature())) {
                throw exception(CYCLE_ACTION_INVALID, "原业务来源未变化，无需修订");
            }
        }
        List<Map<String, Object>> taskViews = new ArrayList<>();
        for (FrPerfTaskDO task : tasks.selectByPeriod(post.orgId(), period)) {
            taskViews.add(taskView(task, metrics.sources(post, period, adjustments(post, period))));
        }
        List<FrPerfTaskArchiveDO> existing = archives.selectByPeriod(post.orgId(), period);
        int nextNo = existing.isEmpty() ? 1 : existing.get(0).getArchiveNo() + 1;
        FrPerfTaskArchiveDO archive = new FrPerfTaskArchiveDO()
                .setCompanyId(post.orgId()).setPeriod(period).setArchiveNo(nextNo)
                .setSupersedes(existing.isEmpty() ? null : existing.get(0).getId())
                .setSourceSignature(signature).setNote(StrUtil.nullToEmpty(note))
                .setAuthorUserId(post.userId());
        long archiveId = cn.hutool.core.util.IdUtil.getSnowflakeNextId();
        jdbcTemplate.update("INSERT INTO fr_perf_task_archive (id, company_id, period, archive_no, supersedes, "
                        + "tasks, source_signature, status, note, author_user_id, create_time) "
                        + "VALUES (?,?,?,?,?,CAST(? AS jsonb),?,?,?,?,now())",
                archiveId, post.orgId(), period, nextNo, archive.getSupersedes(),
                cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(taskViews),
                signature, "ARCHIVED", archive.getNote(), post.userId());
        archive.setId(archiveId);
        commands.record(post, AGGREGATE, archive.getId(), nextNo,
                revise ? "perf-task-archive-revise" : "perf-task-archive",
                Map.of("period", period, "archiveNo", nextNo), idempotencyKey);
        return archiveView(post, period);
    }

    /** 归档签名统一基准（P1-11）：业务源行 + 任务定义集——归档/修订/needsRevision 同源可比 */
    private String currentSignature(PostSnapshot post, String period) {
        List<PerfMetrics.SourceRow> sourceRows = metrics.sources(post, period, adjustments(post, period));
        List<String> taskParts = tasks.selectByPeriod(post.orgId(), period).stream()
                .sorted(java.util.Comparator.comparing(FrPerfTaskDO::getId))
                .map(t -> "task:" + t.getId() + ":" + t.getType() + ":" + t.getTargetCents()
                        + ":" + t.getAssigneeUserId()).toList();
        return PerfSupport.signature(java.util.stream.Stream.concat(
                java.util.stream.Stream.of(PerfMetrics.sourceSignature(sourceRows)), taskParts.stream()).toList());
    }

    public Map<String, Object> archiveView(PostSnapshot post, String period) {
        List<FrPerfTaskArchiveDO> rows0 = archives.selectByPeriod(post.orgId(), period);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("period", period);
        view.put("archives", rows0.stream().map(a -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", String.valueOf(a.getId()));
            row.put("archiveNo", a.getArchiveNo());
            row.put("supersedes", a.getSupersedes() == null ? null : String.valueOf(a.getSupersedes()));
            row.put("tasks", a.getTasks());
            row.put("sourceSignature", a.getSourceSignature());
            row.put("note", a.getNote());
            row.put("createTime", a.getCreateTime());
            return row;
        }).toList());
        view.put("needsRevision", !rows0.isEmpty()
                && !currentSignature(post, period).equals(rows0.get(0).getSourceSignature()));
        return view;
    }

    // ===================== 内部 =====================

    private List<PerfMetrics.SourceRow> rows(PostSnapshot post, String period) {
        return metrics.sources(post, period, adjustments(post, period));
    }

    private List<PerfMetrics.Adjustment> adjustments(PostSnapshot post, String period) {
        List<PerfMetrics.Adjustment> all = new ArrayList<>();
        for (FrPerfCycleDO cycle : cycles.selectByPeriod(post.orgId(), period)) {
            for (Map<String, Object> row : JsonUtil.parseList(cycle.getAdjustments())) {
                Object key = row.get("sourceKey");
                if (key == null) {
                    continue;
                }
                Long person = row.get("personUserId") instanceof Number n ? n.longValue() : null;
                all.add(new PerfMetrics.Adjustment(String.valueOf(key), person,
                        Boolean.TRUE.equals(row.get("excluded"))));
            }
        }
        return all;
    }

    private FrPerfTaskDO requireTask(PostSnapshot post, long taskId) {
        FrPerfTaskDO task = tasks.selectById(taskId);
        if (task == null || task.getCompanyId() != post.orgId()) {
            throw exception(PERF_INPUT_INVALID, "任务不存在");
        }
        return task;
    }

    private void requireNotArchived(PostSnapshot post, String period) {
        if (!archives.selectByPeriod(post.orgId(), period).isEmpty()) {
            throw exception(PERF_TASK_INVALID, "周期已归档，任务锁定");
        }
    }

    /** P1-5：本组织在岗成员（SystemDirectory 真源），姓名服务端快照 */
    private cn.iocoder.yudao.module.platform.org.SystemDirectory.UserBrief requireActiveMember(
            PostSnapshot post, long userId) {
        var briefs = systemDirectory.usersByIds(java.util.List.of(userId));
        var brief = briefs == null || briefs.isEmpty() ? null : briefs.get(0);
        if (brief == null || !brief.active()) {
            throw exception(PERF_INPUT_INVALID, "责任人不存在或已停用，请重新选择");
        }
        return brief;
    }

    /** 角色→组（summary 裁剪用） */
    private static final Map<String, String> DEPT_ROLE_OF = Map.of(
            "sales", "business", "sales-lead", "business", "hq-operations", "business",
            "hq-operations-lead", "business", "hq-design-admin", "business",
            "engineer", "engineering", "project-manager", "engineering",
            "finance", "finance", "finance-lead", "finance");

    private static String deptOfSourceType(String type) {
        return switch (type) {
            case "auto:collectionsAmount", "auto:contract", "auto:publications", "auto:effectiveLeads",
                 "metric:commissionAmount", "metric:effectiveCustomers", "auto:approvedDeliverables" -> "business";
            case "auto:completedNodes", "auto:onTimeNodes", "auto:qualifiedDeliveries" -> "engineering";
            case "auto:financeRegistrations" -> "finance";
            default -> null;
        };
    }

    private static void requirePeriod(String period) {
        if (!PerfMetrics.validPeriod(period)) {
            throw exception(PERF_PERIOD_INVALID, "period 须为 YYYY-MM");
        }
    }

    private static boolean dueInPeriod(LocalDate due, String period) {
        return String.format("%04d-%02d", due.getYear(), due.getMonthValue()).equals(period);
    }

    private static String deptOfType(String type) {
        return switch (type) {
            case "auto:collectionsAmount", "auto:contract", "auto:publications", "auto:effectiveLeads",
                 "auto:approvedDeliverables" -> "business";
            case "auto:completedNodes", "auto:onTimeNodes", "auto:qualifiedDeliveries" -> "engineering";
            case "auto:financeRegistrations" -> "finance";
            default -> "business";
        };
    }

    private static String valueUnit(String type) {
        return type.endsWith("Amount") || type.equals("auto:contract") ? "分" : "个";
    }

    private static String suggestTitle(String type, String previous) {
        String label = switch (type) {
            case "auto:collectionsAmount" -> "回款金额";
            case "auto:contract" -> "签约金额";
            case "auto:completedNodes" -> "完成节点";
            case "auto:onTimeNodes" -> "按期节点";
            case "auto:publications" -> "内容发布";
            case "auto:effectiveLeads" -> "有效线索";
            case "auto:qualifiedDeliveries" -> "合格到货批次";
            case "auto:approvedDeliverables" -> "审核通过成果";
            case "auto:financeRegistrations" -> "收付登记";
            default -> type;
        };
        return "上月实绩参考 · " + label + "（" + previous + "）";
    }

    private static Map<String, Object> payload(TaskCommand cmd) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("period", cmd.period());
        m.put("title", cmd.title());
        m.put("assigneeUserId", cmd.assigneeUserId());
        m.put("type", cmd.type());
        m.put("targetCents", cmd.targetCents());
        m.put("due", cmd.due() == null ? null : cmd.due().toString());
        return m;
    }

    private Map<String, Object> detail(PostSnapshot post, String taskId, List<PerfMetrics.SourceRow> sourceRows) {
        var taskRow = tasks.selectById(Long.parseLong(taskId));
        if (taskRow == null) {
            return Map.of("id", taskId);
        }
        if (sourceRows == null) {
            sourceRows = rows(post, taskRow.getPeriod());
        }
        return taskView(taskRow, sourceRows);
    }
}
