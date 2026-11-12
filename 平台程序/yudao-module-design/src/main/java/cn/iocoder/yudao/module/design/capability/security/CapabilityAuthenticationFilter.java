package cn.iocoder.yudao.module.design.capability.security;

import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.franchise.api.capability.DesignCapabilityContract.Request;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/** A dedicated SecurityFilterChain establishes a service principal, never an end-user login. */
public final class CapabilityAuthenticationFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Object attribute = request.getAttribute(CapabilityIngressFilter.VERIFIED_ATTRIBUTE);
        if (!(attribute instanceof CapabilityRequestAuthenticator.Verified verified)) {
            CapabilityIngressFilter.reject(response, 401);
            return;
        }
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new ServiceAuthentication(verified.principal()));
        SecurityContextHolder.setContext(context);
        TenantContextHolder.clear();
        TenantContextHolder.setTenantId(verified.principal().tenantId());
        TenantContextHolder.setIgnore(false);
        try {
            chain.doFilter(new CapabilityBodyRequest(request, verified.rawBody()), response);
        } finally {
            SecurityContextHolder.clearContext();
            TenantContextHolder.clear();
        }
    }

    public static ServicePrincipal requirePrincipal(HttpServletRequest request) {
        Object attribute = request.getAttribute(CapabilityIngressFilter.VERIFIED_ATTRIBUTE);
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(attribute instanceof CapabilityRequestAuthenticator.Verified verified)
                || !(authentication instanceof ServiceAuthentication)
                || !verified.principal().equals(authentication.getPrincipal())) {
            throw new SecurityException("Verified capability service principal required");
        }
        return verified.principal();
    }

    public static Request<?> requireRequest(HttpServletRequest request) {
        requirePrincipal(request);
        return ((CapabilityRequestAuthenticator.Verified) request.getAttribute(CapabilityIngressFilter.VERIFIED_ATTRIBUTE)).request();
    }

    private static final class ServiceAuthentication extends AbstractAuthenticationToken {
        private static final long serialVersionUID = 1L;
        private final transient ServicePrincipal principal;

        private ServiceAuthentication(ServicePrincipal principal) {
            super(List.of());
            this.principal = principal;
            super.setAuthenticated(true);
        }

        @Override
        public Object getCredentials() {
            return "";
        }

        @Override
        public ServicePrincipal getPrincipal() {
            return principal;
        }

        @Override
        public String getName() {
            return principal.callerAppId();
        }
    }
}
