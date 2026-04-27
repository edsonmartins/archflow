package br.com.archflow.api.config;

import br.com.archflow.security.jwt.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("JwtAuthenticationFilter")
class JwtAuthenticationFilterTest {

    private static final String SECRET = "test-secret-must-be-long-enough-for-hs256-256bits";
    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(SECRET);
    }

    @Test
    @DisplayName("permits any request when auth is disabled")
    void permitsWhenDisabled() throws Exception {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService, false, "");

        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(req.getRequestURI()).thenReturn("/api/admin/triggers");
        when(req.getHeader("Authorization")).thenReturn(null);

        filter.doFilter(req, resp, chain);

        verify(chain, times(1)).doFilter(req, resp);
        verify(resp, never()).setStatus(401);
    }

    @Test
    @DisplayName("rejects protected request without token when auth is enabled")
    void rejectsMissingToken() throws Exception {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService, true, "");

        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        StringWriter body = new StringWriter();
        when(req.getRequestURI()).thenReturn("/api/admin/triggers");
        when(req.getHeader("Authorization")).thenReturn(null);
        when(resp.getWriter()).thenReturn(new PrintWriter(body));

        filter.doFilter(req, resp, chain);

        verify(resp).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(chain, never()).doFilter(any(), any());
        assertThat(body.toString()).contains("MISSING_TOKEN");
    }

    @Test
    @DisplayName("permits public paths even without a token")
    void permitsPublicPath() throws Exception {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService, true, "");

        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(req.getRequestURI()).thenReturn("/api/auth/login");
        when(req.getHeader("Authorization")).thenReturn(null);

        filter.doFilter(req, resp, chain);

        verify(chain, times(1)).doFilter(req, resp);
        verify(resp, never()).setStatus(401);
    }

    @Test
    @DisplayName("populates request attributes from valid access token")
    void populatesAttributes() throws Exception {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService, true, "");
        String token = jwtService.generateAccessToken("user-1", "alice", "tenant_admin", "superadmin");

        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(req.getRequestURI()).thenReturn("/api/admin/workspace");
        when(req.getHeader("Authorization")).thenReturn("Bearer " + token);

        filter.doFilter(req, resp, chain);

        verify(req).setAttribute(eq(JwtAuthenticationFilter.ATTR_USER_ID), eq("user-1"));
        verify(req).setAttribute(eq(JwtAuthenticationFilter.ATTR_USERNAME), eq("alice"));
        verify(req).setAttribute(eq(JwtAuthenticationFilter.ATTR_ROLES),
                eq(List.of("tenant_admin", "superadmin")));
        verify(chain, times(1)).doFilter(req, resp);
    }

    @Test
    @DisplayName("rejects invalid token signature")
    void rejectsInvalidSignature() throws Exception {
        JwtService otherService = new JwtService("a-different-secret-256-bits-padding-padding-padding");
        String tokenSignedWithOther = otherService.generateAccessToken("user-1", "alice", "tenant_admin");

        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService, true, "");
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(req.getRequestURI()).thenReturn("/api/admin/workspace");
        when(req.getHeader("Authorization")).thenReturn("Bearer " + tokenSignedWithOther);
        when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        filter.doFilter(req, resp, chain);

        verify(resp).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("rejects refresh tokens used as access tokens")
    void rejectsRefreshAsAccess() throws Exception {
        String refresh = jwtService.generateRefreshToken("user-1", "alice");

        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtService, true, "");
        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(req.getRequestURI()).thenReturn("/api/admin/workspace");
        when(req.getHeader("Authorization")).thenReturn("Bearer " + refresh);
        when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        filter.doFilter(req, resp, chain);

        verify(resp).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("honours additional public paths supplied via property")
    void honoursAdditionalPublicPaths() throws Exception {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
                jwtService, true, "/api/public/,/api/probe");

        HttpServletRequest req = mock(HttpServletRequest.class);
        HttpServletResponse resp = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(req.getRequestURI()).thenReturn("/api/public/anything");
        when(req.getHeader("Authorization")).thenReturn(null);

        filter.doFilter(req, resp, chain);

        verify(chain, times(1)).doFilter(req, resp);
        verify(resp, never()).setStatus(401);
    }
}
