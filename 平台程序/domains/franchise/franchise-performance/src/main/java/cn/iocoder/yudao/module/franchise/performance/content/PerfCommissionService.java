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

    public PerfCommissionService(FrPerfCommissionPolicyMapper policies,
                                 FrPerfCommissionReleaseMapper releases,
                                 PerformancePayablePort payables, FranchiseCommands commands,
                                 JdbcTemplate jdbc) {
        this.policies = policies;
        this.releases = releases;
        this.payables = payables;
        this.commands = commands;
        this.jdbc = jdbc;
    }

    // ===================== 策略（严格 boss） =====================

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> policy(PostSnapshot post, long projectId, long totalCents, long assigneeUserId,
                                      String assigneeName, String evidenceRef, String note,
                                      String idempotencyKey) {
        if (!"boss".equals(post.roleCode())) {
            throw exception(PERFORMANCE_FORBIDDEN); // 严格 boss（owner 可看不可改）
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectId", projectId);
        payload.put("totalCents", totalCents);
        payload.put("assigneeUserId", assigneeUserId);
        var replayed = commands.replayed(post, idempotencyKey, payload);
        if (replayed.isPresent()) {
            return row(post, projectId);
        }
        if (totalCents <= 0 || totalCents > 10_000_000_000L || assigneeUserId <= 0
                || StrUtil.isBlank(evidenceRef) || StrUtil.isBlank(note)) {
            throw exception(PERF_INPUT_INVALID, "请填写提成总额、在岗归属人、凭据与说明");
        }
        FrPerfCommissionPolicyDO current = policies.selectByProject(post.orgId(), projectId);
        if (current != null && !releases.selectByPolicy(post.orgId(), current.getId()).isEmpty()) {
            throw exception(COMMISSION_POLICY_LOCKED); // 硬锁（蓝本 :77）
        }
        long start = totalCents / 2;
        long topping = totalCents - start;
        if (current == null) {
            policies.insert(new FrPerfCommissionPolicyDO().setCompanyId(post.orgId())
                    .setProjectId(projectId).setTotalCents(totalCents)
                    .setAssigneeUserId(assigneeUserId).setAssigneeName(StrUtil.blankToDefault(assigneeName, ""))
                    .setStartCents(start).setToppingCents(topping)
                    .setVersion(1).setEvidenceRef(StrUtil.trim(evidenceRef)).setNote(StrUtil.trim(note))
                    .setAuthorUserId(post.userId()));
        } else {
            String history = JsonUtil.append(current.getHistory(), Map.of(
                    "version", current.getVersion(), "totalCents", current.getTotalCents(),
                    "assigneeUserId", current.getAssigneeUserId(),
                    "at", String.valueOf(java.time.OffsetDateTime.now())));
            policies.updateById(new FrPerfCommissionPolicyDO().setId(current.getId())
                    .setTotalCents(totalCents).setAssigneeUserId(assigneeUserId)
                    .setAssigneeName(StrUtil.blankToDefault(assigneeName, ""))
                    .setStartCents(start).setToppingCents(topping)
                    .setVersion(current.getVersion() + 1).setHistory(history)
                    .setEvidenceRef(StrUtil.trim(evidenceRef)).setNote(StrUtil.trim(note)));
        }
        commands.record(post, AGGREGATE, policies.selectByProject(post.orgId(), projectId).getId(),
                1, "perf-commission-policy", payload, idempotencyKey);
        return row(post, projectId);
    }

    // ===================== 生成应付（finance 族） =====================

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> release(PostSnapshot post, long projectId, String stage, String idempotencyKey) {
        if (!List.of("finance", "finance-lead").contains(post.roleCode())) {
            throw exception(PERFORMANCE_FORBIDDEN); // release=finance 族
        }
        String stageKey = "TOPPING".equals(stage) ? FrPerfCommissionReleaseDO.STAGE_TOPPING
                : FrPerfCommissionReleaseDO.STAGE_START;
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
                    ("START".equals(stageKey) ? "开工款" : "封顶款"));
        }
        long amountCents = FrPerfCommissionReleaseDO.STAGE_START.equals(stageKey)
                ? policy.getStartCents() : policy.getToppingCents();
        String sourceRef = projectId + ":" + stageKey.toLowerCase();
        // P2-7 兜底标识：财务侧可识别提成应付+规则版本
        long payableId = payables.registerPayable(post, new PerformancePayablePort.PayableCommand(
                projectId, sourceRef, policy.getAssigneeName(), amountCents,
                "perf-commission:" + policy.getId() + ":v" + policy.getVersion(),
                ("START".equals(stageKey) ? "开工款" : "封顶款") + "足额到账后提成"));
        FrPerfCommissionReleaseDO rel = new FrPerfCommissionReleaseDO()
                .setCompanyId(post.orgId()).setPolicyId(policy.getId()).setProjectId(projectId)
                .setStage(stageKey).setPayableId(payableId).setPolicyVersion(policy.getVersion())
                .setReceiptSignature(facts.signature)
                .setReceiptIds(cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(facts.receiptIds))
                .setReleaseUserId(post.userId()).setReleaseAt(java.time.OffsetDateTime.now());
        releases.insert(rel);
        commands.record(post, AGGREGATE, rel.getId(), 1, "perf-commission-release", payload, idempotencyKey);
        return row(post, projectId);
    }

    // ===================== 人工核对（严格 boss） =====================

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> reconcile(PostSnapshot post, long projectId, String stage,
                                         String evidenceRef, String note, String idempotencyKey) {
        if (!"boss".equals(post.roleCode())) {
            throw exception(PERFORMANCE_FORBIDDEN);
        }
        String stageKey = "TOPPING".equals(stage) ? FrPerfCommissionReleaseDO.STAGE_TOPPING
                : FrPerfCommissionReleaseDO.STAGE_START;
        var replayed = commands.replayed(post, idempotencyKey, Map.of("projectId", projectId, "stage", stageKey));
        if (replayed.isPresent()) {
            return row(post, projectId);
        }
        requirePolicy(post, projectId);
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
        String reconciliations = JsonUtil.append(rel.getReconciliations(), record);
        releases.updateById(new FrPerfCommissionReleaseDO().setId(rel.getId())
                .setReceiptSignature(facts.signature)
                .setReceiptIds(cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(facts.receiptIds))
                .setReconciliations(reconciliations));
        commands.record(post, AGGREGATE, rel.getId(), 1, "perf-commission-reconcile",
                Map.of("projectId", projectId, "stage", stageKey), idempotencyKey);
        return row(post, projectId);
    }

    // ===================== 净实收与对账视图 =====================

    record StageFacts(String stage, long required, long received, boolean eligible,
                      String signature, List<Long> receiptIds) {
    }

    /** F14：required=约定；received=fr_eng_collection 该期合计−关联已核实退款 */
    StageFacts stageFacts(PostSnapshot post, long projectId, String stage) {
        String engStage = FrPerfCommissionReleaseDO.STAGE_START.equals(stage) ? "START" : "TOPPING";
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
        for (String stage : List.of(FrPerfCommissionReleaseDO.STAGE_START, FrPerfCommissionReleaseDO.STAGE_TOPPING)) {
            StageFacts facts = stageFacts(post, projectId, stage);
            FrPerfCommissionReleaseDO rel = releases.selectByProjectAndStage(post.orgId(), projectId, stage);
            long amount = FrPerfCommissionReleaseDO.STAGE_START.equals(stage)
                    ? policy.getStartCents() : policy.getToppingCents();
            long paid = 0;
            if (rel != null && rel.getPayableId() != null) {
                paid = jdbc.queryForObject(
                        "SELECT COALESCE(SUM(amount_cents), 0) FROM fr_fin_payment "
                                + "WHERE payable_id = ? AND status = '已核实'", Long.class, rel.getPayableId());
            }
            boolean reviewDue = rel != null && (!facts.eligible
                    || !facts.signature.equals(rel.getReceiptSignature())
                    || rel.getPolicyVersion() != policy.getVersion());
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
            stageView.put("label", FrPerfCommissionReleaseDO.STAGE_START.equals(stage) ? "开工款" : "封顶款");
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

    private FrPerfCommissionPolicyDO requirePolicy(PostSnapshot post, long projectId) {
        FrPerfCommissionPolicyDO policy = policies.selectByProject(post.orgId(), projectId);
        if (policy == null) {
            throw exception(PERF_INPUT_INVALID, "请先由老板确认本项目提成总额及归属人");
        }
        return policy;
    }
}
