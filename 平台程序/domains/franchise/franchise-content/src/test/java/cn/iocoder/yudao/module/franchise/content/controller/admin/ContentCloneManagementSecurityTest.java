package cn.iocoder.yudao.module.franchise.content.controller.admin;

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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises the actual method-security proxy; service tests alone miss the old sales permission denial. */
class ContentCloneManagementSecurityTest {
    @Configuration(proxyBeanMethods=false) @EnableMethodSecurity static class Security {}
    final CurrentPost current=mock(CurrentPost.class);
    final SystemDirectory directory=mock(SystemDirectory.class);
    final ContentCloneService clones=mock(ContentCloneService.class);
    final ContentCloneUploadService uploads=mock(ContentCloneUploadService.class);
    final ApplicationContextRunner context=new ApplicationContextRunner().withUserConfiguration(Security.class)
            .withBean(CurrentPost.class,()->current).withBean(SystemDirectory.class,()->directory)
            .withBean("post",PostPermissionEvaluator.class,()->new PostPermissionEvaluator(current,directory))
            .withBean(ContentCloneService.class,()->clones).withBean(ContentCloneUploadService.class,()->uploads)
            .withBean(ContentCloneController.class);
    @BeforeEach void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("employee",null,List.of()));
    }
    @AfterEach void clear() {SecurityContextHolder.clearContext();}
    private PostSnapshot acting(String role) {
        var post=new PostSnapshot(11,22,33,"store",role,null,false,Set.of());
        when(current.find()).thenReturn(Optional.of(post));when(current.require()).thenReturn(post);return post;
    }
    @Test void personaSelfPermissionAllowsBothAvatarAndVoiceDefaultsWithoutCompanyManagementPermission() {
        var post=acting("sales");when(directory.roleHasPermission("sales",ContentPermissions.PERSONA_SELF)).thenReturn(true);
        when(clones.setDefault(eq(post),anyLong())).thenReturn(Map.of("isDefault",true));
        context.run(ctx->{
            var controller=ctx.getBean(ContentCloneController.class);
            assertThat(controller.setDefault(201).getData()).containsEntry("isDefault",true);
            assertThat(controller.setDefault(301).getData()).containsEntry("isDefault",true);
        });
        verify(clones).setDefault(post,201);verify(clones).setDefault(post,301);
    }
    @Test void companyPersonaPermissionStillReachesTheServiceOwnershipCheck() {
        var post=acting("sales-lead");when(directory.roleHasPermission("sales-lead",ContentPermissions.PERSONA)).thenReturn(true);
        context.run(ctx->ctx.getBean(ContentCloneController.class).setDefault(201));
        verify(clones).setDefault(post,201);
    }
    @Test void queryPermissionAloneDoesNotGrantManagementAndMissingPostIsDenied() {
        acting("sales");when(directory.roleHasPermission("sales",ContentPermissions.QUERY)).thenReturn(true);
        context.run(ctx->{
            var controller=ctx.getBean(ContentCloneController.class);
            assertThatThrownBy(()->controller.setDefault(201)).isInstanceOf(AccessDeniedException.class);
            when(current.find()).thenReturn(Optional.empty());
            assertThatThrownBy(()->controller.setDefault(301)).isInstanceOf(AccessDeniedException.class);
        });
        verifyNoInteractions(clones);
    }
}
