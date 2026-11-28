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
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED;
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

    private static final ZoneId COLLECTION_BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    public static final String AGGREGATE = "fr-eng-project";

    private final EngProjectService projectService;
    private final FrEngPlanNodeMapper planNodes;
    private final FrEngCollectionTermMapper collectionTerms;
    private final FrEngCollectionMapper collections;
    private final FranchiseCommands commands;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    private final cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort unifiedStages;
    private final cn.iocoder.yudao.module.franchise.api.SaleContractQueryPort saleContracts;
    private final EngPlanService planService; // M07-T04 批次#36：收款凭据域侧校验复用（同包 package-visible）
    private final BusinessReceiptPort businessReceipts;

    public EngCollectionService(EngProjectService projectService, FrEngPlanNodeMapper planNodes,
                                FrEngCollectionTermMapper collectionTerms, FrEngCollectionMapper collections,
                                FranchiseCommands commands, BusinessReceiptPort businessReceipts,
                                org.springframework.jdbc.core.JdbcTemplate jdbcTemplate,
                                cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort unifiedStages,
                                cn.iocoder.yudao.module.franchise.api.SaleContractQueryPort saleContracts,
                                EngPlanService planService) {
        this.projectService = projectService;
        this.planNodes = planNodes;
        this.collectionTerms = collectionTerms;
        this.collections = collections;
        this.commands = commands;
        this.businessReceipts = businessReceipts;
        this.jdbcTemplate = jdbcTemplate;
        this.unifiedStages = unifiedStages;
        this.saleContracts = saleContracts;
        this.planService = planService;
    }

    /** Use the same business day for engineering declarations and collections regardless of server time zone. */
    static LocalDate resolveCollectionDate(LocalDate requestedDate, Clock clock) {
        LocalDate today = LocalDate.now(clock.withZone(COLLECTION_BUSINESS_ZONE));
        if (requestedDate == null) return today;
        if (requestedDate.isAfter(today)) {
            throw exception(ENGINEERING_INPUT_INVALID, "收款日期不能晚于今天");
        }
        return requestedDate;
    }

    public record CollectCommand(String stage, BigDecimal amount, String transactionNo,
                                 LocalDate occurredAt, Long fileAssetId, Long stageId, Long planVersion) {
        public CollectCommand(String stage,BigDecimal amount,String transactionNo,LocalDate occurredAt,Long fileAssetId) {
            this(stage,amount,transactionNo,occurredAt,fileAssetId,null,null);
        }
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
        boolean full = canViewProjectAmounts(post,project);
        List<Map<String, Object>> stages = new ArrayList<>();
        // M07-T06：三期遍历（存量两期值经迁移已映射；RUN 期旧值兼容保留遍历不删）
        // M07-T06/R2 P1-1：统一余额贯穿读侧——有统一合同时期累计经统一端口（fr_sale_stage_receipt 净口径）；
        // 无合同（存量）回退工程留痕行累计
        Long unifiedContractId = project.getSaleContractId() != null ? project.getSaleContractId()
                : project.getCustomerId() == null ? null : unifiedStages.activeContractId(post, project.getCustomerId());
        var balances = unifiedContractId == null ? List.<cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort.StageBalance>of()
                : unifiedStages.stageBalances(post, unifiedContractId);
        var currentStages = balances == null || balances.isEmpty() ? List.of(FrEngCollectionTermDO.STAGE_SIGN_AFTER,
                FrEngCollectionTermDO.STAGE_BASE_DONE, FrEngCollectionTermDO.STAGE_CAP_DONE)
                : balances.stream().map(cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort.StageBalance::stage).toList();
        for (String stage : currentStages) {
            var current = balances == null ? null : balances.stream().filter(b -> stage.equals(b.stage())).findFirst().orElse(null);
            long expected = current != null ? current.expectedCents() : terms.stream().filter(t -> stage.equals(t.getStage()))
                    .mapToLong(FrEngCollectionTermDO::getAmountCents).findFirst().orElse(0L);
            long confirmed = current != null ? current.collectedCents() : unifiedContractId != null
                    ? unifiedStages.stageCollectedCents(post, unifiedContractId, stage)
                    : rows.stream().filter(c -> stage.equals(c.getStage()))
                            .mapToLong(FrEngCollectionDO::getAmountCents).sum();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stage", stage);
            if (full) {
                row.put("expectedCents", expected);
                row.put("confirmedCents", confirmed);
                row.put("remainingCents", Math.max(0, expected - confirmed));
            }
            var balance=current!=null?current:new cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort.StageBalance(stage,expected,confirmed);
            if(current!=null) {
                row.put("stageId",current.stageId());row.put("label",current.label());
                row.put("trigger",current.trigger());row.put("nodeIndex",current.nodeIndex());row.put("planVersion",current.planVersion());
            } else row.put("label",stageLabel(stage));
            row.put("due",EngPaymentDates.due(balance,nodes));
            row.put("waitingForWork",EngPaymentDates.waitingForWork(balance,nodes));
            row.put("purpose",purposeOf(stage));
            stages.add(row);
        }
        return stages;
    }

    public List<Map<String,Object>> declarations(PostSnapshot post,long projectId) {
        var project=projectService.requireProject(post,projectId);
        if (!canViewProjectAmounts(post,project)) return List.of();
        return jdbcTemplate.queryForList("""
                SELECT d.id::text AS id,s.stage,d.amount_cents AS "amountCents",d.received_date::text AS "receivedDate",
                    d.transaction_no AS "transactionNo",d.status,d.return_reason AS "returnReason",d.receipt_id::text AS "receiptId"
                FROM fr_sale_collection_declaration d JOIN fr_sale_payment_stage s ON s.id=d.stage_id AND s.company_id=d.company_id
                WHERE d.company_id=? AND EXISTS (
                    SELECT 1 FROM pf_command_event e WHERE e.domain='franchise' AND e.aggregate_type='fr-eng-project'
                    AND e.command='eng-collection-declare' AND e.org_id=d.company_id AND e.aggregate_id=d.id
                    AND e.payload::jsonb->>'projectId'=?)
                ORDER BY d.received_date DESC,d.id DESC
                """,post.orgId(),Long.toString(projectId));
    }

    public List<Map<String, Object>> list(PostSnapshot post, long projectId) {
        FrEngProjectDO project = projectService.requireProject(post, projectId);
        boolean full = canViewProjectAmounts(post,project);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (FrEngCollectionDO c : collections.selectByProject(post.orgId(), projectId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", c.getId());
            row.put("stage", c.getStage());
            row.put("purpose", c.getPurpose());
            row.put("occurredAt", c.getOccurredAt());
            row.put("registrarUserId", c.getRegistrarUserId());
            if (full) {
                row.put("transactionNo", c.getTransactionNo());
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
        // A concurrent request may have recorded this receipt while we waited for the project.
        jdbcTemplate.queryForList("SELECT id FROM fr_eng_project WHERE company_id=? AND id=? FOR UPDATE",
                Long.class, post.orgId(), projectId);
        FrEngProjectDO project = projectService.requireProject(post, projectId);
        boolean declaration="engineer".equals(post.roleCode()) || "project-manager".equals(post.roleCode());
        if (declaration) EngGuard.requireOwner(project,post.userId());
        else if (!java.util.Set.of("finance","finance-lead").contains(post.roleCode())) throw exception(ENGINEERING_INPUT_INVALID,"无权办理工程收款");
        EngGuard.requireActionAllowed(projectService.closureOf(post, projectId), "collect");
        // Normalize every business fact before replay lookup. Keep the old payload only as a
        // compatibility fingerprint; legacy hits are accepted only after checking the persisted row.
        String stageRaw = cmd.stage() == null ? "" : cmd.stage().trim();
        String stage;
        if (FrEngCollectionTermDO.STAGE_TOPPING.equals(stageRaw)) {
            stage = FrEngCollectionTermDO.STAGE_CAP_DONE;
        } else if (java.util.Set.of("SIGN_AFTER","BASE_DONE","CAP_DONE","tail").contains(stageRaw)
                || stageRaw.matches("STAGE_[0-9]+")) {
            stage = stageRaw;
        } else throw exception(ENGINEERING_INPUT_INVALID,"收款节点不合法，请刷新合同付款计划");

        String transactionNo = StrUtil.trim(cmd.transactionNo());
        if (StrUtil.isBlank(transactionNo) || cmd.fileAssetId() == null) {
            throw exception(ENGINEERING_INPUT_INVALID, "请填写收款流水并留存凭据");
        }
        LocalDate date = resolveCollectionDate(cmd.occurredAt(), Clock.systemUTC());
        long amount = EngGuard.requirePositiveCents(cmd.amount());

        Map<String, Object> legacyPayload = new LinkedHashMap<>();
        legacyPayload.put("stage", cmd.stage());
        legacyPayload.put("amount", cmd.amount() == null ? null : cmd.amount().toPlainString());
        legacyPayload.put("transactionNo", cmd.transactionNo());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectId", projectId);
        payload.put("stage", stage);
        payload.put("amountCents", amount);
        payload.put("transactionNo", transactionNo);
        // A missing date is part of the original request; the server's default day is not.
        payload.put("occurredAt", cmd.occurredAt() == null ? null : date.toString());
        payload.put("fileAssetId", cmd.fileAssetId());
        payload.put("dateProvided", cmd.occurredAt() != null);
        if (declaration) {
            payload.put("stageId",cmd.stageId()); payload.put("planVersion",cmd.planVersion());
        }

        var replayed = commands.replayed(post, idempotencyKey, payload, legacyPayload, event -> {
            // The previous version fingerprinted the resolved day. Recover only from a matching
            // recorded collection, never from today's default or another project's receipt.
            if (!AGGREGATE.equals(event.getAggregateType())
                    || !"eng-collect".equals(event.getCommand())) {
                return null;
            }
            var original = cn.iocoder.yudao.framework.common.util.json.JsonUtils.parseTree(event.getPayload());
            if (original.has("dateProvided") || !original.has("projectId")) return null;
            Map<String, Object> recordedPayload = new LinkedHashMap<>(payload);
            recordedPayload.remove("dateProvided");
            if (cmd.occurredAt() == null) {
                // The legacy fingerprint used the server's posting day, not the new business zone.
                // A backdated explicit date could not be that former default.
                var day = event.getOccurredAt().atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDate();
                recordedPayload.put("occurredAt", day.toString());
            }
            return recordedPayload;
        });
        if (replayed.isPresent()) {
            var event = replayed.get();
            if (declaration && AGGREGATE.equals(event.getAggregateType()) && "eng-collection-declare".equals(event.getCommand())) {
                return Map.of("receiptId",0,"declarationId",event.getAggregateId(),"stageStatus","PENDING_CONFIRMATION","replayed",true);
            }
            if (!AGGREGATE.equals(event.getAggregateType()) || !"eng-collect".equals(event.getCommand())) {
                throw exception(IDEMPOTENCY_KEY_REUSED);
            }
            // A legacy fingerprint did not contain project/date/evidence. Verify these facts against
            // the immutable collection row before accepting an upgrade-era retry.
            FrEngCollectionDO prior = collections.selectById(event.getAggregateId());
            if (prior == null
                    || !java.util.Objects.equals(prior.getProjectId(), projectId)
                    || !java.util.Objects.equals(prior.getStage(), stage)
                    || !java.util.Objects.equals(prior.getAmountCents(), amount)
                    || !java.util.Objects.equals(prior.getTransactionNo(), transactionNo)
                    || !java.util.Objects.equals(prior.getFileAssetId(), cmd.fileAssetId())
                    || (cmd.occurredAt() != null && !java.util.Objects.equals(prior.getOccurredAt(), date))) {
                throw exception(IDEMPOTENCY_KEY_REUSED);
            }
            return Map.of("collectionId", prior.getId(),
                    "receiptId", prior.getReceiptId() == null ? 0 : prior.getReceiptId(), "replayed", true);
        }
        // M07-T04 批次#36：收款凭据资产域侧校验（填了即验——假 ID/跨组织/未 ACCEPTED/类型不符全拒；
        // 与 prepare/start/deliver 同款 requireValidAsset，十动作资产链收口）
        if (declaration) planService.requireOwnCollectionProof(post,cmd.fileAssetId());
        else if (cmd.fileAssetId() != null) {
            // R2 P1-2：收款凭据允许 FIN_VOUCHER（finance 岗在 FIN_VOUCHER 通道白名单内——ENG 类通道
            // 角色矩阵排除 finance，签票阶段即 403；v3 侧 collect 同步改传 FIN_VOUCHER）
            planService.requireValidAsset(post, cmd.fileAssetId(),
                    java.util.Set.of("ENG_SITE_PHOTO", "ENG_DRAWING", "FIN_VOUCHER"), "收款");
        }
        // M07-T06/I05（R1 P1-1）：统一实收账——工程收款写通销售统一合同期次（资金事实唯一在
        // fr_fin_receipt + fr_sale_stage_receipt），本域 fr_eng_collection 行仅作留痕引用统一 receiptId，
        // 不参与余额累计；收款余额与资金门禁通过统一合同期次端口读取。
        // 期次归一：三枚举精确匹配；存量旧值 TOPPING 显式映射 CAP_DONE（v3 接线随 M07-T02）；
        // 其它未知值显式拒绝（不得静默错期入账——R1 P2-8）。

        // 门禁（R2 P1-3 与销售域对齐）：仅 CAP_DONE 保留封顶节点门禁（与 CAP_DONE 触发语义精确对应）；
        // BASE_DONE 不设节点门禁——「任意非封顶 DONE 即可触发」被 R1 判过松，而项目无基础里程碑，
        // 与销售域 BASE_DONE 收款语义保持一致（无节点门禁）；「基础完成留痕」由 M07-T04 现场记录链
        // 承接并登记执行记录。SIGN_AFTER 无节点门禁。
        if (FrEngCollectionTermDO.STAGE_CAP_DONE.equals(stage)
                && !EngGuard.toppingDone(planNodesForGate(post, projectId))) {
            throw exception(ENGINEERING_INPUT_INVALID, "请先完成主体封顶");
        }
        // M07-T06/I05（R1 P1-1）：统一链收款——资金事实唯一在 fr_fin_receipt + fr_sale_stage_receipt
        //（限额/幂等/审计/财务登记全在 sales 统一侧，本域不再自建财务收据镜像）；
        // 无活跃统一合同 → 拒绝（I05 规则从严：同一甲方建房必须有统一合同才能收款）
        // R2 P1-2：以工程档案冻结的合同身份为准（prepare 已冻结；未冻结回退当前活跃）——
        // 冻结 A、当前活跃 B（客户作废重签）时拒绝收款并提示重新对账，防止款项错期入账
        Long frozenContractId = jdbcTemplate.queryForObject(
                "SELECT sale_contract_id FROM fr_eng_project WHERE id = ?", Long.class, projectId);
        Long activeContractId = project.getCustomerId() == null ? null
                : unifiedStages.activeContractId(post, project.getCustomerId());
        Long contractId = frozenContractId != null ? frozenContractId : activeContractId;
        if (contractId == null) {
            throw exception(ENGINEERING_INPUT_INVALID, "请先签订统一建房合同（统一实收账收款前提）");
        }
        // The unified stage read locks the contract until this registration commits. Recheck the
        // customer's active contract under that same lock before accepting a branch's payment.
        var balances=unifiedStages.stageBalances(post,contractId);
        if (project.getCustomerId()!=null && !java.util.Objects.equals(contractId,unifiedStages.activeContractId(post,project.getCustomerId()))) {
            throw exception(ENGINEERING_INPUT_INVALID,
                    "统一建房合同已变更（作废重签），请先重新核对合同付款计划");
        }
        if(balances!=null && !balances.isEmpty() && balances.stream().noneMatch(b->stage.equals(b.stage())))
            throw exception(ENGINEERING_INPUT_INVALID,"合同付款节点不存在或已调整，请刷新后重试");
        if (declaration && (balances==null || balances.stream().noneMatch(b -> stage.equals(b.stage())
                && java.util.Objects.equals(b.stageId(),cmd.stageId()) && b.planVersion()!=null
                && java.util.Objects.equals(b.planVersion().longValue(),cmd.planVersion())))) throw exception(ENGINEERING_INPUT_INVALID,"付款计划已有变化，请刷新核对本期");
        if (declaration && balances.stream().noneMatch(b -> stage.equals(b.stage())
                && amount<=b.expectedCents()-b.collectedCents())) throw exception(ENGINEERING_INPUT_INVALID,"本次登记超过该期余款，请刷新核对");
        var unified = unifiedStages.collectStage(post, contractId, stage,
                new cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort.CollectInput(
                        amount, "bank", transactionNo, date, cmd.fileAssetId(),declaration?idempotencyKey:null));
        if (declaration) {
            if (!"PENDING_CONFIRMATION".equals(unified.stageStatus()) || unified.declarationId()==null)
                throw new IllegalStateException("工程收款申报未返回待核实原单");
            if (unified.replayed() && !Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM pf_command_event WHERE domain='franchise' AND aggregate_type=?
                    AND aggregate_id=? AND command='eng-collection-declare' AND org_id=? AND payload::jsonb->>'projectId'=?)
                    """,Boolean.class,AGGREGATE,unified.declarationId(),post.orgId(),Long.toString(projectId)))) {
                throw exception(ENGINEERING_INPUT_INVALID,"这笔流水已提交，请核对本工程原单");
            }
            commands.record(post,AGGREGATE,unified.declarationId(),com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(),
                    "eng-collection-declare",payload,idempotencyKey);
            return Map.of("receiptId",0,"declarationId",unified.declarationId(),"stageStatus",unified.stageStatus(),"replayed",unified.replayed());
        }
        // 工程留痕行：引用统一 receiptId（不参与余额累计——读侧经统一端口）
        FrEngCollectionDO collection = new FrEngCollectionDO().setCompanyId(post.orgId())
                .setProjectId(projectId).setStage(stage).setAmountCents(amount)
                .setTransactionNo(transactionNo).setOccurredAt(date)
                .setFileAssetId(cmd.fileAssetId())
                .setPurpose(purposeOf(stage))
                .setRegistrarUserId(post.userId());
        collections.insert(collection);
        collections.updateById(new FrEngCollectionDO().setId(collection.getId()).setReceiptId(unified.receiptId()));
        commands.record(post, AGGREGATE, collection.getId(),
                projectService.bumpRevision(post, project),
                "eng-collect", payload, idempotencyKey);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("collectionId", collection.getId());
        view.put("receiptId", unified.receiptId());
        view.put("replayed", unified.replayed());
        return view;
    }
    /** M07-T06 三期门禁用节点读取 */
    private List<FrEngPlanNodeDO> planNodesForGate(PostSnapshot post, long projectId) {
        return planNodes.selectByProject(post.orgId(), projectId);
    }

    private static boolean canViewProjectAmounts(PostSnapshot post,FrEngProjectDO project) {
        return EngProjectService.isFinanceOrAbove(post) || (java.util.Set.of("engineer","project-manager").contains(post.roleCode())
                && (java.util.Objects.equals(project.getManagerUserId(),post.userId())
                || java.util.Objects.equals(project.getAssignedEngineerUserId(),post.userId())));
    }

    private static String purposeOf(String stage) {
        return switch (stage) {
            case FrEngCollectionTermDO.STAGE_BASE_DONE -> FrEngCollectionDO.PURPOSE_BASE_DONE;
            case FrEngCollectionTermDO.STAGE_CAP_DONE -> FrEngCollectionDO.PURPOSE_TOPPING;
            case FrEngCollectionTermDO.STAGE_SIGN_AFTER,FrEngCollectionTermDO.STAGE_START -> FrEngCollectionDO.PURPOSE_START;
            default -> "工程进度款";
        };
    }

    private static String stageLabel(String stage) {
        return switch (stage) {
            case FrEngCollectionTermDO.STAGE_SIGN_AFTER -> "签约后";
            case FrEngCollectionTermDO.STAGE_BASE_DONE -> "基础完成后";
            case FrEngCollectionTermDO.STAGE_CAP_DONE -> "封顶后";
            case FrEngCollectionTermDO.STAGE_START -> "开工款";
            case FrEngCollectionTermDO.STAGE_TOPPING -> "封顶款";
            default -> stage;
        };
    }

}
