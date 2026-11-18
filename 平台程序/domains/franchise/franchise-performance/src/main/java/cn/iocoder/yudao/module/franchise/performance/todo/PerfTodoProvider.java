package cn.iocoder.yudao.module.franchise.performance.todo;

import cn.iocoder.yudao.module.franchise.performance.content.PerfCommissionService;
import cn.iocoder.yudao.module.franchise.performance.dal.FrPerfTaskArchiveMapper;
import cn.iocoder.yudao.module.franchise.performance.dal.FrPerfTaskDO;
import cn.iocoder.yudao.module.franchise.performance.dal.FrPerfTaskMapper;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.workflow.TodoItem;
import cn.iocoder.yudao.module.platform.workflow.TodoProvider;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** 绩效任务待办（AC11）：manual 任务未补填且周期未归档 → 被考核人待办（域短码 fr-perf）。 */
@Component
public class PerfTodoProvider implements TodoProvider {

    private final FrPerfTaskMapper tasks;
    private final FrPerfTaskArchiveMapper archives;
    private final PerfCommissionService commissions;

    public PerfTodoProvider(FrPerfTaskMapper tasks, FrPerfTaskArchiveMapper archives, PerfCommissionService commissions) {
        this.tasks = tasks;
        this.archives = archives;
        this.commissions = commissions;
    }

    @Override
    public String domain() {
        return "fr-perf";
    }

    @Override
    public List<TodoItem> list(PostSnapshot post) {
        // 扫近两月未归档周期（跨月首日上月任务仍在办理期）
        LocalDate now = LocalDate.now();
        List<String> periods = List.of(
                String.format("%04d-%02d", now.getYear(), now.getMonthValue()),
                String.format("%04d-%02d", now.minusMonths(1).getYear(), now.minusMonths(1).getMonthValue()));
        List<TodoItem> todos = new ArrayList<>();
        for (String period : periods) {
            if (!archives.selectByPeriod(post.orgId(), period).isEmpty()) {
                continue; // 归档后待办消失（锁定语义）
            }
            for (FrPerfTaskDO task : tasks.selectByPeriodAndAssignee(post.orgId(), period, post.userId())) {
                if (!FrPerfTaskDO.TYPE_MANUAL.equals(task.getType()) || task.getManualDone() != null) {
                    continue;
                }
                todos.add(new TodoItem(domain(), "perf-manual:" + task.getId(),
                        "绩效任务待提交凭证 · " + task.getTitle(), "pending", null,
                        true, null, "/pages/admin/tasks/index?view=mine&period=" + period,
                        task.getCreateTime()));
            }
        }
        todos.addAll(commissions.reviewTodos(post));
        return todos;
    }
}
