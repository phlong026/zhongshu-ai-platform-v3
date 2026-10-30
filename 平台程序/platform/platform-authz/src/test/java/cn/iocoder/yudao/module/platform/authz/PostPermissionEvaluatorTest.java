package cn.iocoder.yudao.module.platform.authz;

import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.SystemDirectory;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PostPermissionEvaluatorTest {

    private final SystemDirectory directory = mock(SystemDirectory.class);
    private final StubCurrentPost currentPost = new StubCurrentPost();
    private final PostPermissionEvaluator evaluator = new PostPermissionEvaluator(currentPost, directory);

    @Test
    void onlyTheCurrentPostsRolePermissionsCount() {
        // 同一人兼业务员与财务：以业务员身份办事时，不能使用财务权限
        when(directory.roleHasPermission("sales", "franchise:customer:create")).thenReturn(true);
        when(directory.roleHasPermission("finance", "franchise:payment:register")).thenReturn(true);
        currentPost.post = post("sales");

        assertThat(evaluator.has("franchise:customer:create")).isTrue();
        assertThat(evaluator.has("franchise:payment:register")).isFalse();
    }

    @Test
    void anyOfSeveralPermissionsIsEnough() {
        when(directory.roleHasPermission("sales", "b")).thenReturn(true);
        currentPost.post = post("sales");

        assertThat(evaluator.has("a", "b")).isTrue();
    }

    @Test
    void noPostMeansNoPermission() {
        currentPost.post = null;

        assertThat(evaluator.has("franchise:customer:create")).isFalse();
    }

    private static PostSnapshot post(String role) {
        return new PostSnapshot(1L, 2L, 3L, "store", role, null, false, Set.of());
    }

    static class StubCurrentPost implements CurrentPost {
        PostSnapshot post;

        @Override
        public Optional<PostSnapshot> find() {
            return Optional.ofNullable(post);
        }
    }

}
