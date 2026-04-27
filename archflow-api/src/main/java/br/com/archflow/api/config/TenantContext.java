package br.com.archflow.api.config;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Resolves the active tenant id for the current HTTP request.
 *
 * <p>The {@link ImpersonationFilter} normalises {@code X-Tenant-Id} and
 * (when enabled) {@code X-Impersonate-Tenant} into a single request
 * attribute — this helper is the canonical reader.
 *
 * <p>Designed for use by per-tenant controllers (BrainSentry, Linktor,
 * Skills, GlobalConfig) so they can partition their in-memory state by
 * tenant without each one reimplementing the lookup.
 */
public final class TenantContext {

    /** Default fallback used when no tenant header was set, e.g. by tests. */
    public static final String DEFAULT_TENANT = "__default__";

    private TenantContext() {}

    /**
     * @return the tenant id associated with the current request, or
     *         {@link #DEFAULT_TENANT} when no request context exists or
     *         no tenant header was supplied. Never {@code null}.
     */
    public static String currentTenantId() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            Object tenant = sra.getRequest().getAttribute(ImpersonationFilter.ATTR_TENANT_ID);
            if (tenant instanceof String s && !s.isBlank()) {
                return s.trim();
            }
        }
        return DEFAULT_TENANT;
    }
}
