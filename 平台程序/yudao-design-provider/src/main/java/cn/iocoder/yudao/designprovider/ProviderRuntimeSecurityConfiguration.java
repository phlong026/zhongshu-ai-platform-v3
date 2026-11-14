package cn.iocoder.yudao.designprovider;

import cn.iocoder.yudao.framework.common.util.servlet.ServletUtils;
import cn.iocoder.yudao.framework.web.core.filter.CacheRequestBodyFilter;
import cn.iocoder.yudao.module.aiorchestration.controller.internal.InternalSignatureVerifier;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.filter.OncePerRequestFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.function.LongSupplier;

/** Runtime service ingress: bounded body caching, HMAC and an atomic configured-window replay fence. */
@Configuration(proxyBeanMethods = false)
class ProviderRuntimeSecurityConfiguration {
    static final String PREFIX = "/internal-api/design/v1/ai-jobs/";
    static final String AUTH_SMOKE_PATH = PREFIX + "auth-smoke";

    @Bean
    FilterRegistrationBean<CacheRequestBodyFilter> providerRuntimeBodyCache() {
        var registration = new FilterRegistrationBean<>(new CacheRequestBodyFilter());
        registration.setName("providerRuntimeBodyCache");
        registration.setOrder(SecurityProperties.DEFAULT_FILTER_ORDER - 2);
        registration.addUrlPatterns(PREFIX + "*");
        return registration;
    }

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> providerRuntimeAuthentication(
            InternalSignatureVerifier signatures, StringRedisTemplate redis,
            @Value("${zhongshu.ai.internal-replay-window:300}") long replayWindowSeconds) {
        var registration = new FilterRegistrationBean<OncePerRequestFilter>();
        registration.setFilter(new RuntimeAuthenticationFilter(signatures, redis, replayWindowSeconds,
                () -> System.currentTimeMillis() / 1000));
        registration.setName("providerRuntimeAuthentication");
        registration.setOrder(SecurityProperties.DEFAULT_FILTER_ORDER - 1);
        registration.addUrlPatterns(PREFIX + "*");
        return registration;
    }

    @Bean
    @Order(1)
    SecurityFilterChain providerRuntimeSecurity(HttpSecurity http) throws Exception {
        return http.securityMatcher(PREFIX + "**")
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Authentication is performed by the outer servlet filter before this chain.
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .build();
    }

    static final class RuntimeAuthenticationFilter extends OncePerRequestFilter {
        private static final Logger LOG = LoggerFactory.getLogger(RuntimeAuthenticationFilter.class);
        private static final long MAX_REPLAY_WINDOW_SECONDS = 3600;
        private final InternalSignatureVerifier signatures;
        private final StringRedisTemplate redis;
        private final long replayWindowSeconds;
        private final LongSupplier epochSeconds;

        RuntimeAuthenticationFilter(InternalSignatureVerifier signatures, StringRedisTemplate redis,
                long replayWindowSeconds, LongSupplier epochSeconds) {
            this.signatures = signatures;
            this.redis = redis;
            validateWindow(replayWindowSeconds);
            this.replayWindowSeconds = replayWindowSeconds;
            this.epochSeconds = epochSeconds;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String timestamp = singleHeader(request, "X-ZS-Timestamp");
            String signature = singleHeader(request, "X-ZS-Signature");
            byte[] body = ServletUtils.getBodyBytes(request);
            if (!signatures.verify(timestamp, signature, request.getMethod(), request.getRequestURI(), body)) {
                LOG.warn("[runtimeAuthentication][reason=INVALID_SIGNATURE method={}]", request.getMethod());
                reject(response, 401, "UNAUTHORIZED");
                return;
            }
            final Duration replayTtl;
            try {
                replayTtl = replayTtl(timestamp, replayWindowSeconds, epochSeconds.getAsLong());
            } catch (IllegalArgumentException unsafeTimestamp) {
                LOG.warn("[runtimeAuthentication][reason=INVALID_TIMESTAMP method={}]", request.getMethod());
                reject(response, 401, "UNAUTHORIZED");
                return;
            }
            // The smoke skips Redis state, but must pass the same overflow-safe timestamp window.
            if (AUTH_SMOKE_PATH.equals(request.getRequestURI())) {
                chain.doFilter(request, response);
                return;
            }
            final Boolean claimed;
            try {
                claimed = redis.opsForValue().setIfAbsent("design-provider:runtime-replay:" + signature, "", replayTtl);
            } catch (RuntimeException unavailable) {
                LOG.warn("[runtimeAuthentication][reason=REPLAY_STORE_UNAVAILABLE failureType={}]",
                        unavailable.getClass().getSimpleName());
                reject(response, 503, "AUTHENTICATION_UNAVAILABLE");
                return;
            }
            if (!Boolean.TRUE.equals(claimed)) {
                LOG.warn("[runtimeAuthentication][reason=REPLAY_REJECTED method={}]", request.getMethod());
                reject(response, 409, "REPLAY_REJECTED");
                return;
            }
            chain.doFilter(request, response);
        }

        static Duration replayTtl(String timestamp, long replayWindowSeconds, long nowEpochSeconds) {
            validateWindow(replayWindowSeconds);
            try {
                long signedAt = Long.parseLong(timestamp);
                long delta = Math.subtractExact(signedAt, nowEpochSeconds);
                if (delta < -replayWindowSeconds || delta > replayWindowSeconds) throw invalidTimestamp();
                long validThroughInclusive = Math.addExact(signedAt, replayWindowSeconds);
                long remaining = Math.subtractExact(Math.addExact(validThroughInclusive, 1), nowEpochSeconds);
                long maximumRemaining = Math.addExact(Math.multiplyExact(replayWindowSeconds, 2), 1);
                if (remaining < 1 || remaining > maximumRemaining) throw invalidTimestamp();
                return Duration.ofSeconds(remaining);
            } catch (NumberFormatException | ArithmeticException invalid) {
                throw invalidTimestamp();
            }
        }

        private static void validateWindow(long replayWindowSeconds) {
            if (replayWindowSeconds < 1 || replayWindowSeconds > MAX_REPLAY_WINDOW_SECONDS) {
                throw new IllegalArgumentException("RUNTIME_REPLAY_WINDOW_INVALID");
            }
        }

        private static IllegalArgumentException invalidTimestamp() {
            return new IllegalArgumentException("RUNTIME_TIMESTAMP_INVALID");
        }

        private static String singleHeader(HttpServletRequest request, String name) {
            var values = Collections.list(request.getHeaders(name));
            return values.size() == 1 ? values.get(0) : null;
        }

        private static void reject(HttpServletResponse response, int status, String code) throws IOException {
            response.setStatus(status);
            response.setCharacterEncoding("UTF-8");
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":" + status + ",\"message\":\"" + code + "\"}");
        }
    }
}
