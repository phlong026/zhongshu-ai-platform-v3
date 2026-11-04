package cn.iocoder.yudao.module.franchise.performance.content;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.franchise.api.PerformancePayablePort;
import cn.iocoder.yudao.module.franchise.performance.dal.FrPerfCommissionPolicyDO;
import cn.iocoder.yudao.module.franchise.performance.dal.FrPerfCommissionPolicyMapper;
import cn.iocoder.yudao.module.franchise.performance.dal.FrPerfCommissionReleaseDO;
import cn.iocoder.yudao.module.franchise.performance.dal.FrPerfCommissionReleaseMapper;
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
 * 回款提成（3.9 / D-6）：policy（严格 boss；对半下取整两列；**已有 release 后整行锁定 004**，
 * 无 release 修改=version+1）→ release（finance 族；净实收足额 007；经 PayablePort 生成 finance 应付，
 * agreementRef=perf-commission:{policyId}:v{version} 兜底标识）→ reconcile（严格 boss；收款变化人工核对）。
 * 净实收（F14）：required=fr_eng_collection_term 该 stage 约定；received=fr_eng_collection 该 stage
 * 合计−fr_fin_refund（关联已核实）；对账状态机五态（F15）。
 */
@Service
public class PerfCommissionService {

    public static final String AGGREGATE = "fr-perf-commission";

    private final FrPerfCommissionPolicyMapper policies;
    private final FrPerfCommissionReleaseMapper releases;
    private final PerformancePayablePort payables;
    private final FranchiseCommands commands;
    private final JdbcTemplate jdbc;
    private final cn.iocoder.yudao.module.platform.org.SystemDirectory systemDirectory;

    public PerfCommissionService(FrPerfCommissionPolicyMapper policies,
                                 FrPerfCommissionReleaseMapper releases,
                                 PerformancePayablePort payables, FranchiseCommands commands,
                                 JdbcTemplate jdbc,
                                 cn.iocoder.yudao.module.platform.org.SystemDirectory systemDirectory) {
        this.policies = policies;
        this.releases = releases;
        this.payables = payables;
        this.commands = commands;
        this.jdbc = jdbc;
        this.systemDirectory = systemDirectory;
    }

    // ===================== 策略（严格 boss） =====================

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> policy(PostSnapshot post, long projectId, long totalCents, long baseDoneCents,
                                      long assigneeUserId,
                                      String assigneeName, String evidenceRef, String note,
                                      String idempotencyKey) {
        if (!"boss".equals(post.roleCode())) {
            throw exception(PERFORMANCE_FORBIDDEN); // 严格 boss（owner 可看不可改）
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectId", projectId);
        payload.put("totalCents", totalCents);
        payload.put("baseDoneCents", baseDoneCents);
        payload.put("assigneeUserId", assigneeUserId);
        payload.put("assigneeName", StrUtil.nullToEmpty(assigneeName));
        payload.put("evidenceRef", StrUtil.nullToEmpty(evidenceRef));
        payload.put("note", StrUtil.nullToEmpty(note));
        var replayed = commands.replayed(post, idempotencyKey, payload);
        if (replayed.isPresent()) {
            return row(post, projectId);
        }
        if (totalCents <= 0 || totalCents > 10_000_000_000L || assigneeUserId <= 0
                || StrUtil.isBlank(evidenceRef) || StrUtil.isBlank(note)) {
            throw exception(PERF_INPUT_INVALID, "请填写提成总额、在岗归属人、凭据与说明");
        }
        FrPerfCommissionPolicyDO current = policies.selectByProject(post.orgId(), projectId);
        // R2 P2-17：policy 行锁前置（硬锁检查与 release 竞争窗口互斥——先锁再查 release）
        if (current != null) {
            jdbc.queryForMap("SELECT id FROM fr_perf_commission_policy WHERE id = ? FOR UPDATE", current.getId());
            current = policies.selectById(current.getId()); // 锁内重读
            if (!releases.selectByPolicy(post.orgId(), current.getId()).isEmpty()) {
                throw exception(COMMISSION_POLICY_LOCKED); // 硬锁（蓝本 :77）
            }
        }
        // P1-5：归属人须本组织在岗（职务真源+姓名服务端快照——客户端名不进 finance recipient）
        var member = requireActiveMember(post, assigneeUserId);
        // P1-5：项目须归属当前组织
        java.util.List<Long> projectOrgs = jdbc.queryForList(
                "SELECT company_id FROM fr_eng_project WHERE id = ?", Long.class, projectId);
        if (projectOrgs.isEmpty() || projectOrgs.get(0) == null || projectOrgs.get(0) != post.orgId()) {
            throw exception(PERF_INPUT_INVALID, "工程不存在或不属于当前组织"); // R2 P2-7：零行稳定返回 001
        }
        // M10-T07：三期拆分（签约后/基础完成后/封顶后；baseDone≥0，Σ三期=total；不发明固定比例）
        if (baseDoneCents < 0 || baseDoneCents > totalCents) {
            throw exception(PERF_INPUT_INVALID, "基础完成后期提成金额须为不小于 0 且不超过总额的数字");
        }
        long start = 0;
        long topping = 0;
        long baseDone = baseDoneCents;
        // 调用方未指定签约后/封顶后拆分时：总额-基础完成后在签约后/封顶后间保持原二分（存量兼容）；
        // baseDone=0 时行为与存量一致
        long rest = totalCents - baseDone;
        start = rest / 2;
        topping = rest - start;
        if (start + baseDone + topping != totalCents) {
            throw exception(PERF_INPUT_INVALID, "三期提成之和必须等于提成总额");
        }
        long policyRowId;
        if (current == null) {
            policyRowId = cn.hutool.core.util.IdUtil.getSnowflakeNextId();
            jdbc.update("INSERT INTO fr_perf_commission_policy (id, company_id, project_id, total_cents, "
                    + "assignee_user_id, assignee_name, start_cents, topping_cents, version, "
                    + "evidence_ref, note, author_user_id, create_time, update_time) "
                    + "VALUES (?,?,?,?,?,?,?,?,1,?,?,?,now(),now())",
                    policyRowId, post.orgId(), projectId, totalCents, assigneeUserId,
                    member.name(), start, topping,
                    StrUtil.trim(evidenceRef), StrUtil.trim(note), post.userId());
        } else {
            String history = JsonUtil.append(current.getHistory(), Map.of(
                    "version", current.getVersion(), "totalCents", current.getTotalCents(),
                    "assigneeUserId", current.getAssigneeUserId(),
                    "at", String.valueOf(java.time.OffsetDateTime.now())));
            policies.updateById(new FrPerfCommissionPolicyDO().setId(current.getId())
                    .setTotalCents(totalCents).setAssigneeUserId(assigneeUserId)
                    .setAssigneeName(member.name())
                    .setStartCents(start).setToppingCents(topping)
                    .setVersion(current.getVersion() + 1)
                    .setEvidenceRef(StrUtil.trim(evidenceRef)).setNote(StrUtil.trim(note)));
            JsonUtil.updateJsonb(jdbc, "fr_perf_commission_policy", Map.of("history", history),
                    current.getId(), post.orgId());
            policyRowId = current.getId();
        }
        // P1-1：record revision=实际落库版本（新建=1、升版=version+1，与重放路径同源）
        FrPerfCommissionPolicyDO saved = policies.selectByProject(post.orgId(), projectId);
        commands.record(post, AGGREGATE, policyRowId,
                saved == null ? 1 : saved.getVersion(), "perf-commission-policy", payload, idempotencyKey);
        return row(post, projectId);
    }

    // ===================== 生成应付（finance 族） =====================

    /** M10-T07：三期归一（SIGN_AFTER/BASE_DONE/CAP_DONE 精确匹配；存量 START/TOPPING 显式映射；未知拒绝） */
    private static String requireStage(String stage) {
        return switch (stage == null ? "" : stage) {
            case FrPerfCommissionReleaseDO.STAGE_SIGN_AFTER -> FrPerfCommissionReleaseDO.STAGE_SIGN_AFTER;
            case FrPerfCommissionReleaseDO.STAGE_BASE_DONE -> FrPerfCommissionReleaseDO.STAGE_BASE_DONE;
            case FrPerfCommissionReleaseDO.STAGE_CAP_DONE -> FrPerfCommissionReleaseDO.STAGE_CAP_DONE;
            case FrPerfCommissionReleaseDO.STAGE_START -> FrPerfCommissionReleaseDO.STAGE_SIGN_AFTER; // 存量显式映射
            case FrPerfCommissionReleaseDO.STAGE_TOPPING -> FrPerfCommissionReleaseDO.STAGE_CAP_DONE; // 存量显式映射
            default -> throw exception(PERF_INPUT_INVALID, "stage 须为 签约后/基础完成后/封顶后（或存量 START/TOPPING）");
        };
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> release(PostSnapshot post, long projectId, String stage, String idempotencyKey) {
        PerfSupport.requireFinance(post); // P1-4：服务层 finance 族校验
        String stageKey = requireStage(stage);
        Map<String, Object> payload = Map.of("projectId", projectId, "stage", stageKey);
        var replayed = commands.replayed(post, idempotencyKey, payload);
        if (replayed.isPresent()) {
            return row(post, projectId);
        }
        FrPerfCommissionPolicyDO policy = requirePolicy(post, projectId);
        FrPerfCommissionReleaseDO existing = releases.selectByProjectAndStage(post.orgId(), projectId, stageKey);
        if (existing != null) {
            return row(post, projectId); // 幂等：已有应付直接返回
        }
        StageFacts facts = stageFacts(post, projectId, stageKey);
        if (!facts.eligible) {
            throw exception(COMMISSION_NOT_ELIGIBLE,
                    stageLabel(stageKey));
        }
        // M10-T07：三期提成金额按 release stage 取对应期约定（BASE_DONE=新增期；其余沿用存量两列）
        long amountCents = FrPerfCommissionReleaseDO.STAGE_BASE_DONE.equals(stageKey)
                ? policy.getBaseDoneCents()
                : (FrPerfCommissionReleaseDO.STAGE_SIGN_AFTER.equals(stageKey)
                        || FrPerfCommissionReleaseDO.STAGE_START.equals(stageKey)
                        ? policy.getStartCents() : policy.getToppingCents());
        if (amountCents <= 0) {
            throw exception(COMMISSION_NOT_ELIGIBLE, "该期提成未约定（0=未约定），不能生成应付");
        }
        String sourceRef = projectId + ":" + stageKey.toLowerCase();
        // P2-7 兜底标识：财务侧可识别提成应付+规则版本
        long payableId = payables.registerPayable(post, new PerformancePayablePort.PayableCommand(
                projectId, sourceRef, policy.getAssigneeName(), amountCents,
                "perf-commission:" + policy.getId() + ":v" + policy.getVersion(),
                stageLabel(stageKey) + "足额到账后提成"));
        long relId = cn.hutool.core.util.IdUtil.getSnowflakeNextId();
        jdbc.update("INSERT INTO fr_perf_commission_release (id, company_id, policy_id, project_id, stage, "
                        + "payable_id, policy_version, receipt_signature, receipt_ids, release_user_id, "
                        + "release_at, create_time, update_time) VALUES (?,?,?,?,?,?,?,?,CAST(? AS jsonb),?,?,now(),now())",
                relId, post.orgId(), policy.getId(), projectId, stageKey, payableId, policy.getVersion(),
                facts.signature, cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(facts.receiptIds),
                post.userId(), java.time.OffsetDateTime.now());
        commands.record(post, AGGREGATE, relId,
                PerfSupport.nextAggregateRevision(jdbc, AGGREGATE, relId), "perf-commission-release", payload, idempotencyKey);
        return row(post, projectId);
    }

    // ===================== 人工核对（严格 boss） =====================

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> reconcile(PostSnapshot post, long projectId, String stage,
                                         String evidenceRef, String note, String idempotencyKey) {
        if (!"boss".equals(post.roleCode())) {
            throw exception(PERFORMANCE_FORBIDDEN);
        }
        String stageKey = requireStage(stage); // R2 P2-19：reconcile 同样严格校验
        var replayed = commands.replayed(post, idempotencyKey, Map.of("projectId", projectId, "stage", stageKey,
                "evidenceRef", StrUtil.nullToEmpty(evidenceRef), "note", StrUtil.nullToEmpty(note)));
        if (replayed.isPresent()) {
            return row(post, projectId);
        }
        requirePolicy(post, projectId);
        // R2 P2-17：release 行锁——并发核对在锁内重读，JSON 数组追加不丢失
        jdbc.queryForMap("SELECT id FROM fr_perf_commission_release WHERE project_id = ? AND stage = ? "
                + "AND company_id = ? FOR UPDATE", projectId, stageKey, post.orgId());
        FrPerfCommissionReleaseDO rel = releases.selectByProjectAndStage(post.orgId(), projectId, stageKey);
        if (rel == null) {
            throw exception(COMMISSION_RELEASE_INVALID);
        }
        StageFacts facts = stageFacts(post, projectId, stageKey);
        if (!facts.eligible) {
            throw exception(COMMISSION_NOT_ELIGIBLE,
                    "退款、冲正或更正后净实收仍未足额，" + ("START".equals(stageKey) ? "开工款" : "封顶款"));
        }
        if (StrUtil.isBlank(evidenceRef) || StrUtil.isBlank(note)) {
            throw exception(PERF_INPUT_INVALID, "请上传人工核对依据并填写说明");
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("previousReceiptSignature", rel.getReceiptSignature());
        record.put("receiptSignature", facts.signature);
        record.put("evidenceRef", StrUtil.trim(evidenceRef));
        record.put("note", StrUtil.trim(note));
        record.put("actorUserId", post.userId());
        record.put("at", String.valueOf(java.time.OffsetDateTime.now()));
        JsonUtil.updateJsonb(jdbc, "fr_perf_commission_release", Map.of(
                "receipt_ids", cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(facts.receiptIds),
                "reconciliations", JsonUtil.append(rel.getReconciliations(), record)),
                rel.getId(), post.orgId());
        jdbc.update("UPDATE fr_perf_commission_release SET receipt_signature = ? WHERE id = ? AND company_id = ?",
                facts.signature, rel.getId(), post.orgId());
        commands.record(post, AGGREGATE, rel.getId(),
                PerfSupport.nextAggregateRevision(jdbc, AGGREGATE, rel.getId()), "perf-commission-reconcile",
                Map.of("projectId", projectId, "stage", stageKey,
                        "evidenceRef", StrUtil.nullToEmpty(evidenceRef), "note", StrUtil.nullToEmpty(note)),
                idempotencyKey);
        return row(post, projectId);
    }

    // ===================== 净实收与对账视图 =====================

    record StageFacts(String stage, long required, long received, boolean eligible,
                      String signature, List<Long> receiptIds) {
    }

    /** F14：required=约定；received=fr_eng_collection 该期合计−关联已核实退款 */
    StageFacts stageFacts(PostSnapshot post, long projectId, String stage) {
        // M10-T07：三期直通（stageKey 已归一为三枚举；存量 START/TOPPING 在 requireStage 已映射）；
        // 工程域迁移 818 已将 fr_eng_collection_term.stage 映射为同枚举
        String engStage = switch (stage) {
            case FrPerfCommissionReleaseDO.STAGE_BASE_DONE -> "BASE_DONE";
            case FrPerfCommissionReleaseDO.STAGE_CAP_DONE -> "CAP_DONE";
            default -> "SIGN_AFTER";
        };
        List<Long> termRows = jdbc.queryForList(
                "SELECT amount_cents FROM fr_eng_collection_term WHERE company_id = ? AND project_id = ? AND stage = ?",
                Long.class, post.orgId(), projectId, engStage);
        long required = termRows.isEmpty() ? 0 : termRows.get(0);
        List<Map<String, Object>> collectionRows = jdbc.queryForList(
                "SELECT id, amount_cents, receipt_id FROM fr_eng_collection "
                        + "WHERE company_id = ? AND project_id = ? AND stage = ?", post.orgId(), projectId, engStage);
        long received = 0;
        List<Long> receiptIds = new ArrayList<>();
        List<String> signatureParts = new ArrayList<>();
        signatureParts.add("required:" + required);
        for (Map<String, Object> row : collectionRows) {
            long amount = ((Number) row.get("amount_cents")).longValue();
            long receiptId = row.get("receipt_id") == null ? 0 : ((Number) row.get("receipt_id")).longValue();
            long refunded = 0;
            if (receiptId > 0) {
                refunded = jdbc.queryForObject(
                        "SELECT COALESCE(SUM(amount_cents), 0) FROM fr_fin_refund "
                                + "WHERE receipt_id = ? AND status = '已核实' AND replaced_by_id IS NULL",
                        Long.class, receiptId);
            }
            received += Math.max(0, amount - refunded);
            if (receiptId > 0) {
                receiptIds.add(receiptId);
            }
            signatureParts.add("c:" + row.get("id") + ":" + amount + ":" + refunded);
        }
        boolean eligible = required > 0 && received >= required;
        return new StageFacts(stage, required, received, eligible,
                String.join("|", signatureParts), receiptIds);
    }

    /** 对账行（F15 五态状态机+paid/outstanding；paid 读 finance 已核实付款） */
    public List<Map<String, Object>> rows(PostSnapshot post) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (FrPerfCommissionPolicyDO policy : policies.selectAll(post.orgId())) {
            out.add(row(post, policy.getProjectId()));
        }
        return out;
    }

    Map<String, Object> row(PostSnapshot post, long projectId) {
        FrPerfCommissionPolicyDO policy = policies.selectByProject(post.orgId(), projectId);
        if (policy == null) {
            return Map.of("projectId", String.valueOf(projectId), "exists", false);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("projectId", String.valueOf(projectId));
        view.put("exists", true);
        view.put("policyId", String.valueOf(policy.getId()));
        view.put("totalCents", policy.getTotalCents());
        view.put("assigneeUserId", policy.getAssigneeUserId());
        view.put("assigneeName", policy.getAssigneeName());
        view.put("version", policy.getVersion());
        List<Map<String, Object>> stages = new ArrayList<>();
        for (String stage : List.of(FrPerfCommissionReleaseDO.STAGE_SIGN_AFTER, FrPerfCommissionReleaseDO.STAGE_BASE_DONE,
                FrPerfCommissionReleaseDO.STAGE_CAP_DONE)) {
            StageFacts facts = stageFacts(post, projectId, stage);
            FrPerfCommissionReleaseDO rel = releases.selectByProjectAndStage(post.orgId(), projectId, stage);
            long amount = FrPerfCommissionReleaseDO.STAGE_BASE_DONE.equals(stage)
                    ? policy.getBaseDoneCents()
                    : (FrPerfCommissionReleaseDO.STAGE_SIGN_AFTER.equals(stage)
                            ? policy.getStartCents() : policy.getToppingCents());
            long paid = 0;
            if (rel != null && rel.getPayableId() != null) {
                paid = jdbc.queryForObject(
                        "SELECT COALESCE(SUM(amount_cents), 0) FROM fr_fin_payment "
                                + "WHERE payable_id = ? AND status = '已核实'", Long.class, rel.getPayableId());
            }
            boolean reviewDue = rel != null && (!facts.eligible
                    || !facts.signature.equals(rel.getReceiptSignature())
                    || !java.util.Objects.equals(rel.getPolicyVersion(), policy.getVersion()));
            String status;
            if (reviewDue) {
                status = "待人工核对";
            } else if (paid >= amount) {
                status = "已付清";
            } else if (rel != null) {
                status = "待付款";
            } else if (facts.eligible) {
                status = "可生成应付";
            } else {
                status = "待足额到账";
            }
            Map<String, Object> stageView = new LinkedHashMap<>();
            stageView.put("stage", stage);
            stageView.put("label", stageLabel(stage));
            stageView.put("amountCents", amount);
            stageView.put("requiredCents", facts.required);
            stageView.put("receivedCents", facts.received);
            stageView.put("eligible", facts.eligible);
            stageView.put("payableId", rel == null || rel.getPayableId() == null ? null : String.valueOf(rel.getPayableId()));
            stageView.put("paidCents", paid);
            stageView.put("outstandingCents", Math.max(0, amount - paid));
            stageView.put("reviewDue", reviewDue);
            stageView.put("reconciliations", rel == null ? null : rel.getReconciliations());
            stageView.put("status", status);
            stages.add(stageView);
        }
        view.put("stages", stages);
        return view;
    }

    /** P1-5：归属人本组织在岗校验（SystemDirectory 真源） */
    private cn.iocoder.yudao.module.platform.org.SystemDirectory.UserBrief requireActiveMember(
            PostSnapshot post, long userId) {
        var briefs = systemDirectory.usersByIds(java.util.List.of(userId));
        var brief = briefs == null || briefs.isEmpty() ? null : briefs.get(0);
        if (brief == null || !brief.active()) {
            throw exception(PERF_INPUT_INVALID, "提成归属人不存在或已停用，请重新选择");
        }
        return brief;
    }

    private FrPerfCommissionPolicyDO requirePolicy(PostSnapshot post, long projectId) {
        FrPerfCommissionPolicyDO policy = policies.selectByProject(post.orgId(), projectId);
        if (policy == null) {
            throw exception(PERF_INPUT_INVALID, "请先由老板确认本项目提成总额及归属人");
        }
        return policy;
    }
    /** M10-T07 三期显示文案 */
    static String stageLabel(String stage) {
        return switch (stage) {
            case FrPerfCommissionReleaseDO.STAGE_SIGN_AFTER -> "签约后期提成";
            case FrPerfCommissionReleaseDO.STAGE_BASE_DONE -> "基础完成后期提成";
            case FrPerfCommissionReleaseDO.STAGE_CAP_DONE -> "封顶后期提成";
            case FrPerfCommissionReleaseDO.STAGE_START -> "开工款";
            case FrPerfCommissionReleaseDO.STAGE_TOPPING -> "封顶款";
            default -> stage;
        };
    }

}
