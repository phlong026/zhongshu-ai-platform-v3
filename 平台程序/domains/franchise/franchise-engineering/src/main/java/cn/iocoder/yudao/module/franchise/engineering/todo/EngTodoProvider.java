package cn.iocoder.yudao.module.franchise.engineering.todo;

import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngCollectionDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngCollectionMapper;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngCollectionTermDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngCollectionTermMapper;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngFollowupDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngFollowupMapper;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngPlanNodeDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngPlanNodeMapper;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngProjectDO;
import cn.iocoder.yudao.module.franchise.engineering.dal.FrEngProjectMapper;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.workflow.TodoItem;
import cn.iocoder.yudao.module.platform.workflow.TodoProvider;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 工程催收回访待办（D-10）：两期未收齐 → 待办（逾期/承诺过期回归分级同蓝本 tasks() 派生口径）。
 * 仅负责人/指定工程师可见（owner 语义）。
 */
@Component
public class EngTodoProvider implements TodoProvider {

    private final FrEngProjectMapper projects;
    private final FrEngCollectionTermMapper collectionTerms;
    private final FrEngCollectionMapper collections;
    private final FrEngPlanNodeMapper planNodes;
    private final FrEngFollowupMapper followups;
    private final cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort unifiedStages;

    public EngTodoProvider(FrEngProjectMapper projects, FrEngCollectionTermMapper collectionTerms,
                           FrEngCollectionMapper collections, FrEngPlanNodeMapper planNodes,
                           FrEngFollowupMapper followups,
                           cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort unifiedStages) {
        this.projects = projects;
        this.collectionTerms = collectionTerms;
        this.collections = collections;
        this.planNodes = planNodes;
        this.followups = followups;
        this.unifiedStages = unifiedStages;
    }

    @Override
    public String domain() {
        return "fr-eng";
    }

    @Override
    public List<TodoItem> list(PostSnapshot post) {
        List<TodoItem> todos = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (FrEngProjectDO project : projects.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FrEngProjectDO>()
                        .eq(FrEngProjectDO::getCompanyId, post.orgId())
                        .ne(FrEngProjectDO::getStatus, FrEngProjectDO.STATUS_ARCHIVED))) {
            boolean own = post.userId() == project.getManagerUserId()
                    || (project.getAssignedEngineerUserId() != null
                    && post.userId() == project.getAssignedEngineerUserId());
            if (!own) {
                continue;
            }
            List<FrEngPlanNodeDO> nodes = planNodes.selectByProject(post.orgId(), project.getId());
            boolean toppingDone = nodes.stream().anyMatch(n ->
                    FrEngPlanNodeDO.MILESTONE_TOPPING.equals(n.getMilestone())
                            && FrEngPlanNodeDO.STATUS_DONE.equals(n.getStatus()));
            LocalDate startDue = nodes.isEmpty() ? null : nodes.get(0).getStartDate();
            // M09-T03/R05（批次#35）：开工资金门槛不足待补收——统一实收账净口径
            // （Σ分笔−Σ已核实退款）。退款回缺不被既有催收投影捕捉（其 confirmed 读工程
            // 留痕行，退款不删留痕行）；本待办与 requireStartGate 同源读数，补收足额即消失。
            // 仅提示补收，不撤任何施工/交付事实（门禁只拦动作）。
            // R1 P3：期款行整表读取一次，投影段与催收段共用（未开工项目量级有限，登记边界）
            java.util.List<FrEngCollectionTermDO> terms = collectionTerms.selectByProject(post.orgId(), project.getId());
            Long currentContractId=project.getSaleContractId()!=null?project.getSaleContractId():project.getCustomerId()==null?null:unifiedStages.activeContractId(post,project.getCustomerId());
            if(currentContractId!=null){
                var current=unifiedStages.stageBalances(post,currentContractId);
                if(current!=null&&!current.isEmpty())terms=current.stream().filter(b->!"tail".equals(b.stage())).map(b->new FrEngCollectionTermDO().setStage(b.stage()).setAmountCents(b.expectedCents())).toList();
            }
            if (project.getActualStartAt() == null) {
                FrEngCollectionTermDO signTerm = terms.stream()
                        .filter(t -> FrEngCollectionTermDO.STAGE_SIGN_AFTER.equals(t.getStage()))
                        .filter(t -> t.getAmountCents() != null && t.getAmountCents() > 0)
                        .findFirst().orElse(null);
                if (signTerm != null) {
                    Long contractId = project.getSaleContractId() != null
                            ? project.getSaleContractId()
                            : (project.getCustomerId() == null ? null
                            : unifiedStages.activeContractId(post, project.getCustomerId()));
                    long confirmed = contractId == null ? 0
                            : unifiedStages.stageCollectedCents(post, contractId, FrEngCollectionTermDO.STAGE_SIGN_AFTER);
                    long shortfall = signTerm.getAmountCents() - confirmed;
                    if (shortfall > 0) {
                        todos.add(new TodoItem(domain(), "fr-eng:gate-shortfall:" + project.getId(),
                                "开工资金门槛不足待补收 · " + project.getName(),
                                "shortfall", shortfall, true, null,
                                "/pages/build/record/index?id=" + project.getId() + "&view=collection",
                                project.getCreateTime()));
                    }
                }
            }
            LocalDate toppingDue = nodes.stream()
                    .filter(n -> FrEngPlanNodeDO.MILESTONE_TOPPING.equals(n.getMilestone()))
                    .findFirst().map(FrEngPlanNodeDO::getEndDate).orElse(null);
            for (FrEngCollectionTermDO term : terms) {
                // M07-T06：CAP_DONE 期须封顶完成；BASE_DONE 期须基础节点完成留痕（与收款门禁同规则）
                if (FrEngCollectionTermDO.STAGE_CAP_DONE.equals(term.getStage()) && !toppingDone) {
                    continue; // 封顶款待封顶完成后进入待办（收款侧前置的同向派生）
                }
                long confirmed = currentContractId!=null?unifiedStages.stageCollectedCents(post,currentContractId,term.getStage()):collections.selectByProject(post.orgId(), project.getId()).stream()
                        .filter(c -> term.getStage().equals(c.getStage()))
                        .mapToLong(FrEngCollectionDO::getAmountCents).sum();
                long remaining = term.getAmountCents() - confirmed;
                if (remaining <= 0) {
                    continue;
                }
                // R2 P2-6：BASE_DONE 到期=基础节点完成日（无则 null——未约定日期不显示逾期压力）
                LocalDate due;
                if (FrEngCollectionTermDO.STAGE_CAP_DONE.equals(term.getStage())) {
                    due = toppingDue;
                } else if (FrEngCollectionTermDO.STAGE_BASE_DONE.equals(term.getStage())) {
                    due = nodes.stream()
                            .filter(n -> !FrEngPlanNodeDO.MILESTONE_TOPPING.equals(n.getMilestone())
                                    && FrEngPlanNodeDO.STATUS_DONE.equals(n.getStatus()))
                            .map(FrEngPlanNodeDO::getEndDate).findFirst().orElse(null);
                } else {
                    due = startDue;
                }
                boolean overdue = due != null && due.isBefore(today);
                FrEngFollowupDO last = followups.selectByProject(post.orgId(), project.getId()).stream()
                        .filter(f -> FrEngFollowupDO.KIND_COLLECTION.equals(f.getKind())
                                && term.getStage().equals(f.getStage()))
                        .findFirst().orElse(null);
                String title = stageTitle(term.getStage())
                        + (overdue ? "已逾期待催收" : "待登记收款") + " · " + project.getName();
                todos.add(new TodoItem(domain(), "eng-collect:" + project.getId() + ":" + term.getStage(),
                        title, overdue ? "overdue" : "pending", remaining,
                        false, null, "/pages/build/record/index?id=" + project.getId() + "&view=collection",
                        last != null ? last.getCreateTime() : null));
            }
        }
        return todos;
    }
    /** M07-T06 三期待办标题 */
    private static String stageTitle(String stage) {
        return switch (stage) {
            case "SIGN_AFTER" -> "签约后期款";
            case "BASE_DONE" -> "基础完成期款";
            case "CAP_DONE" -> "封顶后期款";
            case "START" -> "开工款";
            case "TOPPING" -> "封顶款";
            default -> stage;
        };
    }

}
