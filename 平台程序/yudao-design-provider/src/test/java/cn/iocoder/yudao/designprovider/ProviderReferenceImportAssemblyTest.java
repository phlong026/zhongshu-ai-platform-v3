package cn.iocoder.yudao.designprovider;

import cn.iocoder.yudao.module.design.capability.CaseReferenceImportPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.annotation.ConfigurationClassPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderReferenceImportAssemblyTest {
    @Test
    void productionApplicationDiscoversExactlyOneReferenceImporter() {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("assembly", Map.of(
                "yudao.info.base-package", "cn.iocoder.yudao",
                "zhongshu.design.capability.enabled", "true")));
        var factory = new DefaultListableBeanFactory();
        var reader = new AnnotatedBeanDefinitionReader(factory, environment);
        reader.register(DesignProviderApplication.class);
        var configuration = new ConfigurationClassPostProcessor();
        configuration.setEnvironment(environment);
        configuration.postProcessBeanDefinitionRegistry(factory);
        // Resolve the real production configuration graph without instantiating database or network clients.
        assertThat(factory.getBeanNamesForType(CaseReferenceImportPort.class, true, false)).hasSize(1);
    }
}
