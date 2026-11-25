package cn.iocoder.yudao.module.franchise.engineering.content;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.franchise.api.EngineeringLedgerQueryPort;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngClosureDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngExpenseDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngClosureMapper;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngCollectionDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngCollectionMapper;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngCollectionTermDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngCollectionTermMapper;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngMaterialMapper;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngPlanNodeDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngPlanNodeMapper;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngProjectDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngSupplyDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngSupplyMapper;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.engineering.enums.FranchiseEngineeringErrorCodes.*;

/**
 * 尾段三段（D-11）：deliver（开工+全节点完成+材料齐全+全累计收款门禁）→ settle（返回结算屏汇总）→
 * archive；三动作 CAS 前置链 009。结算屏=材料聚合（自域）+直接费用汇总（M09-T04 真实数据源）+三期收款聚合。
 */
@Service
public class EngClosureService {

    public static final String AGGREGATE = "fr-eng-project";

    private final EngProjectService projectService;
    private final EngPlanService planService;
    private final FrEngClosureMapper closures;
    private final cn.iocoder.yudao.module.franchise.engineering.dal.FrEngExpenseMapper expenses;
    private final FrEngPlanNodeMapper planNodes;
    private final FrEngCollectionTermMapper collectionTerms;
    private final FrEngCollectionMapper collections;
    private final FrEngSupplyMapper supplies;
    private final FrEngMaterialMapper materials;
    private final FranchiseCommands commands;
    private final cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort unifiedStages;
    private final EngineeringLedgerQueryPort ledger;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    public EngClosureService(EngProjectService projectService, EngPlanService planService,
                             FrEngClosureMapper closures, cn.iocoder.yudao.module.franchise.engineering.dal.FrEngExpenseMapper expenses,
                             FrEngPlanNodeMapper planNodes,
                             FrEngCollectionTermMapper collectionTerms, FrEngCollectionMapper collections,
                             FrEngSupplyMapper supplies, FrEngMaterialMapper materials,
                             FranchiseCommands commands, EngineeringLedgerQueryPort ledger,
                             org.springframework.jdbc.core.JdbcTemplate jdbcTemplate,
                             cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort unifiedStages) {
        this.projectService = projectService;
        this.planService = planService;
        this.closures = closures;
        this.expenses = expenses;
        this.planNodes = planNodes;
        this.collectionTerms = collectionTerms;
        this.collections = collections;
        this.supplies = supplies;
        this.materials = materials;
        this.commands = commands;
        this.unifiedStages = unifiedStages;
        this.ledger = ledger;
        this.jdbcTemplate = jdbcTemplate;
    }

    public record DeliverCommand(LocalDate date, String recipient, Long ownerAssetId, Long fileAssetId, String note) {
    }

    public record SettleCommand(LocalDate date, String note, Long assetId) {
    }

    // ===================== 交付（全累计收款门禁 007） =====================

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> deliver(PostSnapshot post, long projectId, DeliverCommand cmd, String idempotencyKey) {
        // R3 P2：重放检查提到动作域白名单之前——已交付项目同键换资产重放必须命中既有记录
        //（否则白名单拒绝 FORBIDDEN 掩盖了「换资产提交」的真实语义）；指纹含两资产 ID
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectId", projectId);
        payload.put("date", cmd.date() == null ? "" : cmd.date().toString());
        payload.put("recipient", StrUtil.nullToEmpty(cmd.recipient()));
        payload.put("ownerAssetId", cmd.ownerAssetId() == null ? "" : String.valueOf(cmd.ownerAssetId()));
        payload.put("fileAssetId", cmd.fileAssetId() == null ? "" : String.valueOf(cmd.fileAssetId()));
        var replayed = commands.replayed(post, idempotencyKey, payload);
        if (replayed.isPresent()) {
            return projectService.detail(post, projectId);
        }
        FrEngProjectDO project = projectService.requireOwned(post, projectId, "deliver");
        List<FrEngPlanNodeDO> nodes = planNodes.selectByProject(post.orgId(), projectId);
        if (project.getActualStartAt() == null
                || nodes.stream().anyMatch(n -> !FrEngPlanNodeDO.STATUS_DONE.equals(n.getStatus()))) {
            throw exception(CLOSURE_ORDER_INVALID); // 请先记录全部施工节点完成
        }
        if (!planService.materialReadyForNode(post, projectId, null)) {
            throw exception(GATE_NOT_MET); // 请先核对材料到货
        }
        // Keep the project's frozen contract; a replacement contract must not clear this project.
        Long contractId = project.getSaleContractId();
        if (contractId == null && project.getCustomerId() != null) {
            contractId = unifiedStages.activeContractId(post, project.getCustomerId());
        }
        if (contractId != null) {
            // An installment must be covered by its own net receipts in the current contract plan.
            boolean outstanding = Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM fr_sale_payment_stage s
                        LEFT JOIN (
                    """ + cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort.STAGE_NET + """
                        ) net ON net.company_id=s.company_id AND net.stage_id=s.id
                        WHERE s.contract_id=? AND s.company_id=? AND NOT s.deleted AND NOT s.after_delivery
                          AND COALESCE(net.collected,0)<s.amount_cents
                    )
                    """, Boolean.class, contractId, post.orgId()));
            if (outstanding) throw exception(GATE_NOT_MET);
        } else {
            long expected = collectionTerms.selectByProject(post.orgId(), projectId).stream()
                    .mapToLong(FrEngCollectionTermDO::getAmountCents).sum();
            long received = collections.selectByProject(post.orgId(), projectId).stream()
                    .mapToLong(FrEngCollectionDO::getAmountCents).sum();
            if (received < expected) throw exception(GATE_NOT_MET);
        }
        LocalDate date = cmd.date() == null ? LocalDate.now() : cmd.date();
        if (StrUtil.isBlank(cmd.recipient()) || cmd.fileAssetId() == null || date.isAfter(LocalDate.now())) {
            throw exception(ENGINEERING_INPUT_INVALID, "请留存业主签收人及交付凭据");
        }
        // M09-T01/I01：交付凭据资产域侧校验（复用 EngPlanService 同款——假 ID/跨组织/未 ACCEPTED/类型不符全拒）
        java.util.Set<String> deliverTypes = java.util.Set.of("ENG_SITE_PHOTO", "ENG_DRAWING");
        planService.requireValidAsset(post, cmd.fileAssetId(), deliverTypes, "交付");
        if (cmd.ownerAssetId() != null) { // 业主签收凭据（可空字段，填了就校验）
            planService.requireValidAsset(post, cmd.ownerAssetId(), deliverTypes, "业主签收");
        }
        closures.updateById(new FrEngClosureDO().setId(closures.selectByProject(post.orgId(), projectId).getId())
                .setDeliveredAt(date).setRecipient(StrUtil.trim(cmd.recipient()))
                .setOwnerAssetId(cmd.ownerAssetId()).setFileAssetId(cmd.fileAssetId())
                .setDeliverNote(StrUtil.nullToEmpty(cmd.note())).setDeliveredBy(post.userId()));
        projectService.recalcStatus(post, project);
        commands.record(post, AGGREGATE, projectId,
                projectService.bumpRevision(post, project),
                "eng-deliver", payload, idempotencyKey);
        return projectService.detail(post, projectId);
    }

    // ===================== 结算（返回结算屏汇总） =====================

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> settle(PostSnapshot post, long projectId, SettleCommand cmd, String idempotencyKey) {
        FrEngProjectDO project = projectService.requireOwned(post, projectId, "settle");
        // 重放输入=落账输入（评审十三 P1-3）：note 一并纳入两处，杜绝重放哈希错位
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("date", cmd.date() == null ? "" : cmd.date().toString());
        payload.put("note", StrUtil.nullToEmpty(cmd.note()));
        var replayed = commands.replayed(post, idempotencyKey, payload);
        if (replayed.isPresent()) {
            return settleSummary(post, projectId);
        }
        FrEngClosureDO closure = closures.selectByProject(post.orgId(), projectId);
        if (closure == null || closure.getDeliveredAt() == null) {
            throw exception(CLOSURE_ORDER_INVALID); // 请在交付后确认项目结算
        }
        if (closure.getSettledAt() != null) {
            throw exception(CLOSURE_ORDER_INVALID); // 已结算（幂等重放已被 commands 拦截，此处为状态防重）
        }
        if (StrUtil.isBlank(cmd.note())) {
            throw exception(ENGINEERING_INPUT_INVALID, "请填写结算确认说明");
        }
        LocalDate date = cmd.date() == null ? LocalDate.now() : cmd.date();
        closures.updateById(new FrEngClosureDO().setId(closure.getId())
                .setSettledAt(date).setSettleNote(StrUtil.trim(cmd.note()))
                .setSettleAssetId(cmd.assetId()).setSettledBy(post.userId()));
        commands.record(post, AGGREGATE, projectId,
                projectService.bumpRevision(post, project),
                "eng-settle", payload, idempotencyKey);
        return settleSummary(post, projectId);
    }

    // ===================== 归档 =====================

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> archive(PostSnapshot post, long projectId, String idempotencyKey) {
        FrEngProjectDO project = projectService.requireOwned(post, projectId, "archive");
        var replayed = commands.replayed(post, idempotencyKey, Map.of("projectId", projectId));
        if (replayed.isPresent()) {
            return projectService.detail(post, projectId);
        }
        FrEngClosureDO closure = closures.selectByProject(post.orgId(), projectId);
        if (closure == null || closure.getDeliveredAt() == null) {
            throw exception(CLOSURE_ORDER_INVALID); // 请先登记交付
        }
        if (closure.getSettledAt() == null) {
            throw exception(CLOSURE_ORDER_INVALID); // 请先确认项目结算，再归档项目资料
        }
        closures.updateById(new FrEngClosureDO().setId(closure.getId())
                .setArchivedAt(java.time.OffsetDateTime.now()).setArchivedBy(post.userId()));
        projectService.recalcStatus(post, project);
        commands.record(post, AGGREGATE, projectId,
                projectService.bumpRevision(post, project),
                "eng-archive", Map.of("projectId", projectId), idempotencyKey);
        return projectService.detail(post, projectId);
    }

    // ===================== 结算屏汇总（蓝本 settleSummary :160） =====================

    public Map<String, Object> settleSummary(PostSnapshot post, long projectId) {
        FrEngProjectDO project = projectService.requireProject(post, projectId);
        // 金额脱敏（AC12/D-13）：工程岗非负责人不得读取收付款金额（评审十三 P1-1）
        boolean full = EngProjectService.isFinanceOrAbove(post)
                || post.userId() == project.getManagerUserId()
                || (project.getAssignedEngineerUserId() != null
                        && post.userId() == project.getAssignedEngineerUserId());
        var supplyRows = supplies.selectByProject(post.orgId(), projectId);
        Map<Long, Long> paidByPayable = ledger.paidCentsByPayableIds(post,
                supplyRows.stream()
                        .map(FrEngSupplyDO::getPayableId).filter(java.util.Objects::nonNull).toList());
        long materialsTotal = 0;
        long materialsPaid = 0;
        for (FrEngSupplyDO s : supplyRows) {
            var material = materials.selectById(s.getMaterialId());
            if (material == null || cn.iocoder.yudao.module.franchise.engineering.dal.FrEngMaterialDO
                    .STATUS_CANCELLED.equals(material.getStatus()) || s.getUnitPriceCents() == null
                    || s.getUnitPriceCents() == 0) {
                continue;
            }
            long originalTotal=BigDecimal.valueOf(s.getUnitPriceCents()).multiply(material.getQuantity()).setScale(0,java.math.RoundingMode.HALF_UP).longValueExact()+(s.getFreightCents()==null?0:s.getFreightCents());
            var financial=s.getPayableId()==null?java.util.List.<Long>of():jdbcTemplate.queryForList("SELECT total_cents FROM fr_fin_payable WHERE id=? AND company_id=?",Long.class,s.getPayableId(),post.orgId());
            materialsTotal+=financial.isEmpty()?originalTotal:financial.get(0);
            materialsPaid += s.getPayableId() == null ? 0 : paidByPayable.getOrDefault(s.getPayableId(), 0L);
        }
        List<Map<String, Object>> stages = new java.util.ArrayList<>();
        long receivedTotal = 0;
        Long summaryContractId=project.getSaleContractId()!=null?project.getSaleContractId():project.getCustomerId()==null?null:unifiedStages.activeContractId(post,project.getCustomerId());
        var balances=summaryContractId==null?List.<cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort.StageBalance>of():unifiedStages.stageBalances(post,summaryContractId);
        var stageNames=balances==null||balances.isEmpty()?List.of(FrEngCollectionTermDO.STAGE_SIGN_AFTER,FrEngCollectionTermDO.STAGE_BASE_DONE,FrEngCollectionTermDO.STAGE_CAP_DONE):balances.stream().map(cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort.StageBalance::stage).toList();
        for (String stage : stageNames) {
            var current=balances==null?null:balances.stream().filter(b->stage.equals(b.stage())).findFirst().orElse(null);
            long expected = current!=null?current.expectedCents():collectionTerms.selectByProject(post.orgId(), projectId).stream()
                    .filter(t -> stage.equals(t.getStage())).mapToLong(FrEngCollectionTermDO::getAmountCents)
                    .findFirst().orElse(0L);
            long confirmed;
            // M07-T06/R2 P1-1：统一余额贯穿读侧——有统一合同时期累计经统一端口；无合同回退工程留痕行
            Long uContractId = summaryContractId;
            if (uContractId != null) {
                confirmed = unifiedStages.stageCollectedCents(post, uContractId, stage);
            } else {
                confirmed = collections.selectByProject(post.orgId(), projectId).stream()
                        .filter(c -> stage.equals(c.getStage()))
                        .mapToLong(FrEngCollectionDO::getAmountCents).sum();
            }
            receivedTotal += confirmed;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stage", stage);
            if (full) {
                row.put("expectedCents", expected);
                row.put("confirmedCents", confirmed);
                row.put("remainingCents", Math.max(0, expected - confirmed));
            }
            stages.add(row);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("projectId", projectId);
        if (full) {
            view.put("materialsTotalCents", materialsTotal);
            view.put("materialsPaidCents", materialsPaid);
            view.put("materialsRemainingCents", Math.max(0, materialsTotal - materialsPaid));
            // M09-T04：直接费用真实汇总——Σ已确认费用（成本口径）；已付=经应付清偿的现金（付款不重计成本）；
            // 退回减已付现金不改已确认费用；无公共费用分摊
            var financeCosts = new java.util.HashMap<>(ledger.projectCostAmounts(post, projectId));
            supplyRows.stream().map(FrEngSupplyDO::getPayableId).filter(java.util.Objects::nonNull)
                    .forEach(financeCosts::remove);
            var expenseIds = new java.util.HashSet<Long>();
            long expensesTotal = 0;
            for (FrEngExpenseDO expense : expenses.selectByProject(post.orgId(), projectId)) {
                Long id = expense.getPayableId();
                Long financialAmount = id == null ? null : financeCosts.remove(id);
                if (FrEngExpenseDO.STATUS_REGISTERED.equals(expense.getStatus())) {
                    expensesTotal += financialAmount == null ? expense.getAmountCents() : financialAmount;
                    if (id != null) expenseIds.add(id);
                }
            }
            // 剩余原单由财务直接登记或划入项目账，原单已计成本，无需复制一张工程费用单。
            expensesTotal += financeCosts.values().stream().mapToLong(Long::longValue).sum();
            expenseIds.addAll(financeCosts.keySet());
            long expensesPaid = ledger.paidCentsByPayableIds(post, expenseIds).values().stream()
                    .mapToLong(Long::longValue).sum();
            view.put("expensesTotalCents", expensesTotal);
            view.put("expensesPaidCents", expensesPaid);
            view.put("expensesRemainingCents", Math.max(0, expensesTotal - expensesPaid));
            view.put("collectionStages", stages);
            view.put("collectionTotalCents", receivedTotal);
        } else {
            view.put("collectionStages", stages);
        }
        return view;
    }
}
