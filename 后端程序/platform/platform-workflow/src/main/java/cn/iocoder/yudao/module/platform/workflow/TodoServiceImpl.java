package cn.iocoder.yudao.module.platform.workflow;

import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;

@Service
@Slf4j
public class TodoServiceImpl implements TodoService {

    private static final Comparator<TodoItem> ORDER = Comparator
            .comparing(TodoItem::actionable).reversed()
            .thenComparing(TodoItem::since, Comparator.nullsLast(Comparator.<OffsetDateTime>reverseOrder()));

    private final List<TodoProvider> providers;

    @Autowired
    public TodoServiceImpl(ObjectProvider<TodoProvider> providers) {
        this(providers.orderedStream().toList());
    }

    public TodoServiceImpl(List<TodoProvider> providers) {
        this.providers = List.copyOf(providers);
    }

    @Override
    public TodoList list(PostSnapshot post) {
        Map<String, TodoItem> byKey = new LinkedHashMap<>();
        List<String> failed = new ArrayList<>();
        for (TodoProvider provider : providers) {
            try {
                for (TodoItem item : provider.list(post)) {
                    byKey.putIfAbsent(item.key(), item);
                }
            } catch (RuntimeException e) {
                // 单个业务域出错不影响其他待办，但必须如实告知调用方并留下排查线索
                log.error("[list][domain={} postId={} orgId={}] 待办计算失败", provider.domain(), post.postId(), post.orgId(), e);
                failed.add(provider.domain());
            }
        }
        List<TodoItem> items = byKey.values().stream().sorted(ORDER).toList();
        int actionable = (int) items.stream().filter(TodoItem::actionable).count();
        return new TodoList(items, actionable, List.copyOf(failed));
    }

}
