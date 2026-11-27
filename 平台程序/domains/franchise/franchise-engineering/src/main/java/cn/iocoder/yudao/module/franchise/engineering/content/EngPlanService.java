package cn.iocoder.yudao.module.franchise.engineering.content;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
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
import cn.iocoder.yudao.module.franchise.org.FranchiseCommandKeys;
import cn.iocoder.yudao.module.platform.command.dal.CommandEventDO;
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
import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED;
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
    private final cn.iocoder.yudao.module.franchise.api.AssetAccessPort assets;
    private final cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort serviceAccounts;
    private final cn.iocoder.yudao.module.franchise.api.SaleContractQueryPort saleContracts;
    private final cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort unifiedStages;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    public EngPlanService(EngProjectService projectService, FrEngPlanVersionMapper planVersions,
                          FrEngPlanNodeMapper planNodes, FrEngSiteRecordMapper siteRecords,
                          FrEngMaterialMapper materials, FrEngSupplyMapper supplies,
                          FrEngSupplyReceiptMapper supplyReceipts, FrEngCollectionTermMapper collectionTerms,
                          FrEngCollectionMapper collections, FranchiseCommands commands,
                          cn.iocoder.yudao.module.franchise.api.AssetAccessPort assets,
                          cn.iocoder.yudao.module.franchise.api.StoreServiceAccountPort serviceAccounts,
                          cn.iocoder.yudao.module.franchise.api.SaleContractQueryPort saleContracts,
                          cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort unifiedStages,
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
        this.assets = assets;
        this.serviceAccounts = serviceAccounts;
        this.saleContracts = saleContracts;
        this.unifiedStages = unifiedStages;
    }

    /**
     * M07-T06 三期开工准备命令：Σ三期=合同总额；签约后>0，基础完成后/封顶后≥0（0=未约定）。
     * 有活跃统一合同时三期金额必须与合同一致（「每期金额按有效合同，不发明固定比例」）。
     */
    public record PrepareCommand(BigDecimal total, BigDecimal signAfter, BigDecimal baseDone, BigDecimal capDone,
                                 Long contractAssetId, String note, String workers, List<NodeInput> nodes,
                                 Integer paymentPlanVersion) {
        public PrepareCommand(BigDecimal total,BigDecimal signAfter,BigDecimal baseDone,BigDecimal capDone,
                              Long asset,String note,String workers,List<NodeInput> nodes) {
            this(total,signAfter,baseDone,capDone,asset,note,workers,nodes,null);
        }
    }

    public record NodeInput(int nodeIndex, String name, LocalDate startDate, LocalDate endDate) {
    }

    public record RescheduleCommand(int nodeIndex, LocalDate newStart, String note) {
    }

    public record NodeRecordCommand(LocalDate date, String note, Long fileAssetId, boolean noMaterials, List<Long> fileAssetIds) {
        public NodeRecordCommand(LocalDate date, String note, Long fileAssetId, boolean noMaterials) {
            this(date, note, fileAssetId, noMaterials, null);
        }
    }

    public record NodeCorrectCommand(LocalDate completedAt, String note, Long fileAssetId, String reason,
                                     Long replacementRecordId, Long replaceFileAssetId) {
        public NodeCorrectCommand(LocalDate completedAt, String note, Long fileAssetId, String reason) {
            this(completedAt, note, fileAssetId, reason, null, null);
        }
    }

    // ===================== 开工准备（无门禁；保存即两期约定固化） =====================

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> prepare(PostSnapshot post, long projectId, PrepareCommand cmd, String idempotencyKey) {
        projectService.requireOwned(post, projectId, "prepare");
        projectService.projects().lockById(projectId);
        FrEngProjectDO project = projectService.requireOwned(post, projectId, "prepare");
        var replayed = commands.replayed(post, idempotencyKey, preparePayload(cmd));
        if (replayed.isPresent()) {
            if(replayed.get().getAggregateId()!=projectId) throw exception(IDEMPOTENCY_KEY_REUSED);
            return projectService.detail(post, projectId);
        }
        if (project.getActualStartAt() != null) {
            throw exception(ENGINEERING_INPUT_INVALID, "开工后的现场情况请在原施工记录补充");
        }
        Long currentContractId = project.getCustomerId() == null ? null
                : saleContracts.findActiveSignedId(post, project.getCustomerId());
        if (project.getSaleContractId() != null && !java.util.Objects.equals(project.getSaleContractId(),currentContractId)) {
            throw exception(ENGINEERING_INPUT_INVALID,"统一建房合同已变更或作废，请核对合同后再保存开工准备");
        }
        if (currentContractId != null) {
            jdbcTemplate.queryForList("SELECT id FROM fr_sale_contract WHERE id=? AND company_id=? FOR UPDATE",Long.class,currentContractId,post.orgId());
            if (!java.util.Objects.equals(currentContractId,saleContracts.findActiveSignedId(post,project.getCustomerId())))
                throw exception(ENGINEERING_INPUT_INVALID,"统一建房合同已变更，请刷新后重试");
            boolean used=Boolean.TRUE.equals(jdbcTemplate.queryForObject("SELECT EXISTS(SELECT 1 FROM fr_eng_project WHERE company_id=? AND sale_contract_id=? AND id<>?)",Boolean.class,post.orgId(),currentContractId,projectId));
            if(used) throw exception(ENGINEERING_INPUT_INVALID,"销售合同已关联其他工程，请核对本次开工准备");
        }
        var formal=currentContractId==null?null:saleContracts.findPaymentPlanById(post,currentContractId);
        long total,signAfter,baseDone,capDone;
        Long contractAssetId;
        if(formal!=null) {
            if(cmd.paymentPlanVersion()!=null && cmd.paymentPlanVersion()!=formal.version())
                throw exception(ENGINEERING_INPUT_INVALID,"付款计划已更新，请刷新合同付款计划后重试");
            total=formal.totalCents();
            signAfter=formal.stages().stream().filter(s->EngPaymentDates.beforeStart(s.trigger(),s.key())).mapToLong(s->s.amountCents()).sum();
            baseDone=formal.stages().stream().filter(s->"BASE_DONE".equals(s.key())).mapToLong(s->s.amountCents()).sum();
            capDone=formal.stages().stream().filter(s->"CAP_DONE".equals(s.key())).mapToLong(s->s.amountCents()).sum();
            contractAssetId=formal.assetId();
            if(cmd.total()!=null && EngGuard.requirePositiveCents(cmd.total())!=total)
                throw exception(ENGINEERING_INPUT_INVALID,"合同总额已更新，请使用正式合同付款计划");
            if(cmd.signAfter()!=null && cmd.signAfter().movePointRight(2).longValueExact()!=signAfter
                    || cmd.baseDone()!=null && cmd.baseDone().movePointRight(2).longValueExact()!=baseDone
                    || cmd.capDone()!=null && cmd.capDone().movePointRight(2).longValueExact()!=capDone)
                throw exception(ENGINEERING_INPUT_INVALID,"付款节点由正式合同继承，请刷新付款计划");
            if(cmd.contractAssetId()!=null && !java.util.Objects.equals(cmd.contractAssetId(),contractAssetId))
                throw exception(ENGINEERING_INPUT_INVALID,"请使用正式合同中的签约凭据");
            var indices=planNodes.selectByProject(post.orgId(),projectId).stream().map(FrEngPlanNodeDO::getNodeIndex).collect(java.util.stream.Collectors.toSet());
            if(formal.stages().stream().anyMatch(s->"NODE_COMPLETED".equals(s.trigger()) && !indices.contains(s.nodeIndex())))
                throw exception(ENGINEERING_INPUT_INVALID,"付款计划引用了本工程不存在的施工节点，请由业务或财务更正合同约定");
        } else {
            // Historical manual projects retain their explicit agreement. Draft entrustments are never read here.
            total=EngGuard.requirePositiveCents(cmd.total());
            signAfter=cmd.signAfter()==null?-1:EngGuard.requirePositiveCents(cmd.signAfter());
            baseDone=cmd.baseDone()==null?-1:cmd.baseDone().movePointRight(2).longValueExact();
            capDone=cmd.capDone()==null?-1:cmd.capDone().movePointRight(2).longValueExact();
            contractAssetId=cmd.contractAssetId();
            if(signAfter<0) throw exception(ENGINEERING_INPUT_INVALID,"签约后期金额必填且大于 0");
            if(baseDone<0 || capDone<0) throw exception(ENGINEERING_INPUT_INVALID,"基础完成后/封顶后金额须为不小于 0 的数字（未约定填 0）");
            if(signAfter+baseDone+capDone!=total) throw exception(ENGINEERING_INPUT_INVALID,"三期金额之和必须等于合同总额");
            var old=project.getCustomerId()==null?null:saleContracts.findStageAmounts(post,project.getCustomerId());
            if(old!=null && (old.signAfterCents()!=signAfter || old.baseDoneCents()!=baseDone || old.capDoneCents()!=capDone))
                throw exception(ENGINEERING_INPUT_INVALID,"三期金额须与统一建房合同约定一致，不得自行拆分");
        }
        if (StrUtil.isBlank(cmd.note()) || cmd.note().length() > 1000) {
            throw exception(ENGINEERING_INPUT_INVALID, "请填写开工准备记录");
        }
        // M07-T03/I04：合同凭据资产域侧校验（本组织+ACCEPTED+工程/合同类型）
        requireValidAsset(post, contractAssetId,
                java.util.Set.of("ENG_DRAWING", "ENG_SITE_PHOTO", "CONTRACT_ATTACHMENT"), "开工合同");
        // 已有实际收款：沿用原合同金额和凭据（蓝本 :56）
        List<FrEngCollectionDO> received = collections.selectByProject(post.orgId(), projectId);
        if (formal==null && !received.isEmpty() && (project.getContractTotalCents() != null && project.getContractTotalCents() > 0)) {
            if (project.getContractTotalCents() != total || project.getFirstCents() != signAfter
                    // R1 P1：Long 与 Long 比较须 Objects.equals（雪花资产 ID 超缓存区间，引用比较恒不等——
                    // 已有收款分支会把合法保存误拒为「沿用原凭据」）
                    || !java.util.Objects.equals(project.getContractAssetId(), contractAssetId)) {
                throw exception(ENGINEERING_INPUT_INVALID, "已有实际收款，沿用原合同金额和凭据");
            }
        }
        boolean changed = project.getContractTotalCents() == null || project.getContractTotalCents() == 0
                || project.getContractTotalCents() != total || project.getFirstCents() != signAfter
                || !java.util.Objects.equals(project.getContractAssetId(), contractAssetId);
        int nextVersion = (project.getContractVersion() == null ? 0 : project.getContractVersion()) + (changed ? 1 : 0);
        projectService.projects().updateById(new FrEngProjectDO().setId(projectId)
                .setContractTotalCents(total).setFirstCents(signAfter) // 存量兼容列=签约后期约定
                .setSaleContractId(currentContractId != null ? currentContractId : project.getSaleContractId())
                .setContractAssetId(contractAssetId).setContractVersion(nextVersion)
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
        if(formal!=null) {
            jdbcTemplate.update("UPDATE fr_eng_project SET payment_plan_version=?,payment_plan_snapshot=CAST(? AS jsonb) WHERE id=? AND company_id=?",
                    formal.version(),JsonUtils.toJsonString(formal),projectId,post.orgId());
            for(var stage:formal.stages()) upsertTerm(post,projectId,stage.key(),stage.amountCents());
            var retained=formal.stages().stream().map(s->s.key()).collect(java.util.stream.Collectors.toSet());
            for(var term:collectionTerms.selectByProject(post.orgId(),projectId)) if(!retained.contains(term.getStage())) collectionTerms.deleteById(term.getId());
        } else {
        // M07-T06 三期约定固化（0=未约定也建行；同额幂等；与销售统一合同三期对齐）
        upsertTerm(post, projectId, FrEngCollectionTermDO.STAGE_SIGN_AFTER, signAfter);
        upsertTerm(post, projectId, FrEngCollectionTermDO.STAGE_BASE_DONE, baseDone);
        upsertTerm(post, projectId, FrEngCollectionTermDO.STAGE_CAP_DONE, capDone);
        }
        // 准备时可一并保存已知日期；沿用原节点，远期日期可后补。
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
        m.put("signAfter", cmd.signAfter() == null ? null : cmd.signAfter().toPlainString());
        m.put("baseDone", cmd.baseDone() == null ? null : cmd.baseDone().toPlainString());
        m.put("capDone", cmd.capDone() == null ? null : cmd.capDone().toPlainString());
        m.put("contractAssetId", cmd.contractAssetId());
        m.put("note", cmd.note());
        if(cmd.paymentPlanVersion()!=null) m.put("paymentPlanVersion",cmd.paymentPlanVersion());
        if(cmd.workers()!=null) m.put("workers",cmd.workers());
        if(cmd.nodes()!=null) m.put("nodes",cmd.nodes());
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
        idempotencyKey=commands.requireIdempotencyKey(idempotencyKey);
        jdbcTemplate.queryForObject("SELECT pg_advisory_xact_lock(?)",Object.class,
                cn.iocoder.yudao.module.franchise.org.FranchiseCommandKeys.lockKey(
                        new cn.iocoder.yudao.module.franchise.org.FranchiseCommandKeys.PostKeyScope("franchise",post.userId()),idempotencyKey));
        projectService.requireOwned(post,projectId,"start");
        projectService.projects().lockById(projectId);
        FrEngProjectDO project = projectService.requireOwned(post, projectId, "start");
        if (cmd==null) throw exception(ENGINEERING_INPUT_INVALID,"请填写实际开工记录");
        Map<String,Object> payload=new LinkedHashMap<>();
        payload.put("projectId",projectId); payload.put("input",cmd);
        var previous=commands.previous(post,idempotencyKey,"eng-start",projectId);
        if (previous.isPresent()) {
            if (!AGGREGATE.equals(previous.get().getAggregateType())) throw exception(IDEMPOTENCY_KEY_REUSED);
            var recorded=JsonUtils.parseTree(previous.get().getPayload());
            if (recorded.isObject() && recorded.size()==1 && recorded.has("date")) {
                String submittedDate=cmd.date()==null?"":cmd.date().toString();
                if (!submittedDate.equals(recorded.path("date").asText()) || cmd.fileAssetId()==null
                        || cmd.fileAssetIds()!=null && !cmd.fileAssetIds().isEmpty()) throw exception(IDEMPOTENCY_KEY_REUSED);
                boolean saved=Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                        SELECT EXISTS(SELECT 1 FROM fr_eng_site_record r JOIN fr_eng_plan_node n
                          ON n.id=r.node_id AND n.company_id=r.company_id AND n.project_id=r.project_id
                        WHERE r.company_id=? AND r.project_id=? AND n.milestone='START' AND r.completion AND r.result='PASS'
                          AND r.occurred_at=? AND r.note=? AND r.file_asset_ids=CAST(? AS jsonb))
                        """,Boolean.class,post.orgId(),projectId,project.getActualStartAt(),
                        StrUtil.blankToDefault(cmd.note(),"正式开工"),JsonList.of(cmd.fileAssetId())));
                if (!saved) throw exception(IDEMPOTENCY_KEY_REUSED);
            } else commands.replayed(post,idempotencyKey,payload);
            return projectService.detail(post, projectId);
        }
        if (cmd.date()==null || cmd.date().isAfter(LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))))
            throw exception(ENGINEERING_INPUT_INVALID,"请填写已发生的实际开工日期");
        if (cmd.note()!=null && cmd.note().length()>1000 || cmd.fileAssetIds()!=null && !cmd.fileAssetIds().isEmpty())
            throw exception(ENGINEERING_INPUT_INVALID,"开工说明不超过1000字，请使用单份明确的开工凭据");
        List<FrEngPlanNodeDO> nodes = planNodes.selectByProject(post.orgId(), projectId);
        var starts = nodes.stream().filter(n -> "START".equals(n.getMilestone())).toList();
        if (project.getActualStartAt() != null || project.getPreparedAt() == null
                || starts.size() != 1 || starts.get(0).getStartDate() == null
                || !starts.get(0).getStartDate().equals(starts.get(0).getEndDate())) {
            throw exception(ENGINEERING_INPUT_INVALID, "请先完成开工准备并填写计划开工日期");
        }
        requireStartGate(post, projectId);
        // M07-T03/I04：开工凭据资产域侧校验
        requireValidAsset(post, cmd.fileAssetId(),
                java.util.Set.of("ENG_SITE_PHOTO", "ENG_DRAWING"), "开工");
        LocalDate startDate = cmd.date();
        projectService.projects().updateById(new FrEngProjectDO().setId(projectId)
                .setActualStartAt(startDate));
        project.setActualStartAt(startDate); // 内存 DO 同步（recalcStatus 派生依赖）
        for (FrEngPlanNodeDO n : nodes) {
            if ("START".equals(n.getMilestone())) {
                planNodes.updateById(new FrEngPlanNodeDO().setId(n.getId()).setStatus("DONE")
                        .setCompletedAt(startDate).setResponsibleUserId(post.userId()));
                jdbcTemplate.update("INSERT INTO fr_eng_site_record(id,company_id,project_id,node_id,occurred_at,note,completion,result,review_mode,file_asset_ids,author_user_id) VALUES(?,?,?,?,?,?,TRUE,'PASS','SINGLE_PERSON_RECORD',CAST(? AS jsonb),?)",
                        cn.hutool.core.util.IdUtil.getSnowflakeNextId(),post.orgId(),projectId,n.getId(),startDate,
                        StrUtil.blankToDefault(cmd.note(),"正式开工"),JsonList.of(cmd.fileAssetId()),post.userId());
            }
        }
        nodes.stream().filter(n -> !"START".equals(n.getMilestone()) && !"DONE".equals(n.getStatus())).findFirst()
                .ifPresent(n -> planNodes.updateById(new FrEngPlanNodeDO().setId(n.getId()).setStatus("IN_PROGRESS")));
        projectService.recalcStatus(post, project); // 唯一真相：actualStart 已回内存，派生 IN_PROGRESS 落库
        commands.record(post, AGGREGATE, projectId, projectService.bumpRevision(post, project),
                "eng-start", payload, idempotencyKey);
        return projectService.detail(post, projectId);
    }

    /** START 门禁：开工款累计已核算 ≥ 约定额（operations.js gate rank：start 期先收齐） */
    void requireStartGate(PostSnapshot post, long projectId) {
        // M07-T06/R1 P1-1：统一实收账读侧——签约后期累计经统一端口（fr_sale_stage_receipt 净口径），
        // 不再读工程留痕行（避免销售已收齐签约款而工程镜像缺失导致无法开工）
        Long contractId = resolveContractId(post, projectId);
        if(contractId!=null) {
            var states=jdbcTemplate.queryForList("SELECT status FROM fr_sale_contract WHERE id=? AND company_id=? FOR UPDATE",String.class,contractId,post.orgId());
            if(!states.isEmpty() && !"SIGNED".equals(states.get(0))) throw exception(ENGINEERING_INPUT_INVALID,"统一建房合同已失效，请核对合同后再开工");
            var current=unifiedStages.stageBalances(post,contractId);
            if(current!=null && !current.isEmpty()) {
                var project=projectService.requireProject(post,projectId);
                var formal=saleContracts.findPaymentPlanById(post,contractId);
                if(formal!=null && !java.util.Objects.equals(project.getPaymentPlanVersion(),formal.version()))
                    throw exception(ENGINEERING_INPUT_INVALID,"付款计划已更新，请先重新确认开工准备");
                if(current.stream().filter(s->EngPaymentDates.beforeStart(s.trigger(),s.stage()))
                        .anyMatch(s->s.collectedCents()<s.expectedCents())) throw exception(GATE_NOT_MET);
                return;
            }
        }
        long expected=termAmount(post,projectId,FrEngCollectionTermDO.STAGE_SIGN_AFTER);
        long confirmed=contractId==null?0:unifiedStages.stageCollectedCents(post,contractId,FrEngCollectionTermDO.STAGE_SIGN_AFTER);
        if(confirmed<expected) throw exception(GATE_NOT_MET);
    }

    public Map<String,Object> paymentPlanView(PostSnapshot post,long projectId) {
        var project=projectService.requireProject(post,projectId);
        if(!EngProjectService.isFinanceOrAbove(post) && !java.util.Objects.equals(project.getManagerUserId(),post.userId())
                && !java.util.Objects.equals(project.getAssignedEngineerUserId(),post.userId()))
            throw exception(ENGINEERING_INPUT_INVALID,"无权查看该工程付款计划");
        var current=project.getCustomerId()==null?null:saleContracts.findPaymentPlan(post,project.getCustomerId());
        var result=new LinkedHashMap<String,Object>();
        result.put("paymentPlan",current);result.put("preparedVersion",project.getPaymentPlanVersion());
        result.put("contractChanged",project.getSaleContractId()!=null && (current==null || project.getSaleContractId()!=current.contractId()));
        result.put("needsRefresh",current!=null && !java.util.Objects.equals(project.getPaymentPlanVersion(),current.version()));
        return result;
    }

    long termAmount(PostSnapshot post, long projectId, String stage) {
        Long contractId=resolveContractId(post,projectId);
        if(contractId!=null){
            var current=unifiedStages.stageBalances(post,contractId);
            if(current!=null)for(var balance:current)if(stage.equals(balance.stage()))return balance.expectedCents();
        }
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
        lockPlanCommandKey(post, idempotencyKey);
        FrEngProjectDO project = projectService.requireOwned(post, projectId, "plan");
        var legacy = planPayload(inputs);
        var payload = planCommandPayload(projectId, "eng-plan-save", legacy);
        var replayed = commands.replayed(post, idempotencyKey, payload, legacy);
        if (replayed.isPresent()) {
            requirePlanEvent(replayed.get(), projectId, "eng-plan-save");
            return planView(post, projectId);
        }
        saveNodeDates(post, project, inputs, "plan");
        commands.record(post, AGGREGATE, projectId, projectService.bumpRevision(post, project),
                "eng-plan-save", payload, idempotencyKey);
        return planView(post, projectId);
    }

    private void lockPlanCommandKey(PostSnapshot post, String key) {
        if (StrUtil.isBlank(key)) return;
        // Match FranchiseCommands' historical scoped key, including trimming and its length limit.
        // Serialize retries without changing the existing project/node lock order of other commands.
        long lock = FranchiseCommandKeys.lockKey(new FranchiseCommandKeys.PostKeyScope("franchise", post.userId()),
                StrUtil.maxLength(key.trim(), 80));
        jdbcTemplate.queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(?)) s", Integer.class, lock);
    }

    private static Map<String, Object> planCommandPayload(long projectId, String operation, Object input) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operation", operation);
        payload.put("projectId", projectId);
        payload.put("input", input);
        return payload;
    }

    private static void requirePlanEvent(CommandEventDO event, long projectId, String command) {
        // Legacy fingerprints lack project/operation; their recorded aggregate must supply both.
        if (!AGGREGATE.equals(event.getAggregateType()) || event.getAggregateId() != projectId
                || !command.equals(event.getCommand())) throw exception(IDEMPOTENCY_KEY_REUSED);
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
     * 保留原节点及编号；日期可整对留空或并行，START 为单日；已完成记录不改。
     */
    private void saveNodeDates(PostSnapshot post, FrEngProjectDO project, List<NodeInput> inputs, String action) {
        List<FrEngPlanNodeDO> nodes = planNodes.selectByProject(post.orgId(), project.getId());
        if (inputs.size() != nodes.size()) {
            throw exception(PLAN_INVALID); // 请保留原施工节点
        }
        boolean hasTopping = false;
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
                continue; // 已完成节点跳过
            }
            if (in.startDate() == null && in.endDate() == null) continue;
            if (in.startDate() == null || in.endDate() == null || in.endDate().isBefore(in.startDate())) {
                throw exception(PLAN_INVALID);
            }
            if ("START".equals(node.getMilestone()) && !in.startDate().equals(in.endDate())) throw exception(PLAN_INVALID);
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
            // Explicit nulls keep the current rows consistent with the saved snapshot when a future range is cleared.
            jdbcTemplate.update("UPDATE fr_eng_plan_node SET name=?,start_date=?,end_date=?,baseline_end_date=COALESCE(baseline_end_date,?) WHERE id=? AND company_id=?",
                    "START".equals(node.getMilestone()) ? "正式开工" : StrUtil.blankToDefault(in.name(), node.getName()),
                    in.startDate(), in.endDate(), in.endDate(), node.getId(), post.orgId());
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
        lockPlanCommandKey(post, idempotencyKey);
        FrEngProjectDO project = projectService.requireOwned(post, projectId, "reschedule");
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("nodeIndex", cmd.nodeIndex());
        legacy.put("newStart", cmd.newStart() == null ? null : cmd.newStart().toString());
        legacy.put("note", StrUtil.nullToEmpty(cmd.note()));
        var payload = planCommandPayload(projectId, "eng-plan-reschedule", legacy);
        var replayed = commands.replayed(post, idempotencyKey, payload, legacy);
        if (replayed.isPresent()) {
            requirePlanEvent(replayed.get(), projectId, "eng-plan-reschedule");
            return planView(post, projectId);
        }
        List<FrEngPlanNodeDO> nodes = planNodes.selectByProject(post.orgId(), projectId);
        FrEngPlanNodeDO target = nodes.stream()
                .filter(n -> n.getNodeIndex() == cmd.nodeIndex()).findFirst()
                .orElseThrow(() -> exception(PLAN_INVALID));
        if (FrEngPlanNodeDO.STATUS_DONE.equals(target.getStatus()) || cmd.newStart() == null
                || target.getStartDate() == null || target.getEndDate() == null) {
            throw exception(PLAN_INVALID); // 已完成节点不能改期
        }
        long delta = cmd.newStart().toEpochDay() - target.getStartDate().toEpochDay();
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
        List<Long> assetIds = cmd.fileAssetIds() == null
                ? (cmd.fileAssetId() == null ? List.of() : List.of(cmd.fileAssetId())) : cmd.fileAssetIds();
        if (assetIds.isEmpty() || assetIds.size() > 9 || assetIds.stream().anyMatch(java.util.Objects::isNull)
                || assetIds.stream().distinct().count() != assetIds.size()) {
            throw exception(ENGINEERING_INPUT_INVALID, "请选择1至9份不同的现场验收凭据");
        }
        // 重放输入=落账输入（评审十三 P1-3）：nodeId+原始日期两处同形
        Map<String, Object> payload = new LinkedHashMap<>(Map.of("nodeId", nodeId,
                "date", cmd.date() == null ? "" : cmd.date().toString()));
        // 新多凭据契约锁定完整意图；旧单凭据命令保留历史幂等指纹兼容。
        if (cmd.fileAssetIds() != null) {
            payload.put("note", StrUtil.nullToEmpty(cmd.note()));
            payload.put("fileAssetIds", assetIds.stream().map(String::valueOf).toList());
        }
        var replayed = commands.replayed(post, idempotencyKey, payload);
        if (replayed.isPresent()) {
            return projectService.detail(post, projectId);
        }
        List<FrEngPlanNodeDO> nodes = planNodes.selectByProject(post.orgId(), projectId);
        FrEngPlanNodeDO node = nodes.stream().filter(n -> n.getId() == nodeId).findFirst()
                .orElseThrow(() -> exception(ENGINEERING_INPUT_INVALID));
        if ("START".equals(node.getMilestone())) throw exception(ENGINEERING_INPUT_INVALID,"请通过正式开工入口记录开工");
        FrEngPlanNodeDO current = EngGuard.currentNode(nodes);
        if (project.getActualStartAt() == null || current == null || node.getId() != current.getId()) {
            throw exception(ENGINEERING_INPUT_INVALID, "请从当前施工节点继续记录");
        }
        LocalDate date = cmd.date() == null ? LocalDate.now() : cmd.date();
        if (date.isAfter(LocalDate.now())) {
            throw exception(ENGINEERING_INPUT_INVALID, "记录日期不能晚于今天");
        }
        if (StrUtil.isBlank(cmd.note())) {
            throw exception(ENGINEERING_INPUT_INVALID, "请记录现场情况");
        }
        // M07-T03/I04：现场/验收凭据资产域侧校验
        for (Long assetId : assetIds) requireValidAsset(post, assetId,
                java.util.Set.of("ENG_SITE_PHOTO", "ENG_DRAWING"), "现场验收");
        if (!materialReadyForNode(post, projectId, nodeId)) {
            throw exception(GATE_NOT_MET); // 本节点材料尚未合格到齐
        }
        // 自动验收留痕（单人记录，D-9）
        jdbcTemplate.update("INSERT INTO fr_eng_site_record (id, company_id, project_id, node_id, "
                        + "occurred_at, note, completion, result, review_mode, file_asset_ids, author_user_id, create_time) "
                        + "VALUES (?,?,?,?,?,?,TRUE,?,?,CAST(? AS jsonb),?,now())",
                cn.hutool.core.util.IdUtil.getSnowflakeNextId(), post.orgId(), projectId, nodeId, date,
                cmd.note(), FrEngSiteRecordDO.RESULT_PASS, FrEngSiteRecordDO.MODE_SINGLE_PERSON,
                JsonList.of(assetIds.stream().map(String::valueOf).toArray()), post.userId());
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
        if ("START".equals(node.getMilestone())) return "正式开工记录不能按施工节点撤回";
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
                    .anyMatch(c -> FrEngCollectionDO.STAGE_CAP_DONE.equals(c.getStage()));
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
        // 日期、可选说明及精确换图目标共同定义本次更正，重试不能沿用同键修改凭据或原因。
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("nodeId", nodeId);
        payload.put("completedAt", cmd.completedAt() == null ? "" : cmd.completedAt().toString());
        payload.put("note", StrUtil.trimToEmpty(cmd.note()));
        payload.put("reason", StrUtil.trimToEmpty(cmd.reason()));
        payload.put("fileAssetId", cmd.fileAssetId() == null ? "" : cmd.fileAssetId().toString());
        payload.put("replacementRecordId", cmd.replacementRecordId() == null ? "" : cmd.replacementRecordId().toString());
        payload.put("replaceFileAssetId", cmd.replaceFileAssetId() == null ? "" : cmd.replaceFileAssetId().toString());
        var previous = commands.previous(post, idempotencyKey, "eng-node-correct", projectId);
        if (previous.isPresent()) {
            if (!AGGREGATE.equals(previous.get().getAggregateType())) throw exception(IDEMPOTENCY_KEY_REUSED);
            var recorded = JsonUtils.parseTree(previous.get().getPayload());
            if (recorded.isObject() && recorded.size() == 2 && recorded.has("nodeId") && recorded.has("completedAt")) {
                // 旧版只记录节点和日期；用已保存结果补核其余字段，不能把旧指纹当成任意内容的通行证。
                if (!Long.toString(nodeId).equals(recorded.path("nodeId").asText())
                        || cmd.completedAt() == null || !cmd.completedAt().toString().equals(recorded.path("completedAt").asText())) {
                    throw exception(IDEMPOTENCY_KEY_REUSED);
                }
                requireSavedLegacyCorrection(post, projectId, nodeId, cmd);
            } else {
                commands.replayed(post, idempotencyKey, payload);
            }
            return projectService.detail(post, projectId);
        }
        List<FrEngPlanNodeDO> nodes = planNodes.selectByProject(post.orgId(), projectId);
        FrEngPlanNodeDO node = nodes.stream().filter(n -> n.getId() == nodeId).findFirst()
                .orElseThrow(() -> exception(NODE_ACTION_INVALID));
        if ("START".equals(node.getMilestone()) || !FrEngPlanNodeDO.STATUS_DONE.equals(node.getStatus()) || cmd.completedAt() == null
                || StrUtil.isBlank(cmd.reason())) {
            throw exception(NODE_ACTION_INVALID);
        }
        List<FrEngSiteRecordDO> completions = siteRecords.selectByProject(post.orgId(), projectId).stream()
                .filter(record -> record.getNodeId() != null && record.getNodeId() == nodeId
                        && Boolean.TRUE.equals(record.getCompletion())).toList();
        boolean targeted = cmd.replacementRecordId() != null || cmd.replaceFileAssetId() != null;
        if (targeted && (cmd.replacementRecordId() == null || cmd.replaceFileAssetId() == null || cmd.fileAssetId() == null)) {
            throw exception(ENGINEERING_INPUT_INVALID, "请选择要替换的原凭据和新凭据");
        }
        Long replacementRecordId = cmd.replacementRecordId();
        String replacedAssetId = cmd.replaceFileAssetId() == null ? null : cmd.replaceFileAssetId().toString();
        if (cmd.fileAssetId() != null) {
            // 旧单凭据客户端仅在原记录确为一张时兼容，不能把多图隐式缩成一张。
            if (!targeted) {
                if (completions.size() != 1 || completionAssetIds(completions.get(0)).size() != 1) {
                    throw exception(ENGINEERING_INPUT_INVALID, "存在多份验收凭据，请选择要替换的原凭据");
                }
                replacementRecordId = completions.get(0).getId();
                replacedAssetId = completionAssetIds(completions.get(0)).get(0);
            }
            Long targetRecordId = replacementRecordId;
            FrEngSiteRecordDO target = completions.stream().filter(record -> record.getId().equals(targetRecordId))
                    .findFirst().orElseThrow(() -> exception(ENGINEERING_INPUT_INVALID, "原验收记录已变化，请刷新后重试"));
            List<String> originalAssets = completionAssetIds(target);
            if (!originalAssets.contains(replacedAssetId)
                    || (!cmd.fileAssetId().toString().equals(replacedAssetId) && originalAssets.contains(cmd.fileAssetId().toString()))) {
                throw exception(ENGINEERING_INPUT_INVALID, "原凭据已变化或新凭据重复，请刷新后重新选择");
            }
            requireValidAsset(post, cmd.fileAssetId(),
                    java.util.Set.of("ENG_SITE_PHOTO", "ENG_DRAWING"), "节点更正");
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
                    .filter(c -> FrEngCollectionDO.STAGE_CAP_DONE.equals(c.getStage()))
                    .map(FrEngCollectionDO::getOccurredAt).min(LocalDate::compareTo).orElse(null);
            if (toppingPaid != null && d.isAfter(toppingPaid)) {
                throw exception(NODE_ACTION_INVALID); // 封顶完成日期不能晚于已登记的封顶款收款日期
            }
        }
        String corrections = JsonList.append(node.getCorrections(), Map.of(
                "completedAt", node.getCompletedAt() == null ? "" : node.getCompletedAt().toString(),
                "note", node.getName() + " 完成更正", "reason", StrUtil.trim(cmd.reason()),
                "date", LocalDate.now().toString(), "actorUserId", post.userId()));
        jdbcTemplate.update("UPDATE fr_eng_plan_node SET completed_at = ?, corrections = CAST(? AS jsonb), "
                + "update_time = now() WHERE id = ? AND company_id = ?", d, corrections, nodeId, post.orgId());
        // 原说明和整组凭据保存在既有更正历史；只换被明确选中的那张，其余保持原样。
        for (FrEngSiteRecordDO record : completions) {
            List<String> originalAssets = completionAssetIds(record);
            String recordHistory = JsonList.append(record.getCorrections(), Map.of(
                    "occurredAt", record.getOccurredAt() == null ? "" : record.getOccurredAt().toString(),
                    "note", StrUtil.nullToEmpty(record.getNote()), "fileAssetIds", originalAssets,
                    "reason", StrUtil.trim(cmd.reason()), "date", LocalDate.now().toString(),
                    "actorUserId", Long.toString(post.userId())));
            List<String> nextAssets = new ArrayList<>(originalAssets);
            if (record.getId().equals(replacementRecordId)) {
                nextAssets.set(nextAssets.indexOf(replacedAssetId), cmd.fileAssetId().toString());
            }
            jdbcTemplate.update("UPDATE fr_eng_site_record SET occurred_at = ?, note = ?, "
                            + "file_asset_ids = CAST(? AS jsonb), corrections = CAST(? AS jsonb) WHERE id = ? AND company_id = ?",
                    d, StrUtil.isBlank(cmd.note()) ? record.getNote() : StrUtil.trim(cmd.note()),
                    JsonList.of(nextAssets.toArray()), recordHistory, record.getId(), post.orgId());
        }
        commands.record(post, AGGREGATE, projectId, projectService.bumpRevision(post, project),
                "eng-node-correct", payload, idempotencyKey);
        return projectService.detail(post, projectId);
    }

    private void requireSavedLegacyCorrection(PostSnapshot post, long projectId, long nodeId, NodeCorrectCommand cmd) {
        if (cmd.replacementRecordId() != null || cmd.replaceFileAssetId() != null
                || cmd.fileAssetId() == null || StrUtil.isBlank(cmd.note()) || StrUtil.isBlank(cmd.reason())) {
            throw exception(IDEMPOTENCY_KEY_REUSED);
        }
        FrEngPlanNodeDO node = planNodes.selectByProject(post.orgId(), projectId).stream()
                .filter(n -> n.getId() == nodeId).findFirst().orElseThrow(() -> exception(IDEMPOTENCY_KEY_REUSED));
        if (!FrEngPlanNodeDO.STATUS_DONE.equals(node.getStatus()) || !cmd.completedAt().equals(node.getCompletedAt())
                || StrUtil.isBlank(node.getCorrections())) throw exception(IDEMPOTENCY_KEY_REUSED);
        var history = JsonUtils.parseTree(node.getCorrections());
        if (!history.isArray() || history.isEmpty()) throw exception(IDEMPOTENCY_KEY_REUSED);
        var last = history.get(history.size() - 1);
        if (!StrUtil.trimToEmpty(cmd.reason()).equals(StrUtil.trimToEmpty(last.path("reason").asText()))
                || !Long.toString(post.userId()).equals(last.path("actorUserId").asText())) {
            throw exception(IDEMPOTENCY_KEY_REUSED);
        }
        var completions = siteRecords.selectByProject(post.orgId(), projectId).stream()
                .filter(record -> record.getNodeId() != null && record.getNodeId() == nodeId
                        && Boolean.TRUE.equals(record.getCompletion())).toList();
        if (completions.isEmpty() || completions.stream().anyMatch(record ->
                !cmd.completedAt().equals(record.getOccurredAt())
                        || !StrUtil.trimToEmpty(cmd.note()).equals(StrUtil.trimToEmpty(record.getNote()))
                        || !completionAssetIds(record).equals(List.of(cmd.fileAssetId().toString())))) {
            throw exception(IDEMPOTENCY_KEY_REUSED);
        }
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

    private static List<String> completionAssetIds(FrEngSiteRecordDO record) {
        return StrUtil.isBlank(record.getFileAssetIds()) ? List.of()
                : cn.iocoder.yudao.framework.common.util.json.JsonUtils.parseArray(record.getFileAssetIds(), String.class);
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
    /** 工程档案冻结的统一合同 id；未冻结时回退当前活跃合同（存量档案兼容） */
    private Long resolveContractId(PostSnapshot post, long projectId) {
        Long frozen = jdbcTemplate.queryForObject(
                "SELECT sale_contract_id FROM fr_eng_project WHERE id = ?", Long.class, projectId);
        if (frozen != null) {
            return frozen;
        }
        Long customerId = jdbcTemplate.queryForObject(
                "SELECT customer_id FROM fr_eng_project WHERE id = ?", Long.class, projectId);
        return customerId == null ? null : saleContracts.findActiveSignedId(post, customerId);
    }

    /**
     * M07-T03/I04：工程凭据资产引用校验——存在+ACCEPTED+本组织属主（validateReference）+类型白名单。
     * 假 ID/跨组织/未验收/错误类型全部拒绝；不再只判 assetId 非空。
     */
    // M09-T01：package-visible——EngClosureService.deliver 交付凭据复用同一校验（同包）
    void requireOwnCollectionProof(PostSnapshot post,long fileId) {
        var actor=new cn.iocoder.yudao.module.franchise.api.AssetAccessPort.Actor(post.orgId(),
                serviceAccounts.requireServiceAccount(post.orgId()),post.userId(),post.postId());
        if (!assets.isOwnAcceptedUpload(actor,fileId,"FIN_VOUCHER")) throw exception(ENGINEERING_INPUT_INVALID,"请上传本人经办的本笔收款凭据");
    }

    void requireValidAsset(PostSnapshot post, Long assetId, java.util.Set<String> allowedTypes, String usage) {
        if (assetId == null) {
            throw exception(ENGINEERING_INPUT_INVALID, "请上传有效的" + usage + "凭据");
        }
        long serviceAccount = serviceAccounts.requireServiceAccount(post.orgId());
        var actor = new cn.iocoder.yudao.module.franchise.api.AssetAccessPort.Actor(
                post.orgId(), serviceAccount, post.userId(), post.postId());
        if (!assets.validateReference(actor, assetId, allowedTypes)) {
            throw exception(ENGINEERING_INPUT_INVALID, usage + "凭据资产不存在、未验收、不属于本组织或类型不符");
        }
    }

}
