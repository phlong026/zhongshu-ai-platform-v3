package cn.iocoder.yudao.server.split;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Installs the precise legacy B handler whitelist only when the local gate is configured. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "platform.legacy-b-write-control-directory")
public class LegacyPlatformHttpWriteWebConfiguration {
    @Bean
    WebMvcConfigurer legacyPlatformHttpWriteWebConfigurer(LegacyPlatformWriteGate gate,
            @Value("${platform.legacy-design-bridge.enabled:true}") boolean legacyBridgeEnabled) {
        var interceptor = new LegacyPlatformHttpWriteInterceptor(gate, legacyBridgeEnabled);
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                // Match the resolved HandlerMethod; host, URL prefix and user headers are not ownership evidence.
                registry.addInterceptor(interceptor).order(Ordered.HIGHEST_PRECEDENCE);
            }
        };
    }
}
