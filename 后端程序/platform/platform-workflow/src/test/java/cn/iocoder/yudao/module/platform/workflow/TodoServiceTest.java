package cn.iocoder.yudao.module.platform.workflow;

import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TodoServiceTest {

    private static final PostSnapshot POST = new PostSnapshot(1L, 2L, 3L, "store", "sales", null, false, Set.of());
    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-10-22T09:00:00+08:00");

    @Test
    void mergesProvidersActionableFirstThenNewestFirst() {
        TodoProvider a = provider("a", List.of(
                item("a", "a:1", false, T0.plusHours(5)),
                item("a", "a:2", true, T0.plusHours(1))));
        TodoProvider b = provider("b", List.of(item("b", "b:1", true, T0.plusHours(3))));

        TodoList result = new TodoServiceImpl(List.of(a, b)).list(POST);

        assertThat(result.items()).extracting(TodoItem::key).containsExactly("b:1", "a:2", "a:1");
        assertThat(result.actionableCount()).isEqualTo(2);
        assertThat(result.failedDomains()).isEmpty();
    }

    @Test
    void duplicateKeysAreShownOnce() {
        TodoProvider a = provider("a", List.of(item("a", "same", true, T0), item("a", "same", true, T0)));

        assertThat(new TodoServiceImpl(List.of(a)).list(POST).items()).hasSize(1);
    }

    @Test
    void aFailingDomainIsReportedInsteadOfBreakingAllTodos() {
        TodoProvider ok = provider("ok", List.of(item("ok", "ok:1", true, T0)));
        TodoProvider broken = new TodoProvider() {
            @Override
            public String domain() {
                return "broken";
            }

            @Override
            public List<TodoItem> list(PostSnapshot post) {
                throw new IllegalStateException("数据库超时");
            }
        };

        TodoList result = new TodoServiceImpl(List.of(broken, ok)).list(POST);

        assertThat(result.items()).extracting(TodoItem::key).containsExactly("ok:1");
        assertThat(result.failedDomains()).containsExactly("broken");
    }

    private static TodoProvider provider(String domain, List<TodoItem> items) {
        return new TodoProvider() {
            @Override
            public String domain() {
                return domain;
            }

            @Override
            public List<TodoItem> list(PostSnapshot post) {
                return items;
            }
        };
    }

    private static TodoItem item(String domain, String key, boolean actionable, OffsetDateTime since) {
        return new TodoItem(domain, key, "标题 " + key, "状态", null, actionable, "/web/" + key, "/pages/" + key, since);
    }

}
