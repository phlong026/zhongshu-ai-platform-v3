package cn.iocoder.yudao.module.franchise.content.controller.admin;

import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.module.franchise.content.content.ContentDraftService;
import cn.iocoder.yudao.module.franchise.content.dal.FrContentCourseProgressDO;
import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.authz.PostPermissionEvaluator;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;
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

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 学习进度是有效任职员工的本人功能，不借此扩大内容业务权限。 */
class ContentCourseProgressSecurityTest {

    private static final long ORG = 33L;
    private static final long USER = 22L;

    private final CurrentPost currentPost = mock(CurrentPost.class);
    private final SystemDirectory directory = mock(SystemDirectory.class);
    private final ContentDraftService drafts = mock(ContentDraftService.class);

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(Security.class)
            .withBean(CurrentPost.class, () -> currentPost)
            .withBean(SystemDirectory.class, () -> directory)
            .withBean("post", PostPermissionEvaluator.class,
                    () -> new PostPermissionEvaluator(currentPost, directory))
            .withBean(ContentDraftService.class, () -> drafts)
            .withBean(ContentDraftController.class);

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("employee", null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class Security {
    }

    @Test
    void everyActiveEmployeeRoleCanReadOnlyOwnOrganizationProgress() {
        List<String> roles = List.of("boss", "sales", "engineer", "finance", "project-manager",
                "finance-lead", "owner", "sales-lead", "hq-designer", "hq-design-lead",
                "hq-design-admin", "hq-operations", "hq-operations-lead");
        context.run(ctx -> {
            MockMvc mvc = mvc(ctx);
            for (String role : roles) {
                PostSnapshot post = new PostSnapshot(11L, USER, ORG, "store", role, null, false, Set.of());
                when(currentPost.find()).thenReturn(Optional.of(post));
                when(currentPost.require()).thenReturn(post);

                mvc.perform(get("/franchise/content/course-progress"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.code").value(0));
            }
        });

        verify(drafts, times(13)).courseProgress(ORG, USER, null);
    }

    @Test
    void financeListOmitsOtherRoleHistoryWhileKeepingOwnTraining() {
        PostSnapshot finance = new PostSnapshot(11L, USER, ORG, "store", "finance", null, false, Set.of());
        when(currentPost.require()).thenReturn(finance);
        when(drafts.courseProgress(ORG, USER, null)).thenReturn(List.of(
                new FrContentCourseProgressDO().setCourseId("ai-video").setChapterIndex(0),
                new FrContentCourseProgressDO().setCourseId("finance").setChapterIndex(1)));
        context.run(ctx -> mvc(ctx).perform(get("/franchise/content/course-progress"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].courseId").value("finance")));
        verify(drafts).courseProgress(ORG, USER, null);
    }

    @Test
    void engineerCannotRequestSalesTrainingEvenByDirectApiLink() {
        PostSnapshot engineer = new PostSnapshot(11L, USER, ORG, "store", "engineer", null, false, Set.of());
        when(currentPost.require()).thenReturn(engineer);
        context.run(ctx -> mvc(ctx).perform(get("/franchise/content/course-progress").param("courseId", "ai-video"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(403)));
        verify(drafts, never()).courseProgress(anyLong(), anyLong(), any());
    }

    @Test
    void learningReadAccessDoesNotGrantCompletionOrGeneralContentQuery() {
        PostSnapshot engineer = new PostSnapshot(11L, USER, ORG, "store", "engineer", null, false, Set.of());
        when(currentPost.find()).thenReturn(Optional.of(engineer));
        when(currentPost.require()).thenReturn(engineer);

        context.run(ctx -> {
            MockMvc mvc = mvc(ctx);
            mvc.perform(post("/franchise/content/course-progress")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"courseId\":\"course-1\",\"chapterIndex\":2}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(403));
            mvc.perform(get("/franchise/content/drafts"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(403));
        });

        verify(drafts, never()).completeChapter(anyLong(), anyLong(), any(), anyInt());
        verify(drafts, never()).listWorks(anyLong(), anyLong());
    }

    @Test
    void learningAccessStillRequiresAnActiveCurrentPost() {
        when(currentPost.find()).thenReturn(Optional.empty());
        when(currentPost.require()).thenThrow(new ServiceException(1_103_000_000, "当前账号没有有效的工作身份"));

        context.run(ctx -> mvc(ctx).perform(get("/franchise/content/course-progress"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1_103_000_000)));

        verify(drafts, never()).courseProgress(anyLong(), anyLong(), any());
    }

    private MockMvc mvc(org.springframework.context.ApplicationContext ctx) {
        return MockMvcBuilders.standaloneSetup(ctx.getBean(ContentDraftController.class))
                .setControllerAdvice(new GlobalExceptionHandler("test", mock(ApiErrorLogCommonApi.class)))
                .build();
    }
}
