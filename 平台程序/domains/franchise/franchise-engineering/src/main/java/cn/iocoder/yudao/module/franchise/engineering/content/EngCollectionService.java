package cn.iocoder.yudao.module.franchise.engineering.content;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.franchise.api.BusinessReceiptPort;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngClosureDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngCollectionDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngCollectionMapper;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngCollectionTermDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngCollectionTermMapper;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngPlanNodeDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngPlanNodeMapper;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngProjectDO;
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
 * 两期收款（D-8）：开工准备即固化约定（START/TOPPING 两行）；登记（collect 码）——
 * **收款侧前置：TOPPING 期收款须封顶节点已完成**（蓝本 :60 方向）；本期累计≤应收 006；
 * 流水全公司唯一；经 BusinessReceiptPort 写通 finance（sourceRef=engineering:{projectId}:{stage}:{collectionId}，
 * 多笔累计制下以本域行 id 作唯一成分）+ 本域留痕（登记即已核算，C4）。
 */
@Service
public class EngCollectionService {

    public static final String AGGREGATE = "fr-eng-project";

    private final EngProjectService projectService;
    private final FrEngPlanNodeMapper planNodes;
    private final FrEngCollectionTermMapper collectionTerms;
    private final FrEngCollectionMapper collections;
    private final FranchiseCommands commands;
    private final BusinessReceiptPort businessReceipts;

    public EngCollectionService(EngProjectService projectService, FrEngPlanNodeMapper planNodes,
                                FrEngCollectionTermMapper collectionTerms, FrEngCollectionMapper collections,
                                FranchiseCommands commands, BusinessReceiptPort businessReceipts) {
        this.projectService = projectService;
        this.planNodes = planNodes;
        this.collectionTerms = collectionTerms;
        this.collections = collections;
        this.commands = commands;
        this.businessReceipts = businessReceipts;
    }

    public record CollectCommand(String stage, BigDecimal amount, String transactionNo,
                                 LocalDate occurredAt, Long fileAssetId) {
    }

    // ===================== 汇总（两期 expected/confirmed/remaining/due） =====================

    public List<Map<String, Object>> summary(PostSnapshot post, long projectId) {
        FrEngProjectDO project = projectService.requireProject(post, projectId);
        List<FrEngCollectionTermDO> terms = collectionTerms.selectByProject(post.orgId(), projectId);
        List<FrEngCollectionDO> rows = collections.selectByProject(post.orgId(), projectId);
        List<FrEngPlanNodeDO> nodes = planNodes.selectByProject(post.orgId(), projectId);
        LocalDate startDue = nodes.isEmpty() ? null : nodes.get(0).getStartDate();
        LocalDate toppingDue = nodes.stream()
                .filter(n -> FrEngPlanNodeDO.MILESTONE_TOPPING.equals(n.getMilestone()))
                .findFirst().map(FrEngPlanNodeDO::getEndDate).orElse(null);
        boolean full = EngProjectService.isFinanceOrAbove(post)
                || post.userId() == project.getManagerUserId();
        List<Map<String, Object>> stages = new ArrayList<>();
        for (String stage : List.of(FrEngCollectionTermDO.STAGE_START, FrEngCollectionTermDO.STAGE_TOPPING)) {
            long expected = terms.stream().filter(t -> stage.equals(t.getStage()))
                    .mapToLong(FrEngCollectionTermDO::getAmountCents).findFirst().orElse(0L);
            long confirmed = rows.stream().filter(c -> stage.equals(c.getStage()))
                    .mapToLong(FrEngCollectionDO::getAmountCents).sum();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stage", stage);
            row.put("expectedCents", expected);
            row.put("confirmedCents", confirmed);
            row.put("remainingCents", Math.max(0, expected - confirmed));
            row.put("due", FrEngCollectionTermDO.STAGE_START.equals(stage) ? startDue : toppingDue);
            row.put("purpose", FrEngCollectionTermDO.STAGE_START.equals(stage)
                    ? FrEngCollectionDO.PURPOSE_START : FrEngCollectionDO.PURPOSE_TOPPING);
            stages.add(row);
        }
        return stages;
    }

    public List<Map<String, Object>> list(PostSnapshot post, long projectId) {
        FrEngProjectDO project = projectService.requireProject(post, projectId);
        boolean full = EngProjectService.isFinanceOrAbove(post)
                || post.userId() == project.getManagerUserId();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (FrEngCollectionDO c : collections.selectByProject(post.orgId(), projectId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", c.getId());
            row.put("stage", c.getStage());
            row.put("purpose", c.getPurpose());
            row.put("transactionNo", c.getTransactionNo());
            row.put("occurredAt", c.getOccurredAt());
            row.put("registrarUserId", c.getRegistrarUserId());
            if (full) {
                row.put("amountCents", c.getAmountCents());
                row.put("fileAssetId", c.getFileAssetId());
                row.put("receiptId", c.getReceiptId());
            }
            rows.add(row);
        }
        return rows;
    }

    // ===================== 登记（TOPPING 前置在收款侧 + 累计门禁 + 写通） =====================

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> register(PostSnapshot post, long projectId, CollectCommand cmd, String idempotencyKey) {
        FrEngProjectDO project = projectService.requireProject(post, projectId);
        EngGuard.requireActionAllowed(projectService.closureOf(post, projectId), "collect");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stage", cmd.stage());
        payload.put("amount", cmd.amount() == null ? null : cmd.amount().toPlainString());
        payload.put("transactionNo", cmd.transactionNo());
        var replayed = commands.replayed(post, idempotencyKey, payload);
        if (replayed.isPresent()) {
            // 幂等重放返回既有（含 finance 收据锚——AC13 全写命令重放口径）
            FrEngCollectionDO prior = collections.selectById(replayed.get().getAggregateId());
            return Map.of("collectionId", prior.getId(),
                    "receiptId", prior.getReceiptId() == null ? 0 : prior.getReceiptId(), "replayed", true);
        }
        String stage = FrEngCollectionTermDO.STAGE_TOPPING.equals(cmd.stage())
                ? FrEngCollectionTermDO.STAGE_TOPPING : FrEngCollectionTermDO.STAGE_START;
        long expected = collectionTerms.selectByProject(post.orgId(), projectId).stream()
                .filter(t -> stage.equals(t.getStage())).mapToLong(FrEngCollectionTermDO::getAmountCents)
                .findFirst().orElseThrow(() -> exception(ENGINEERING_INPUT_INVALID, "请等待合同及两期收款约定确认"));
        // 收款侧前置：TOPPING 期收款须封顶节点已完成（蓝本 :60——方向勘正于 V2）
        if (FrEngCollectionTermDO.STAGE_TOPPING.equals(stage)) {
            boolean topped = EngGuard.toppingDone(planNodes.selectByProject(post.orgId(), projectId));
            if (!topped) {
                throw exception(ENGINEERING_INPUT_INVALID, "请先完成主体封顶");
            }
        }
        if (StrUtil.isBlank(cmd.transactionNo()) || cmd.fileAssetId() == null) {
            throw exception(ENGINEERING_INPUT_INVALID, "请填写收款流水并留存凭据");
        }
        LocalDate date = cmd.occurredAt() == null ? LocalDate.now() : cmd.occurredAt();
        if (date.isAfter(LocalDate.now())) {
            throw exception(ENGINEERING_INPUT_INVALID, "收款日期不能晚于今天");
        }
        long amount = EngGuard.requirePositiveCents(cmd.amount());
        long confirmed = collections.selectByProject(post.orgId(), projectId).stream()
                .filter(c -> stage.equals(c.getStage())).mapToLong(FrEngCollectionDO::getAmountCents).sum();
        if (confirmed + amount > expected) {
            throw exception(COLLECTION_EXCEEDED);
        }
        FrEngCollectionDO collection = new FrEngCollectionDO().setCompanyId(post.orgId())
                .setProjectId(projectId).setStage(stage).setAmountCents(amount)
                .setTransactionNo(StrUtil.trim(cmd.transactionNo())).setOccurredAt(date)
                .setFileAssetId(cmd.fileAssetId())
                .setPurpose(FrEngCollectionTermDO.STAGE_START.equals(stage)
                        ? FrEngCollectionDO.PURPOSE_START : FrEngCollectionDO.PURPOSE_TOPPING)
                .setRegistrarUserId(post.userId());
        collections.insert(collection);
        // 写通 finance（sourceRef 含本域行 id——多笔累计制唯一成分；幂等=Port 内 source_ref 查重+键命名空间）
        String sourceRef = "engineering:" + projectId + ":" + stage.toLowerCase() + ":" + collection.getId();
        var saved = businessReceipts.registerBusinessReceipt(post, new BusinessReceiptPort.BusinessReceipt(
                project.getCustomerId() == null ? 0 : project.getCustomerId(), sourceRef,
                StrUtil.nullToEmpty(project.getCustomerName()) + "·"
                        + (FrEngCollectionTermDO.STAGE_START.equals(stage) ? "开工款" : "封顶款") + "回款",
                amount, "bank", StrUtil.trim(cmd.transactionNo()), date));
        collections.updateById(new FrEngCollectionDO().setId(collection.getId()).setReceiptId(saved.receiptId()));
        commands.record(post, AGGREGATE, collection.getId(),
                projectService.bumpRevision(post, project),
                "eng-collect", payload, idempotencyKey);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("collectionId", collection.getId());
        view.put("receiptId", saved.receiptId());
        view.put("replayed", saved.replayed());
        return view;
    }
}
