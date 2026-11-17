package cn.iocoder.yudao.module.platform.workflow.message;

import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.workflow.TodoItem;
import java.util.List;
import java.util.Map;

/** Domain adapters supply object-scoped business facts and authorized destinations. */
public interface MessageCenterResolver {
    default boolean allowTodo(PostSnapshot post,TodoItem todo){return true;}
    default void resolve(PostSnapshot post, Map<String,Object> message, List<TodoItem> todos) { }
    default void resolveTodo(PostSnapshot post, TodoItem todo, Map<String,Object> message) { }
}
