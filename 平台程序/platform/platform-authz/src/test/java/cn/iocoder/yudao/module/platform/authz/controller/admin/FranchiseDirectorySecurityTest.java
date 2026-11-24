package cn.iocoder.yudao.module.platform.authz.controller.admin;

import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.module.platform.authz.service.FranchiseDirectoryService;
import cn.iocoder.yudao.module.platform.authz.service.FranchiseMapService;
import cn.iocoder.yudao.module.platform.authz.service.FranchiseRegionCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FranchiseDirectorySecurityTest {
    @Configuration @EnableMethodSecurity static class Security { }
    public static class PermissionCheck {
        final Set<String> granted=new HashSet<>();
        public boolean hasPermission(String permission){return granted.contains(permission);}
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}

    @Test void mapReadPermissionNeverGrantsDirectoryImportEditOrAccountOpening() {
        var check=new PermissionCheck();check.granted.add(FranchiseMapService.READ_ALL);
        var service=mock(FranchiseDirectoryService.class);var regions=mock(FranchiseRegionCatalog.class);
        login();
        context(check,service,regions).run(ctx->{
            var controller=ctx.getBean(FranchiseDirectoryController.class);
            var response=new MockHttpServletResponse();
            var file=new MockMultipartFile("file","synthetic.xlsx","application/octet-stream",new byte[]{1});
            assertThatThrownBy(()->controller.page("",null,null,1,20)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(()->controller.candidates("")).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(()->controller.regions(null)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(()->controller.precheck(file,response)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(()->controller.submit(file,response)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(()->controller.create(null)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(()->controller.update("id",null)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(()->controller.onboard(null,response)).isInstanceOf(AccessDeniedException.class);
            verifyNoInteractions(service,regions);
        });
    }

    @Test void authorizedWebAdministratorCanReadRegionsWithoutACurrentPostAndImportsRevalidate() {
        var check=new PermissionCheck();check.granted.add("platform:org:onboard");
        var service=mock(FranchiseDirectoryService.class);var regions=mock(FranchiseRegionCatalog.class);
        when(regions.children(null)).thenReturn(List.of());login();
        context(check,service,regions).run(ctx->{
            var controller=ctx.getBean(FranchiseDirectoryController.class);
            assertThat(controller.regions(null).getData()).isEmpty();
            var response=new MockHttpServletResponse();byte[] content={1,2,3};
            var file=new MockMultipartFile("file","synthetic.xlsx","application/octet-stream",content);
            try { controller.precheck(file,response);controller.submit(file,response); }
            catch(Exception e){throw new AssertionError(e);}
            verify(service).importWorkbook(content,true,99);
            verify(service).importWorkbook(content,false,99);
            assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
            check.granted.clear();
            assertThatThrownBy(()->controller.submit(file,response)).isInstanceOf(AccessDeniedException.class);
        });
    }

    private static ApplicationContextRunner context(PermissionCheck check,FranchiseDirectoryService directory,FranchiseRegionCatalog regions) {
        return new ApplicationContextRunner().withUserConfiguration(Security.class,FranchiseDirectoryController.class)
                .withBean("ss",PermissionCheck.class,()->check)
                .withBean(FranchiseDirectoryService.class,()->directory)
                .withBean(FranchiseRegionCatalog.class,()->regions);
    }
    private static void login() {
        var user=new LoginUser();user.setId(99L);user.setUserType(UserTypeEnum.ADMIN.getValue());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,null,List.of()));
    }
}
