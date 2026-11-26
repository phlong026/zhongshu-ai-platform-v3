package cn.iocoder.yudao.module.franchise.engineering.content;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.franchise.api.EngineeringLedgerQueryPort;
import cn.iocoder.yudao.module.franchise.api.EngineeringPayablePort;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngMaterialDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngMaterialMapper;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngMaterialMovementDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngMaterialMovementMapper;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngPlanNodeDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngPlanNodeMapper;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngProjectDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngProjectMapper;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception0;
import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED;
import static cn.iocoder.yudao.module.franchise.engineering.enums.FranchiseEngineeringErrorCodes.*;

/**
 * 材料四实体链（D-6）：计划（计划单分组）→ 供应 terms（采购金额=terms 码，经 PayablePort 生成 finance 应付；
 * 已付款后金额/凭据锁死只能补证 004）→ 到货（流水唯一 005；◆质检/差异自动补发）→ ◆去向。
 * 调整边界：0=取消、不得少于已到货、节点完成后不得加大（004）。
 */
@Service
public class EngMaterialService {

    public static final String AGGREGATE = "fr-eng-project";

    private final EngProjectService projectService;
    private final FrEngProjectMapper projects;
    private final FrEngPlanNodeMapper planNodes;
    private final FrEngMaterialMapper materials;
    private final FrEngSupplyMapper supplies;
    private final FrEngSupplyReceiptMapper supplyReceipts;
    private final FrEngMaterialMovementMapper movements;
    private final FranchiseCommands commands;
    private final EngineeringPayablePort payables;
    private final EngineeringLedgerQueryPort ledger;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    private final EngPlanService planService; // M07-T04 批次#36：到货凭据域侧校验复用（同包）

    public EngMaterialService(EngProjectService projectService, FrEngProjectMapper projects,
                              FrEngPlanNodeMapper planNodes, FrEngMaterialMapper materials,
                              FrEngSupplyMapper supplies, FrEngSupplyReceiptMapper supplyReceipts,
                              FrEngMaterialMovementMapper movements, FranchiseCommands commands,
                              EngineeringPayablePort payables, EngineeringLedgerQueryPort ledger,
                              org.springframework.jdbc.core.JdbcTemplate jdbcTemplate,
                              EngPlanService planService) {
        this.projectService = projectService;
        this.jdbcTemplate = jdbcTemplate;
        this.projects = projects;
        this.planNodes = planNodes;
        this.materials = materials;
        this.supplies = supplies;
        this.supplyReceipts = supplyReceipts;
        this.movements = movements;
        this.commands = commands;
        this.payables = payables;
        this.ledger = ledger;
        this.planService = planService;
    }

    public record PlanLine(String name, String spec, String unit, BigDecimal quantity) {
    }

    public record PlanCommand(long nodeId, LocalDate due, List<PlanLine> lines, String note) {
    }

    public record AdjustCommand(BigDecimal quantity, String reason) {
    }

    public record TermsCommand(String supplier, BigDecimal unitPrice, BigDecimal freight,
                               String contact, LocalDate promiseDate, String basis) {
    }

    public record ArrivalCommand(Long parentReceiptId, BigDecimal expected, BigDecimal actual,
                                 String quality, String transactionNo, Long fileAssetId,
                                 LocalDate occurredAt, String note,
                                 Map<String, Object> qualityResolution) {
    }

    public record ArrivalCorrectionCommand(BigDecimal actual, String reason) {
    }

    public record MovementCommand(long materialId, String kind, BigDecimal quantity,
                                  String note, Long receiverUserId) {
    }

    // ===================== 计划提交（每次提交一个计划单分组） =====================

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> submitPlan(PostSnapshot post, long projectId, PlanCommand cmd, String idempotencyKey) {
        projectService.requireProject(post, projectId);
        projects.lockById(projectId);
        FrEngProjectDO project = projectService.requireOwned(post, projectId, "materials");
        if (cmd.due() == null) {
            throw exception(ENGINEERING_INPUT_INVALID, "请填写需到货日期");
        }
        List<PlanLine> lines = cmd.lines() == null ? List.of() : cmd.lines().stream()
                .filter(l -> l != null && StrUtil.isNotBlank(l.name()))
                .map(EngMaterialService::normalizedPlanLine).toList();
        if (lines.isEmpty()) {
            throw exception(ENGINEERING_INPUT_INVALID, "请填写材料清单");
        }
        var previous = commands.previous(post, idempotencyKey, "eng-material-plan", projectId);
        if (previous.isPresent()) {
            var event = previous.get();
            if (!AGGREGATE.equals(event.getAggregateType())) throw exception(IDEMPOTENCY_KEY_REUSED);
            var recorded = JsonUtils.parseTree(event.getPayload());
            long planId;
            if (recorded.has("materialPlanId")) {
                planId = recorded.get("materialPlanId").asLong();
                commands.replayed(post, idempotencyKey, planSubmission(planId, projectId, cmd, lines));
            } else {
                commands.replayed(post, idempotencyKey, legacyPlanPayload(cmd));
                planId = verifiedLegacyPlan(post.orgId(), projectId, cmd, lines);
            }
            return submittedPlan(post, projectId, planId);
        }
        FrEngPlanNodeDO node = planNodes.selectByProject(post.orgId(), projectId).stream()
                .filter(n -> n.getId() == cmd.nodeId()).findFirst()
                .orElseThrow(() -> exception(ENGINEERING_INPUT_INVALID, "请选择本项目的施工节点"));
        if ("START".equals(node.getMilestone()) || FrEngPlanNodeDO.STATUS_DONE.equals(node.getStatus())) {
            throw exception(ENGINEERING_INPUT_INVALID, "请选择未完成的施工节点");
        }
        long planId = cn.hutool.core.util.IdUtil.getSnowflakeNextId();
        for (PlanLine line : lines) {
            FrEngMaterialDO material = new FrEngMaterialDO().setCompanyId(post.orgId())
                    .setProjectId(projectId).setMaterialPlanId(planId).setNodeId(cmd.nodeId())
                    .setName(line.name()).setSpec(line.spec()).setUnit(line.unit()).setQuantity(line.quantity())
                    .setRequiredDate(cmd.due()).setStatus(FrEngMaterialDO.STATUS_SUBMITTED);
            materials.insert(material);
            supplies.insert(new FrEngSupplyDO().setCompanyId(post.orgId()).setProjectId(projectId)
                    .setMaterialId(material.getId()).setQuantity(line.quantity())
                    .setUnitPriceCents(0L).setFreightCents(0L).setPayer(FrEngSupplyDO.PAYER_STORE)
                    .setStatus(FrEngSupplyDO.STATUS_PENDING_TERMS).setPaid(false));
        }
        commands.record(post, AGGREGATE, projectId,
                projectService.bumpRevision(post, project),
                "eng-material-plan", planSubmission(planId, projectId, cmd, lines), idempotencyKey);
        return submittedPlan(post, projectId, planId);
    }

    private Map<String, Object> submittedPlan(PostSnapshot post, long projectId, long planId) {
        Map<String, Object> view = summary(post, projectId, planId);
        view.put("materialPlanId", planId);
        return view;
    }

    private static PlanLine normalizedPlanLine(PlanLine line) {
        if (StrUtil.isBlank(line.unit())) throw exception(ENGINEERING_INPUT_INVALID, "请填写材料单位");
        return new PlanLine(StrUtil.trim(line.name()), StrUtil.nullToEmpty(line.spec()),
                StrUtil.trim(line.unit()), EngGuard.requireQuantity(line.quantity()));
    }

    private record PlanSubmission(long materialPlanId, long projectId, long nodeId, String due,
                                  List<PlanLine> lines, String note) {}

    private static PlanSubmission planSubmission(long planId, long projectId, PlanCommand cmd, List<PlanLine> lines) {
        return new PlanSubmission(planId, projectId, cmd.nodeId(), cmd.due().toString(), lines,
                StrUtil.trimToEmpty(cmd.note()));
    }

    private long verifiedLegacyPlan(long orgId, long projectId, PlanCommand cmd, List<PlanLine> lines) {
        // Old events lack a batch ID. Ignore batches already linked to newer events.
        // A matching description alone cannot distinguish two historical orders for the same node and date.
        List<Long> candidates = jdbcTemplate.queryForList("""
                SELECT DISTINCT m.material_plan_id FROM fr_eng_material m
                WHERE m.company_id=? AND m.project_id=? AND m.node_id=? AND m.required_date=?
                  AND NOT EXISTS (
                    SELECT 1 FROM pf_command_event e
                    WHERE e.domain='franchise' AND e.org_id=m.company_id AND e.aggregate_id=m.project_id
                      AND e.aggregate_type='fr-eng-project' AND e.command='eng-material-plan'
                      AND e.payload::jsonb->>'materialPlanId'=m.material_plan_id::text)
                """, Long.class, orgId, projectId, cmd.nodeId(), cmd.due());
        if (candidates.size() != 1 || StrUtil.isNotBlank(cmd.note())) {
            throw exception(ENGINEERING_INPUT_INVALID, "旧版计划缺少批次或备注留痕，请核对原材料计划");
        }
        long planId = candidates.get(0);
        Boolean adjusted = jdbcTemplate.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM pf_command_event e JOIN fr_eng_material m
                    ON m.company_id=e.org_id AND m.project_id=e.aggregate_id
                    AND m.id::text=e.payload::jsonb->>'materialId'
                    WHERE e.domain='franchise' AND e.org_id=? AND e.aggregate_id=?
                      AND e.aggregate_type='fr-eng-project' AND e.command='eng-material-adjust'
                      AND m.material_plan_id=?)
                """, Boolean.class, orgId, projectId, planId);
        if (Boolean.TRUE.equals(adjusted)) {
            throw exception(ENGINEERING_INPUT_INVALID, "旧版材料计划数量已有调整，请核对原计划");
        }
        List<PlanLine> saved = materials.selectByPlan(orgId, planId).stream()
                .sorted(Comparator.comparing(FrEngMaterialDO::getId))
                .map(m -> normalizedPlanLine(new PlanLine(m.getName(), m.getSpec(), m.getUnit(), m.getQuantity())))
                .toList();
        if (!saved.equals(lines)) throw exception(IDEMPOTENCY_KEY_REUSED);
        return planId;
    }

    private static Map<String, Object> legacyPlanPayload(PlanCommand cmd) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nodeId", cmd.nodeId());
        m.put("due", cmd.due() == null ? null : cmd.due().toString());
        m.put("lineCount", cmd.lines() == null ? 0 : cmd.lines().size());
        return m;
    }

    // ===================== 需用数量调整（0=取消；边界 004） =====================

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> adjust(PostSnapshot post, long materialId, AdjustCommand cmd, String idempotencyKey) {
        FrEngMaterialDO material = materials.selectById(materialId);
        if (material == null || material.getCompanyId() != post.orgId()) {
            throw exception(ENGINEERING_INPUT_INVALID, "材料不存在");
        }
        FrEngProjectDO project = projectService.requireOwned(post, material.getProjectId(), "materials");
        // M07-T01/I02：replay 与 record 必须共用同一份规范化输入（此前 replay 用 {materialId,quantity}、
        // record 用 {materialId,from,to,reason}——字段集不同摘要不匹配，同键同输入重试被误判键复用）；
        // LinkedHashMap 固定键序防跨 JVM 序列化漂移（批次#21 同款教训）
        var canonical = new java.util.LinkedHashMap<String, Object>();
        canonical.put("materialId", materialId);
        canonical.put("quantity", cmd.quantity() == null ? null : cmd.quantity().toPlainString());
        canonical.put("reason", cmd.reason() == null ? "" : cmd.reason().trim());
        var replayed = commands.replayed(post, idempotencyKey, canonical);
        if (replayed.isPresent()) {
            return summary(post, material.getProjectId(), material.getMaterialPlanId());
        }
        if (StrUtil.isBlank(cmd.reason())) {
            throw exception(ENGINEERING_INPUT_INVALID, "请填写调整原因");
        }
        BigDecimal target = cmd.quantity();
        if (target == null || target.scale() > 3 || target.compareTo(new BigDecimal("10000000")) > 0
                || target.compareTo(BigDecimal.ZERO) < 0) {
            throw exception(ENGINEERING_INPUT_INVALID, "请填写有效需用数量");
        }
        if (target.compareTo(material.getQuantity()) == 0) {
            throw exception(ENGINEERING_INPUT_INVALID, "调整后数量与原值相同");
        }
        FrEngSupplyDO supply = supplies.selectByMaterial(post.orgId(), materialId);
        if (supply == null) {
            throw exception(ENGINEERING_INPUT_INVALID, "供应事项不存在");
        }
        // 已付款锁定（蓝本 paidOut：交财务在原付款事项处理）
        requireNotPaid(post, supply);
        BigDecimal received = EngGuard.goodQuantity(supplyReceipts.selectBySupply(post.orgId(), supply.getId()));
        if (target.add(new BigDecimal("0.0001")).compareTo(received) < 0) {
            throw exception(MATERIAL_INVALID, material.getName() + "已到货" + received + material.getUnit()
                    + "，需用数量不能少于已到数量");
        }
        FrEngPlanNodeDO planNode = planNodes.selectById(material.getNodeId());
        if (planNode != null && FrEngPlanNodeDO.STATUS_DONE.equals(planNode.getStatus())
                && target.compareTo(material.getQuantity()) > 0) {
            throw exception(MATERIAL_INVALID, "本计划所属节点已完成，加大需用数量请先撤回节点完成");
        }
        materials.updateById(new FrEngMaterialDO().setId(materialId).setQuantity(target)
                .setStatus(target.compareTo(BigDecimal.ZERO) == 0
                        ? FrEngMaterialDO.STATUS_CANCELLED : FrEngMaterialDO.STATUS_SUBMITTED));
        supplies.updateById(new FrEngSupplyDO().setId(supply.getId()).setQuantity(target)
                .setStatus(target.compareTo(BigDecimal.ZERO) == 0
                        ? FrEngSupplyDO.STATUS_CANCELLED : supply.getStatus()));
        // record 与 replay 严格同一 canonical（from/to 若入 payload，重试时 from=现值≠首次 from 仍摘要不匹配——
        // 变更前后值由 material.quantity 现值承载，审计粒度让步给幂等正确性，登记执行记录）
        commands.record(post, AGGREGATE, material.getProjectId(),
                projectService.bumpRevision(post, project),
                "eng-material-adjust", canonical, idempotencyKey);
        return summary(post, material.getProjectId(), material.getMaterialPlanId());
    }

    // ===================== 采购金额 terms（terms 码；经 PayablePort 生成 finance 应付） =====================

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> terms(PostSnapshot post, long supplyId, TermsCommand cmd, String idempotencyKey) {
        idempotencyKey=commands.requireIdempotencyKey(idempotencyKey);
        long lockKey=cn.iocoder.yudao.module.franchise.org.FranchiseCommandKeys.lockKey(
                new cn.iocoder.yudao.module.franchise.org.FranchiseCommandKeys.PostKeyScope("franchise",post.userId()),idempotencyKey);
        jdbcTemplate.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(?)) s",Integer.class,lockKey);
        jdbcTemplate.queryForList("SELECT id FROM fr_eng_supply WHERE id=? AND company_id=? FOR UPDATE",supplyId,post.orgId());
        FrEngSupplyDO supply = supplies.selectById(supplyId);
        if (supply == null || supply.getCompanyId() != post.orgId()) {
            throw exception(ENGINEERING_INPUT_INVALID, "供应事项不存在");
        }
        FrEngProjectDO project = projects.selectById(supply.getProjectId());
        if (project == null || project.getCompanyId() != post.orgId()) {
            throw exception(ENGINEERING_INPUT_INVALID, "工程不存在");
        }
        EngGuard.requireActionAllowed(projectService.closureOf(post, supply.getProjectId()), "material-terms");
        var replayed = commands.replayed(post, idempotencyKey, termsPayload(supplyId, cmd));
        if (replayed.isPresent()) {
            return supplyView(post, supply);
        }
        if (StrUtil.isBlank(cmd.supplier()) || cmd.unitPrice() == null || StrUtil.isBlank(cmd.basis())) {
            throw exception(ENGINEERING_INPUT_INVALID, "请填写实际供应方、单价与采购凭据");
        }
        long unitPrice = EngGuard.requirePositiveCents(cmd.unitPrice());
        long freight = cmd.freight() == null || cmd.freight().compareTo(BigDecimal.ZERO) == 0 ? 0
                : EngGuard.requirePositiveCents(cmd.freight());
        FrEngMaterialDO material = materials.selectById(supply.getMaterialId());
        if (material == null || FrEngMaterialDO.STATUS_CANCELLED.equals(material.getStatus())) {
            throw exception(ENGINEERING_INPUT_INVALID, "材料已取消，无需记录采购金额");
        }
        // 已付款锁定：金额/凭据沿用原值（蓝本 :64）
        if (supply.getPayableId() != null && ledger.hasActivePayment(post, supply.getPayableId())) {
            boolean same = supply.getUnitPriceCents() != null && supply.getUnitPriceCents() == unitPrice
                    && supply.getFreightCents() != null && supply.getFreightCents() == freight
                    && cmd.supplier().equals(supply.getSupplier())
                    && StrUtil.nullToEmpty(cmd.basis()).equals(StrUtil.nullToEmpty(supply.getBasis()));
            if (!same) {
                throw exception(MATERIAL_INVALID, "已有付款，请沿用原采购金额和凭据，补证请在原付款事项办理");
            }
            commands.record(post, AGGREGATE, supply.getProjectId(),
                projectService.bumpRevision(post, projectService.requireProject(post, supply.getProjectId())),
                "eng-material-terms-replay",
                    termsPayload(supplyId, cmd), idempotencyKey);
            return supplyView(post, supply);
        }
        // 应付=单价×数量+运费（数量三位小数→分，HALF_UP）
        long amountCents = BigDecimal.valueOf(unitPrice).multiply(material.getQuantity())
                .setScale(0, java.math.RoundingMode.HALF_UP).longValueExact() + freight;
        String sourceRef = "engineering-payable:" + supply.getProjectId() + ":" + supplyId;
        var payableCommand=new EngineeringPayablePort.PayableCommand(
                supply.getProjectId(), sourceRef, StrUtil.trim(cmd.supplier()), amountCents, StrUtil.trim(cmd.basis()));
        long payableId;
        if(supply.getPayableId()==null) payableId=payables.registerPayable(post,payableCommand);
        else {
            payableId=supply.getPayableId();
            payables.updateUnpaid(post,payableId,payableCommand,"eng-terms:"+cn.hutool.crypto.digest.DigestUtil.sha256Hex(idempotencyKey));
        }
        supplies.updateById(new FrEngSupplyDO().setId(supplyId)
                .setSupplier(StrUtil.trim(cmd.supplier())).setContact(StrUtil.nullToEmpty(cmd.contact()))
                .setPromiseDate(cmd.promiseDate())
                .setUnitPriceCents(unitPrice).setFreightCents(freight)
                .setBasis(StrUtil.trim(cmd.basis())).setPayer(FrEngSupplyDO.PAYER_STORE)
                .setStatus(FrEngSupplyDO.STATUS_READY_FINANCE)
                .setTermsUserId(post.userId()).setTermsAt(java.time.OffsetDateTime.now())
                .setPayableId(payableId).setPaid(false));
        commands.record(post, AGGREGATE, supply.getProjectId(),
                projectService.bumpRevision(post, project),
                "eng-material-terms", termsPayload(supplyId, cmd), idempotencyKey);
        return supplyView(post, supplies.selectById(supplyId));
    }

    /** 质检处置中的编号兼容小程序字符串及整数输入。 */
    private static long resolutionId(Map<String, Object> resolution, String field) {
        try {
            return Long.parseLong(String.valueOf(resolution.get(field)).trim());
        } catch (NumberFormatException invalid) {
            throw exception(ENGINEERING_INPUT_INVALID, "质检处置缺少有效的批次或凭证");
        }
    }

    private static Map<String, Object> termsPayload(long supplyId, TermsCommand cmd) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("supplyId", supplyId);
        m.put("supplier", StrUtil.trim(cmd.supplier()));
        m.put("unitPrice", cmd.unitPrice() == null ? null : cmd.unitPrice().stripTrailingZeros().toPlainString());
        m.put("freight", cmd.freight() == null ? "0" : cmd.freight().stripTrailingZeros().toPlainString());
        m.put("basis",StrUtil.trim(cmd.basis()));
        m.put("contact",StrUtil.nullToEmpty(cmd.contact()));
        m.put("promiseDate",cmd.promiseDate()==null?null:cmd.promiseDate().toString());
        return m;
    }

    // ===================== 到货（流水唯一 005；◆质检/差异自动补发） =====================

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> arrival(PostSnapshot post, long supplyId, ArrivalCommand cmd, String idempotencyKey) {
        FrEngSupplyDO supply = supplies.selectById(supplyId);
        if (supply == null || supply.getCompanyId() != post.orgId()) {
            throw exception(ENGINEERING_INPUT_INVALID, "供应事项不存在");
        }
        projects.lockById(supply.getProjectId());
        projectService.requireOwned(post, supply.getProjectId(), "materials");
        // 分支判定+payload 归一先于重放：重放输入=落账输入，三形态同形（评审十三 P1-3）
        // 形态③：质检处置（对既有 REJECTED 行补「更换后合格」；顶层凭据/流水不适用）
        String command;
        Map<String, Object> legacy = new LinkedHashMap<>();
        if (cmd.qualityResolution() != null && cmd.parentReceiptId() == null && cmd.actual() == null) {
            command = "eng-supply-quality";
            legacy.put("receiptId", resolutionId(cmd.qualityResolution(), "receiptId"));
        } else if (cmd.parentReceiptId() != null) {
            command = "eng-reship-receive";
            legacy.put("receiptId", cmd.parentReceiptId());
            legacy.put("actual", EngGuard.requireQuantity(cmd.actual()).toPlainString());
        } else {
            command = "eng-supply-arrival";
            legacy.put("supplyId", supplyId);
            legacy.put("actual", EngGuard.requireQuantity(cmd.actual()).toPlainString());
            legacy.put("quality", StrUtil.blankToDefault(cmd.quality(), FrEngSupplyReceiptDO.QUALITY_OK));
        }
        var payload = new LinkedHashMap<>(legacy);
        payload.put("command", command);
        payload.put("supplyId", supplyId);
        if ("eng-supply-quality".equals(command)) {
            payload.put("fileAssetId", resolutionId(cmd.qualityResolution(), "fileAssetId"));
            payload.put("note", java.util.Objects.toString(cmd.qualityResolution().get("note"), ""));
        } else {
            payload.put("expected", cmd.expected() == null ? null : cmd.expected().stripTrailingZeros().toPlainString());
            payload.put("quality", StrUtil.blankToDefault(cmd.quality(), FrEngSupplyReceiptDO.QUALITY_OK));
            payload.put("transactionNo", StrUtil.trim(cmd.transactionNo()));
            payload.put("fileAssetId", cmd.fileAssetId());
            payload.put("occurredAt", cmd.occurredAt() == null ? null : cmd.occurredAt().toString());
            payload.put("note", StrUtil.nullToEmpty(cmd.note()));
        }
        var replayed = commands.replayed(post, idempotencyKey, payload, legacy);
        if (replayed.isPresent()) {
            var event = replayed.get();
            if (!command.equals(event.getCommand()) || !AGGREGATE.equals(event.getAggregateType())
                    || !supply.getProjectId().equals(event.getAggregateId())) {
                throw exception(IDEMPOTENCY_KEY_REUSED);
            }
            if (!JsonUtils.parseTree(event.getPayload()).has("command")) {
                EngLegacyArrival.verify(command, cmd, payload, event, supplyReceipts.selectBySupply(post.orgId(), supplyId),
                        jdbcTemplate);
            }
            return supplyView(post, supply);
        }
        LocalDate occurredAt = cmd.occurredAt() == null ? LocalDate.now() : cmd.occurredAt();
        if (occurredAt.isAfter(LocalDate.now())) {
            throw exception(ENGINEERING_INPUT_INVALID, "收货日期不能晚于今天");
        }
        if (cmd.qualityResolution() != null && cmd.parentReceiptId() == null && cmd.actual() == null) {
            // R3 P1-5：质检处置凭据无条件过域侧校验（Number/String 双形状解析——假 ID/跨组织/未 ACCEPTED/类型不符全拒）
            long resolutionAssetId = resolutionId(cmd.qualityResolution(), "fileAssetId");
            planService.requireValidAsset(post, resolutionAssetId,
                    java.util.Set.of("ENG_SITE_PHOTO"), "质检处置");
            FrEngSupplyReceiptDO receipt = requireReceipt(post, cmd.qualityResolution());
            if (!java.util.Objects.equals(receipt.getSupplyId(), supply.getId())) throw exception(ENGINEERING_INPUT_INVALID, "收货批次与供货单不符");
            resolveQuality(post, receipt, cmd.qualityResolution());
            commands.record(post, AGGREGATE, supply.getProjectId(),
                projectService.bumpRevision(post, projectService.requireProject(post, supply.getProjectId())),
                "eng-supply-quality", payload, idempotencyKey);
            return supplyView(post, supply);
        }
        if (cmd.fileAssetId() == null || StrUtil.isBlank(cmd.transactionNo())) {
            throw exception(ENGINEERING_INPUT_INVALID, "请填写收货流水并留存到货凭据");
        }
        // M07-T04 批次#36：到货凭据资产域侧校验（假 ID/跨组织/未 ACCEPTED/类型不符全拒）
        planService.requireValidAsset(post, cmd.fileAssetId(),
                java.util.Set.of("ENG_SITE_PHOTO"), "到货");
        // 形态②：补发到货（parentReceiptId=RESHIP 行）
        if (cmd.parentReceiptId() != null) {
            FrEngSupplyReceiptDO reship = supplyReceipts.selectById(cmd.parentReceiptId());
            if (reship == null || reship.getCompanyId() != post.orgId()
                    || !FrEngSupplyReceiptDO.KIND_RESHIP.equals(reship.getKind())
                    || reship.getSupplyId() != supplyId || reship.getActualQuantity() != null) {
                throw exception(ENGINEERING_INPUT_INVALID, "补发记录不存在或已登记实收");
            }
            BigDecimal actual = EngGuard.requireQuantity(cmd.actual());
            // 补发回填同样受计划量累计上限约束（评审十三 P3-3：此前经「更正+补发」链路可超计划）。
            // scale-3 定点数精确比较，不加容差——补发恰可补齐计划缺口（received+actual == quantity）必须放行
            FrEngMaterialDO reshipMaterial = materials.selectById(supply.getMaterialId());
            if (reshipMaterial != null && EngGuard.goodQuantity(supplyReceipts.selectBySupply(post.orgId(), supply.getId()))
                    .add(actual).compareTo(reshipMaterial.getQuantity()) > 0) {
                throw exception(MATERIAL_INVALID, "补发回填后累计到货超过计划数量，请先更正原记录");
            }
            requireTxnFree(post, cmd.transactionNo());
            supplyReceipts.updateById(new FrEngSupplyReceiptDO().setId(reship.getId())
                    .setActualQuantity(actual)
                    .setQuality(StrUtil.blankToDefault(cmd.quality(), FrEngSupplyReceiptDO.QUALITY_OK))
                    .setTransactionNo(StrUtil.trim(cmd.transactionNo())).setOccurredAt(occurredAt)
                    .setFileAssetId(cmd.fileAssetId()).setNote(StrUtil.nullToEmpty(cmd.note())));
            commands.record(post, AGGREGATE, supply.getProjectId(),
                projectService.bumpRevision(post, projectService.requireProject(post, supply.getProjectId())),
                "eng-reship-receive", payload, idempotencyKey);
            return supplyView(post, supply);
        }
        // 形态①：新收货（RECEIPT 行 + 差异自动 RESHIP）
        return newReceipt(post, supply, cmd, occurredAt, idempotencyKey, command, payload);
    }

    private FrEngSupplyReceiptDO requireReceipt(PostSnapshot post, Map<String, Object> resolution) {
        long receiptId = resolutionId(resolution, "receiptId");
        FrEngSupplyReceiptDO receipt = supplyReceipts.selectById(receiptId);
        if (receipt == null || receipt.getCompanyId() != post.orgId()
                || !FrEngSupplyReceiptDO.QUALITY_REJECTED.equals(receipt.getQuality())) {
            throw exception(ENGINEERING_INPUT_INVALID, "该批次无待处理质量异常");
        }
        return receipt;
    }

    private void resolveQuality(PostSnapshot post, FrEngSupplyReceiptDO receipt, Map<String, Object> resolution) {
        String note = java.util.Objects.toString(resolution.get("note"), "");
        if (StrUtil.isBlank(note)) {
            throw exception(ENGINEERING_INPUT_INVALID, "请填写质检处置说明并留存凭据");
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("result", "更换后合格");
        row.put("note", note);
        row.put("fileAssetId", resolutionId(resolution, "fileAssetId"));
        int updated = jdbcTemplate.update("UPDATE fr_eng_supply_receipt SET quality_resolution = CAST(? AS jsonb), "
                        + "update_time = now() WHERE id = ? AND company_id = ? AND quality_resolution->>'result' IS DISTINCT FROM '更换后合格'",
                cn.iocoder.yudao.framework.common.util.json.JsonUtils.toJsonString(row),
                receipt.getId(), post.orgId());
        if (updated != 1) throw exception(ENGINEERING_INPUT_INVALID, "该批次已处置");
    }

    private Map<String, Object> newReceipt(PostSnapshot post, FrEngSupplyDO supply, ArrivalCommand cmd,
                                           LocalDate occurredAt, String idempotencyKey,
                                           String command, Map<String, Object> payload) {
        FrEngMaterialDO material = materials.selectById(supply.getMaterialId());
        if (material == null || FrEngMaterialDO.STATUS_CANCELLED.equals(material.getStatus())) {
            throw exception(ENGINEERING_INPUT_INVALID, "材料已取消，不能确认到货");
        }
        BigDecimal actual = EngGuard.requireQuantity(cmd.actual());
        String quality = StrUtil.blankToDefault(cmd.quality(), FrEngSupplyReceiptDO.QUALITY_OK);
        if (!FrEngSupplyReceiptDO.QUALITY_OK.equals(quality) && !FrEngSupplyReceiptDO.QUALITY_REJECTED.equals(quality)) {
            throw exception(ENGINEERING_INPUT_INVALID, "请选择有效质量结论");
        }
        // 行锁（FOR UPDATE）串行化累计上限读-判-写（评审十三 P2-1：并发到货可越过计划量）
        BigDecimal received = EngGuard.goodQuantity(supplyReceipts.selectBySupply(post.orgId(), supply.getId()));
        BigDecimal expected = cmd.expected() != null ? cmd.expected()
                : material.getQuantity().subtract(received).max(BigDecimal.ZERO);
        if (received.add(actual).compareTo(material.getQuantity().add(new BigDecimal("0.0001"))) > 0) {
            throw exception(MATERIAL_INVALID, "累计到货超过计划数量，请核对原记录");
        }
        requireTxnFree(post, cmd.transactionNo());
        FrEngSupplyReceiptDO receipt = new FrEngSupplyReceiptDO().setCompanyId(post.orgId())
                .setProjectId(supply.getProjectId()).setSupplyId(supply.getId())
                .setKind(FrEngSupplyReceiptDO.KIND_RECEIPT)
                .setTransactionNo(StrUtil.trim(cmd.transactionNo()))
                .setExpectedQuantity(expected).setActualQuantity(actual)
                .setQuality(quality).setOccurredAt(occurredAt).setFileAssetId(cmd.fileAssetId())
                .setNote(StrUtil.nullToEmpty(cmd.note())).setAuthorUserId(post.userId());
        long receiptId = cn.hutool.core.util.IdUtil.getSnowflakeNextId();
        jdbcTemplate.update("INSERT INTO fr_eng_supply_receipt (id, company_id, project_id, supply_id, "
                        + "kind, transaction_no, expected_quantity, actual_quantity, quality, occurred_at, "
                        + "file_asset_id, note, author_user_id, create_time, update_time) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,now(),now())",
                receiptId, post.orgId(), supply.getProjectId(),
                supply.getId(), FrEngSupplyReceiptDO.KIND_RECEIPT, StrUtil.trim(cmd.transactionNo()),
                expected, actual, quality, occurredAt, cmd.fileAssetId(),
                StrUtil.nullToEmpty(cmd.note()), post.userId());
        receipt.setId(receiptId); // 行由 jdbc 落库（jsonb 列禁 MP insert），补 id 供补发行挂靠
        // ◆差异自动补发行（数量=应到-实到，待补发到货回填 actual）
        if (expected.subtract(actual).compareTo(new BigDecimal("0.0001")) > 0) {
            long reshipId = cn.hutool.core.util.IdUtil.getSnowflakeNextId();
            jdbcTemplate.update("INSERT INTO fr_eng_supply_receipt (id, company_id, project_id, supply_id, "
                            + "kind, parent_receipt_id, transaction_no, expected_quantity, occurred_at, "
                            + "file_asset_id, note, author_user_id, create_time, update_time) "
                            + "VALUES (?,?,?,?,?,?,?, ?,?,?,?, ?,now(),now())",
                    reshipId, post.orgId(), supply.getProjectId(), supply.getId(),
                    FrEngSupplyReceiptDO.KIND_RESHIP, receipt.getId(), "RESHIP-" + reshipId,
                    expected.subtract(actual), occurredAt, cmd.fileAssetId(),
                    "差异自动补发（应到" + expected + "，实到" + actual + "）", post.userId());
        }
        commands.record(post, AGGREGATE, supply.getProjectId(),
                projectService.bumpRevision(post, projectService.requireProject(post, supply.getProjectId())),
                command, payload, idempotencyKey);
        return supplyView(post, supply);
    }

    private void requireTxnFree(PostSnapshot post, String transactionNo) {
        boolean exists = supplyReceipts.selectList(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FrEngSupplyReceiptDO>()
                                .eq(FrEngSupplyReceiptDO::getCompanyId, post.orgId())
                                .eq(FrEngSupplyReceiptDO::getTransactionNo, StrUtil.trim(transactionNo)))
                .size() > 0;
        if (exists) {
            throw exception(RECEIPT_DUPLICATE);
        }
    }

    // ===================== 实到更正（留痕 004 边界） =====================

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> arrivalCorrection(PostSnapshot post, long receiptId,
                                                 ArrivalCorrectionCommand cmd, String idempotencyKey) {
        FrEngSupplyReceiptDO receipt = supplyReceipts.selectById(receiptId);
        if (receipt == null || receipt.getCompanyId() != post.orgId()) {
            throw exception(ENGINEERING_INPUT_INVALID, "到货记录不存在");
        }
        projects.lockById(receipt.getProjectId());
        receipt = supplyReceipts.selectById(receiptId);
        FrEngSupplyDO supply = supplies.selectById(receipt.getSupplyId());
        FrEngMaterialDO material = materials.selectById(supply.getMaterialId());
        projectService.requireOwned(post, supply.getProjectId(), "materials");
        if (StrUtil.isBlank(cmd.reason()) || cmd.actual() == null || cmd.actual().scale() > 3
                || cmd.actual().compareTo(BigDecimal.ZERO) <= 0) {
            throw exception(ENGINEERING_INPUT_INVALID, "请填写更正原因与有效实到数量");
        }
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("receiptId", receiptId);
        legacy.put("to", cmd.actual().toPlainString());
        var payload = new LinkedHashMap<>(legacy);
        payload.put("to", cmd.actual().stripTrailingZeros().toPlainString());
        payload.put("reason", cmd.reason());
        var replayed = commands.replayed(post, idempotencyKey, payload, legacy);
        if (replayed.isPresent()) {
            if (legacyMaterialEvent(replayed.get(), supply.getProjectId(), "eng-arrival-correct", "reason")) {
                var reasons = new java.util.HashSet<String>();
                if (receipt.getCorrections() != null) {
                    for (var row : JsonUtils.parseTree(receipt.getCorrections())) {
                        if (row.path("actorUserId").asLong() == post.userId()
                                && new BigDecimal(row.path("to").asText()).compareTo(cmd.actual()) == 0) {
                            reasons.add(row.path("reason").asText());
                        }
                    }
                }
                if (!reasons.contains(cmd.reason())) throw exception(IDEMPOTENCY_KEY_REUSED);
                if (reasons.size() > 1) throw exception(ENGINEERING_INPUT_INVALID, "该批次有多次同量更正，请核对历史原因");
            }
            return supplyView(post, supply);
        }
        if (receipt.getActualQuantity() == null) {
            throw exception(ENGINEERING_INPUT_INVALID, "这批货还未登记实收，请用确认到货");
        }
        BigDecimal q = cmd.actual();
        if (q.compareTo(receipt.getActualQuantity()) == 0) {
            throw exception(ENGINEERING_INPUT_INVALID, "更正后的数量与原记录相同");
        }
        var receipts = supplyReceipts.selectBySupply(post.orgId(), supply.getId());
        if (EngGuard.qualityPassed(receipt) && q.compareTo(receipt.getActualQuantity()) < 0) {
            BigDecimal correctedGood = EngGuard.goodQuantity(receipts).subtract(receipt.getActualQuantity()).add(q);
            if (correctedGood.compareTo(movements.allocatedQuantity(post.orgId(), supply.getProjectId(), material.getId())) < 0) {
                throw exception0(MATERIAL_INVALID.getCode(), "更正后合格到货量低于已登记去向的数量，请核对领用、损耗、退料和调拨记录");
            }
        }
        BigDecimal others = receipts.stream()
                .filter(r -> r.getId() != receiptId && r.getActualQuantity() != null)
                .map(FrEngSupplyReceiptDO::getActualQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal correctedTotal = others.add(q);
        if (correctedTotal.compareTo(material.getQuantity()) > 0) {
            throw exception(MATERIAL_INVALID, "更正后累计到货超过计划数量，请先调整需用数量");
        }
        FrEngPlanNodeDO planNode = planNodes.selectById(material.getNodeId());
        if (planNode != null && FrEngPlanNodeDO.STATUS_DONE.equals(planNode.getStatus())
                && correctedTotal.compareTo(material.getQuantity()) < 0) {
            throw exception(MATERIAL_INVALID, "本计划所属节点已完成，到货改少前请先撤回节点完成");
        }
        String corrections = EngPlanService.JsonList.append(receipt.getCorrections(), Map.of(
                "from", receipt.getActualQuantity().toPlainString(),
                "to", q.toPlainString(), "reason", cmd.reason(),
                "date", LocalDate.now().toString(), "actorUserId", post.userId()));
        jdbcTemplate.update("UPDATE fr_eng_supply_receipt SET actual_quantity = ?, expected_quantity = ?, "
                + "corrections = CAST(? AS jsonb), update_time = now() WHERE id = ? AND company_id = ?",
                q, q, corrections, receiptId, post.orgId());
        commands.record(post, AGGREGATE, supply.getProjectId(),
                projectService.bumpRevision(post, projectService.requireProject(post, supply.getProjectId())),
                "eng-arrival-correct", payload, idempotencyKey);
        return supplyView(post, supply);
    }

    // ===================== ◆材料去向 =====================

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> movement(PostSnapshot post, long projectId, MovementCommand cmd, String idempotencyKey) {
        projectService.requireProject(post, projectId);
        FrEngProjectDO project = projects.lockById(projectId);
        projectService.requireOwned(post, projectId, "materials");
        // 数量先归一再统一 payload（重放输入=落账输入——评审十三 P1-3）
        BigDecimal q = EngGuard.requireQuantity(cmd.quantity());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("materialId", cmd.materialId());
        payload.put("kind", cmd.kind());
        payload.put("quantity", q.toPlainString());
        var legacy = new LinkedHashMap<>(payload);
        payload.put("projectId", projectId);
        payload.put("note", StrUtil.trim(cmd.note()));
        payload.put("receiverUserId", cmd.receiverUserId());
        var replayed = commands.replayed(post, idempotencyKey, payload, legacy);
        if (replayed.isPresent()) {
            if (legacyMaterialEvent(replayed.get(), projectId, "eng-material-movement", "projectId")) {
                var candidates = movements.selectByProject(post.orgId(), projectId).stream()
                        .filter(row -> row.getMaterialId() == cmd.materialId() && row.getAuthorUserId() == post.userId()
                                && cmd.kind().equals(row.getKind()) && q.compareTo(row.getQuantity()) == 0).toList();
                long matches = candidates.stream()
                        .filter(row -> java.util.Objects.equals(row.getReceiverUserId(), cmd.receiverUserId())
                                && java.util.Objects.equals(row.getNote(), payload.get("note"))).count();
                if (matches == 0) throw exception(IDEMPOTENCY_KEY_REUSED);
                if (matches != candidates.size()) throw exception(ENGINEERING_INPUT_INVALID, "存在多笔相同数量的材料流转，请核对原单");
            }
            return summary(post, projectId, null);
        }
        FrEngMaterialDO material = materials.selectById(cmd.materialId());
        if (material == null || material.getCompanyId() != post.orgId()
                || material.getProjectId() != projectId) {
            throw exception(ENGINEERING_INPUT_INVALID, "材料需求不存在");
        }
        if (!List.of(FrEngMaterialMovementDO.KIND_CONSUME, FrEngMaterialMovementDO.KIND_DAMAGE,
                FrEngMaterialMovementDO.KIND_RETURN, FrEngMaterialMovementDO.KIND_SURPLUS_TRANSFER)
                .contains(cmd.kind()) || StrUtil.isBlank(cmd.note())) {
            throw exception(ENGINEERING_INPUT_INVALID, "请选择材料去向并说明用途或原因");
        }
        // 行锁串行化可分配量读-判-写（评审十三 P2-1）
        FrEngSupplyDO supply = supplies.selectByMaterial(post.orgId(), material.getId());
        BigDecimal good = EngGuard.goodQuantity(supplyReceipts.selectBySupply(post.orgId(), supply.getId()));
        BigDecimal used = movements.allocatedQuantity(post.orgId(), projectId, material.getId());
        if (q.compareTo(good.subtract(used)) > 0) {
            throw exception(ENGINEERING_INPUT_INVALID, "本批可分配材料不足，不能重复耗用或退料");
        }
        movements.insert(new FrEngMaterialMovementDO().setCompanyId(post.orgId()).setProjectId(projectId)
                .setMaterialId(material.getId()).setKind(cmd.kind()).setQuantity(q)
                .setSettlementStatus(FrEngMaterialMovementDO.KIND_RETURN.equals(cmd.kind())
                        ? "PENDING_SETTLE" : "REGISTERED")
                .setReceiverUserId(cmd.receiverUserId())
                .setNote(StrUtil.trim(cmd.note())).setAuthorUserId(post.userId()));
        // revision 走 bumpRevision：固定 0 会撞 pf_command_event 唯一约束，第二次登记必炸（评审十三 P1-2）
        commands.record(post, AGGREGATE, projectId, projectService.bumpRevision(post, project),
                "eng-material-movement", payload, idempotencyKey);
        return summary(post, projectId, null);
    }

    private static boolean legacyMaterialEvent(cn.iocoder.yudao.module.platform.command.dal.CommandEventDO event,
                                               long projectId, String command, String addedField) {
        if (!command.equals(event.getCommand()) || !AGGREGATE.equals(event.getAggregateType())
                || event.getAggregateId() != projectId) throw exception(IDEMPOTENCY_KEY_REUSED);
        return !JsonUtils.parseTree(event.getPayload()).has(addedField);
    }

    // ===================== 汇总（到货+付款派生态，蓝本 plans()） =====================

    public Map<String, Object> summary(PostSnapshot post, long projectId, Long materialPlanId) {
        FrEngProjectDO project = projects.selectById(projectId);
        if (project == null || project.getCompanyId() != post.orgId()) {
            throw exception(ENGINEERING_INPUT_INVALID, "工程不存在");
        }
        List<FrEngMaterialDO> rows = materials.selectByProject(post.orgId(), projectId).stream()
                .filter(m -> materialPlanId == null || materialPlanId.equals(m.getMaterialPlanId())).toList();
        List<FrEngSupplyDO> supplyRows = supplies.selectByProject(post.orgId(), projectId);
        List<FrEngSupplyReceiptDO> receiptRows = supplyReceipts.selectByProject(post.orgId(), projectId);
        Map<Long, Long> paidByPayable = ledger.paidCentsByPayableIds(post,
                supplyRows.stream().map(FrEngSupplyDO::getPayableId).filter(java.util.Objects::nonNull).toList());
        Map<Long,Boolean> lockedByPayable=ledger.activePaymentsByPayableIds(post,
                supplyRows.stream().map(FrEngSupplyDO::getPayableId).filter(java.util.Objects::nonNull).toList());
        boolean full = EngProjectService.isFinanceOrAbove(post)
                || post.userId() == project.getManagerUserId()
                || (project.getAssignedEngineerUserId() != null && post.userId() == project.getAssignedEngineerUserId());

        Map<Long, List<FrEngSupplyReceiptDO>> bySupply = new LinkedHashMap<>();
        for (FrEngSupplyReceiptDO r : receiptRows) {
            bySupply.computeIfAbsent(r.getSupplyId(), k -> new ArrayList<>()).add(r);
        }
        List<Map<String, Object>> plans = new ArrayList<>();
        Map<Long, List<Map<String, Object>>> planGroups = new LinkedHashMap<>();
        for (FrEngMaterialDO m : rows) {
            FrEngSupplyDO s = supplyRows.stream().filter(x -> x.getMaterialId() != null && x.getMaterialId().equals(m.getId())).findFirst().orElse(null);
            List<FrEngSupplyReceiptDO> receipts = s == null ? List.of() : bySupply.getOrDefault(s.getId(), List.of());
            BigDecimal received = EngGuard.goodQuantity(receipts);
            long total = s == null || s.getUnitPriceCents() == null || s.getUnitPriceCents() == 0 ? 0
                    : BigDecimal.valueOf(s.getUnitPriceCents()).multiply(m.getQuantity())
                            .setScale(0, java.math.RoundingMode.HALF_UP).longValueExact()
                            + (s.getFreightCents() == null ? 0 : s.getFreightCents());
            if(s!=null && s.getPayableId()!=null){var financial=jdbcTemplate.queryForList("SELECT total_cents FROM fr_fin_payable WHERE id=? AND company_id=?",Long.class,s.getPayableId(),post.orgId());if(!financial.isEmpty())total=financial.get(0);}

            long paid = s == null || s.getPayableId() == null ? 0 : paidByPayable.getOrDefault(s.getPayableId(), 0L);
            boolean cancelled = FrEngMaterialDO.STATUS_CANCELLED.equals(m.getStatus());
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("materialId", m.getId());
            line.put("nodeId", String.valueOf(m.getNodeId()));
            line.put("name", m.getName());
            line.put("spec", m.getSpec());
            line.put("unit", m.getUnit());
            line.put("quantity", m.getQuantity());
            line.put("requiredDate", m.getRequiredDate());
            line.put("cancelled", cancelled);
            line.put("termsLocked",s!=null && s.getPayableId()!=null && !Boolean.FALSE.equals(lockedByPayable.get(s.getPayableId())));
            line.put("received", received);
            line.put("remaining", cancelled ? BigDecimal.ZERO
                    : m.getQuantity().subtract(received).max(BigDecimal.ZERO));
            line.put("paymentStatus", cancelled ? "已取消"
                    : total == 0 ? "待记录采购金额" : paid >= total ? "已付清" : paid > 0 ? "部分已付" : "待付款");
            if (full) {
                // 这里是实收历史。待补发行只影响剩余量提示，不是实收、质检或可更正记录。
                line.put("receipts", receipts.stream().filter(r -> r.getActualQuantity() != null).map(r -> {
                    Map<String, Object> receipt = new LinkedHashMap<>();
                    receipt.put("id", String.valueOf(r.getId()));
                    receipt.put("actualQuantity", r.getActualQuantity());
                    receipt.put("quality", r.getQuality());
                    receipt.put("qualified", EngGuard.qualityPassed(r));
                    receipt.put("occurredAt", r.getOccurredAt());
                    receipt.put("transactionNo", r.getTransactionNo());
                    receipt.put("fileAssetId", r.getFileAssetId());
                    receipt.put("corrections", r.getCorrections());
                    return receipt;
                }).toList());
                line.put("supplyId", s == null ? null : s.getId());
                line.put("payableId",s==null || s.getPayableId()==null?null:String.valueOf(s.getPayableId()));
                line.put("supplier", s == null ? "" : StrUtil.nullToEmpty(s.getSupplier()));
                line.put("totalCents", total);
                line.put("paidCents", paid);
                line.put("remainingCents", Math.max(0, total - paid));
            }
            planGroups.computeIfAbsent(m.getMaterialPlanId(), k -> new ArrayList<>()).add(line);
        }
        for (Map.Entry<Long, List<Map<String, Object>>> group : planGroups.entrySet()) {
            List<Map<String, Object>> lines = group.getValue();
            List<Map<String, Object>> active = lines.stream()
                    .filter(l -> !Boolean.TRUE.equals(l.get("cancelled"))).toList();
            BigDecimal allReceived = BigDecimal.ZERO;
            BigDecimal allQuantity = BigDecimal.ZERO;
            for (Map<String, Object> l : lines) {
                allReceived = allReceived.add((BigDecimal) l.get("received"));
                allQuantity = allQuantity.add((BigDecimal) l.get("quantity"));
            }
            String arrival = active.isEmpty() ? "已取消"
                    : active.stream().allMatch(l -> ((BigDecimal) l.get("remaining")).compareTo(BigDecimal.ZERO) == 0)
                    ? "已验收齐全"
                    : active.stream().anyMatch(l -> ((BigDecimal) l.get("received")).compareTo(BigDecimal.ZERO) > 0)
                    ? "部分到货" : "待到货";
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("materialPlanId", group.getKey());
            plan.put("lines", lines);
            plan.put("arrival", arrival);
            if (full) {
                long total = lines.stream().mapToLong(l -> l.get("totalCents") == null ? 0
                        : (long) l.get("totalCents")).sum();
                long paid = lines.stream().mapToLong(l -> l.get("paidCents") == null ? 0
                        : (long) l.get("paidCents")).sum();
                plan.put("totalCents", total);
                plan.put("paidCents", paid);
                plan.put("remainingCents", Math.max(0, total - paid));
            }
            plans.add(plan);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("projectId", projectId);
        view.put("plans", plans);
        return view;
    }

    Map<String, Object> supplyView(PostSnapshot post, FrEngSupplyDO supply) {
        FrEngMaterialDO material = materials.selectById(supply.getMaterialId());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("supplyId", supply.getId());
        view.put("materialId", supply.getMaterialId());
        view.put("materialName", material == null ? null : material.getName());
        view.put("status", supply.getStatus());
        FrEngProjectDO project=projects.selectById(supply.getProjectId());
        if(project!=null && project.getCompanyId()==post.orgId() && (EngProjectService.isFinanceOrAbove(post)
                || post.userId()==project.getManagerUserId()
                || (project.getAssignedEngineerUserId()!=null && post.userId()==project.getAssignedEngineerUserId()))) {
            view.put("payableId",supply.getPayableId()==null?null:String.valueOf(supply.getPayableId()));
        }
        view.put("paid", supply.getPayableId() != null
                && ledger.paidCents(post, supply.getPayableId()) > 0);
        view.put("termsLocked",supply.getPayableId()!=null && ledger.hasActivePayment(post,supply.getPayableId()));
        view.put("receipts", supplyReceipts.selectBySupply(post.orgId(), supply.getId()).stream()
                .map(r -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", r.getId());
                    row.put("kind", r.getKind());
                    row.put("parentReceiptId", r.getParentReceiptId());
                    row.put("transactionNo", r.getTransactionNo());
                    row.put("expectedQuantity", r.getExpectedQuantity());
                    row.put("actualQuantity", r.getActualQuantity());
                    row.put("quality", r.getQuality());
                    row.put("qualityResolution", r.getQualityResolution());
                    row.put("occurredAt", r.getOccurredAt());
                    row.put("corrections", r.getCorrections());
                    return row;
                }).toList());
        return view;
    }

    private void requireNotPaid(PostSnapshot post, FrEngSupplyDO supply) {
        if (supply.getPayableId() != null && ledger.hasActivePayment(post, supply.getPayableId())) {
            throw exception(MATERIAL_INVALID, "已有付款，请由财务在原付款事项处理");
        }
    }
}
