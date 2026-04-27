package br.com.archflow.api.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Recognises the frontend's {@code X-Impersonate-Tenant} header and
 * re-exposes it as the request attribute {@code archflow.tenantId}
 * plus the MDC entry {@code tenantId}.
 *
 * <p>Used by {@code WorkspaceControllerImpl.resolveTenantId} and by
 * audit logging. The header is accepted as-is; Real deployments
 * should gate it behind a role check (only superadmin may impersonate)
 * before this filter runs — that authorisation belongs in the Spring
 * Security chain, not here.
 */
@Component
public class ImpersonationFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(ImpersonationFilter.class);
    public static final String HEADER_IMPERSONATE = "X-Impersonate-Tenant";
    public static final String HEADER_TENANT      = "X-Tenant-Id";
    public static final String ATTR_TENANT_ID     = "archflow.tenantId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest http) {
            String tenant = http.getHeader(HEADER_IMPERSONATE);
            if (tenant == null || tenant.isBlank()) {
                tenant = http.getHeader(HEADER_TENANT);
            }
            if (tenant != null && !tenant.isBlank()) {
                String normalized = tenant.trim();
                request.setAttribute(ATTR_TENANT_ID, normalized);
                org.slf4j.MDC.put("tenantId", normalized);
                log.debug("Request tagged with tenantId={}", normalized);
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            org.slf4j.MDC.remove("tenantId");
        }
    }
}
