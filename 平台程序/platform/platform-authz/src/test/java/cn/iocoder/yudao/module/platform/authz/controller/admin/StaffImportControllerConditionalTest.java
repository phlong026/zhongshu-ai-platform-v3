package cn.iocoder.yudao.module.platform.authz.controller.admin;

import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.authz.service.StaffAccountOnboardService;
import cn.iocoder.yudao.module.platform.org.OrgService;
import cn.iocoder.yudao.module.platform.org.StaffImportService;
import cn.iocoder.yudao.module.platform.org.StaffPostService;
import cn.iocoder.yudao.module.platform.org.SystemDirectory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotatedElementUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * P1 批2：员工导入 B-only 装配三档（absent / true / false）。
 * 旧融合进程无该属性（absent）或显式 true → 零 bean；B 独立进程 false → 注册。
 */
class StaffImportControllerConditionalTest {

    @Test
    void controllerCarriesBOnlyConditionalAnnotation() {
        ConditionalOnProperty cond = AnnotatedElementUtils.findMergedAnnotation(
                StaffImportController.class, ConditionalOnProperty.class);
        assertThat(cond).isNotNull();
        assertThat(cond.name()).containsExactly("platform.legacy-design-bridge.enabled");
        assertThat(cond.havingValue()).isEqualTo("false");
        assertThat(cond.matchIfMissing()).isFalse();
    }

    @Test
    void serviceCarriesBOnlyConditionalAnnotation() {
        ConditionalOnProperty cond = AnnotatedElementUtils.findMergedAnnotation(
                StaffImportService.class, ConditionalOnProperty.class);
        assertThat(cond).isNotNull();
        assertThat(cond.name()).containsExactly("platform.legacy-design-bridge.enabled");
        assertThat(cond.havingValue()).isEqualTo("false");
        assertThat(cond.matchIfMissing()).isFalse();
    }

    @Configuration(proxyBeanMethods = false)
    @Import(StaffImportController.class)
    static class ControllerWiring {
    }

    @Configuration(proxyBeanMethods = false)
    @Import(StaffImportService.class)
    static class ServiceWiring {
    }

    private ApplicationContextRunner runner(String property, Class<?> wiring, boolean controller) {
        ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(wiring);
        if (property != null) {
            runner = runner.withPropertyValues("platform.legacy-design-bridge.enabled=" + property);
        }
        if (controller) {
            return runner
                    .withBean(CurrentPost.class, () -> mock(CurrentPost.class))
                    .withBean(StaffAccountOnboardService.class, () -> mock(StaffAccountOnboardService.class))
                    .withBean(StaffImportService.class, () -> mock(StaffImportService.class));
        }
        return runner
                .withBean(StaffPostService.class, () -> mock(StaffPostService.class))
                .withBean(OrgService.class, () -> mock(OrgService.class))
                .withBean(SystemDirectory.class, () -> mock(SystemDirectory.class));
    }

    @Test
    void controllerAbsentPropertyHasNoBean() {
        runner(null, ControllerWiring.class, true)
                .run(ctx -> assertThat(ctx).doesNotHaveBean(StaffImportController.class));
    }

    @Test
    void controllerTruePropertyHasNoBean() {
        runner("true", ControllerWiring.class, true)
                .run(ctx -> assertThat(ctx).doesNotHaveBean(StaffImportController.class));
    }

    @Test
    void controllerFalsePropertyRegistersBean() {
        runner("false", ControllerWiring.class, true)
                .run(ctx -> assertThat(ctx).hasSingleBean(StaffImportController.class));
    }

    @Test
    void serviceAbsentPropertyHasNoBean() {
        runner(null, ServiceWiring.class, false)
                .run(ctx -> assertThat(ctx).doesNotHaveBean(StaffImportService.class));
    }

    @Test
    void serviceTruePropertyHasNoBean() {
        runner("true", ServiceWiring.class, false)
                .run(ctx -> assertThat(ctx).doesNotHaveBean(StaffImportService.class));
    }

    @Test
    void serviceFalsePropertyRegistersBean() {
        runner("false", ServiceWiring.class, false)
                .run(ctx -> assertThat(ctx).hasSingleBean(StaffImportService.class));
    }
}
