package cn.iocoder.yudao.module.design.capability.security;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;

/** Runs before body caching, tenant header extraction, and the normal user-token security chain. */
final class CapabilityIngressFilter implements Filter {
    static final String VERIFIED_ATTRIBUTE = CapabilityIngressFilter.class.getName() + ".verified";
    private static final Logger log = LoggerFactory.getLogger(CapabilityIngressFilter.class);
    private final CapabilityRequestAuthenticator authenticator;

    CapabilityIngressFilter(CapabilityRequestAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    public void doFilter(ServletRequest input, ServletResponse output, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) input;
        HttpServletResponse response = (HttpServletResponse) output;
        if (!CapabilityRequestAuthenticator.isCapabilityRequest(request)) {
            chain.doFilter(request, response);
            return;
        }
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
        request.removeAttribute(VERIFIED_ATTRIBUTE);
        try {
            if (authenticator == null) {
                reject(response, 404);
                return;
            }
            final CapabilityRequestAuthenticator.Verified verified;
            try {
                verified = authenticator.authenticate(request);
            } catch (CapabilityAuthenticationException e) {
                log.warn("[capabilityAuth] rejected status={} reason={}", e.status(), e.getMessage());
                reject(response, e.status());
                return;
            }
            request.setAttribute(VERIFIED_ATTRIBUTE, verified);
            chain.doFilter(new CapabilityBodyRequest(request, verified.rawBody()), response);
        } finally {
            request.removeAttribute(VERIFIED_ATTRIBUTE);
            SecurityContextHolder.clearContext();
            TenantContextHolder.clear();
        }
    }

    static void reject(HttpServletResponse response, int status) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\":" + status + ",\"message\":\"Capability request rejected\"}");
    }
}
