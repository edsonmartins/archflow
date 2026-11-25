package br.com.archflow.api.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * Recognises the frontend's {@code X-Impersonate-Tenant} header and
 * re-exposes it as the request attribute {@code archflow.tenantId}
 * plus the MDC entry {@code tenantId}.
 *
 * <p>The plain {@code X-Tenant-Id} header only echoes the caller's own
 * tenant: when a validated JWT carries a {@code tenantId} claim, a header
 * naming another tenant is ignored (and logged) — the JWT tenant stays.
 * Without a JWT tenant (auth off, dev) the header is accepted as before.
 * The {@code X-Impersonate-Tenant} header, however, lets a
 * superadmin act as another tenant; until JWT/role-based auth is wired
 * into archflow-api, accepting it from anyone would let any frontend
 * read any workspace. We therefore:
 * <ul>
 *   <li>Gate {@code X-Impersonate-Tenant} behind the
 *       {@code archflow.admin.impersonation.enabled} property
 *       (default {@code false} — set to {@code true} only in dev).</li>
 *   <li>Log every impersonation attempt at INFO so audit trails make
 *       it visible.</li>
 * </ul>
 * When real auth lands the property gate should be replaced with a
 * Spring-Security role check (only superadmin may impersonate).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ImpersonationFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(ImpersonationFilter.class);
    public static final String HEADER_IMPERSONATE = "X-Impersonate-Tenant";
    public static final String HEADER_TENANT      = "X-Tenant-Id";
    public static final String ATTR_TENANT_ID     = "archflow.tenantId";

    private final boolean impersonationEnabled;

    public ImpersonationFilter(
            @Value("${archflow.admin.impersonation.enabled:false}") boolean impersonationEnabled) {
        this.impersonationEnabled = impersonationEnabled;
        if (impersonationEnabled) {
            log.warn("Tenant impersonation header is ENABLED — this should only be on in dev profiles");
        }
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest http) {
            String tenant = null;
            String impersonated = http.getHeader(HEADER_IMPERSONATE);
            if (impersonated != null && !impersonated.isBlank()) {
                if (impersonationEnabled && callerCanImpersonate(http)) {
                    tenant = impersonated.trim();
                    log.info("Tenant impersonation: tenantId={} path={} method={}",
                            tenant, http.getRequestURI(), http.getMethod());
                } else {
                    log.warn("Rejected X-Impersonate-Tenant header (enabled={}, role check passed={}): path={}",
                            impersonationEnabled, callerCanImpersonate(http), http.getRequestURI());
                }
            }
            if (tenant == null) {
                String headerTenant = http.getHeader(HEADER_TENANT);
                if (headerTenant != null && !headerTenant.isBlank()) {
                    String doJwt = jwtTenant(http);
                    if (doJwt != null && !doJwt.equals(headerTenant.trim())) {
                        // O header não pode trocar o tenant do token. Com chave de LLM por tenant
                        // (PropertyTenantKeyResolver), aceitar isto deixaria um usuário do tenant A
                        // rodar — e faturar — na chave do tenant B só mandando o header. Fica o do
                        // JWT, que o JwtAuthenticationFilter já gravou no atributo.
                        log.warn("Ignored X-Tenant-Id that differs from the JWT tenant: jwt={} header={} path={}",
                                doJwt, headerTenant.trim(), http.getRequestURI());
                    } else {
                        tenant = headerTenant.trim();
                    }
                }
            }
            if (tenant != null) {
                request.setAttribute(ATTR_TENANT_ID, tenant);
                org.slf4j.MDC.put("tenantId", tenant);
                log.debug("Request tagged with tenantId={}", tenant);
            }
        }
        try {
            chain.doFilter(request, response);
        } finally {
            org.slf4j.MDC.remove("tenantId");
        }
    }

    /** O tenant do token, quando o {@link JwtAuthenticationFilter} validou um; senão {@code null}. */
    private static String jwtTenant(HttpServletRequest http) {
        return http.getAttribute(JwtAuthenticationFilter.ATTR_TENANT) instanceof String s && !s.isBlank()
                ? s.trim() : null;
    }

    /**
     * When the JWT auth filter has run and populated the request with a
     * roles attribute, only callers carrying the {@code superadmin} role
     * may impersonate. When the JWT filter is disabled (auth.enabled=false),
     * the property gate alone applies — typical for dev / E2E.
     */
    private boolean callerCanImpersonate(HttpServletRequest http) {
        Object rolesAttr = http.getAttribute(JwtAuthenticationFilter.ATTR_ROLES);
        if (rolesAttr instanceof List<?> roles) {
            return roles.contains("superadmin") || roles.contains("ROLE_SUPERADMIN")
                    || roles.contains("ADMIN") || roles.contains("ROLE_ADMIN");
        }
        // Sem roles no request (auth desligada) → NEGA. "Sem autenticação =
        // pode impersonar" permitiria a qualquer cliente ler qualquer tenant
        // via header; em dev com auth off, use o tenant real em vez de
        // impersonation.
        return false;
    }
}
