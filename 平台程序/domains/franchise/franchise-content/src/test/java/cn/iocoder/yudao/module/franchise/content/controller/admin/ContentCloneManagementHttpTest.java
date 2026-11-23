package cn.iocoder.yudao.module.franchise.content.controller.admin;

import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.module.franchise.content.ContentPermissions;
import cn.iocoder.yudao.module.franchise.content.content.ContentCloneService;
import cn.iocoder.yudao.module.franchise.content.content.ContentCloneUploadService;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ContentCloneManagementHttpTest {
    private static final String RESOURCE = "/franchise/content/clones/201";
    private final CurrentPost currentPost = mock(CurrentPost.class);
    private final SystemDirectory directory = mock(SystemDirectory.class);
    private final ContentCloneService clones = mock(ContentCloneService.class);
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(Security.class)
            .withBean(CurrentPost.class, () -> currentPost)
            .withBean("post", PostPermissionEvaluator.class, () -> new PostPermissionEvaluator(currentPost, directory))
            .withBean(ContentCloneController.class,
                    () -> new ContentCloneController(clones, currentPost, mock(ContentCloneUploadService.class)));

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class Security {}

    @BeforeEach void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("employee", null, List.of()));
    }

    @AfterEach void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test void personaSelfPermissionCanReachAllThreeManagementRoutes() {
        var post = setPost("sales");
        when(directory.roleHasPermission("sales", ContentPermissions.PERSONA_SELF)).thenReturn(true);
        when(clones.rename(post, 201L, "新名称")).thenReturn(Map.of("title", "新名称", "canRename", true));
        when(clones.setDefault(post, 201L)).thenReturn(Map.of("isDefault", true, "canSetDefault", false));
        context.run(ctx -> {
            var mvc = MockMvcBuilders.standaloneSetup(ctx.getBean(ContentCloneController.class))
                    .setControllerAdvice(new GlobalExceptionHandler("test", mock(ApiErrorLogCommonApi.class))).build();
            mvc.perform(put(RESOURCE + "/title").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"新名称\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.title").value("新名称"))
                    .andExpect(jsonPath("$.data.canRename").value(true));
            mvc.perform(put(RESOURCE + "/default")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.isDefault").value(true));
            mvc.perform(delete(RESOURCE)).andExpect(status().isOk()).andExpect(jsonPath("$.data").value(true));
        });
        verify(clones).delete(post, 201L);
    }

    @Test void unrelatedRoleCannotReachManagementViaDirectRoutes() {
        setPost("engineer");
        context.run(ctx -> {
            var mvc = MockMvcBuilders.standaloneSetup(ctx.getBean(ContentCloneController.class))
                    .setControllerAdvice(new GlobalExceptionHandler("test", mock(ApiErrorLogCommonApi.class))).build();
            mvc.perform(put(RESOURCE + "/title").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"新名称\"}"))
                    .andExpect(jsonPath("$.code").value(403));
            mvc.perform(put(RESOURCE + "/default")).andExpect(jsonPath("$.code").value(403));
            mvc.perform(delete(RESOURCE)).andExpect(jsonPath("$.code").value(403));
        });
        verifyNoInteractions(clones);
    }

    private PostSnapshot setPost(String role) {
        var post = new PostSnapshot(11L, 22L, 33L, "store", role, null, false, Set.of());
        when(currentPost.find()).thenReturn(Optional.of(post));
        when(currentPost.require()).thenReturn(post);
        return post;
    }
}
