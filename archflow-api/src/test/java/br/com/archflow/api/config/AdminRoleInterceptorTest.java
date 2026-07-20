package br.com.archflow.api.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AdminRoleInterceptor (enforcement de admin em /api/admin/global|tenants)")
class AdminRoleInterceptorTest {

    private static MockHttpServletRequest request(Object roles) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/admin/tenants");
        if (roles != null) {
            request.setAttribute(JwtAuthenticationFilter.ATTR_ROLES, roles);
        }
        return request;
    }

    @Test
    @DisplayName("auth desligada (dev): deixa passar sem roles")
    void allowsWhenAuthDisabled() throws Exception {
        AdminRoleInterceptor interceptor = new AdminRoleInterceptor(false);

        assertThat(interceptor.preHandle(request(null), new MockHttpServletResponse(), null))
                .isTrue();
    }

    @Test
    @DisplayName("auth ligada sem roles: 403 com corpo JSON claro")
    void deniesWithoutRoles() throws Exception {
        AdminRoleInterceptor interceptor = new AdminRoleInterceptor(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request(null), response, null);

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).contains("application/json");
        assertThat(response.getContentAsString())
                .contains("\"error\":\"FORBIDDEN\"")
                .contains("ADMIN");
    }

    @Test
    @DisplayName("auth ligada com role não-admin: 403")
    void deniesNonAdminRole() throws Exception {
        AdminRoleInterceptor interceptor = new AdminRoleInterceptor(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request(List.of("VIEWER", "DESIGNER")), response, null);

        assertThat(allowed).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("role ADMIN autoriza; variantes ROLE_ADMIN/superadmin também")
    void allowsAdminVariants() throws Exception {
        AdminRoleInterceptor interceptor = new AdminRoleInterceptor(true);

        assertThat(interceptor.preHandle(request(List.of("ADMIN")),
                new MockHttpServletResponse(), null)).isTrue();
        assertThat(interceptor.preHandle(request(List.of("VIEWER", "ROLE_ADMIN")),
                new MockHttpServletResponse(), null)).isTrue();
        assertThat(interceptor.preHandle(request(List.of("superadmin")),
                new MockHttpServletResponse(), null)).isTrue();
    }

    @Test
    @DisplayName("preflight OPTIONS passa sem roles (CORS)")
    void allowsPreflight() throws Exception {
        AdminRoleInterceptor interceptor = new AdminRoleInterceptor(true);
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/admin/tenants");

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), null)).isTrue();
    }

    @Test
    @DisplayName("configuração registra o interceptor nos dois paths de admin global")
    void configurationRegistersInterceptor() {
        var registry = new InterceptorRegistry() {
            List<Object> interceptors() {
                return getInterceptors();
            }
        };

        new AdminSecurityConfiguration(true).addInterceptors(registry);

        List<Object> registered = registry.interceptors();
        assertThat(registered).hasSize(1);
        // MappedInterceptor embrulha o nosso quando há path patterns.
        Object first = registered.get(0);
        assertThat(first).isInstanceOf(org.springframework.web.servlet.handler.MappedInterceptor.class);
        var mapped = (org.springframework.web.servlet.handler.MappedInterceptor) first;
        assertThat(mapped.getInterceptor()).isInstanceOf(AdminRoleInterceptor.class);
        assertThat(mapped.matches(withParsedPath("/api/admin/global/models"))).isTrue();
        assertThat(mapped.matches(withParsedPath("/api/admin/tenants/t-1"))).isTrue();
        assertThat(mapped.matches(withParsedPath("/api/workflows"))).isFalse();
    }

    private static MockHttpServletRequest withParsedPath(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        org.springframework.web.util.ServletRequestPathUtils.parseAndCache(request);
        return request;
    }
}
