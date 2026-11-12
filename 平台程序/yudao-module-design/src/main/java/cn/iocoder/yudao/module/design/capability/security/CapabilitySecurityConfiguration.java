package cn.iocoder.yudao.module.design.capability.security;

import cn.iocoder.yudao.framework.common.enums.WebFilterOrderEnum;
import cn.iocoder.yudao.framework.signature.core.redis.ApiSignatureRedisDAO;
import cn.iocoder.yudao.module.franchise.api.capability.security.ReplayWindow;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;

import java.time.Clock;
import java.util.EnumSet;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CapabilitySecurityProperties.class)
public class CapabilitySecurityConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "zhongshu.design.capability", name = "enabled", havingValue = "true")
    CapabilityRequestAuthenticator capabilityRequestAuthenticator(CapabilitySecurityProperties properties,
            StringRedisTemplate redis, CallerOrganizationResolver resolver, CapabilityProviderReadiness readiness) {
        // Explicit readiness must check the real backing services; no bean-presence fallback is accepted.
        readiness.verifyReady();
        return new CapabilityRequestAuthenticator(properties.keyring(),
                new ReplayWindow(properties.getReplayWindowSeconds(), properties.getClockSkewSeconds()),
                Clock.systemUTC(), new ApiSignatureRedisDAO(redis), resolver, properties.getMaxBodyBytes());
    }

    @Bean
    FilterRegistrationBean<CapabilityIngressFilter> capabilityIngressRegistration(CapabilitySecurityProperties properties,
            ObjectProvider<CapabilityRequestAuthenticator> authenticators) {
        var registration = new FilterRegistrationBean<>(new CapabilityIngressFilter(
                properties.isEnabled() ? authenticators.getObject() : null));
        // Limits raw body reads before the shared body-cache filter and rejects tenant/user headers before extraction.
        registration.setOrder(WebFilterOrderEnum.REQUEST_BODY_CACHE_FILTER - 1);
        registration.setDispatcherTypes(EnumSet.allOf(DispatcherType.class));
        registration.setAsyncSupported(true); // Unrelated endpoints keep async; the capability wrapper disables it locally.
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain capabilitySecurityFilterChain(HttpSecurity http) throws Exception {
        return http.securityMatcher(CapabilityRequestAuthenticator::isCapabilityRequest)
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .anonymous(AbstractHttpConfigurer::disable)
                .sessionManagement(config -> config.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(config -> config.securityContextRepository(new NullSecurityContextRepository()))
                .exceptionHandling(config -> config.authenticationEntryPoint((request, response, exception) ->
                        CapabilityIngressFilter.reject(response, 401)))
                .authorizeHttpRequests(config -> config.anyRequest().authenticated())
                .addFilterBefore(new CapabilityAuthenticationFilter(), AnonymousAuthenticationFilter.class)
                .build();
    }
}
