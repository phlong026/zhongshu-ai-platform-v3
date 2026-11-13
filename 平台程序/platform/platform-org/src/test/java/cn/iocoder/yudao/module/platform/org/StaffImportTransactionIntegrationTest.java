package cn.iocoder.yudao.module.platform.org;

import cn.iocoder.yudao.module.platform.org.dal.OrgUnitDO;
import cn.iocoder.yudao.module.platform.org.dal.PostTemplateDO;
import cn.iocoder.yudao.module.platform.test.PlatformPgIntegrationTest;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static cn.iocoder.yudao.module.platform.org.enums.PlatformOrgErrorCodes.TEAM_NOT_IN_ORG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P2-1：逐行独立事务的真实回滚证明。
 *
 * <p>路径：提交行通过 checkRow 后进入真实 StaffPostServiceImpl.createStaffInOrg；
 * 测试用 OrgService 把“幽灵组”映射到不存在的 teamId，使 createUser 之后的 assignPost
 * 团队校验抛 TEAM_NOT_IN_ORG。@Transactional 应回滚本行 system_users 插入；
 * 同批此前成功行已提交、不受影响。
 */
@Import({OrgServiceImpl.class, StaffPostServiceImpl.class,
        StaffImportTransactionIntegrationTest.Config.class})
class StaffImportTransactionIntegrationTest extends PlatformPgIntegrationTest {

    private static final String ORG_TYPE = "imp-tx-store";
    private static final String ROLE = "imp-tx-sales";
    private static final long PHANTOM_TEAM = 9_876_543_210L;

    @TestConfiguration
    static class Config {
        @Bean
        FakeSystemDirectory fakeSystemDirectory(JdbcTemplate jdbc) {
            return new TransactionalDirectory(jdbc);
        }
    }

    /** 在真实事务内同时写 system_users 探针表，用于验证回滚。 */
    static class TransactionalDirectory extends FakeSystemDirectory {
        private final JdbcTemplate jdbc;

        TransactionalDirectory(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public long createUser(String name, String mobile, Long deptId) {
            long id = super.createUser(name, mobile, deptId);
            jdbc.update("INSERT INTO system_users(id, nickname, mobile) VALUES (?,?,?)", id, name, mobile);
            return id;
        }
    }

    @Resource
    private OrgService orgService;
    @Resource
    private StaffPostService staffPostService;
    @Resource
    private FakeSystemDirectory directory;
    @Resource
    private JdbcTemplate jdbc;

    private long orgId;
    private long teamId;

    @BeforeEach
    void setUp() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS system_users ("
                + "id bigint PRIMARY KEY, nickname varchar(30), mobile varchar(11))");
        jdbc.update("INSERT INTO pf_org_type(code, domain, name) VALUES (?, 'test', '门店') ON CONFLICT DO NOTHING",
                ORG_TYPE);
        jdbc.update("INSERT INTO pf_post_template(role_code, domain, org_types, line, lead, inherits_role_code) "
                + "VALUES (?, 'test', ?, 'sales', FALSE, NULL) ON CONFLICT DO NOTHING", ROLE, ORG_TYPE);
        orgId = orgService.createOrg(ORG_TYPE, "导入事务门店" + System.nanoTime(), null);
        teamId = orgService.createTeam(orgId, null, "业务一组", "sales");
        directory.roleIdsByCode.put(ROLE, 701L);
        directory.deptNames.put(teamId, "业务一组");
        directory.deptNames.put(PHANTOM_TEAM, "幽灵组");
    }

    @Test
    void failedRowRollsBackItsUserAndDoesNotAffectEarlierCommittedRow() {
        OrgService orgMock = mock(OrgService.class);
        when(orgMock.listTeams(orgId)).thenReturn(List.of(
                new OrgUnitDO().setDeptId(teamId),
                new OrgUnitDO().setDeptId(PHANTOM_TEAM)));
        when(orgMock.listPostTemplates(orgId)).thenReturn(List.of(new PostTemplateDO().setRoleCode(ROLE)));
        StaffImportService service = new StaffImportService(staffPostService, orgMock, directory);

        StaffImportService.ImportResult result = service.submit(orgId, List.of(
                new StaffImportService.Row(1, "张三", "13800138011", ROLE, "业务一组"),
                new StaffImportService.Row(2, "李四", "13800138012", ROLE, "幽灵组")), 99L);

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.rows().get(1).code()).isEqualTo(TEAM_NOT_IN_ORG.getCode());
        // ② 同批成功行仍在（逐行独立事务）
        assertThat(staffPostService.listOrgPosts(orgId)).hasSize(1)
                .allSatisfy(post -> assertThat(post.getTeamId()).isEqualTo(teamId));
        // ① 失败行新建的 system_users 已回滚（零残留）；成功行保留
        assertThat(userCount("13800138011")).isEqualTo(1);
        assertThat(userCount("13800138012")).isZero();
    }

    private int userCount(String mobile) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM system_users WHERE mobile = ?",
                Integer.class, mobile);
        return count == null ? 0 : count;
    }
}
