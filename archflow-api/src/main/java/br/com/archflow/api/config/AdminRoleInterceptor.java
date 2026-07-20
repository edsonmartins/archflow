package br.com.archflow.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;

/**
 * Enforcement de role admin nos endpoints de administração global
 * ({@code /api/admin/global/**} e {@code /api/admin/tenants/**}, fase 5.4 do
 * plano de homologação): sem role {@code ADMIN}/{@code SUPERADMIN} no token,
 * a resposta é {@code 403} com corpo JSON claro.
 *
 * <p>As roles vêm do request attribute
 * {@link JwtAuthenticationFilter#ATTR_ROLES}, populado pelo filtro JWT que
 * roda antes de qualquer interceptor MVC.
 *
 * <p>Quando {@code archflow.security.auth.enabled=false} (dev/E2E sem login
 * wired) o interceptor deixa passar — em produção o
 * {@link ProductionReadinessGuard} já recusa o boot com auth desligada, então
 * não há como esta permissividade vazar para prod.
 */
public class AdminRoleInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AdminRoleInterceptor.class);

    private final boolean authEnabled;
    private final ObjectMapper json = new ObjectMapper();

    public AdminRoleInterceptor(boolean authEnabled) {
        this.authEnabled = authEnabled;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!authEnabled) {
            return true;
        }
        // Preflight CORS não carrega Authorization; o CorsRegistry responde.
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        Object roles = request.getAttribute(JwtAuthenticationFilter.ATTR_ROLES);
        if (roles instanceof Collection<?> collection) {
            for (Object role : collection) {
                if (isAdminRole(String.valueOf(role))) {
                    return true;
                }
            }
        }
        log.warn("Denying non-admin access to {} {} (roles: {})",
                request.getMethod(), request.getRequestURI(), roles);
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter().write(json.writeValueAsString(Map.of(
                "error", "FORBIDDEN",
                "message", "Administration endpoints require the ADMIN role",
                "status", 403)));
        return false;
    }

    /** {@code ADMIN}/{@code SUPERADMIN} (com ou sem prefixo {@code ROLE_}), case-insensitive. */
    static boolean isAdminRole(String role) {
        if (role == null) {
            return false;
        }
        String normalized = role.trim();
        if (normalized.regionMatches(true, 0, "ROLE_", 0, 5)) {
            normalized = normalized.substring(5);
        }
        return "ADMIN".equalsIgnoreCase(normalized) || "SUPERADMIN".equalsIgnoreCase(normalized);
    }
}
