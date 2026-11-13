package cn.iocoder.yudao.module.platform.authz.controller.admin;

import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.module.platform.authz.FakeSystemDirectoryForOnboard;
import cn.iocoder.yudao.module.platform.authz.RequestCurrentPost;
import cn.iocoder.yudao.module.platform.authz.enums.PlatformAuthzErrorCodes;
import cn.iocoder.yudao.module.platform.command.CommandLogImpl;
import cn.iocoder.yudao.module.platform.org.OrgService;
import cn.iocoder.yudao.module.platform.org.OrgServiceImpl;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import cn.iocoder.yudao.module.platform.org.StaffPostServiceImpl;
import cn.iocoder.yudao.module.platform.test.PlatformPgIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 身份配置 HTTP 与 PostgreSQL 的端到端契约。
 *
 * <p>组织、部门、任职、当前岗位校验都使用真实 service 和 Testcontainers PostgreSQL。
 * {@link WorkflowDirectory} 只代替 yudao 的账号/角色/部门目录，不代替任职表或组织范围校验。
 * standalone MockMvc 不组装 Spring Method Security，因此本类验证真实 CurrentPost/组织范围，
 * 不将 {@code @PreAuthorize("@post.has(...)")} 声明为已通过。
 */
@Import({OrgServiceImpl.class, StaffPostServiceImpl.class, CommandLogImpl.class, TeamCommandService.class,
        StaffIdentityWorkflowIntegrationTest.Config.class})
class StaffIdentityWorkflowIntegrationTest extends PlatformPgIntegrationTest {

    private static final String ORG_TYPE = "wf-store";
    private static final String BOSS = "wf-boss";
    private static final String SALES = "wf-sales";
    private static final String FINANCE = "wf-finance";
    private static final AtomicLong SEQUENCES = new AtomicLong(System.nanoTime() % 10_000_000L);

    @TestConfiguration
    static class Config {
        @Bean
        WorkflowDirectory workflowDirectory() {
            return new WorkflowDirectory();
        }
    }

    static final class WorkflowDirectory extends FakeSystemDirectoryForOnboard {
        private final Map<String, Set<String>> permissions = new HashMap<>();

        @Override
        public boolean roleHasPermission(String roleCode, String permission) {
            return permissions.getOrDefault(roleCode, Set.of()).contains(permission);
        }
    }

    @Resource
    private OrgService orgService;
    @Resource
    private StaffPostService staffPostService;
    @Resource
    private TeamCommandService teamCommandService;
    @Resource
    private WorkflowDirectory directory;
    @Resource
    private JdbcTemplate jdbc;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        jdbc.update("INSERT INTO pf_org_type(code, domain, name) VALUES (?,?,?) ON CONFLICT DO NOTHING",
                ORG_TYPE, "workflow-test", "流程测试门店");
        jdbc.update("INSERT INTO pf_post_template(role_code, domain, org_types, line, lead, inherits_role_code) VALUES "
                        + "(?,?,?,?,?,NULL),(?,?,?,?,?,NULL),(?,?,?,?,?,NULL) ON CONFLICT DO NOTHING",
                BOSS, "workflow-test", ORG_TYPE, "management", true,
                SALES, "workflow-test", ORG_TYPE, "sales", false,
                FINANCE, "workflow-test", ORG_TYPE, "finance", false);
        directory.roleIdsByCode.put(BOSS, 9_001L);
        directory.roleIdsByCode.put(SALES, 9_002L);
        directory.roleIdsByCode.put(FINANCE, 9_003L);
        directory.permissions.put(BOSS, Set.of("platform:org:query", "platform:org:manage"));
        directory.permissions.put(SALES, Set.of("platform:org:query"));
        directory.permissions.put(FINANCE, Set.of("platform:org:manage"));

        RequestCurrentPost currentPost = new RequestCurrentPost(staffPostService);
        StaffOrgAdminController staffController = new StaffOrgAdminController();
        ReflectionTestUtils.setField(staffController, "currentPost", currentPost);
        ReflectionTestUtils.setField(staffController, "staffPostService", staffPostService);
        ReflectionTestUtils.setField(staffController, "orgService", orgService);
        ReflectionTestUtils.setField(staffController, "teamCommandService", teamCommandService);
        ReflectionTestUtils.setField(staffController, "systemDirectory", directory);

        MyPostController postController = new MyPostController();
        ReflectionTestUtils.setField(postController, "staffPostService", staffPostService);
        ReflectionTestUtils.setField(postController, "currentPost", currentPost);
        ReflectionTestUtils.setField(postController, "systemDirectory", directory);
        mvc = MockMvcBuilders.standaloneSetup(staffController, postController).build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createsDepartmentReusesMobileAccountAndPersistsAdditionalPost() throws Exception {
        Fixture fixture = fixture();
        authenticate(fixture.bossUserId());

        long teamId = number(createTeam(fixture.orgA(), fixture.bossPostA(), "业务部"), "$.data.deptId");
        MvcResult inA = createStaff(fixture.orgA(), fixture.bossPostA(), fixture.mobile(), SALES, teamId);
        MvcResult inB = createStaff(fixture.orgB(), fixture.bossPostB(), fixture.mobile(), FINANCE, null);
        long userId = number(inA, "$.data.userId");
        long postA = number(inA, "$.data.postId");
        long postB = number(inB, "$.data.postId");

        MvcResult additional = mvc.perform(post("/platform/org/posts")
                        .header(RequestCurrentPost.HEADER, fixture.bossPostA())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orgId\":" + fixture.orgA() + ",\"userId\":" + userId
                                + ",\"roleCode\":\"" + FINANCE + "\",\"teamId\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.roleCode").value(FINANCE))
                .andReturn();
        long additionalPost = number(additional, "$.data.postId");

        assertThat(number(inB, "$.data.userId")).isEqualTo(userId);
        assertThat(directory.userIdByMobile(fixture.mobile())).isEqualTo(userId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_org_unit WHERE dept_id=? AND org_id=? AND kind='TEAM'",
                Integer.class, teamId, fixture.orgA())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_staff_post WHERE user_id=? AND status='ACTIVE'",
                Integer.class, userId)).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT id FROM pf_staff_post WHERE user_id=? ORDER BY id", Long.class, userId))
                .containsExactlyInAnyOrder(postA, postB, additionalPost);
    }

    @Test
    void listsBothPostsAndSwitchesCurrentContextByExactHeader() throws Exception {
        Fixture fixture = fixture();
        EmployeePosts employee = createEmployeeInBothOrganizations(fixture, false);
        authenticate(employee.userId());

        mvc.perform(get("/platform/posts/mine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[?(@.postId == " + employee.postA() + ")].orgId")
                        .value(hasItem(Math.toIntExact(fixture.orgA()))))
                .andExpect(jsonPath("$.data[?(@.postId == " + employee.postB() + ")].orgId")
                        .value(hasItem(Math.toIntExact(fixture.orgB()))));

        mvc.perform(get("/platform/posts/current-context").header(RequestCurrentPost.HEADER, employee.postA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.post.orgId").value(fixture.orgA()))
                .andExpect(jsonPath("$.data.post.roleCode").value(SALES))
                .andExpect(jsonPath("$.data.permissions", hasSize(1)))
                .andExpect(jsonPath("$.data.permissions[0]").value("platform:org:query"));

        mvc.perform(get("/platform/posts/current-context").header(RequestCurrentPost.HEADER, employee.postB()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.post.orgId").value(fixture.orgB()))
                .andExpect(jsonPath("$.data.post.roleCode").value(FINANCE))
                .andExpect(jsonPath("$.data.permissions", hasSize(1)))
                .andExpect(jsonPath("$.data.permissions[0]").value("platform:org:manage"));
    }

    @Test
    void rejectsCrossOrganizationQueryBeforeReadingStaff() throws Exception {
        Fixture fixture = fixture();
        authenticate(fixture.bossUserId());

        Throwable thrown = catchThrowable(() -> mvc.perform(get("/platform/org/staff")
                .param("orgId", String.valueOf(fixture.orgB()))
                .header(RequestCurrentPost.HEADER, fixture.bossPostA())));

        assertThat(rootCause(thrown)).isInstanceOfSatisfying(ServiceException.class,
                error -> assertThat(error.getCode()).isEqualTo(PlatformAuthzErrorCodes.ORG_SCOPE_FORBIDDEN.getCode()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_staff_post WHERE org_id=?",
                Integer.class, fixture.orgB())).isEqualTo(1);
    }

    @Test
    void revocationAndOrgSuspensionInvalidateOldPostsBeforePhoneRecovery() throws Exception {
        Fixture fixture = fixture();
        EmployeePosts employee = createEmployeeInBothOrganizations(fixture, true);
        authenticate(fixture.bossUserId());

        mvc.perform(delete("/platform/org/posts/{postId}", employee.postA())
                        .header(RequestCurrentPost.HEADER, fixture.bossPostA()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));
        assertThat(postStatus(employee.postA())).isEqualTo("REVOKED");

        mvc.perform(post("/platform/org/staff-status")
                        .header(RequestCurrentPost.HEADER, fixture.bossPostA())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":" + employee.userId() + ",\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(true));
        assertThat(postStatus(employee.additionalPostA())).isEqualTo("REVOKED");
        assertThat(postStatus(employee.postB())).isEqualTo("ACTIVE");

        authenticate(employee.userId());
        Throwable oldPost = catchThrowable(() -> mvc.perform(get("/platform/posts/current-context")
                .header(RequestCurrentPost.HEADER, employee.postA())));
        assertThat(rootCause(oldPost)).isInstanceOfSatisfying(ServiceException.class,
                error -> assertThat(error.getCode()).isEqualTo(PlatformAuthzErrorCodes.POST_NOT_USABLE.getCode()));

        authenticate(fixture.bossUserId());
        MvcResult restored = createStaff(fixture.orgA(), fixture.bossPostA(), fixture.mobile(), SALES, null);
        long restoredPost = number(restored, "$.data.postId");
        assertThat(number(restored, "$.data.userId")).isEqualTo(employee.userId());
        assertThat(restoredPost).isNotEqualTo(employee.postA());
        assertThat(postStatus(restoredPost)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForList("SELECT status FROM pf_staff_post WHERE id IN (?,?) ORDER BY id",
                String.class, employee.postA(), employee.additionalPostA())).containsOnly("REVOKED");

        authenticate(employee.userId());
        mvc.perform(get("/platform/posts/mine"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[?(@.postId == " + restoredPost + ")]", hasSize(1)))
                .andExpect(jsonPath("$.data[?(@.postId == " + employee.postB() + ")]", hasSize(1)))
                .andExpect(jsonPath("$.data[?(@.postId == " + employee.postA() + ")]", hasSize(0)));
        mvc.perform(get("/platform/posts/current-context").header(RequestCurrentPost.HEADER, employee.postB()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.post.orgId").value(fixture.orgB()))
                .andExpect(jsonPath("$.data.post.postId").value(employee.postB()));
    }

    private Fixture fixture() {
        long suffix = SEQUENCES.incrementAndGet();
        long orgA = orgService.createOrg(ORG_TYPE, "A店-" + suffix, null);
        long orgB = orgService.createOrg(ORG_TYPE, "B店-" + suffix, null);
        long bossUser = directory.createUser("老板", mobile(suffix + 40_000_000L), null);
        long bossPostA = staffPostService.assignPost(bossUser, orgA, BOSS, null, bossUser);
        long bossPostB = staffPostService.assignPost(bossUser, orgB, BOSS, null, bossUser);
        return new Fixture(orgA, orgB, bossUser, bossPostA, bossPostB, mobile(suffix));
    }

    private EmployeePosts createEmployeeInBothOrganizations(Fixture fixture, boolean additionalPost) throws Exception {
        authenticate(fixture.bossUserId());
        long teamId = number(createTeam(fixture.orgA(), fixture.bossPostA(), "业务部"), "$.data.deptId");
        MvcResult inA = createStaff(fixture.orgA(), fixture.bossPostA(), fixture.mobile(), SALES, teamId);
        MvcResult inB = createStaff(fixture.orgB(), fixture.bossPostB(), fixture.mobile(), FINANCE, null);
        long userId = number(inA, "$.data.userId");
        long extra = 0L;
        if (additionalPost) {
            MvcResult assigned = mvc.perform(post("/platform/org/posts")
                            .header(RequestCurrentPost.HEADER, fixture.bossPostA())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"orgId\":" + fixture.orgA() + ",\"userId\":" + userId
                                    + ",\"roleCode\":\"" + FINANCE + "\",\"teamId\":null}"))
                    .andExpect(status().isOk())
                    .andReturn();
            extra = number(assigned, "$.data.postId");
        }
        return new EmployeePosts(userId, number(inA, "$.data.postId"), number(inB, "$.data.postId"), extra);
    }

    private MvcResult createTeam(long orgId, long bossPostId, String name) throws Exception {
        return mvc.perform(post("/platform/org/teams")
                        .header(RequestCurrentPost.HEADER, bossPostId)
                        .header("Idempotency-Key", "it-team-" + orgId + "-" + name)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orgId\":" + orgId + ",\"name\":\"" + name + "\",\"line\":\"sales\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
    }

    private MvcResult createStaff(long orgId, long bossPostId, String mobile, String roleCode, Long teamId)
            throws Exception {
        String team = teamId == null ? "null" : teamId.toString();
        return mvc.perform(post("/platform/org/staff")
                        .header(RequestCurrentPost.HEADER, bossPostId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orgId\":" + orgId + ",\"name\":\"陈新\",\"mobile\":\"" + mobile
                                + "\",\"roleCode\":\"" + roleCode + "\",\"teamId\":" + team + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
    }

    private void authenticate(long userId) {
        LoginUser loginUser = new LoginUser();
        loginUser.setId(userId);
        loginUser.setUserType(UserTypeEnum.ADMIN.getValue());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, List.of()));
    }

    private long number(MvcResult result, String path) throws Exception {
        Number value = JsonPath.read(result.getResponse().getContentAsString(), path);
        return value.longValue();
    }

    private String postStatus(long postId) {
        return jdbc.queryForObject("SELECT status FROM pf_staff_post WHERE id=?", String.class, postId);
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String mobile(long value) {
        return "138" + String.format("%08d", Math.floorMod(value, 100_000_000L));
    }

    private record Fixture(long orgA, long orgB, long bossUserId, long bossPostA, long bossPostB, String mobile) {
    }

    private record EmployeePosts(long userId, long postA, long postB, long additionalPostA) {
    }
}
