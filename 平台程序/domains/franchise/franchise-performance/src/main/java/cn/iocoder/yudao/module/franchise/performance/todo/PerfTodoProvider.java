package cn.iocoder.yudao.module.franchise.performance.todo;

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

    public PerfTodoProvider(FrPerfTaskMapper tasks, FrPerfTaskArchiveMapper archives) {
        this.tasks = tasks;
        this.archives = archives;
    }

    @Override
    public String domain() {
        return "fr-perf";
    }

    @Override
    public List<TodoItem> list(PostSnapshot post) {
        String period = String.format("%04d-%02d", LocalDate.now().getYear(), LocalDate.now().getMonthValue());
        if (!archives.selectByPeriod(post.orgId(), period).isEmpty()) {
            return List.of(); // 归档后待办消失
        }
        List<TodoItem> todos = new ArrayList<>();
        for (FrPerfTaskDO task : tasks.selectByPeriodAndAssignee(post.orgId(), period, post.userId())) {
            if (!FrPerfTaskDO.TYPE_MANUAL.equals(task.getType()) || task.getManualDone() != null) {
                continue;
            }
            todos.add(new TodoItem(domain(), "perf-manual:" + task.getId(),
                    "绩效任务待提交凭证 · " + task.getTitle(), "pending", null,
                    true, null, "/pages/admin/tasks/index?view=mine&period=" + period,
                    task.getCreateTime()));
        }
        return todos;
    }
}
