package br.com.archflow.api.config;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("TenantContext")
class TenantContextTest {

    @AfterEach
    void cleanup() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("falls back to DEFAULT_TENANT when no request scope is bound")
    void noRequestScope() {
        assertThat(TenantContext.currentTenantId()).isEqualTo(TenantContext.DEFAULT_TENANT);
    }

    @Test
    @DisplayName("returns the request attribute populated by ImpersonationFilter")
    void returnsAttributeFromRequest() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(ImpersonationFilter.ATTR_TENANT_ID)).thenReturn("tenant_acme");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThat(TenantContext.currentTenantId()).isEqualTo("tenant_acme");
    }

    @Test
    @DisplayName("trims whitespace from the attribute value")
    void trimsWhitespace() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(ImpersonationFilter.ATTR_TENANT_ID)).thenReturn("  tenant_acme  ");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThat(TenantContext.currentTenantId()).isEqualTo("tenant_acme");
    }

    @Test
    @DisplayName("falls back when the attribute is blank")
    void blankFallsBack() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(ImpersonationFilter.ATTR_TENANT_ID)).thenReturn("   ");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThat(TenantContext.currentTenantId()).isEqualTo(TenantContext.DEFAULT_TENANT);
    }

    @Test
    @DisplayName("falls back when the attribute is not a String")
    void nonStringAttributeFallsBack() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(ImpersonationFilter.ATTR_TENANT_ID)).thenReturn(42);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThat(TenantContext.currentTenantId()).isEqualTo(TenantContext.DEFAULT_TENANT);
    }
}
