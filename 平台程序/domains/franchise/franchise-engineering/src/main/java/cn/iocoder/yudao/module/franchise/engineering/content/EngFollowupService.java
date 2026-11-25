package cn.iocoder.yudao.module.franchise.engineering.content;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngCollectionDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngCollectionMapper;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngCollectionTermDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngCollectionTermMapper;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngFollowupDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngFollowupMapper;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngMaterialDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngMaterialMapper;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngPlanNodeDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngPlanNodeMapper;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngProjectDO;
import cn.iocoder.yudao.module.franchise.org.FranchiseCommands;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.franchise.engineering.enums.FranchiseEngineeringErrorCodes.*;

/**
 * 催办双维度（D-10）：dunning=stage 维度（COLLECTION）；材料催到货=material_plan_id 维度（MATERIAL）。
 * 同日同内容幂等（数据库行查重，命中返回现状不重写）；催收回访待办=EngTodoProvider（分级查询派生）。
 */
@Service
public class EngFollowupService {

    public static final String AGGREGATE = "fr-eng-project";

    private final EngProjectService projectService;
    private final FrEngFollowupMapper followups;
    private final FrEngCollectionTermMapper collectionTerms;
    private final FrEngCollectionMapper collections;
    private final FrEngPlanNodeMapper planNodes;
    private final FrEngMaterialMapper materials;
    private final FranchiseCommands commands;
    private final org.springframework.beans.factory.ObjectProvider<cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort> paymentAccounts;

    public EngFollowupService(EngProjectService projectService, FrEngFollowupMapper followups,
                              FrEngCollectionTermMapper collectionTerms, FrEngCollectionMapper collections,
                              FrEngPlanNodeMapper planNodes, FrEngMaterialMapper materials,
                              FranchiseCommands commands,org.springframework.beans.factory.ObjectProvider<cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort> paymentAccounts) {
        this.projectService = projectService;
        this.followups = followups;
        this.collectionTerms = collectionTerms;
        this.collections = collections;
        this.planNodes = planNodes;
        this.materials = materials;
        this.commands = commands;
        this.paymentAccounts=paymentAccounts;
    }

    public record DunningCommand(String stage, LocalDate promiseDate, String note) {
    }

    public record MaterialFollowupCommand(long materialPlanId, LocalDate promiseDate, String note) {
    }

    /** 催收回访（stage 维度；TOPPING 须封顶完成；本期有剩余才可催收） */
    @Transactional(rollbackFor = Exception.class)
    public List<Map<String, Object>> dunning(PostSnapshot post, long projectId, DunningCommand cmd, String idempotencyKey) {
        FrEngProjectDO project = projectService.requireOwned(post, projectId, "dunning");
        Map<String, Object> payload = dunningPayload(cmd);
        var replayed = commands.replayed(post, idempotencyKey, payload);
        if (replayed.isPresent()) {
            return list(post, projectId);
        }
        String raw=cmd.stage()==null?"":cmd.stage().trim();
        String stage="TOPPING".equals(raw)?"CAP_DONE":"START".equals(raw)?"SIGN_AFTER":raw;
        var accounts=paymentAccounts.getIfAvailable();
        Long contractId=project.getSaleContractId()!=null?project.getSaleContractId():project.getCustomerId()==null || accounts==null?null:accounts.activeContractId(post,project.getCustomerId());
        cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort.StageBalance balance;
        if(contractId!=null) {
            if(accounts==null) throw exception(ENGINEERING_INPUT_INVALID,"合同收款服务暂不可用");
            balance=accounts.stageBalances(post,contractId).stream().filter(s->stage.equals(s.stage())).findFirst()
                    .orElseThrow(()->exception(ENGINEERING_INPUT_INVALID,"付款节点不存在，请刷新合同付款计划"));
        } else {
            long expected=collectionTerms.selectByProject(post.orgId(),projectId).stream().filter(t->stage.equals(t.getStage())).mapToLong(FrEngCollectionTermDO::getAmountCents).findFirst()
                    .orElseThrow(()->exception(ENGINEERING_INPUT_INVALID,"付款节点不存在，请刷新合同付款计划"));
            long confirmed=collections.selectByProject(post.orgId(),projectId).stream().filter(c->stage.equals(c.getStage())).mapToLong(FrEngCollectionDO::getAmountCents).sum();
            balance=new cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort.StageBalance(stage,expected,confirmed);
        }
        if(EngPaymentDates.waitingForWork(balance,planNodes.selectByProject(post.orgId(),projectId))) throw exception(ENGINEERING_INPUT_INVALID,"请先完成约定施工节点再登记催收");
        long expected=balance.expectedCents(),confirmed=balance.collectedCents();
        if (expected - confirmed <= 0) {
            throw exception(ENGINEERING_INPUT_INVALID, "本期款项已收齐，无需催收登记");
        }
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        if (cmd.promiseDate() != null && cmd.promiseDate().isBefore(today)) {
            throw exception(ENGINEERING_INPUT_INVALID, "承诺付款日须为今天或之后的日期");
        }
        String promise = cmd.promiseDate() == null ? "" : cmd.promiseDate().toString();
        String note = StrUtil.nullToEmpty(cmd.note());
        // 同日同内容幂等（蓝本 material-followup 同款：命中返回现状不重写）
        boolean duplicate = followups.selectByProject(post.orgId(), projectId).stream().anyMatch(f ->
                FrEngFollowupDO.KIND_COLLECTION.equals(f.getKind()) && stage.equals(f.getStage())
                        && f.getCreateTime() != null && localDay(f.getCreateTime()).equals(today)
                        && promise.equals(f.getPromiseDate() == null ? "" : f.getPromiseDate().toString())
                        && note.equals(StrUtil.nullToEmpty(f.getNote())));
        if (!duplicate) {
            followups.insert(new FrEngFollowupDO().setCompanyId(post.orgId()).setProjectId(projectId)
                    .setKind(FrEngFollowupDO.KIND_COLLECTION).setStage(stage)
                    .setPromiseDate(cmd.promiseDate()).setNote(note).setAuthorUserId(post.userId()));
        }
        commands.record(post, AGGREGATE, projectId,
                projectService.bumpRevision(post, project),
                "eng-dunning", payload, idempotencyKey);
        return list(post, projectId);
    }

    /** 材料催到货（material_plan_id 维度；计划未到齐未取消；承诺日≥今天；同日同内容幂等） */
    @Transactional(rollbackFor = Exception.class)
    public List<Map<String, Object>> materialFollowup(PostSnapshot post, long projectId,
                                                      MaterialFollowupCommand cmd, String idempotencyKey) {
        FrEngProjectDO project = projectService.requireOwned(post, projectId, "materials");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("materialPlanId", cmd.materialPlanId());
        payload.put("promiseDate", cmd.promiseDate() == null ? "" : cmd.promiseDate().toString());
        payload.put("note", StrUtil.nullToEmpty(cmd.note()));
        var replayed = commands.replayed(post, idempotencyKey, payload);
        if (replayed.isPresent()) {
            return list(post, projectId);
        }
        List<FrEngMaterialDO> planLines = materials.selectByPlan(post.orgId(), cmd.materialPlanId());
        if (planLines.isEmpty() || planLines.get(0).getProjectId() != projectId) {
            throw exception(ENGINEERING_INPUT_INVALID, "材料计划不存在");
        }
        boolean cancelled = planLines.stream().allMatch(m ->
                FrEngMaterialDO.STATUS_CANCELLED.equals(m.getStatus()));
        if (cancelled) {
            throw exception(ENGINEERING_INPUT_INVALID, "该材料计划已取消，无需催到货");
        }
        if (StrUtil.isBlank(cmd.note())) {
            throw exception(ENGINEERING_INPUT_INVALID, "请填写本次催到货联系说明");
        }
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        if (cmd.promiseDate() != null && cmd.promiseDate().isBefore(today)) {
            throw exception(ENGINEERING_INPUT_INVALID, "承诺到货日须为今天或之后的日期");
        }
        String promise = cmd.promiseDate() == null ? "" : cmd.promiseDate().toString();
        String note = StrUtil.trim(cmd.note());
        boolean duplicate = followups.selectByProject(post.orgId(), projectId).stream().anyMatch(f ->
                FrEngFollowupDO.KIND_MATERIAL.equals(f.getKind())
                        && f.getMaterialPlanId() != null && f.getMaterialPlanId() == cmd.materialPlanId()
                        && f.getCreateTime() != null && localDay(f.getCreateTime()).equals(today)
                        && promise.equals(f.getPromiseDate() == null ? "" : f.getPromiseDate().toString())
                        && note.equals(StrUtil.nullToEmpty(f.getNote())));
        if (!duplicate) {
            followups.insert(new FrEngFollowupDO().setCompanyId(post.orgId()).setProjectId(projectId)
                    .setKind(FrEngFollowupDO.KIND_MATERIAL).setMaterialPlanId(cmd.materialPlanId())
                    .setPromiseDate(cmd.promiseDate()).setNote(note).setAuthorUserId(post.userId()));
        }
        commands.record(post, AGGREGATE, projectId,
                projectService.bumpRevision(post, project),
                "eng-material-followup", payload, idempotencyKey);
        return list(post, projectId);
    }

    /** create_time 经 JDBC 读回为 UTC 偏移，与 JVM 当日（+08）比较前须归一——
     *  直接 toLocalDate 在 +08 的 0-8 点时段会把当日误判为前一日，同日幂等失效（e2e AC9 深夜跑暴露）。 */
    private static LocalDate localDay(OffsetDateTime t) {
        return t.atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDate();
    }

    private static Map<String, Object> dunningPayload(DunningCommand cmd) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stage", cmd.stage());
        m.put("promiseDate", cmd.promiseDate() == null ? "" : cmd.promiseDate().toString());
        m.put("note", StrUtil.nullToEmpty(cmd.note()));
        return m;
    }

    public List<Map<String, Object>> list(PostSnapshot post, long projectId) {
        projectService.requireProject(post, projectId);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (FrEngFollowupDO f : followups.selectByProject(post.orgId(), projectId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", f.getId());
            row.put("kind", f.getKind());
            row.put("stage", f.getStage());
            row.put("materialPlanId", f.getMaterialPlanId());
            row.put("promiseDate", f.getPromiseDate());
            row.put("note", f.getNote());
            row.put("authorUserId", f.getAuthorUserId());
            row.put("createTime", f.getCreateTime());
            rows.add(row);
        }
        return rows;
    }
}
