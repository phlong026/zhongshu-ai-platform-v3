package cn.iocoder.yudao.module.platform.org;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.platform.test.PlatformPgIntegrationTest;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;

import static cn.iocoder.yudao.module.platform.org.enums.PlatformOrgErrorCodes.POST_TEAM_CONFLICT;
import static cn.iocoder.yudao.module.platform.org.enums.PlatformOrgErrorCodes.TEAM_NOT_IN_ORG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * P1 批2：员工批量导入服务（预检只读 / 提交逐行独立事务 / 幂等重试 / 团队冲突 / 姓名保留 / 500 上限）。
 *
 * <p>真实 PG + FakeSystemDirectory；组织/团队/岗位模板走既有 OrgService 迁移与校验语义。
 * 预检断言“零写入”（用户集合与任职表均不变）。
 */
@Import({OrgServiceImpl.class, StaffPostServiceImpl.class, StaffImportService.class,
        StaffImportServiceIntegrationTest.Config.class})
@TestPropertySource(properties = "platform.legacy-design-bridge.enabled=false")
class StaffImportServiceIntegrationTest extends PlatformPgIntegrationTest {

    private static final String ORG_TYPE = "imp-store";
    private static final String ROLE = "imp-sales";

    @TestConfiguration
    static class Config {
        @Bean
        FakeSystemDirectory fakeSystemDirectory() {
            return new FakeSystemDirectory();
        }
    }

    @Resource
    private OrgService orgService;
    @Resource
    private StaffPostService staffPostService;
    @Resource
    private StaffImportService importService;
    @Resource
    private FakeSystemDirectory directory;
    @Resource
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private long orgId;
    private long teamA;
    private long teamB;

    @BeforeEach
    void setUp() {
        registerFixtures();
    }

    private void registerFixtures() {
        jdbc.update("INSERT INTO pf_org_type(code, domain, name) VALUES (?, 'test', '门店') ON CONFLICT DO NOTHING",
                ORG_TYPE);
        jdbc.update("INSERT INTO pf_post_template(role_code, domain, org_types, line, lead, inherits_role_code) "
                + "VALUES (?, 'test', ?, 'sales', FALSE, NULL) ON CONFLICT DO NOTHING", ROLE, ORG_TYPE);
        directory.roleIdsByCode.put(ROLE, 601L);
        orgId = orgService.createOrg(ORG_TYPE, "导入测试门店" + System.nanoTime(), null);
        teamA = orgService.createTeam(orgId, null, "业务一组", "sales");
        teamB = orgService.createTeam(orgId, null, "业务二组", "sales");
        // FakeSystemDirectory.createDept 不保存名称（真实实现会）；此处按真实语义补名称。
        directory.deptNames.put(teamA, "业务一组");
        directory.deptNames.put(teamB, "业务二组");
    }

    private static StaffImportService.Row row(int line, String name, String mobile, String role, String team) {
        return new StaffImportService.Row(line, name, mobile, role, team);
    }

    private int postCount() {
        return staffPostService.listOrgPosts(orgId).size();
    }

    @Test
    void precheckIsReadOnlyAndReportsOk() {
        int usersBefore = directory.users.size();
        var result = importService.precheck(orgId,
                List.of(row(1, "张三", "13800000001", ROLE, "业务一组")));

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.created()).isEqualTo(1);
        assertThat(result.skipped()).isZero();
        assertThat(result.failed()).isZero();
        assertThat(result.rows()).singleElement().satisfies(r -> {
            assertThat(r.line()).isEqualTo(1);
            assertThat(r.status()).isEqualTo("OK");
            assertThat(r.code()).isNull();
        });
        assertThat(directory.users).hasSize(usersBefore);
        assertThat(postCount()).isZero();
    }

    @Test
    void precheckReportsRowErrorsWithoutWrites() {
        // 重名团队：同一名称两支团队
        long dupA = orgService.createTeam(orgId, null, "重名组", "sales");
        long dupB = orgService.createTeam(orgId, null, "重名组", "sales");
        directory.deptNames.put(dupA, "重名组");
        directory.deptNames.put(dupB, "重名组");

        int usersBefore = directory.users.size();
        var result = importService.precheck(orgId, List.of(
                row(1, "甲", "13800000011", ROLE, "不存在的团队"),
                row(2, "乙", "13800000012", ROLE, "重名组"),
                row(3, "丙", "13800000013", "imp-unknown", "业务一组"),
                row(4, "丁", "123", ROLE, "业务一组"),
                row(5, "x".repeat(21), "13800000014", ROLE, "业务一组"),
                row(6, "", "13800000015", ROLE, "业务一组"),
                row(7, "戊", "13800000016", ROLE, "  ")));

        assertThat(result.failed()).isEqualTo(7);
        assertThat(result.rows()).extracting(StaffImportService.RowResult::line)
                .containsExactly(1, 2, 3, 4, 5, 6, 7);
        assertThat(result.rows().get(0).code()).isEqualTo(TEAM_NOT_IN_ORG.getCode());
        assertThat(result.rows().get(0).message()).contains("不存在的团队");
        assertThat(result.rows().get(1).code()).isEqualTo(400);
        assertThat(result.rows().get(1).message()).contains("重名组").contains("2");
        assertThat(result.rows().get(2).code()).isEqualTo(400);
        assertThat(result.rows().get(3).code()).isEqualTo(400);
        assertThat(result.rows().get(4).code()).isEqualTo(400);
        assertThat(result.rows().get(5).code()).isEqualTo(400);
        assertThat(result.rows().get(6).code()).isEqualTo(400);
        assertThat(directory.users).hasSize(usersBefore);
        assertThat(postCount()).isZero();
    }

    @Test
    void precheckDetectsIdempotentSkipAndTeamConflict() {
        long user = directory.createUser("李四", "13800000021", orgId);
        long postId = staffPostService.assignPostInOrg(user, orgId, ROLE, teamA, 99L);

        var result = importService.precheck(orgId, List.of(
                row(1, "李四", "13800000021", ROLE, "业务一组"),
                row(2, "李四", "13800000021", ROLE, "业务二组")));

        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.rows().get(0).status()).isEqualTo("SKIPPED");
        assertThat(result.rows().get(0).postId()).isEqualTo(postId);
        assertThat(result.rows().get(1).status()).isEqualTo("FAILED");
        assertThat(result.rows().get(1).code()).isEqualTo(POST_TEAM_CONFLICT.getCode());
    }

    @Test
    void precheckMarksExistingAccountNamePreserved() {
        directory.createUser("原姓名", "13800000031", orgId);
        var result = importService.precheck(orgId,
                List.of(row(1, "新姓名", "13800000031", ROLE, "业务一组")));
        assertThat(result.rows()).singleElement().satisfies(r -> {
            assertThat(r.status()).isEqualTo("OK");
            assertThat(r.note()).contains("保留原资料");
        });
    }

    @Test
    void submitPartiallySucceedsWithPerRowTransactions() {
        var result = importService.submit(orgId, List.of(
                row(1, "张三", "13800000041", ROLE, "业务一组"),
                row(2, "李四", "13800000042", ROLE, "业务二组"),
                row(3, "王五", "13800000043", "imp-unknown", "业务一组")), 99L);

        assertThat(result.total()).isEqualTo(3);
        assertThat(result.created()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.rows().get(0).status()).isEqualTo("CREATED");
        assertThat(result.rows().get(1).status()).isEqualTo("CREATED");
        assertThat(result.rows().get(2).status()).isEqualTo("FAILED");
        assertThat(result.rows().get(2).line()).isEqualTo(3);
        assertThat(postCount()).isEqualTo(2);
    }

    @Test
    void submitIsIdempotentOnRetry() {
        List<StaffImportService.Row> rows = List.of(
                row(1, "张三", "13800000051", ROLE, "业务一组"),
                row(2, "李四", "13800000052", ROLE, "业务二组"));

        var first = importService.submit(orgId, rows, 99L);
        var second = importService.submit(orgId, rows, 99L);

        assertThat(first.created()).isEqualTo(2);
        assertThat(second.created()).isZero();
        assertThat(second.skipped()).isEqualTo(2);
        assertThat(postCount()).isEqualTo(2);
    }

    @Test
    void submitKeepsExistingAccountName() {
        long user = directory.createUser("原姓名", "13800000061", orgId);
        var result = importService.submit(orgId,
                List.of(row(1, "新姓名", "13800000061", ROLE, "业务一组")), 99L);

        assertThat(result.created()).isEqualTo(1);
        assertThat(directory.userNames.get(user)).isEqualTo("原姓名");
        assertThat(postCount()).isEqualTo(1);
    }

    @Test
    void batchCapRejectsEmptyAndOverLimitWithoutWrites() {
        int usersBefore = directory.users.size();
        ServiceException empty = catchThrowableOfType(() -> importService.precheck(orgId, List.of()),
                ServiceException.class);
        assertThat(empty).isNotNull();
        assertThat(empty.getCode()).isEqualTo(400);

        List<StaffImportService.Row> tooMany = new ArrayList<>();
        for (int i = 1; i <= 501; i++) {
            tooMany.add(row(i, "员工" + i, "139" + String.format("%08d", i), ROLE, "业务一组"));
        }
        ServiceException over = catchThrowableOfType(() -> importService.submit(orgId, tooMany, 99L),
                ServiceException.class);
        assertThat(over).isNotNull();
        assertThat(over.getCode()).isEqualTo(400);
        ServiceException overPrecheck = catchThrowableOfType(() -> importService.precheck(orgId, tooMany),
                ServiceException.class);
        assertThat(overPrecheck).isNotNull();
        assertThat(overPrecheck.getCode()).isEqualTo(400);
        ServiceException emptySubmit = catchThrowableOfType(() -> importService.submit(orgId, List.of(), 99L),
                ServiceException.class);
        assertThat(emptySubmit).isNotNull();
        assertThat(emptySubmit.getCode()).isEqualTo(400);
        assertThat(directory.users).hasSize(usersBefore);
        assertThat(postCount()).isZero();
    }

    @Test
    void duplicateRowsInBatchFailSecondOccurrence() {
        List<StaffImportService.Row> rows = List.of(
                row(1, "张三", "13800000071", ROLE, "业务一组"),
                row(2, "张三", "13800000071", ROLE, "业务一组"));

        var precheck = importService.precheck(orgId, rows);
        assertThat(precheck.created()).isEqualTo(1);
        assertThat(precheck.failed()).isEqualTo(1);
        assertThat(precheck.rows().get(1).message()).contains("批内重复").contains("1");

        var submit = importService.submit(orgId, rows, 99L);
        assertThat(submit.created()).isEqualTo(1);
        assertThat(submit.failed()).isEqualTo(1);
        assertThat(postCount()).isEqualTo(1);
    }

    @Test
    void submitTeamConflictRowDoesNotAffectOtherRows() {
        long user = directory.createUser("李四", "13800000081", orgId);
        staffPostService.assignPostInOrg(user, orgId, ROLE, teamA, 99L);

        var result = importService.submit(orgId, List.of(
                row(1, "张三", "13800000082", ROLE, "业务一组"),
                row(2, "李四", "13800000081", ROLE, "业务二组")), 99L);

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.rows().get(1).code()).isEqualTo(POST_TEAM_CONFLICT.getCode());
        assertThat(staffPostService.listActivePosts(user)).singleElement()
                .satisfies(p -> assertThat(p.getTeamId()).isEqualTo(teamA));
    }
}
