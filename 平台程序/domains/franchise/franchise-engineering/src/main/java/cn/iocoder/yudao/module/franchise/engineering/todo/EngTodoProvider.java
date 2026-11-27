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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import static cn.iocoder.yudao.module.franchise.api.EngineeringRectificationAssignments.ROUTING;
import static cn.iocoder.yudao.module.franchise.api.EngineeringRectificationAssignments.CURRENT_POST;

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
    private final JdbcTemplate jdbc;

    public EngTodoProvider(FrEngProjectMapper projects, FrEngCollectionTermMapper collectionTerms,
                           FrEngCollectionMapper collections, FrEngPlanNodeMapper planNodes,
                           FrEngFollowupMapper followups,
                           cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort unifiedStages, JdbcTemplate jdbc) {
        this.projects = projects;
        this.collectionTerms = collectionTerms;
        this.collections = collections;
        this.planNodes = planNodes;
        this.followups = followups;
        this.unifiedStages = unifiedStages;
        this.jdbc = jdbc;
    }

    @Override
    public String domain() {
        return "fr-eng";
    }

    @Override
    public List<TodoItem> list(PostSnapshot post) {
        List<TodoItem> todos = new ArrayList<>();
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        boolean engineeringPost="store".equals(post.orgType()) && java.util.Set.of("engineer","project-manager").contains(post.roleCode());
        boolean activeEngineer=engineeringPost && Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS("+CURRENT_POST+")",
                Boolean.class,post.postId(),post.orgId(),post.userId()));
        if (engineeringPost) {
            todos.addAll(rectifications(post,today));
        }
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
            for (var n : nodes) {
                if (activeEngineer && cn.iocoder.yudao.module.franchise.engineering.content.EngProgress.overdue(n,today)
                        && !java.util.Set.of("DELIVERED","SETTLED","ARCHIVED").contains(project.getStatus())) {
                    int days=cn.iocoder.yudao.module.franchise.engineering.content.EngProgress.deviationDays(n,today);
                    todos.add(new TodoItem(domain(),"fr-eng:delay:"+n.getId(),
                            n.getName()+"延期"+days+"天 · "+project.getName(),
                            n.getRecoveryDate()!=null&&n.getRecoveryDate().isBefore(today)?"恢复计划已逾期":"施工延期",null,true,null,
                            "/pages/build/record/index?id="+project.getId()+"&view=delay&nodeId="+n.getId(),project.getCreateTime()));
                }
            }
            Long contractId=project.getSaleContractId()!=null?project.getSaleContractId():project.getCustomerId()==null?null:unifiedStages.activeContractId(post,project.getCustomerId());
            var current=contractId==null?java.util.List.<cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort.StageBalance>of():unifiedStages.stageBalances(post,contractId);
            if(current==null || current.isEmpty()) {
                var cash=collections.selectByProject(post.orgId(),project.getId());
                current=collectionTerms.selectByProject(post.orgId(),project.getId()).stream().map(t->
                        new cn.iocoder.yudao.module.franchise.api.UnifiedStageAccountPort.StageBalance(t.getStage(),t.getAmountCents(),
                                contractId==null?cash.stream().filter(c->t.getStage().equals(c.getStage())).mapToLong(FrEngCollectionDO::getAmountCents).sum():unifiedStages.stageCollectedCents(post,contractId,t.getStage()))).toList();
            }
            if(project.getActualStartAt()==null) {
                long shortfall=current.stream().filter(b->cn.iocoder.yudao.module.franchise.engineering.content.EngPaymentDates.beforeStart(b.trigger(),b.stage()))
                        .mapToLong(b->Math.max(0,b.expectedCents()-b.collectedCents())).sum();
                if(shortfall>0) todos.add(new TodoItem(domain(),"fr-eng:gate-shortfall:"+project.getId(),
                        "开工资金门槛不足待补收 · "+project.getName(),"shortfall",shortfall,true,null,
                        "/pages/build/record/index?id="+project.getId()+"&view=payments",project.getCreateTime()));
            }
            var contactHistory=followups.selectByProject(post.orgId(),project.getId());
            for(var balance:current) {
                if(cn.iocoder.yudao.module.franchise.engineering.content.EngPaymentDates.waitingForWork(balance,nodes)) continue;
                long remaining=balance.expectedCents()-balance.collectedCents();
                if(remaining<=0) continue;
                LocalDate due=cn.iocoder.yudao.module.franchise.engineering.content.EngPaymentDates.due(balance,nodes);
                boolean overdue=due!=null && due.isBefore(today);
                var last=contactHistory.stream().filter(f->FrEngFollowupDO.KIND_COLLECTION.equals(f.getKind()) && balance.stage().equals(f.getStage())).findFirst().orElse(null);
                String label=balance.label()==null?stageTitle(balance.stage()):balance.label();
                todos.add(new TodoItem(domain(),"eng-collect:"+project.getId()+":"+balance.stage(),
                        label+(overdue?"已逾期待催收":"待登记收款")+" · "+project.getName(),overdue?"overdue":"pending",remaining,false,null,
                        "/pages/build/record/index?id="+project.getId()+"&view=collection&stage="
                                +java.net.URLEncoder.encode(balance.stage(),java.nio.charset.StandardCharsets.UTF_8),last==null?null:last.getCreateTime()));
            }
        }
        return todos;
    }
    private List<TodoItem> rectifications(PostSnapshot post,LocalDate today) {
        return jdbc.query("""
            SELECT r.id,r.project_id,r.title,p.name,r.state,r.due_date,r.create_time,
                   routing.state AS assignment
            FROM fr_eng_rectification r JOIN fr_eng_project p ON p.id=r.project_id AND p.company_id=r.company_id
            """+ROUTING+"""
            WHERE r.company_id=? AND r.state IN ('OPEN','REVIEW') AND routing.handler=?
            ORDER BY r.due_date NULLS LAST,r.id
            """,(rs,index)->{
                long id=rs.getLong("id");
                LocalDate due=rs.getObject("due_date",LocalDate.class);
                String assignment=rs.getString("assignment");
                String status="UNASSIGNED".equals(assignment)?"待分派":"REASSIGN".equals(assignment)?"待重新分派":
                        "REVIEW".equals(rs.getString("state"))?"待复验":"待整改";
                if (due!=null && due.isBefore(today)) status+=" · 已逾期";
                return new TodoItem(domain(),"fr-eng:rectification:"+id,rs.getString("title")+" · "+rs.getString("name"),
                        status,null,true,null,"/pages/build/issues/index?project="+rs.getLong("project_id")+"&issue="+id,
                        rs.getObject("create_time",java.time.OffsetDateTime.class));
            },post.orgId(),post.userId());
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
