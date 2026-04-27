package br.com.archflow.api.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ImpersonationFilter")
class ImpersonationFilterTest {

    @Test
    @DisplayName("accepts X-Tenant-Id header and propagates to request attribute")
    void acceptsTenantIdHeader() throws Exception {
        ImpersonationFilter filter = new ImpersonationFilter(false);
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(req.getHeader(ImpersonationFilter.HEADER_IMPERSONATE)).thenReturn(null);
        when(req.getHeader(ImpersonationFilter.HEADER_TENANT)).thenReturn("tenant_acme");

        filter.doFilter(req, resp, chain);

        verify(req).setAttribute(ImpersonationFilter.ATTR_TENANT_ID, "tenant_acme");
        verify(chain, times(1)).doFilter(req, resp);
    }

    @Test
    @DisplayName("rejects X-Impersonate-Tenant when impersonation is disabled")
    void rejectsImpersonationWhenDisabled() throws Exception {
        ImpersonationFilter filter = new ImpersonationFilter(false);
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(req.getHeader(ImpersonationFilter.HEADER_IMPERSONATE)).thenReturn("tenant_acme");
        when(req.getHeader(ImpersonationFilter.HEADER_TENANT)).thenReturn(null);
        when(req.getRequestURI()).thenReturn("/api/admin/workspace");

        filter.doFilter(req, resp, chain);

        // Header was rejected; no tenant attribute set, but the chain continues.
        verify(req, never()).setAttribute(eq(ImpersonationFilter.ATTR_TENANT_ID), any());
        verify(chain, times(1)).doFilter(req, resp);
    }

    @Test
    @DisplayName("accepts impersonation when enabled and no JWT roles attribute is present (dev mode)")
    void acceptsInDevMode() throws Exception {
        ImpersonationFilter filter = new ImpersonationFilter(true);
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(req.getHeader(ImpersonationFilter.HEADER_IMPERSONATE)).thenReturn("tenant_acme");
        when(req.getAttribute(JwtAuthenticationFilter.ATTR_ROLES)).thenReturn(null);
        when(req.getRequestURI()).thenReturn("/api/admin/workspace");
        when(req.getMethod()).thenReturn("GET");

        filter.doFilter(req, resp, chain);

        verify(req).setAttribute(ImpersonationFilter.ATTR_TENANT_ID, "tenant_acme");
    }

    @Test
    @DisplayName("rejects impersonation when JWT carries no superadmin role")
    void rejectsWithoutSuperadminRole() throws Exception {
        ImpersonationFilter filter = new ImpersonationFilter(true);
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(req.getHeader(ImpersonationFilter.HEADER_IMPERSONATE)).thenReturn("tenant_other");
        when(req.getHeader(ImpersonationFilter.HEADER_TENANT)).thenReturn("tenant_self");
        when(req.getAttribute(JwtAuthenticationFilter.ATTR_ROLES)).thenReturn(List.of("tenant_admin"));
        when(req.getRequestURI()).thenReturn("/api/admin/workspace");

        filter.doFilter(req, resp, chain);

        // Should fall back to the X-Tenant-Id, not honour the impersonation.
        verify(req).setAttribute(ImpersonationFilter.ATTR_TENANT_ID, "tenant_self");
    }

    @Test
    @DisplayName("accepts impersonation when JWT carries superadmin role")
    void acceptsWithSuperadminRole() throws Exception {
        ImpersonationFilter filter = new ImpersonationFilter(true);
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(req.getHeader(ImpersonationFilter.HEADER_IMPERSONATE)).thenReturn("tenant_acme");
        when(req.getAttribute(JwtAuthenticationFilter.ATTR_ROLES))
                .thenReturn(List.of("superadmin"));
        when(req.getRequestURI()).thenReturn("/api/admin/workspace");
        when(req.getMethod()).thenReturn("GET");

        ArgumentCaptor<String> attrValue = ArgumentCaptor.forClass(String.class);
        filter.doFilter(req, resp, chain);

        verify(req).setAttribute(eq(ImpersonationFilter.ATTR_TENANT_ID), attrValue.capture());
        assertThat(attrValue.getValue()).isEqualTo("tenant_acme");
    }
}
