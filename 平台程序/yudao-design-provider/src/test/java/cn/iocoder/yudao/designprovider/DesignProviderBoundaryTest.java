package cn.iocoder.yudao.designprovider;

import cn.iocoder.yudao.module.design.capability.DesignCapabilityController;
import cn.iocoder.yudao.module.aiorchestration.controller.internal.RuntimeCallTraceInternalController;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class DesignProviderBoundaryTest {

    @Test
    void providerImportsOnlyServiceControllersAndExcludesUserFacingControllersFromScanning() {
        var scan = DesignProviderApplication.class.getAnnotation(org.springframework.context.annotation.ComponentScan.class);
        assertThat(scan).isNotNull();
        assertThat(Set.of(scan.basePackages())).containsExactlyInAnyOrder(
                "cn.iocoder.yudao.module.design",
                "cn.iocoder.yudao.module.aiorchestration",
                "cn.iocoder.yudao.module.commerce.pricing",
                "cn.iocoder.yudao.module.commerce.points",
                "cn.iocoder.yudao.module.infra.zhongshu");
        var excludedAnnotations = Arrays.stream(scan.excludeFilters())
                .filter(filter -> filter.type() == org.springframework.context.annotation.FilterType.ANNOTATION)
                .flatMap(filter -> Arrays.stream(filter.classes()))
                .collect(Collectors.toSet());
        assertThat(excludedAnnotations).contains(RestController.class, Controller.class);

        var imported = DesignProviderApplication.class.getAnnotation(org.springframework.context.annotation.Import.class);
        assertThat(imported).isNotNull();
        assertThat(imported.value()).contains(DesignCapabilityController.class, ProviderAiJobInternalController.class,
                RuntimeCallTraceInternalController.class, ProviderRuntimeAssetController.class,
                ProviderOutputTicketService.class, ProviderPromptEventService.class);
        var capability = DesignCapabilityController.class.getAnnotation(RequestMapping.class);
        assertThat(capability.value()).containsExactly("/internal-api/design/v1/platform");
        assertThat(Arrays.stream(capability.value())).noneMatch(path ->
                path.startsWith("/app-api") || path.startsWith("/admin-api"));
    }
}
