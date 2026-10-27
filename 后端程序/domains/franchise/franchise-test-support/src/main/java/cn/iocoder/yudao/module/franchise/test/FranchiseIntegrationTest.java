package cn.iocoder.yudao.module.franchise.test;

import cn.iocoder.yudao.framework.datapermission.config.YudaoDataPermissionAutoConfiguration;
import cn.iocoder.yudao.framework.datapermission.core.db.DataPermissionRuleHandler;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.authz.OrgScopeRule;
import cn.iocoder.yudao.module.platform.command.CommandLogImpl;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.StaffPostServiceImpl;
import cn.iocoder.yudao.module.platform.org.SystemDirectory;
import cn.iocoder.yudao.module.platform.test.PlatformPgIntegrationTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 加盟协同域集成测试基类。
 *
 * <p>在平台测试基类之上加：yudao 数据权限框架 + 平台组织范围规则（业务表按当前组织过滤）、
 * 真实的任职服务（岗位继承读 pf_post_template）、命令事件日志，以及可在用例中切换的当前工作身份 {@link #as}。
 * yudao 系统目录用 Mockito 替身（本域测试不建部门、不同步角色）。
 */
@Import({YudaoDataPermissionAutoConfiguration.class, OrgScopeRule.class, StaffPostServiceImpl.class,
        CommandLogImpl.class, FranchiseIntegrationTest.Config.class})
public abstract class FranchiseIntegrationTest extends PlatformPgIntegrationTest {

    protected static final AtomicLong IDS = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    @MockitoBean
    protected SystemDirectory systemDirectory;

    @TestConfiguration
    public static class Config {
        @Bean
        public SwitchableCurrentPost switchableCurrentPost() {
            return new SwitchableCurrentPost();
        }

        /** 测试上下文默认懒加载；数据权限拦截器要在创建时挂到 MyBatis 上，这里强制创建 */
        @Bean
        @Lazy(false)
        public Object eagerDataPermission(DataPermissionRuleHandler handler) {
            return handler;
        }
    }

    /** 当前工作身份：组织范围过滤与业务规则都以它为准 */
    public static class SwitchableCurrentPost implements CurrentPost {
        private PostSnapshot post;

        @Override
        public Optional<PostSnapshot> find() {
            return Optional.ofNullable(post);
        }

        public void set(PostSnapshot post) {
            this.post = post;
        }
    }

    @jakarta.annotation.Resource
    private SwitchableCurrentPost currentPost;

    /** 切换当前工作身份，返回该身份便于直接传给服务 */
    protected PostSnapshot as(PostSnapshot post) {
        currentPost.set(post);
        return post;
    }

    protected static long newId() {
        return IDS.incrementAndGet();
    }

    /** 构造一个工作身份（不落库）：用户与任职编号自动生成 */
    protected static PostSnapshot post(long orgId, String orgType, String role) {
        return new PostSnapshot(newId(), newId(), orgId, orgType, role, null, false, Set.of());
    }

    protected static PostSnapshot post(long orgId, String orgType, String role, long userId) {
        return new PostSnapshot(newId(), userId, orgId, orgType, role, null, false, Set.of());
    }

    /** 负责人：所在团队及其下级团队 */
    protected static PostSnapshot lead(long orgId, String role, long teamId, Set<Long> managedTeams) {
        return new PostSnapshot(newId(), newId(), orgId, "store", role, teamId, true, managedTeams);
    }

}
