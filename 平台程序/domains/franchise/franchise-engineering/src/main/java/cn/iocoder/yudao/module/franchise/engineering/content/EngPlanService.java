package cn.iocoder.yudao.module.franchise.engineering.content;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngCollectionDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngCollectionMapper;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngCollectionTermDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngCollectionTermMapper;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngMaterialDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngMaterialMapper;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngPlanNodeDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngPlanNodeMapper;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngPlanVersionDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngPlanVersionMapper;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngProjectDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngSiteRecordDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngSiteRecordMapper;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngSupplyDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngSupplyMapper;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngSupplyReceiptDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngSupplyReceiptMapper;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.engineering.enums.FranchiseEngineeringErrorCodes.*;

/**
 * 施工计划链：开工准备（收款约定即固化，无门禁 D-8）→ 实际开工（START 期资金门禁）→
 * 保存计划/整体顺移（◆经典链算法）→ 节点完成（材料门禁+completion 留痕，无收款门禁）→
 * 撤回（最近完成链）→ 更正（日期链含封顶完成≤封顶款收款日）。
 * 规则来源：engineering-records.js:55-86（recordFlow 唯一基准）+ model.js:62（◆顺移吸收）。
 */
@Service
public class EngPlanService {

    public static final String AGGREGATE = "fr-eng-project";

    private final EngProjectService projectService;
    private final FrEngPlanVersionMapper planVersions;
    private final FrEngPlanNodeMapper planNodes;
    private final FrEngSiteRecordMapper siteRecords;
    private final FrEngMaterialMapper materials;
    private final FrEngSupplyMapper supplies;
    private final FrEngSupplyReceiptMapper supplyReceipts;
    private final FrEngCollectionTermMapper collectionTerms;
    private final FrEngCollectionMapper collections;
    private final FranchiseCommands commands;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    public EngPlanService(EngProjectService projectService, FrEngPlanVersionMapper planVersions,
                          FrEngPlanNodeMapper planNodes, FrEngSiteRecordMapper siteRecords,
                          FrEngMaterialMapper materials, FrEngSupplyMapper supplies,
                          FrEngSupplyReceiptMapper supplyReceipts, FrEngCollectionTermMapper collectionTerms,
                          FrEngCollectionMapper collections, FranchiseCommands commands,
                          org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        this.projectService = projectService;
        this.jdbcTemplate = jdbcTemplate;
        this.planVersions = planVersions;
        this.planNodes = planNodes;
        this.siteRecords = siteRecords;
        this.materials = materials;
        this.supplies = supplies;
        this.supplyReceipts = supplyReceipts;
        this.collectionTerms = collectionTerms;
        this.collections = collections;
        this.commands = commands;
    }

    public record PrepareCommand(BigDecimal total, BigDecimal first, Long contractAssetId, String note,
                                 String workers, List<NodeInput> nodes) {
    }

    public record NodeInput(int nodeIndex, String name, LocalDate startDate, LocalDate endDate) {
    }

    public record RescheduleCommand(int nodeIndex, LocalDate newStart, String note) {
    }

    public record NodeRecordCommand(LocalDate date, String note, Long fileAssetId, boolean noMaterials) {
    }

    public record NodeCorrectCommand(LocalDate completedAt, String note, Long fileAssetId, String reason) {
    }

    // ===================== 开工准备（无门禁；保存即两期约定固化） =====================

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> prepare(PostSnapshot post, long projectId, PrepareCommand cmd, String idempotencyKey) {
        FrEngProjectDO project = projectService.requireOwned(post, projectId, "prepare");
        var replayed = commands.replayed(post, idempotencyKey, preparePayload(cmd));
        if (replayed.isPresent()) {
            return projectService.detail(post, projectId);
        }
        if (project.getActualStartAt() != null) {
            throw exception(ENGINEERING_INPUT_INVALID, "开工后的现场情况请在原施工记录补充");
        }
        long total = EngGuard.requirePositiveCents(cmd.total());
        long first = EngGuard.requirePositiveCents(cmd.first());
        if (first >= total) {
            throw exception(ENGINEERING_INPUT_INVALID, "开工款必须小于合同总额，余款作为封顶款");
        }
        if (cmd.contractAssetId() == null || StrUtil.isBlank(cmd.note()) || cmd.note().length() > 1000) {
            throw exception(ENGINEERING_INPUT_INVALID, "请上传有效的双方已签合同凭据并填写开工准备记录");
        }
        // 已有实际收款：沿用原合同金额和凭据（蓝本 :56）
        List<FrEngCollectionDO> received = collections.selectByProject(post.orgId(), projectId);
        if (!received.isEmpty() && (project.getContractTotalCents() != null && project.getContractTotalCents() > 0)) {
            if (project.getContractTotalCents() != total || project.getFirstCents() != first
                    || project.getContractAssetId() == null || project.getContractAssetId() != cmd.contractAssetId()) {
                throw exception(ENGINEERING_INPUT_INVALID, "已有实际收款，沿用原合同金额和凭据");
            }
        }
        boolean changed = project.getContractTotalCents() == null || project.getContractTotalCents() == 0
                || project.getContractTotalCents() != total || project.getFirstCents() != first
                || project.getContractAssetId() == null || project.getContractAssetId() != cmd.contractAssetId();
        int nextVersion = (project.getContractVersion() == null ? 0 : project.getContractVersion()) + (changed ? 1 : 0);
        projectService.projects().updateById(new FrEngProjectDO().setId(projectId)
                .setContractTotalCents(total).setFirstCents(first)
                .setContractAssetId(cmd.contractAssetId()).setContractVersion(nextVersion)
                .setContractDate(LocalDate.now())
                .setPreparedNote(cmd.note())
                .setPreparedAt(java.time.OffsetDateTime.now()));
        // prepared_workers JSONB 走 JdbcTemplate CAST（红线：jsonb 列禁 MyBatis-Plus 直写）
        if (cmd.workers() != null && !cmd.workers().isBlank()) {
            String workersJson = cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(
                    java.util.Arrays.stream(cmd.workers().split("[,，、\\s]+"))
                            .map(String::trim).filter(w -> !w.isEmpty()).toList());
            jdbcTemplate.update("UPDATE fr_eng_project SET prepared_workers = CAST(? AS jsonb) "
                    + "WHERE id = ? AND company_id = ?", workersJson, projectId, post.orgId());
        }
        // 两期约定固化（开工准备即固化：START=first、TOPPING=total-first；同额幂等）
        upsertTerm(post, projectId, FrEngCollectionTermDO.STAGE_START, first);
        upsertTerm(post, projectId, FrEngCollectionTermDO.STAGE_TOPPING, total - first);
        // 携带节点（prepare 也是改期入口之一：日期齐全校验+整体重建）
        if (cmd.nodes() != null && !cmd.nodes().isEmpty()) {
            saveNodeDates(post, project, cmd.nodes(), "prepare");
        }
        projectService.recalcStatus(post, project);
        commands.record(post, AGGREGATE, projectId,
                projectService.bumpRevision(post, project),
                "eng-prepare", preparePayload(cmd), idempotencyKey);
        return projectService.detail(post, projectId);
    }

    private static Map<String, Object> preparePayload(PrepareCommand cmd) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", cmd.total() == null ? null : cmd.total().toPlainString());
        m.put("first", cmd.first() == null ? null : cmd.first().toPlainString());
        m.put("contractAssetId", cmd.contractAssetId());
        m.put("note", cmd.note());
        return m;
    }

    private void upsertTerm(PostSnapshot post, long projectId, String stage, long amountCents) {
        List<FrEngCollectionTermDO> rows = collectionTerms.selectByProject(post.orgId(), projectId);
        FrEngCollectionTermDO existing = rows.stream()
                .filter(t -> stage.equals(t.getStage())).findFirst().orElse(null);
        if (existing == null) {
            collectionTerms.insert(new FrEngCollectionTermDO().setCompanyId(post.orgId())
                    .setProjectId(projectId).setStage(stage).setAmountCents(amountCents));
        } else if (existing.getAmountCents() != amountCents) {
            collectionTerms.updateById(new FrEngCollectionTermDO().setId(existing.getId())
                    .setAmountCents(amountCents));
        }
    }

    // ===================== 实际开工（START 期资金门禁 007） =====================

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> start(PostSnapshot post, long projectId,
                                     NodeRecordCommand cmd, String idempotencyKey) {
        FrEngProjectDO project = projectService.requireOwned(post, projectId, "start");
        // 重放输入=落账输入（评审十三 P1-3）：统一 cmd 原始日期形状，杜绝 now() 归一后的哈希错位
        Map<String, Object> payload = Map.of("date", cmd.date() == null ? "" : cmd.date().toString());
        var replayed = commands.replayed(post, idempotencyKey, payload);
        if (replayed.isPresent()) {
            return projectService.detail(post, projectId);
        }
        List<FrEngPlanNodeDO> nodes = planNodes.selectByProject(post.orgId(), projectId);
        if (project.getActualStartAt() != null || project.getPreparedAt() == null
                || nodes.isEmpty() || nodes.stream().anyMatch(n -> n.getStartDate() == null || n.getEndDate() == null)) {
            throw exception(ENGINEERING_INPUT_INVALID, "请先完成开工准备并填写全部节点计划日期");
        }
        requireStartGate(post, projectId);
        if (cmd.fileAssetId() == null) {
            throw exception(ENGINEERING_INPUT_INVALID, "请留存有效开工凭据");
        }
        LocalDate startDate = cmd.date() == null ? LocalDate.now() : cmd.date();
        if (startDate.isAfter(LocalDate.now())) {
            throw exception(ENGINEERING_INPUT_INVALID, "开工日期不能晚于今天");
        }
        projectService.projects().updateById(new FrEngProjectDO().setId(projectId)
                .setActualStartAt(startDate));
        project.setActualStartAt(startDate); // 内存 DO 同步（recalcStatus 派生依赖）
        FrEngPlanNodeDO first = nodes.get(0);
        planNodes.updateById(new FrEngPlanNodeDO().setId(first.getId())
                .setStatus(FrEngPlanNodeDO.STATUS_IN_PROGRESS));
        projectService.recalcStatus(post, project); // 唯一真相：actualStart 已回内存，派生 IN_PROGRESS 落库
        commands.record(post, AGGREGATE, projectId, projectService.bumpRevision(post, project),
                "eng-start", payload, idempotencyKey);
        return projectService.detail(post, projectId);
    }

    /** START 门禁：开工款累计已核算 ≥ 约定额（operations.js gate rank：start 期先收齐） */
    void requireStartGate(PostSnapshot post, long projectId) {
        long expected = termAmount(post, projectId, FrEngCollectionTermDO.STAGE_START);
        long confirmed = stageConfirmed(post, projectId, FrEngCollectionTermDO.STAGE_START);
        if (confirmed < expected) {
            throw exception(GATE_NOT_MET);
        }
    }

    long termAmount(PostSnapshot post, long projectId, String stage) {
        return collectionTerms.selectByProject(post.orgId(), projectId).stream()
                .filter(t -> stage.equals(t.getStage())).mapToLong(FrEngCollectionTermDO::getAmountCents)
                .findFirst().orElseThrow(() -> exception(ENGINEERING_INPUT_INVALID, "请等待合同及两期收款约定确认"));
    }

    long stageConfirmed(PostSnapshot post, long projectId, String stage) {
        return collections.selectByProject(post.orgId(), projectId).stream()
                .filter(c -> stage.equals(c.getStage())).mapToLong(FrEngCollectionDO::getAmountCents).sum();
    }

    // ===================== 保存计划（整体提交；已完成节点跳过） =====================

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> savePlan(PostSnapshot post, long projectId,
                                        List<NodeInput> inputs, String idempotencyKey) {
        FrEngProjectDO project = projectService.requireOwned(post, projectId, "plan");
        var replayed = commands.replayed(post, idempotencyKey, planPayload(inputs));
        if (replayed.isPresent()) {
            return planView(post, projectId);
        }
        saveNodeDates(post, project, inputs, "plan");
        commands.record(post, AGGREGATE, projectId, projectService.bumpRevision(post, project),
                "eng-plan-save", planPayload(inputs), idempotencyKey);
        return planView(post, projectId);
    }

    private static List<Map<String, Object>> planPayload(List<NodeInput> inputs) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (NodeInput n : inputs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nodeIndex", n.nodeIndex());
            m.put("name", n.name());
            m.put("startDate", n.startDate() == null ? null : n.startDate().toString());
            m.put("endDate", n.endDate() == null ? null : n.endDate().toString());
            rows.add(m);
        }
        return rows;
    }

    /**
     * 节点日期整体写入（prepare 携带节点与 PUT plan 共用）：
     * 集合一致（nodeIndex 对齐现有行）+日期齐全+不重叠+须含 TOPPING；已完成节点跳过（改期入口，蓝本 :88）。
     */
    private void saveNodeDates(PostSnapshot post, FrEngProjectDO project, List<NodeInput> inputs, String action) {
        List<FrEngPlanNodeDO> nodes = planNodes.selectByProject(post.orgId(), project.getId());
        if (inputs.size() != nodes.size()) {
            throw exception(PLAN_INVALID); // 请保留原施工节点
        }
        boolean hasTopping = false;
        LocalDate prevEnd = null;
        for (int i = 0; i < inputs.size(); i++) {
            NodeInput in = inputs.get(i);
            FrEngPlanNodeDO node = nodes.get(i);
            if (in.nodeIndex() != node.getNodeIndex()) {
                throw exception(PLAN_INVALID);
            }
            if (FrEngPlanNodeDO.MILESTONE_TOPPING.equals(node.getMilestone())) {
                hasTopping = true;
            }
            if (FrEngPlanNodeDO.STATUS_DONE.equals(node.getStatus())) {
                prevEnd = node.getEndDate();
                continue; // 已完成节点跳过
            }
            if (in.startDate() == null || in.endDate() == null || in.endDate().isBefore(in.startDate())) {
                throw exception(PLAN_INVALID);
            }
            if (prevEnd != null && !in.startDate().isAfter(prevEnd)) {
                throw exception(PLAN_INVALID); // 节点日期不可重叠
            }
            prevEnd = in.endDate();
        }
        if (!hasTopping) {
            throw exception(PLAN_INVALID); // 请保留封顶验收（主体封顶）节点
        }
        int nextVersion = nextVersion(post, project.getId());
        snapshot(post, project.getId(), nextVersion, withInputs(nodes, inputs), "MANUAL");
        for (int i = 0; i < inputs.size(); i++) {
            NodeInput in = inputs.get(i);
            FrEngPlanNodeDO node = nodes.get(i);
            if (FrEngPlanNodeDO.STATUS_DONE.equals(node.getStatus())) {
                continue;
            }
            planNodes.updateById(new FrEngPlanNodeDO().setId(node.getId())
                    .setName(StrUtil.blankToDefault(in.name(), node.getName()))
                    .setStartDate(in.startDate()).setEndDate(in.endDate()));
        }
    }

    private static List<EngTemplate.PlanSnapshotRow> withInputs(List<FrEngPlanNodeDO> nodes, List<NodeInput> inputs) {
        List<EngTemplate.PlanSnapshotRow> rows = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            NodeInput in = inputs.get(i);
            FrEngPlanNodeDO node = nodes.get(i);
            boolean done = FrEngPlanNodeDO.STATUS_DONE.equals(node.getStatus());
            rows.add(new EngTemplate.PlanSnapshotRow(node.getNodeIndex(),
                    StrUtil.blankToDefault(in.name(), node.getName()), node.getMilestone(),
                    done ? node.getStartDate() : in.startDate(),
                    done ? node.getEndDate() : in.endDate(),
                    node.getStatus()));
        }
        return rows;
    }

    // ===================== 整体顺移（◆经典链算法；材料 required_date 随移） =====================

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> reschedule(PostSnapshot post, long projectId, RescheduleCommand cmd, String idempotencyKey) {
        FrEngProjectDO project = projectService.requireOwned(post, projectId, "reschedule");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodeIndex", cmd.nodeIndex());
        payload.put("newStart", cmd.newStart() == null ? null : cmd.newStart().toString());
        payload.put("note", StrUtil.nullToEmpty(cmd.note()));
        var replayed = commands.replayed(post, idempotencyKey, payload);
        if (replayed.isPresent()) {
            return planView(post, projectId);
        }
        List<FrEngPlanNodeDO> nodes = planNodes.selectByProject(post.orgId(), projectId);
        FrEngPlanNodeDO target = nodes.stream()
                .filter(n -> n.getNodeIndex() == cmd.nodeIndex()).findFirst()
                .orElseThrow(() -> exception(PLAN_INVALID));
        if (FrEngPlanNodeDO.STATUS_DONE.equals(target.getStatus()) || cmd.newStart() == null
                || target.getStartDate() == null) {
            throw exception(PLAN_INVALID); // 已完成节点不能改期
        }
        long delta = cmd.newStart().toEpochDay() - target.getStartDate().toEpochDay();
        // 上一节点不得重叠（顺移仅向右或左移但不压前一节点）
        int index = nodes.indexOf(target);
        if (index > 0) {
            FrEngPlanNodeDO prev = nodes.get(index - 1);
            LocalDate prevEnd = FrEngPlanNodeDO.STATUS_DONE.equals(prev.getStatus())
                    ? prev.getEndDate() : prev.getEndDate();
            if (prevEnd != null && !cmd.newStart().isAfter(prevEnd)) {
                throw exception(PLAN_INVALID); // 改期后与上一节点重叠
            }
        }
        int nextVersion = nextVersion(post, projectId);
        List<EngTemplate.PlanSnapshotRow> snapshotRows = new ArrayList<>();
        List<FrEngMaterialDO> allMaterials = materials.selectByProject(post.orgId(), projectId);
        for (FrEngPlanNodeDO node : nodes) {
            LocalDate newStart = node.getStartDate();
            LocalDate newEnd = node.getEndDate();
            boolean moved = node.getNodeIndex() >= target.getNodeIndex()
                    && !FrEngPlanNodeDO.STATUS_DONE.equals(node.getStatus())
                    && node.getStartDate() != null && node.getEndDate() != null;
            if (moved) {
                newStart = node.getStartDate().plusDays(delta);
                newEnd = node.getEndDate().plusDays(delta);
                planNodes.updateById(new FrEngPlanNodeDO().setId(node.getId())
                        .setStartDate(newStart).setEndDate(newEnd));
                // ◆材料需到场日随移（经典链 :62）
                for (FrEngMaterialDO material : allMaterials) {
                    if (material.getNodeId() != null && material.getNodeId().equals(node.getId())
                            && material.getRequiredDate() != null) {
                        materials.updateById(new FrEngMaterialDO().setId(material.getId())
                                .setRequiredDate(material.getRequiredDate().plusDays(delta)));
                    }
                }
            }
            snapshotRows.add(new EngTemplate.PlanSnapshotRow(node.getNodeIndex(), node.getName(),
                    node.getMilestone(), newStart, newEnd, node.getStatus()));
        }
        snapshot(post, projectId, nextVersion, snapshotRows, "MANUAL");
        commands.record(post, AGGREGATE, projectId, projectService.bumpRevision(post, project),
                "eng-plan-reschedule", payload, idempotencyKey);
        return planView(post, projectId);
    }

    // ===================== 节点完成 / 撤回 / 更正 =====================

    /** 节点完成：材料门禁 007 + completion 现场记录自动落（无收款门禁，D-8） */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> completeNode(PostSnapshot post, long projectId, long nodeId,
                                            NodeRecordCommand cmd, String idempotencyKey) {
        FrEngProjectDO project = projectService.requireOwned(post, projectId, "node");
        // 重放输入=落账输入（评审十三 P1-3）：nodeId+原始日期两处同形
        Map<String, Object> payload = Map.of("nodeId", nodeId,
                "date", cmd.date() == null ? "" : cmd.date().toString());
        var replayed = commands.replayed(post, idempotencyKey, payload);
        if (replayed.isPresent()) {
            return projectService.detail(post, projectId);
        }
        List<FrEngPlanNodeDO> nodes = planNodes.selectByProject(post.orgId(), projectId);
        FrEngPlanNodeDO node = nodes.stream().filter(n -> n.getId() == nodeId).findFirst()
                .orElseThrow(() -> exception(ENGINEERING_INPUT_INVALID));
        FrEngPlanNodeDO current = EngGuard.currentNode(nodes);
        if (project.getActualStartAt() == null || current == null || node.getId() != current.getId()) {
            throw exception(ENGINEERING_INPUT_INVALID, "请从当前施工节点继续记录");
        }
        LocalDate date = cmd.date() == null ? LocalDate.now() : cmd.date();
        if (date.isAfter(LocalDate.now())) {
            throw exception(ENGINEERING_INPUT_INVALID, "记录日期不能晚于今天");
        }
        if (StrUtil.isBlank(cmd.note()) || cmd.fileAssetId() == null) {
            throw exception(ENGINEERING_INPUT_INVALID, "请记录现场情况及验收凭据");
        }
        if (!materialReadyForNode(post, projectId, nodeId)) {
            throw exception(GATE_NOT_MET); // 本节点材料尚未合格到齐
        }
        // 自动验收留痕（单人记录，D-9）
        jdbcTemplate.update("INSERT INTO fr_eng_site_record (id, company_id, project_id, node_id, "
                        + "occurred_at, note, completion, result, review_mode, file_asset_ids, author_user_id, create_time) "
                        + "VALUES (?,?,?,?,?,?,TRUE,?,?,CAST(? AS jsonb),?,now())",
                cn.hutool.core.util.IdUtil.getSnowflakeNextId(), post.orgId(), projectId, nodeId, date,
                cmd.note(), FrEngSiteRecordDO.RESULT_PASS, FrEngSiteRecordDO.MODE_SINGLE_PERSON,
                JsonList.of(cmd.fileAssetId()), post.userId());
        planNodes.updateById(new FrEngPlanNodeDO().setId(nodeId)
                .setStatus(FrEngPlanNodeDO.STATUS_DONE).setCompletedAt(date)
                .setResponsibleUserId(post.userId()));
        int index = nodes.indexOf(node);
        if (index + 1 < nodes.size()) {
            FrEngPlanNodeDO next = nodes.get(index + 1);
            if (FrEngPlanNodeDO.STATUS_PENDING.equals(next.getStatus())) {
                planNodes.updateById(new FrEngPlanNodeDO().setId(next.getId())
                        .setStatus(FrEngPlanNodeDO.STATUS_IN_PROGRESS));
            }
        }
        projectService.recalcStatus(post, project);
        commands.record(post, AGGREGATE, projectId, projectService.bumpRevision(post, project),
                "eng-node-complete", payload, idempotencyKey);
        return projectService.detail(post, projectId);
    }

    boolean materialReadyForNode(PostSnapshot post, long projectId, Long nodeId) {
        List<FrEngMaterialDO> rows = materials.selectByProject(post.orgId(), projectId);
        if (rows.isEmpty()) {
            return true;
        }
        List<FrEngSupplyDO> supplyRows = supplies.selectByProject(post.orgId(), projectId);
        List<FrEngSupplyReceiptDO> receiptRows = supplyReceipts.selectByProject(post.orgId(), projectId);
        Map<Long, List<FrEngSupplyReceiptDO>> bySupply = new LinkedHashMap<>();
        for (FrEngSupplyReceiptDO r : receiptRows) {
            bySupply.computeIfAbsent(r.getSupplyId(), k -> new ArrayList<>()).add(r);
        }
        List<EngGuard.MaterialFact> facts = new ArrayList<>();
        for (FrEngMaterialDO m : rows) {
            List<FrEngSupplyReceiptDO> mReceipts = new ArrayList<>();
            for (FrEngSupplyDO s : supplyRows) {
                if (s.getMaterialId() != null && s.getMaterialId().equals(m.getId())) {
                    mReceipts.addAll(bySupply.getOrDefault(s.getId(), List.of()));
                }
            }
            facts.add(new EngGuard.MaterialFact(m.getId(),
                    m.getNodeId() == null ? 0 : m.getNodeId(), m.getQuantity(),
                    FrEngMaterialDO.STATUS_CANCELLED.equals(m.getStatus()), mReceipts));
        }
        return EngGuard.materialReady(facts, nodeId);
    }

    /** 撤回（reopenBlock 前置链 003；验收留痕降级为普通现场记录） */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> reopenNode(PostSnapshot post, long projectId, long nodeId,
                                          String reason, String idempotencyKey) {
        FrEngProjectDO project = projectService.requireOwned(post, projectId, "node");
        // 重放输入=落账输入（评审十三 P1-3）：nodeId+reason 两处同形
        Map<String, Object> payload = Map.of("nodeId", nodeId, "reason", StrUtil.nullToEmpty(reason));
        var replayed = commands.replayed(post, idempotencyKey, payload);
        if (replayed.isPresent()) {
            return projectService.detail(post, projectId);
        }
        List<FrEngPlanNodeDO> nodes = planNodes.selectByProject(post.orgId(), projectId);
        FrEngPlanNodeDO node = nodes.stream().filter(n -> n.getId() == nodeId).findFirst()
                .orElseThrow(() -> exception(NODE_ACTION_INVALID));
        String block = reopenBlock(post, projectId, nodes, node);
        if (block != null) {
            throw exception(NODE_ACTION_INVALID);
        }
        if (StrUtil.isBlank(reason)) {
            throw exception(ENGINEERING_INPUT_INVALID, "请填写撤回原因");
        }
        int index = nodes.indexOf(node);
        // 撤回历史留痕 + completion 记录降级为普通现场记录
        String reopenHistory = JsonList.append(node.getReopenHistory(), Map.of(
                "completedAt", node.getCompletedAt() == null ? "" : node.getCompletedAt().toString(),
                "reason", reason, "date", LocalDate.now().toString(), "actorUserId", post.userId()));
        jdbcTemplate.update("UPDATE fr_eng_plan_node SET status = 'IN_PROGRESS', completed_at = NULL, "
                + "responsible_user_id = NULL, reopen_history = CAST(? AS jsonb), update_time = now() "
                + "WHERE id = ? AND company_id = ?", reopenHistory, nodeId, post.orgId());
        for (FrEngSiteRecordDO record : siteRecords.selectByProject(post.orgId(), projectId)) {
            if (record.getNodeId() != null && record.getNodeId() == nodeId
                    && Boolean.TRUE.equals(record.getCompletion())) {
                siteRecords.updateById(new FrEngSiteRecordDO().setId(record.getId())
                        .setCompletion(false).setResult(null).setReviewMode(null));
            }
        }
        if (index + 1 < nodes.size()) {
            FrEngPlanNodeDO next = nodes.get(index + 1);
            if (FrEngPlanNodeDO.STATUS_IN_PROGRESS.equals(next.getStatus())) {
                planNodes.updateById(new FrEngPlanNodeDO().setId(next.getId())
                        .setStatus(FrEngPlanNodeDO.STATUS_PENDING));
            }
        }
        projectService.recalcStatus(post, project);
        commands.record(post, AGGREGATE, projectId, projectService.bumpRevision(post, project),
                "eng-node-reopen", payload, idempotencyKey);
        return projectService.detail(post, projectId);
    }

    /** 蓝本 reopenBlock（:36）：最近完成+下一节点无记录+topping 未收封顶款；null=可撤回 */
    private String reopenBlock(PostSnapshot post, long projectId, List<FrEngPlanNodeDO> nodes, FrEngPlanNodeDO node) {
        if (!FrEngPlanNodeDO.STATUS_DONE.equals(node.getStatus())) {
            return "请选择已完成的施工节点";
        }
        int index = nodes.indexOf(node);
        if (nodes.subList(index + 1, nodes.size()).stream()
                .anyMatch(n -> FrEngPlanNodeDO.STATUS_DONE.equals(n.getStatus()))) {
            return "请先撤回最近完成的节点";
        }
        if (index + 1 < nodes.size()) {
            long nextId = nodes.get(index + 1).getId();
            boolean nextHasRecord = siteRecords.selectByProject(post.orgId(), projectId).stream()
                    .anyMatch(r -> r.getNodeId() != null && r.getNodeId() == nextId);
            if (nextHasRecord) {
                return "下一节点已有现场记录，不能撤回";
            }
        }
        if (FrEngPlanNodeDO.MILESTONE_TOPPING.equals(node.getMilestone())) {
            boolean toppingPaid = collections.selectByProject(post.orgId(), projectId).stream()
                    .anyMatch(c -> FrEngCollectionDO.STAGE_TOPPING.equals(c.getStage()));
            if (toppingPaid) {
                return "已登记封顶款，不能撤回主体封顶";
            }
        }
        return null;
    }

    /** 更正（日期链约束：≥开工/≥上一节点/≤下一节点首条记录/◆封顶完成≤封顶款收款日，蓝本 :83） */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> correctNode(PostSnapshot post, long projectId, long nodeId,
                                           NodeCorrectCommand cmd, String idempotencyKey) {
        FrEngProjectDO project = projectService.requireOwned(post, projectId, "node");
        // 重放输入=落账输入（评审十三 P1-3）：nodeId+completedAt 两处同形
        Map<String, Object> payload = Map.of("nodeId", nodeId,
                "completedAt", cmd.completedAt() == null ? "" : cmd.completedAt().toString());
        var replayed = commands.replayed(post, idempotencyKey, payload);
        if (replayed.isPresent()) {
            return projectService.detail(post, projectId);
        }
        List<FrEngPlanNodeDO> nodes = planNodes.selectByProject(post.orgId(), projectId);
        FrEngPlanNodeDO node = nodes.stream().filter(n -> n.getId() == nodeId).findFirst()
                .orElseThrow(() -> exception(NODE_ACTION_INVALID));
        if (!FrEngPlanNodeDO.STATUS_DONE.equals(node.getStatus()) || cmd.completedAt() == null
                || StrUtil.isBlank(cmd.reason()) || StrUtil.isBlank(cmd.note()) || cmd.fileAssetId() == null) {
            throw exception(NODE_ACTION_INVALID);
        }
        LocalDate d = cmd.completedAt();
        int index = nodes.indexOf(node);
        if (project.getActualStartAt() != null && d.isBefore(project.getActualStartAt())) {
            throw exception(NODE_ACTION_INVALID); // 完成日期不能早于实际开工日期
        }
        if (index > 0) {
            FrEngPlanNodeDO prev = nodes.get(index - 1);
            if (prev.getCompletedAt() != null && d.isBefore(prev.getCompletedAt())) {
                throw exception(NODE_ACTION_INVALID); // 完成日期不能早于上一节点完成日期
            }
        }
        if (index + 1 < nodes.size()) {
            long nextId = nodes.get(index + 1).getId();
            LocalDate nextFirst = siteRecords.selectByProject(post.orgId(), projectId).stream()
                    .filter(r -> r.getNodeId() != null && r.getNodeId() == nextId)
                    .map(FrEngSiteRecordDO::getOccurredAt).filter(java.util.Objects::nonNull)
                    .min(LocalDate::compareTo).orElse(null);
            if (nextFirst != null && d.isAfter(nextFirst)) {
                throw exception(NODE_ACTION_INVALID); // 完成日期不能晚于下一节点的首条记录
            }
        }
        if (FrEngPlanNodeDO.MILESTONE_TOPPING.equals(node.getMilestone())) {
            LocalDate toppingPaid = collections.selectByProject(post.orgId(), projectId).stream()
                    .filter(c -> FrEngCollectionDO.STAGE_TOPPING.equals(c.getStage()))
                    .map(FrEngCollectionDO::getOccurredAt).min(LocalDate::compareTo).orElse(null);
            if (toppingPaid != null && d.isAfter(toppingPaid)) {
                throw exception(NODE_ACTION_INVALID); // 封顶完成日期不能晚于已登记的封顶款收款日期
            }
        }
        String corrections = JsonList.append(node.getCorrections(), Map.of(
                "completedAt", node.getCompletedAt() == null ? "" : node.getCompletedAt().toString(),
                "note", node.getName() + " 完成更正", "reason", cmd.reason(),
                "date", LocalDate.now().toString(), "actorUserId", post.userId()));
        jdbcTemplate.update("UPDATE fr_eng_plan_node SET completed_at = ?, corrections = CAST(? AS jsonb), "
                + "update_time = now() WHERE id = ? AND company_id = ?", d, corrections, nodeId, post.orgId());
        // 同步 completion 现场记录（蓝本 :83 同步 inspection/site/topping）
        for (FrEngSiteRecordDO record : siteRecords.selectByProject(post.orgId(), projectId)) {
            if (record.getNodeId() != null && record.getNodeId() == nodeId
                    && Boolean.TRUE.equals(record.getCompletion())) {
                jdbcTemplate.update("UPDATE fr_eng_site_record SET occurred_at = ?, note = ?, "
                                + "file_asset_ids = CAST(? AS jsonb) WHERE id = ? AND company_id = ?",
                        d, cmd.note(), JsonList.of(cmd.fileAssetId()), record.getId(), post.orgId());
            }
        }
        commands.record(post, AGGREGATE, projectId, projectService.bumpRevision(post, project),
                "eng-node-correct", payload, idempotencyKey);
        return projectService.detail(post, projectId);
    }

    // ===================== 读 =====================

    public Map<String, Object> planView(PostSnapshot post, long projectId) {
        FrEngProjectDO project = projectService.requireProject(post, projectId);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("projectId", projectId);
        view.put("nodes", planNodes.selectByProject(post.orgId(), projectId).stream()
                .map(EngProjectService::nodeView).toList());
        return view;
    }

    public List<Map<String, Object>> history(PostSnapshot post, long projectId) {
        projectService.requireProject(post, projectId);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (FrEngPlanVersionDO v : planVersions.selectByProject(post.orgId(), projectId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("version", v.getVersion());
            row.put("source", v.getSource());
            row.put("compiledAt", v.getCompiledAt());
            row.put("nodes", v.getNodes());
            rows.add(row);
        }
        return rows;
    }

    // ===================== 内部 =====================

    private int nextVersion(PostSnapshot post, long projectId) {
        List<FrEngPlanVersionDO> rows = planVersions.selectByProject(post.orgId(), projectId);
        return rows.stream().mapToInt(FrEngPlanVersionDO::getVersion).max().orElse(0) + 1;
    }

    /** 快照（带日期与状态的完整节点清单 JSONB；JdbcTemplate CAST 惯例） */
    private void snapshot(PostSnapshot post, long projectId, int version,
                          List<EngTemplate.PlanSnapshotRow> rows, String source) {
        jdbcTemplate.update("INSERT INTO fr_eng_plan_version (id, company_id, project_id, version, nodes, source, "
                        + "compiled_at, author_user_id, create_time) VALUES (?,?,?,?,CAST(? AS jsonb),?,?,?,now())",
                cn.hutool.core.util.IdUtil.getSnowflakeNextId(), post.orgId(), projectId,
                version, EngTemplate.snapshotJson(rows), source, java.time.OffsetDateTime.now(), post.userId());
    }

    /** JSONB 小工具（字符串数组/对象追加；写路径统一 JdbcTemplate CAST 惯例） */
    static final class JsonList {

        static String of(Object... items) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < items.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(items[i]));
            }
            return sb.append(']').toString();
        }

        static String append(String existingJsonArray, Map<String, Object> row) {
            List<Map<String, Object>> rows = new ArrayList<>();
            if (StrUtil.isNotBlank(existingJsonArray)) {
                for (Object parsed : cn.iocoder.yudao.framework.common.util.json.JsonUtils.parseArray(
                        existingJsonArray, Map.class)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> parsedRow = (Map<String, Object>) parsed;
                    rows.add(parsedRow);
                }
            }
            rows.add(row);
            return cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(rows);
        }
    }
}
