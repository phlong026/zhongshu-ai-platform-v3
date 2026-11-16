package cn.iocoder.yudao.module.platform.authz.controller.admin;

import cn.iocoder.yudao.module.platform.authz.service.OrgAccountAdminService;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.apilog.core.annotation.ApiAccessLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrgAccountAdminSecurityTest {
    @Configuration @EnableMethodSecurity static class Security { }
    public static class PermissionCheck {
        final Set<String> granted=new HashSet<>();
        public boolean hasPermission(String permission){return granted.contains(permission);}
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    @Test void requiresBothOrganizationAndPasswordPermissions() {
        var check=new PermissionCheck();var service=mock(OrgAccountAdminService.class);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("test","",List.of()));
        try(var security=mockStatic(SecurityFrameworkUtils.class)) {
            security.when(SecurityFrameworkUtils::getLoginUserId).thenReturn(99L);
            new ApplicationContextRunner().withUserConfiguration(Security.class,OrgAccountAdminController.class)
                    .withBean("ss",PermissionCheck.class,()->check)
                    .withBean(OrgAccountAdminService.class,()->service).run(ctx->{
                        var c=ctx.getBean(OrgAccountAdminController.class);
                        var body=new OrgAccountAdminController.ResetPassword("Example321!","门店","本人申请","key");
                        assertThatThrownBy(()->c.account(7)).isInstanceOf(AccessDeniedException.class);
                        check.granted.add("platform:org:onboard");
                        assertThatThrownBy(()->c.reset(7,body)).isInstanceOf(AccessDeniedException.class);
                        check.granted.add("system:user:update-password");
                        c.reset(7,body);
                        verify(service).reset(7,99,"Example321!","门店","本人申请","key");
                        check.granted.remove("platform:org:onboard");
                        assertThatThrownBy(()->c.reset(7,body)).isInstanceOf(AccessDeniedException.class);
                    });
        }
    }
    @Test void neverLogsPasswordRequestOrResponse() throws Exception {
        var annotation=OrgAccountAdminController.class.getMethod("reset",long.class,OrgAccountAdminController.ResetPassword.class).getAnnotation(ApiAccessLog.class);
        assertThat(annotation.requestEnable()).isFalse();assertThat(annotation.responseEnable()).isFalse();
    }
}
