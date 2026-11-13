package cn.iocoder.yudao.module.platform.authz.controller.admin;

import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.authz.PostPermissionEvaluator;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
import cn.iocoder.yudao.module.platform.org.StaffImportService;
import cn.iocoder.yudao.module.platform.org.SystemDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P1 批2：员工导入 HTTP 契约（B-only false 装配 + 生产异常链）。
 * 权限=platform:org:manage；组织范围=当前岗位组织；单批上限由 Service 权威校验。
 */
class StaffImportControllerHttpContractTest {

    private static final long ORG = 151L;
    private static final PostSnapshot BOSS =
            new PostSnapshot(21L, 9L, ORG, "store", "boss", null, false, Set.of());
    private static final String ONE_ROW = "{\"orgId\":" + ORG + ",\"rows\":["
            + "{\"line\":1,\"name\":\"张三\",\"mobile\":\"13800000000\","
            + "\"roleCode\":\"sales\",\"teamName\":\"业务一组\"}]}";

    private final CurrentPost currentPost = mock(CurrentPost.class);
    private final SystemDirectory directory = mock(SystemDirectory.class);
    private final StaffImportService service = mock(StaffImportService.class);

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withPropertyValues("platform.legacy-design-bridge.enabled=false")
            .withUserConfiguration(Security.class)
            .withBean(CurrentPost.class, () -> currentPost)
            .withBean(SystemDirectory.class, () -> directory)
            .withBean("post", PostPermissionEvaluator.class, () -> new PostPermissionEvaluator(currentPost, directory))
            .withBean(StaffImportService.class, () -> service)
            .withBean(StaffImportController.class);

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", null, List.of()));
        when(currentPost.find()).thenReturn(Optional.of(BOSS));
        when(currentPost.require()).thenReturn(BOSS);
        when(directory.roleHasPermission("boss", "platform:org:manage")).thenReturn(true);
        when(service.precheck(eq(ORG), any())).thenReturn(result("OK"));
        when(service.submit(eq(ORG), any(), anyLong())).thenReturn(result("CREATED"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class Security {
    }

    private MockMvc mvc(org.springframework.context.ApplicationContext ctx) {
        return MockMvcBuilders.standaloneSetup(ctx.getBean(StaffImportController.class))
                .setControllerAdvice(new GlobalExceptionHandler("test", mock(ApiErrorLogCommonApi.class)))
                .build();
    }

    private static StaffImportService.ImportResult result(String status) {
        return new StaffImportService.ImportResult(ORG, 1, 1, 0, 0,
                List.of(new StaffImportService.RowResult(1, "张三", "13800000000", "sales", "业务一组",
                        status, null, "", 77L, 88L, null)));
    }

    @Test
    void precheckReturnsRowResults() {
        context.run(ctx -> mvc(ctx).perform(post("/platform/org/staff-import/precheck")
                        .contentType(MediaType.APPLICATION_JSON).content(ONE_ROW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.created").value(1))
                .andExpect(jsonPath("$.data.rows[0].line").value(1))
                .andExpect(jsonPath("$.data.rows[0].status").value("OK")));
        verify(service).precheck(eq(ORG), any());
    }

    @Test
    void submitReturnsRowResults() {
        context.run(ctx -> mvc(ctx).perform(post("/platform/org/staff-import")
                        .contentType(MediaType.APPLICATION_JSON).content(ONE_ROW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[0].status").value("CREATED"))
                .andExpect(jsonPath("$.data.rows[0].postId").value(77)));
        verify(service).submit(eq(ORG), any(), eq(9L));
    }

    @Test
    void missingManagePermissionIsDeniedWithProductionEnvelope() {
        when(directory.roleHasPermission("boss", "platform:org:manage")).thenReturn(false);
        context.run(ctx -> mvc(ctx).perform(post("/platform/org/staff-import/precheck")
                        .contentType(MediaType.APPLICATION_JSON).content(ONE_ROW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403)));
    }

    @Test
    void crossOrganizationScopeIsForbiddenWithProductionEnvelope() {
        PostSnapshot otherOrg = new PostSnapshot(21L, 9L, 999L, "store", "boss", null, false, Set.of());
        when(currentPost.find()).thenReturn(Optional.of(otherOrg));
        when(currentPost.require()).thenReturn(otherOrg);
        context.run(ctx -> mvc(ctx).perform(post("/platform/org/staff-import")
                        .contentType(MediaType.APPLICATION_JSON).content(ONE_ROW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1_103_000_004)));
    }

    @Test
    void overLimitFromServiceRendersBadRequestEnvelope() {
        when(service.submit(eq(ORG), any(), anyLong()))
                .thenThrow(new ServiceException(400, "单批须为 1-500 行"));
        context.run(ctx -> mvc(ctx).perform(post("/platform/org/staff-import")
                        .contentType(MediaType.APPLICATION_JSON).content(ONE_ROW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400)));
    }

    @Test
    void nullRowIsRejectedWithSafeBadRequestEnvelope() {
        context.run(ctx -> mvc(ctx).perform(post("/platform/org/staff-import/precheck")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orgId\":" + ORG + ",\"rows\":[null]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400)));
    }

    @Test
    void importEndpointsArePostOnly() {
        context.run(ctx -> mvc(ctx).perform(get("/platform/org/staff-import/precheck"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(405)));
    }
}
