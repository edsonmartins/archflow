package br.com.archflow.api.config;

import br.com.archflow.security.jwt.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates {@code Authorization: Bearer <token>} headers, populates
 * request attributes with the user / role / tenant claims, and rejects
 * requests to protected endpoints when the token is missing or invalid.
 *
 * <p>Public paths (login, refresh, health checks, websockets) bypass
 * the filter entirely. Everything else requires a valid access token.
 *
 * <p>Run order is set to {@link Ordered#HIGHEST_PRECEDENCE} + 10 so this
 * filter runs <em>before</em> {@link ImpersonationFilter}; impersonation
 * then has access to the resolved role and can decide whether to honour
 * the {@code X-Impersonate-Tenant} header.
 *
 * <p>Disabled by default (see {@code archflow.security.auth.enabled})
 * so dev / E2E mock flows that have not yet wired login keep working.
 * Production deployments MUST flip the property to {@code true}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class JwtAuthenticationFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    public static final String ATTR_USER_ID  = "archflow.userId";
    public static final String ATTR_USERNAME = "archflow.username";
    public static final String ATTR_ROLES    = "archflow.roles";
    public static final String ATTR_TENANT   = "archflow.jwtTenantId";

    private static final List<String> DEFAULT_PUBLIC_PATHS = List.of(
            "/api/auth/login",
            "/api/auth/refresh",
            "/api/auth/logout",
            "/api/health",
            "/actuator/",
            "/realtime/",
            "/ws/"
    );

    private final JwtService jwtService;
    private final boolean enabled;
    private final Set<String> publicPaths;
    private final ObjectMapper jsonWriter = new ObjectMapper();

    public JwtAuthenticationFilter(
            JwtService jwtService,
            @Value("${archflow.security.auth.enabled:false}") boolean enabled,
            @Value("${archflow.security.auth.publicPaths:}") String additionalPublicPaths) {
        this.jwtService = jwtService;
        this.enabled = enabled;
        Set<String> paths = new HashSet<>(DEFAULT_PUBLIC_PATHS);
        if (additionalPublicPaths != null && !additionalPublicPaths.isBlank()) {
            paths.addAll(Arrays.asList(additionalPublicPaths.trim().split("\\s*,\\s*")));
        }
        this.publicPaths = paths;
        if (enabled) {
            log.info("JWT authentication filter is ENABLED; public paths: {}", paths);
        } else {
            log.warn("JWT authentication filter is DISABLED — set archflow.security.auth.enabled=true in production");
        }
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest http) || !(response instanceof HttpServletResponse httpResp)) {
            chain.doFilter(request, response);
            return;
        }

        String path = http.getRequestURI();
        boolean isPublic = isPublicPath(path);

        String authHeader = http.getHeader("Authorization");
        String token = stripBearer(authHeader);

        if (token != null) {
            try {
                Claims claims = jwtService.validateToken(token);
                if (!"access".equals(claims.get("type", String.class))) {
                    if (!enabled || isPublic) {
                        // permissive: just skip populating attributes
                    } else {
                        reject(httpResp, "INVALID_TOKEN_TYPE", "Refresh tokens cannot be used for API calls");
                        return;
                    }
                } else {
                    populateAttributes(http, claims);
                }
            } catch (RuntimeException e) {
                if (enabled && !isPublic) {
                    log.debug("Rejected request to {} — invalid token: {}", path, e.getMessage());
                    reject(httpResp, "INVALID_TOKEN", "Invalid or expired access token");
                    return;
                }
                // Permissive mode: log and continue without auth attributes.
                log.debug("Ignoring invalid token (auth disabled or public path): {}", e.getMessage());
            }
        } else if (enabled && !isPublic) {
            reject(httpResp, "MISSING_TOKEN", "Authorization header is required");
            return;
        }

        chain.doFilter(request, response);
    }

    private boolean isPublicPath(String path) {
        if (path == null) return false;
        for (String prefix : publicPaths) {
            if (path.equals(prefix) || path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private void populateAttributes(HttpServletRequest http, Claims claims) {
        http.setAttribute(ATTR_USER_ID, claims.getSubject());
        Object username = claims.get("username");
        if (username != null) http.setAttribute(ATTR_USERNAME, String.valueOf(username));
        Object roles = claims.get("roles");
        if (roles instanceof java.util.Collection<?> coll) {
            http.setAttribute(ATTR_ROLES, List.copyOf((java.util.Collection<String>) coll));
        }
        Object tenantClaim = claims.get("tenantId");
        if (tenantClaim instanceof String s && !s.isBlank()) {
            http.setAttribute(ATTR_TENANT, s);
            // Also set the canonical request attribute used by TenantContext
            // so per-tenant controllers see the JWT-derived tenant even when
            // no X-Tenant-Id header is set.
            if (http.getAttribute(ImpersonationFilter.ATTR_TENANT_ID) == null) {
                http.setAttribute(ImpersonationFilter.ATTR_TENANT_ID, s);
            }
        }
    }

    private void reject(HttpServletResponse resp, String code, String message) throws IOException {
        resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        resp.setContentType("application/json");
        resp.getWriter().write(jsonWriter.writeValueAsString(java.util.Map.of(
                "error", code,
                "message", message
        )));
    }

    private static String stripBearer(String header) {
        if (header == null) return null;
        if (header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = header.substring(7).trim();
            return token.isEmpty() ? null : token;
        }
        return null;
    }

    /** Test/admin helper — checks if the current request bears a given role. */
    public static boolean currentRequestHasRole(HttpServletRequest http, String role) {
        Object roles = http.getAttribute(ATTR_ROLES);
        if (roles instanceof List<?> list) {
            return list.contains(role);
        }
        return false;
    }
}
