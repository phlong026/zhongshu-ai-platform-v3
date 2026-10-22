package cn.iocoder.yudao.module.franchise.test;

import cn.iocoder.yudao.module.platform.org.OrgService;
import cn.iocoder.yudao.module.platform.org.OrgServiceImpl;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import cn.iocoder.yudao.module.platform.org.StaffPostServiceImpl;
import cn.iocoder.yudao.module.platform.test.PlatformPgIntegrationTest;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

/**
 * 加盟协同域集成测试基类：在平台表之上再执行本域迁移（db/migration/franchise），
 * 平台组织与任职用真实实现，yudao 系统目录用内存实现，当前工作身份由 {@link #currentPost} 手动切换。
 */
@Import({OrgServiceImpl.class, StaffPostServiceImpl.class, FranchisePgIntegrationTest.Config.class})
@TestPropertySource(properties = "platform.test.extra-flyway-locations=,classpath:db/migration/franchise")
public abstract class FranchisePgIntegrationTest extends PlatformPgIntegrationTest {

    @TestConfiguration
    public static class Config {
        @Bean
        FakeSystemDirectory fakeSystemDirectory() {
            return FakeSystemDirectory.INSTANCE;
        }

        @Bean
        @Primary
        FixedCurrentPost fixedCurrentPost() {
            return new FixedCurrentPost();
        }

        @Bean
        FranchiseFixture franchiseFixture(OrgService orgService, StaffPostService staffPostService) {
            return new FranchiseFixture(orgService, staffPostService);
        }
    }

    @Resource
    protected FixedCurrentPost currentPost;
    @Resource
    protected FranchiseFixture fixture;

    @AfterEach
    void clearCurrentPost() {
        currentPost.clear();
    }

}
