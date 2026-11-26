package br.com.archflow.api.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * Resolves the request tenant into the request attribute {@code archflow.tenantId} (read by
 * {@link TenantContext}) plus the MDC entry {@code tenantId}.
 *
 * <p>Precedence:
 * <ol>
 *   <li>{@code X-Impersonate-Tenant} — only when {@code archflow.admin.impersonation.enabled} is on
 *       AND the JWT carries an admin/superadmin role ({@link #callerCanImpersonate}). This is the
 *       ONLY way an authenticated caller acts on a tenant other than its own; every attempt is
 *       logged.</li>
 *   <li>The JWT {@code tenantId} claim (already copied by {@link JwtAuthenticationFilter}); a token
 *       without the claim belongs to the default tenant ({@link TenantContext#DEFAULT_TENANT}), and
 *       that value is written to the attribute — a JWT-authenticated request never leaves it
 *       unset.</li>
 *   <li>{@code X-Tenant-Id} — honoured as the tenant only when NO JWT authenticated the request
 *       (auth off in dev, gateways in front of public paths).</li>
 * </ol>
 *
 * <p>For a JWT-authenticated request, {@code X-Tenant-Id} may only <em>echo</em> the token's tenant.
 * A different value is answered with {@code 403 TENANT_MISMATCH} (RCHFLW-44): until then the header
 * silently overwrote the claim, and any authenticated user read and wrote another tenant's files,
 * knowledge bases, jobs and configuration just by knowing its id. Rejecting — instead of ignoring —
 * keeps a client that really meant another tenant from writing into its own by accident.
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
                headerTenant = headerTenant == null || headerTenant.isBlank() ? null : headerTenant.trim();
                if (authenticatedByJwt(http)) {
                    // O tenant de quem tem token é o do token. O header só pode repeti-lo: se
                    // pudesse trocá-lo, bastaria conhecer o id de outro tenant para ler e alterar
                    // os dados dele (RCHFLW-44). Token sem a claim = tenant padrão, não "livre".
                    String tokenTenant = jwtTenant(http);
                    String expected = tokenTenant != null ? tokenTenant : TenantContext.DEFAULT_TENANT;
                    if (headerTenant != null && !headerTenant.equals(expected)) {
                        log.warn("Rejected X-Tenant-Id that differs from the JWT tenant: header={} jwt={} "
                                + "path={}", headerTenant, expected, http.getRequestURI());
                        rejectTenantMismatch(response);
                        return;
                    }
                    // Grava o tenant efetivo (inclusive o padrão), não a claim crua: quem lê o
                    // atributo direto (ingest, workspace, conversa) recebia null para um token sem
                    // claim e caía no próprio fallback ou não fixava tenant nenhum, divergindo do
                    // TenantContext. Fechado: todo leitor vê o mesmo tenant.
                    tenant = expected;
                } else {
                    tenant = headerTenant;
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

    /**
     * True when {@link JwtAuthenticationFilter} validated an access token for this request — it is
     * what writes the user/roles attributes. Only then is there an identity whose tenant the
     * {@code X-Tenant-Id} header could forge.
     */
    private static boolean authenticatedByJwt(HttpServletRequest http) {
        return http.getAttribute(JwtAuthenticationFilter.ATTR_USER_ID) != null
                || http.getAttribute(JwtAuthenticationFilter.ATTR_ROLES) != null;
    }

    private static String jwtTenant(HttpServletRequest http) {
        Object claim = http.getAttribute(JwtAuthenticationFilter.ATTR_TENANT);
        return claim instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    private static void rejectTenantMismatch(ServletResponse response) throws IOException {
        if (response instanceof HttpServletResponse http) {
            http.setStatus(HttpServletResponse.SC_FORBIDDEN);
            http.setContentType("application/json");
            http.getWriter().write("{\"error\":\"TENANT_MISMATCH\","
                    + "\"message\":\"X-Tenant-Id must match the tenant of the access token\"}");
        }
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
            return rolesMayImpersonate(roles);
        }
        // Sem roles no request (auth desligada) → NEGA. "Sem autenticação =
        // pode impersonar" permitiria a qualquer cliente ler qualquer tenant
        // via header; em dev com auth off, use o tenant real em vez de
        // impersonation.
        return false;
    }

    /**
     * Os papéis que podem agir noutro tenant. Compartilhado com o handshake do WebSocket de voz
     * ({@link WebSocketConfiguration}), onde o tenant vem do caminho em vez do header — a regra
     * precisa ser a mesma nos dois, senão o WS vira o atalho para outro tenant.
     */
    static boolean rolesMayImpersonate(java.util.Collection<?> roles) {
        return roles.contains("superadmin") || roles.contains("ROLE_SUPERADMIN")
                || roles.contains("ADMIN") || roles.contains("ROLE_ADMIN");
    }
}
