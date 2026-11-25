package cn.iocoder.yudao.module.platform.announcement;

import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.module.platform.announcement.controller.admin.AnnouncementAdminController;
import cn.iocoder.yudao.module.platform.announcement.controller.admin.AnnouncementAppController;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.authz.RequestCurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import cn.iocoder.yudao.module.platform.org.SystemDirectory;
import cn.iocoder.yudao.module.platform.org.dal.StaffPostDO;
import cn.iocoder.yudao.module.platform.test.PlatformPgIntegrationTest;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static cn.iocoder.yudao.module.platform.announcement.AnnouncementModel.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real PostgreSQL/Flyway, real service guards, and real current-post header resolution. */
@TestPropertySource(properties = {
        "platform.test.extra-flyway-locations=,classpath:db/migration/franchise,classpath:db/migration/platform-wallet,classpath:db/migration/franchise-asset",
        "zhongshu.org-point.headquarters-tenant-id=1"
})
@Import({AnnouncementIntegrationTest.Config.class, AnnouncementAccess.class, AnnouncementService.class})
class AnnouncementIntegrationTest extends PlatformPgIntegrationTest {
    @TestConfiguration
    static class Config {
        @Bean StaffPostService staffPostService() { return mock(StaffPostService.class); }
        @Bean SystemDirectory directory() { return mock(SystemDirectory.class); }
        @Bean CurrentPost currentPost(StaffPostService service) { return new RequestCurrentPost(service); }
    }

    private static final AtomicLong IDS = new AtomicLong(8_741_000_000_000L);
    @Resource private JdbcTemplate jdbc;
    @Resource private AnnouncementService service;
    @Resource private StaffPostService staff;
    @Resource private SystemDirectory directory;
    private final Map<Long, PostSnapshot> posts = new HashMap<>();
    private long tenant, hq, storeA, storeB, brandA, brandB;
    private PostSnapshot administrator, finance, salesA, salesB;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        jdbc.execute("ALTER TABLE system_role ADD COLUMN IF NOT EXISTS deleted int2 DEFAULT 0");
        jdbc.execute("CREATE TABLE IF NOT EXISTS system_users(id bigint PRIMARY KEY,tenant_id bigint,nickname text,mobile text,username text,password text,status int DEFAULT 0,deleted int2 DEFAULT 0,update_time timestamptz)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS system_user_role(id bigserial PRIMARY KEY,user_id bigint,role_id bigint,tenant_id bigint,deleted int2 DEFAULT 0)");
        reset(staff, directory);
        posts.clear();
        when(staff.findActivePost(anyLong(), anyLong())).thenAnswer(invocation -> {
            PostSnapshot post = posts.get(invocation.getArgument(1, Long.class));
            return post != null && post.userId() == invocation.getArgument(0, Long.class)
                    ? Optional.of(post) : Optional.empty();
        });
        when(directory.roleHasPermission(anyString(), anyString())).thenAnswer(invocation -> {
            String role = invocation.getArgument(0), permission = invocation.getArgument(1);
            return (AnnouncementAccess.QUERY.equals(permission) && Set.of("hq-admin", "finance", "finance-lead").contains(role))
                    || (AnnouncementAccess.MANAGE.equals(permission) && "hq-admin".equals(role));
        });
        tenant = id();
        hq = org(tenant, "headquarters");
        storeA = org(tenant, "store");
        storeB = org(tenant, "store");
        administrator = createPost(tenant, hq, "headquarters", "hq-admin");
        finance = createPost(tenant, hq, "headquarters", "finance");
        salesA = createPost(tenant, storeA, "store", "sales");
        salesB = createPost(tenant, storeB, "store", "sales");
        brandA = storeBrand(storeA, salesA.userId(), "公告品牌甲" + tenant);
        brandB = storeBrand(storeB, salesB.userId(), "公告品牌乙" + tenant);
        mvc = MockMvcBuilders.standaloneSetup(new AnnouncementAdminController(service), new AnnouncementAppController(service))
                .setControllerAdvice(new GlobalExceptionHandler("announcement-test", mock(ApiErrorLogCommonApi.class)))
                .build();
        select(administrator);
    }

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void brandVisibilityAppliesToListsAndDirectDetailsAndHeadquartersFinanceIsReadOnly() {
        Detail a = publish(save("甲品牌", List.of(Long.toString(brandA))));
        Detail b = publish(save("乙品牌", List.of(Long.toString(brandB))));
        Detail all = publish(save("全品牌", List.of()));
        select(salesA);
        assertThat(service.appList("", 1, 20).list()).extracting(Summary::id).containsExactlyInAnyOrder(a.id(), all.id());
        assertThat(service.appDetail(a.id()).content()).isEqualTo("公告正文\n第二行");
        denied(404, () -> service.appDetail(b.id()));
        denied(403, service::options);
        select(salesB);
        assertThat(service.appList("", 1, 20).list()).extracting(Summary::id).containsExactlyInAnyOrder(b.id(), all.id());
        select(finance);
        assertThat(service.appList("", 1, 20).list()).hasSize(3);
        assertThat(service.options().canWrite()).isFalse();
        assertThat(service.adminDetail(a.id()).id()).isEqualTo(a.id());
        denied(403, () -> service.create(save("越权", List.of())));
        denied(403, () -> service.update(a.id(), revised(a, "越权")));
        denied(403, () -> service.publish(b.id(), new Action(b.revision())));
        denied(403, () -> service.withdraw(a.id(), new Action(a.revision())));
    }

    @Test
    void storeFinanceAndOtherHeadquartersRolesDoNotInheritCrossBrandOrPublishingAccess() {
        Detail a = publish(save("甲品牌", List.of(Long.toString(brandA))));
        Detail b = publish(save("乙品牌", List.of(Long.toString(brandB))));
        Detail global = publish(save("全体", List.of()));
        var storeFinance = createPost(tenant, storeA, "store", "finance");
        select(storeFinance);
        assertThat(service.appList("", 1, 20).list()).extracting(Summary::id).containsExactlyInAnyOrder(a.id(), global.id());
        denied(404, () -> service.appDetail(b.id()));
        denied(403, service::options);
        var legacyAdmin = createPost(tenant, hq, "headquarters", "hq-design-admin");
        select(legacyAdmin);
        assertThat(service.appList("", 1, 20).list()).extracting(Summary::id).containsExactly(global.id());
        denied(403, () -> service.create(save("旧岗位越权", List.of())));
    }

    @Test
    void publicationWithdrawalAndRevisionChecksKeepDraftsPrivateAndAuditEveryAcceptedMutation() {
        Detail draft = service.create(save("版本控制", List.of(Long.toString(brandA))));
        select(salesA);
        assertThat(service.appList("", 1, 20).list()).isEmpty();
        denied(404, () -> service.appDetail(draft.id()));
        select(administrator);
        Detail published = service.publish(draft.id(), new Action(draft.revision()));
        assertThat(Instant.parse(published.publishedAt())).isNotNull();
        denied(409, () -> service.update(published.id(), revised(published, "不能直接编辑")));
        denied(409, () -> service.withdraw(published.id(), new Action(draft.revision())));
        Detail withdrawn = service.withdraw(published.id(), new Action(published.revision()));
        select(salesA);
        denied(404, () -> service.appDetail(published.id()));
        select(administrator);
        Detail changed = service.update(withdrawn.id(), revised(withdrawn, "更新后的标题"));
        assertThat(changed.status()).isEqualTo("DRAFT");
        denied(409, () -> service.update(withdrawn.id(), revised(withdrawn, "旧页面覆盖")));
        Detail republished = service.publish(changed.id(), new Action(changed.revision()));
        select(salesA);
        assertThat(service.appDetail(draft.id()).title()).isEqualTo("更新后的标题");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_announcement_audit WHERE tenant_id=? AND announcement_id=?", Long.class, tenant, draft.id())).isEqualTo(5L);
        assertThat(republished.revision()).isEqualTo(4L);
    }

    @Test
    void tenantBoundariesCoverBrandsDetailsWritesAndPostSnapshots() {
        Detail announcement = publish(save("租户隔离", List.of()));
        long otherTenant = id();
        long foreignBrand = jdbc.queryForObject("INSERT INTO pf_map_brand(tenant_id,name) VALUES(?,?) RETURNING id", Long.class, otherTenant, "其他租户品牌");
        denied(400, () -> service.create(save("跨租户品牌", List.of(Long.toString(foreignBrand)))));
        var otherAdmin = createPost(otherTenant, org(otherTenant, "headquarters"), "headquarters", "hq-admin");
        select(otherAdmin, otherTenant);
        assertThat(service.adminList("", "", "", 1, 20).list()).isEmpty();
        denied(404, () -> service.adminDetail(announcement.id()));
        denied(404, () -> service.update(announcement.id(), revised(announcement, "跨租户改写")));
        // Even a forged snapshot with a matching login cannot escape the org's actual tenant.
        login(administrator.userId(), otherTenant);
        requestPost(administrator.postId());
        denied(403, () -> service.appList("", 1, 20));
        TenantContextHolder.setTenantId(tenant);
        denied(401, () -> service.appList("", 1, 20));
    }

    @Test
    void revokedDisabledAndPermissionRemovedIdentitiesFailClosed() {
        Detail item = publish(save("访问控制", List.of()));
        select(salesA);
        jdbc.update("UPDATE pf_staff_post SET status='REVOKED',revoked_at=now() WHERE id=?", salesA.postId());
        denied(403, () -> service.appDetail(item.id()));
        select(salesB);
        jdbc.update("UPDATE system_dept SET status=1 WHERE id=?", salesB.orgId());
        denied(403, () -> service.appDetail(item.id()));
        select(administrator);
        when(directory.roleHasPermission("hq-admin", AnnouncementAccess.MANAGE)).thenReturn(false);
        assertThat(service.options().canWrite()).isFalse();
        denied(403, () -> service.withdraw(item.id(), new Action(item.revision())));
        jdbc.update("UPDATE system_users SET status=1 WHERE id=?", administrator.userId());
        denied(403, service::options);
    }

    @Test
    void noBrandOrAmbiguousBrandAssignmentShowsOnlyGlobalAnnouncements() {
        Detail targeted = publish(save("定向", List.of(Long.toString(brandA))));
        Detail global = publish(save("通用", List.of()));
        long noBrandOrg = org(tenant, "store");
        var noBrand = createPost(tenant, noBrandOrg, "store", "sales");
        select(noBrand);
        assertThat(service.appList("", 1, 20).list()).extracting(Summary::id).containsExactly(global.id());
        denied(404, () -> service.appDetail(targeted.id()));
        // The account projection can contain more than one historical brand for an org.
        jdbc.update("UPDATE pf_store_location SET org_id=? WHERE org_id=? AND tenant_id=?", storeA, storeB, tenant);
        select(salesA);
        assertThat(service.appList("", 1, 20).list()).extracting(Summary::id).containsExactly(global.id());
    }

    @Test
    void validationAndLiteralSearchDoNotAcceptMalformedScopesOrWildcardExpansion() {
        denied(400, () -> service.create(new Save(" ", "", "正文", "ALL", List.of(), false, null)));
        denied(400, () -> service.create(new Save("标题", "", "正文", "BRANDS", List.of(), false, null)));
        denied(400, () -> service.create(new Save("标题", "", "正文", "ALL", List.of(Long.toString(brandA)), false, null)));
        denied(400, () -> service.create(save("标题", List.of("9223372036854775808"))));
        denied(400, () -> service.create(new Save("标题", "", "x".repeat(20001), "ALL", List.of(), false, null)));
        denied(400, () -> service.adminList("", "BOGUS", "", 1, 20));
        denied(400, () -> service.appList("", 0, 20));
        denied(400, () -> service.appList("", 1, 101));
        Detail literal = publish(save("完成度100%_说明", List.of()));
        publish(save("普通公告", List.of()));
        select(salesA);
        assertThat(service.appList("%_", 1, 20).list()).extracting(Summary::id).containsExactly(literal.id());
        assertThat(service.appList("' OR 1=1 --", 1, 20).list()).isEmpty();
        assertThat(service.appList("", 1, 1).total()).isEqualTo(2L);
        assertThat(service.appList("", 2, 1).list()).hasSize(1);
    }

    @Test
    void httpUsesCurrentPostAndReturnsStringIdsUtcTimesAndReadOnlyFinanceContract() throws Exception {
        Detail published = publish(save("HTTP公告", List.of(Long.toString(brandA))));
        mvc.perform(get("/platform/announcements/admin/options").header(CurrentPost.HEADER, administrator.postId()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.canWrite").value(true))
                .andExpect(jsonPath("$.data.brands[0].id").isString());
        select(salesA);
        mvc.perform(get("/platform/announcements/app").header(CurrentPost.HEADER, salesA.postId()))
                .andExpect(jsonPath("$.data.list[0].id").value(published.id()))
                .andExpect(jsonPath("$.data.list[0].content").doesNotExist())
                .andExpect(jsonPath("$.data.list[0].publishedAt").value(published.publishedAt()));
        mvc.perform(get("/platform/announcements/app/{id}", published.id()).header(CurrentPost.HEADER, salesA.postId()))
                .andExpect(jsonPath("$.data.brandIds[0]").value(Long.toString(brandA)))
                .andExpect(jsonPath("$.data.content").value("公告正文\n第二行"));
        select(finance);
        mvc.perform(get("/platform/announcements/admin/options").header(CurrentPost.HEADER, finance.postId()))
                .andExpect(jsonPath("$.data.canWrite").value(false));
        mvc.perform(post("/platform/announcements/admin/{id}/withdraw", published.id())
                        .header(CurrentPost.HEADER, finance.postId()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":" + published.revision() + "}"))
                .andExpect(jsonPath("$.code").value(403));
        SecurityContextHolder.clearContext();
        mvc.perform(get("/platform/announcements/app")).andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void repeatedDraftSaveViaHttpReturnsOriginalWithoutCreatingAnotherDraftOrAudit() throws Exception {
        String key = UUID.randomUUID().toString();
        String input = """
                {"title":"待确认的公告草稿","content":"正文","visibility":"ALL","brandIds":[],"createRequestId":"%s"}
                """.formatted(key);
        String createdJson = mvc.perform(post("/platform/announcements/admin")
                        .header(CurrentPost.HEADER, administrator.postId()).contentType(MediaType.APPLICATION_JSON)
                        .content(input))
                .andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString();
        String draftId = com.jayway.jsonpath.JsonPath.read(createdJson, "$.data.id");
        mvc.perform(post("/platform/announcements/admin")
                        .header(CurrentPost.HEADER, administrator.postId()).contentType(MediaType.APPLICATION_JSON)
                        .content(input))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.id").value(draftId));
        mvc.perform(post("/platform/announcements/admin")
                        .header(CurrentPost.HEADER, administrator.postId()).contentType(MediaType.APPLICATION_JSON)
                        .content(input.replace("正文", "修改的内容")))
                .andExpect(jsonPath("$.code").value(409));
        assertThat(service.adminList("", "DRAFT", "", 1, 20).list()).extracting(Summary::id).containsExactly(draftId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pf_announcement_audit WHERE announcement_id=?", Long.class,
                draftId)).isEqualTo(1L);
        Detail current = service.publish(draftId, new Action(0L));
        mvc.perform(post("/platform/announcements/admin")
                        .header(CurrentPost.HEADER, administrator.postId()).contentType(MediaType.APPLICATION_JSON)
                        .content(input))
                .andExpect(jsonPath("$.data.id").value(draftId)).andExpect(jsonPath("$.data.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.revision").value((int) current.revision()));
        select(finance);
        denied(403, () -> service.create(new Save("待确认的公告草稿", "", "正文", "ALL", List.of(), false, null, key)));
        select(createPost(tenant, hq, "headquarters", "hq-admin"));
        var independent = service.create(new Save("待确认的公告草稿", "", "正文", "ALL", List.of(), false, null, key));
        assertThat(independent.id()).isNotEqualTo(draftId);
        long otherTenant = id();
        select(createPost(otherTenant, org(otherTenant, "headquarters"), "headquarters", "hq-admin"), otherTenant);
        assertThat(service.create(new Save("待确认的公告草稿", "", "正文", "ALL", List.of(), false, null, key)).id())
                .isNotIn(draftId, independent.id());
    }

    @Test
    void concurrentCreateRetriesUseOneCommittedDraft() throws Exception {
        var input = new Save("并行保存", "", "正文", "ALL", List.of(), false, null, UUID.randomUUID().toString());
        var barrier = new CyclicBarrier(2);
        Callable<String> create = () -> {
            select(administrator);
            try {
                barrier.await(10, TimeUnit.SECONDS);
                return service.create(input).id();
            } finally {
                clear();
            }
        };
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(create);
            var retry = pool.submit(create);
            String id = first.get(20, TimeUnit.SECONDS);
            assertThat(retry.get(20, TimeUnit.SECONDS)).isEqualTo(id);
            assertThat(service.adminList("", "DRAFT", "", 1, 20).list()).extracting(Summary::id).containsExactly(id);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void superAdminWithoutPostIsCompatibleButInvalidOrSelectedStorePostNeverFallsBack() throws Exception {
        long user = user(1), superRole = role(1, "super_admin");
        jdbc.update("INSERT INTO system_user_role(id,user_id,role_id,tenant_id,deleted) VALUES(?,?,?,?,0)", id(), user, superRole, 1);
        login(user, 1);
        requestPost(null);
        assertThat(service.options().canWrite()).isTrue();
        mvc.perform(get("/platform/announcements/admin/options"))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.canWrite").value(true));
        mvc.perform(get("/platform/announcements/admin/options").header(CurrentPost.HEADER, "999999999"))
                .andExpect(jsonPath("$.code").value(1_103_000_002));
        var storePost = createPostFor(user, 1, org(1, "store"), "store", "finance");
        mvc.perform(get("/platform/announcements/admin/options").header(CurrentPost.HEADER, storePost.postId()))
                .andExpect(jsonPath("$.code").value(403));
        login(user, tenant);
        requestPost(null);
        denied(403, service::options);
    }

    private Detail publish(Save save) {
        Detail draft = service.create(save);
        return service.publish(draft.id(), new Action(draft.revision()));
    }
    private Save save(String title, List<String> brands) {
        return new Save(title, "公告摘要", "公告正文\n第二行", brands.isEmpty() ? "ALL" : "BRANDS", brands, false, null);
    }
    private Save revised(Detail current, String title) {
        return new Save(title, current.summary(), current.content(), current.visibility(), current.brandIds(), current.pinned(), current.revision());
    }
    private long org(long targetTenant, String type) {
        long id = id();
        jdbc.update("INSERT INTO system_dept(id,name,tenant_id,status,deleted) VALUES(?,?,?,0,0)", id, "公告测试组织" + id, targetTenant);
        jdbc.update("INSERT INTO pf_org_unit(dept_id,kind,org_id,org_type) VALUES(?,'ORG',?,?)", id, id, type);
        return id;
    }
    private long user(long targetTenant) {
        long id = id();
        jdbc.update("INSERT INTO system_users(id,tenant_id,username,nickname,mobile,password,status,deleted) VALUES(?,?,?,?,?,'test-disabled-password',0,0)", id, targetTenant, "announcement" + id, "测试用户", "");
        return id;
    }
    private long role(long targetTenant, String code) {
        var existing = jdbc.queryForList("SELECT id FROM system_role WHERE tenant_id=? AND code=? AND status=0", Long.class, targetTenant, code);
        if (!existing.isEmpty()) return existing.get(0);
        long id = id();
        jdbc.update("INSERT INTO system_role(id,tenant_id,name,code,status,deleted) VALUES(?,?,?,?,0,0)", id, targetTenant, "公告测试岗位", code);
        return id;
    }
    private PostSnapshot createPost(long targetTenant, long org, String type, String role) {
        return createPostFor(user(targetTenant), targetTenant, org, type, role);
    }
    private PostSnapshot createPostFor(long user, long targetTenant, long org, String type, String role) {
        role(targetTenant, role);
        long id = id();
        jdbc.update("INSERT INTO pf_staff_post(id,user_id,org_id,role_code) VALUES(?,?,?,?)", id, user, org, role);
        var snapshot = new PostSnapshot(id, user, org, type, role, null, false, Set.of());
        posts.put(id, snapshot);
        StaffPostDO stored = new StaffPostDO();stored.setId(id);
        when(staff.listActivePosts(user)).thenReturn(List.of(stored));
        return snapshot;
    }
    private long storeBrand(long org, long user, String name) {
        jdbc.update("""
                INSERT INTO pf_franchise_account(account_id,tenant_id,org_id,user_id,login_name,org_name,owner_name,
                    account_type,brand,province,city,area,area_pinyin,owner_pinyin,created_by)
                VALUES(?,?,?,?,?,?,?,'BOSS',?,'湖北省','武汉市','黄陂区','huangpi','test',?)
                """, UUID.randomUUID().toString(), tenant, org, user, "announcement-store-" + org,
                "公告测试门店" + org, "测试负责人", name, administrator.userId());
        return jdbc.queryForObject("SELECT brand_id FROM pf_store_location WHERE tenant_id=? AND org_id=?", Long.class, tenant, org);
    }
    private void select(PostSnapshot post) { select(post, tenant); }
    private void select(PostSnapshot post, long targetTenant) { login(post.userId(), targetTenant); requestPost(post.postId()); }
    private void login(long user, long targetTenant) {
        TenantContextHolder.setTenantId(targetTenant);
        var login = new LoginUser().setId(user).setTenantId(targetTenant).setUserType(2);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(login, null, List.of()));
    }
    private void requestPost(Long id) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (id != null) request.addHeader(CurrentPost.HEADER, id);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
    private static long id() { return IDS.incrementAndGet(); }
    private static void denied(int code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(ServiceException.class)
                .satisfies(error -> assertThat(((ServiceException) error).getCode()).isEqualTo(code));
    }
}
