package cn.iocoder.yudao.designprovider;

import cn.iocoder.yudao.module.design.capability.security.CapabilityAuthenticationFilter;
import cn.iocoder.yudao.module.franchise.api.DesignServicePort;
import cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.Operation;
import cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.Request;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Provider-owned fail-closed rules that cannot yet be proven by A's reusable adapter. */
@Configuration(proxyBeanMethods = false)
class ProviderCapabilityBoundaryConfiguration {
    private static final Logger log = LoggerFactory.getLogger(ProviderCapabilityBoundaryConfiguration.class);

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> providerCapabilityBoundary() {
        var registration = new FilterRegistrationBean<OncePerRequestFilter>();
        registration.setFilter(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                try {
                    var envelope = CapabilityAuthenticationFilter.requireRequest(request);
                    if (isBlockedCaseReference(envelope)) {
                        log.warn("[design-provider][case-reference-denied reason=AUTHORIZATION_NOT_READY caller={} org={} operation={} requestKeyDigest={}]",
                                envelope.callerAppId(), envelope.orgId(), envelope.operation(),
                                requestKeyDigest(envelope.requestKey()));
                        response.setStatus(503);
                        response.setContentType("application/json");
                        response.setCharacterEncoding("UTF-8");
                        response.getWriter().write("{\"code\":503,\"message\":\"CASE_REFERENCE_PROVIDER_AUTHORIZATION_NOT_READY\"}");
                        return;
                    }
                } catch (SecurityException unauthenticated) {
                    // The dedicated capability security chain owns unsigned and invalid requests.
                }
                chain.doFilter(request, response);
            }
        });
        registration.setName("providerCapabilityBoundary");
        registration.setOrder(SecurityProperties.DEFAULT_FILTER_ORDER + 1);
        registration.addUrlPatterns("/internal-api/design/v1/platform/*");
        return registration;
    }

    static boolean isBlockedCaseReference(Request<?> envelope) {
        return envelope.operation() == Operation.CREATE_PROJECT
                && envelope.payload() instanceof DesignServicePort.DesignProjectCreate create
                && "CASE_REFERENCE".equals(create.sourceType());
    }

    static String requestKeyDigest(String requestKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(requestKey).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
