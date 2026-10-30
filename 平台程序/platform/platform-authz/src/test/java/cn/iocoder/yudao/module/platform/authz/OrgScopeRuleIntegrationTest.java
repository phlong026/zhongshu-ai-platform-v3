package cn.iocoder.yudao.module.platform.authz;

import cn.iocoder.yudao.framework.datapermission.config.YudaoDataPermissionAutoConfiguration;
import cn.iocoder.yudao.framework.datapermission.core.db.DataPermissionRuleHandler;
import cn.iocoder.yudao.framework.datapermission.core.util.DataPermissionUtils;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.test.PlatformPgIntegrationTest;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@Import({YudaoDataPermissionAutoConfiguration.class, OrgScopeRule.class, OrgScopeRuleIntegrationTest.Config.class})
class OrgScopeRuleIntegrationTest extends PlatformPgIntegrationTest {

    private static final AtomicLong IDS = new AtomicLong(System.nanoTime() % 1_000_000_000L);
    private static final long ORG_A = 100L;
    private static final long ORG_B = 200L;
    private static final long HQ = 300L;

    @TestConfiguration
    static class Config {
        @Bean
        PostPermissionEvaluatorTest.StubCurrentPost stubCurrentPost() {
            return new PostPermissionEvaluatorTest.StubCurrentPost();
        }

        @Bean
        OrgScopeCustomizer probeScope() {
            return rule -> rule.register("authz_scope_probe", "company_id", "design_org_id");
        }

        /** 测试上下文默认懒加载；数据权限拦截器要在 Bean 创建时挂到 MyBatis 上，这里强制创建 */
        @Bean
        @Lazy(false)
        Object eagerDataPermission(DataPermissionRuleHandler handler) {
            return handler;
        }
    }

    @Resource
    private ScopeProbeMapper mapper;
    @Resource
    private PostPermissionEvaluatorTest.StubCurrentPost currentPost;
    @Resource
    private JdbcTemplate jdbc;

    private String batch;

    @BeforeEach
    void seed() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS authz_scope_probe(id BIGINT PRIMARY KEY, company_id BIGINT, design_org_id BIGINT, batch VARCHAR(32))");
        batch = "b" + IDS.incrementAndGet();
        insert(ORG_A, null);
        insert(ORG_A, HQ);   // 委托给总部的 A 店事项
        insert(ORG_B, null);
        insert(ORG_B, HQ);   // 委托给总部的 B 店事项
    }

    @Test
    void storeSeesOnlyItsOwnRows() {
        currentPost.post = post(ORG_A);

        assertThat(mapper.selectBatch(batch)).extracting(ScopeProbeDO::getCompanyId).containsOnly(ORG_A).hasSize(2);
    }

    @Test
    void headquartersSeesOnlyRowsExplicitlyAssignedToIt() {
        currentPost.post = post(HQ);

        assertThat(mapper.selectBatch(batch)).extracting(ScopeProbeDO::getDesignOrgId).containsOnly(HQ).hasSize(2);
    }

    @Test
    void ruleAppliesToAliasedHandWrittenSql() {
        currentPost.post = post(ORG_B);

        assertThat(mapper.selectBatchWithAlias(batch)).extracting(ScopeProbeDO::getCompanyId).containsOnly(ORG_B).hasSize(2);
    }

    /** MyBatis-Plus 3.5.17 的 selectOne 走游标查询，默认不经过数据权限拦截 */
    @Test
    void selectOneIsScopedToo() {
        long rowOfB = jdbc.queryForObject("SELECT id FROM authz_scope_probe WHERE batch = ? AND company_id = ? AND design_org_id IS NULL",
                Long.class, batch, ORG_B);
        currentPost.post = post(ORG_A);

        assertThat(mapper.selectOne(ScopeProbeDO::getId, rowOfB)).isNull();
        assertThat(mapper.selectCount(ScopeProbeDO::getId, rowOfB)).isZero();
        currentPost.post = post(ORG_B);
        assertThat(mapper.selectOne(ScopeProbeDO::getId, rowOfB)).isNotNull();
        currentPost.post = null;
        assertThat(DataPermissionUtils.executeIgnore(() -> mapper.selectOne(ScopeProbeDO::getId, rowOfB))).isNotNull();
    }

    @Test
    void withoutAPostNothingIsVisible() {
        currentPost.post = null;

        assertThat(mapper.selectBatch(batch)).isEmpty();
    }

    @Test
    void explicitIgnoreSeesEverything() {
        currentPost.post = null;

        assertThat(DataPermissionUtils.executeIgnore(() -> mapper.selectBatch(batch))).hasSize(4);
    }

    @Test
    void updatesAndDeletesAreScopedToo() {
        currentPost.post = post(ORG_A);

        int deleted = mapper.delete(ScopeProbeDO::getBatch, batch);

        assertThat(deleted).isEqualTo(2);
        currentPost.post = null;
        assertThat(DataPermissionUtils.executeIgnore(() -> mapper.selectBatch(batch)))
                .extracting(ScopeProbeDO::getCompanyId).containsOnly(ORG_B);
    }

    private void insert(long companyId, Long designOrgId) {
        jdbc.update("INSERT INTO authz_scope_probe(id, company_id, design_org_id, batch) VALUES (?,?,?,?)",
                IDS.incrementAndGet(), companyId, designOrgId, batch);
    }

    private static PostSnapshot post(long orgId) {
        return new PostSnapshot(IDS.incrementAndGet(), IDS.incrementAndGet(), orgId, "store", "sales", null, false, Set.of());
    }

}
