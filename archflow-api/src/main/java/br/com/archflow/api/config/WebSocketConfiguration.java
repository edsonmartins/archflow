package br.com.archflow.api.config;

import br.com.archflow.api.realtime.SpringRealtimeController;
import br.com.archflow.security.auth.AuthService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Registers the realtime voice WebSocket endpoint used by the frontend.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfiguration implements WebSocketConfigurer {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WebSocketConfiguration.class);

    /** Strict match on the registered pattern; rejects trailing slashes and extra segments. */
    private static final Pattern REALTIME_PATH = Pattern.compile(
            "^/api/realtime/([^/]+)/([^/]+)$");

    private final SpringRealtimeController realtimeController;
    private final String[] allowedOrigins;
    private final br.com.archflow.security.jwt.JwtService jwtService;
    private final AuthService authService;
    private final boolean authEnabled;
    private final boolean impersonationEnabled;

    // SpringRealtimeController only exists when a RealtimeAdapter is configured
    // (e.g. the dev profile); a hard constructor dependency would fail the boot
    // of every profile without one, including prod.
    @org.springframework.beans.factory.annotation.Autowired
    public WebSocketConfiguration(
            org.springframework.beans.factory.ObjectProvider<SpringRealtimeController> realtimeController,
            org.springframework.beans.factory.ObjectProvider<br.com.archflow.security.jwt.JwtService> jwtService,
            org.springframework.beans.factory.ObjectProvider<AuthService> authService,
            @org.springframework.beans.factory.annotation.Value(
                    "${archflow.cors.allowed-origins:http://localhost:5173,http://localhost:5174}")
            String[] allowedOrigins,
            @org.springframework.beans.factory.annotation.Value(
                    "${archflow.security.auth.enabled:false}") boolean authEnabled,
            @org.springframework.beans.factory.annotation.Value(
                    "${archflow.admin.impersonation.enabled:false}") boolean impersonationEnabled) {
        this(realtimeController.getIfAvailable(), jwtService.getIfAvailable(), authService.getIfAvailable(),
                allowedOrigins, authEnabled, impersonationEnabled);
    }

    WebSocketConfiguration(SpringRealtimeController realtimeController,
                           br.com.archflow.security.jwt.JwtService jwtService, AuthService authService,
                           String[] allowedOrigins, boolean authEnabled, boolean impersonationEnabled) {
        this.realtimeController = realtimeController;
        this.jwtService = jwtService;
        this.authService = authService;
        this.allowedOrigins = allowedOrigins;
        this.authEnabled = authEnabled;
        this.impersonationEnabled = impersonationEnabled;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        if (realtimeController == null) {
            log.info("No RealtimeAdapter configured; realtime WebSocket endpoint not registered");
            return;
        }
        // Mesma property de origins do CORS HTTP (fase 5.7) — antes era "*",
        // que aceitava handshake de qualquer site.
        registry.addHandler(delegatingHandler(), "/api/realtime/{tenantId}/{personaId}")
                .addInterceptors(pathVariablesInterceptor())
                .setAllowedOrigins(allowedOrigins);
    }

    private WebSocketHandler delegatingHandler() {
        return new org.springframework.web.socket.handler.AbstractWebSocketHandler() {
            @Override
            public void afterConnectionEstablished(org.springframework.web.socket.WebSocketSession session) throws Exception {
                try {
                    WebSocketHandler delegate = realtimeController.handleUpgrade(
                            path(session, "tenantId"), path(session, "personaId"));
                    session.getAttributes().put(SpringRealtimeController.class.getName(), delegate);
                    delegate.afterConnectionEstablished(session);
                } catch (Exception e) {
                    log.warn("Realtime handler initialization failed; closing session {}",
                            session.getId(), e);
                    try {
                        session.close(CloseStatus.SERVER_ERROR
                                .withReason(trimCloseReason(e.getMessage())));
                    } catch (Exception closeErr) {
                        log.debug("Secondary error closing failed session", closeErr);
                    }
                    throw e;
                }
            }

            @Override
            protected void handleTextMessage(org.springframework.web.socket.WebSocketSession session,
                                             org.springframework.web.socket.TextMessage message) throws Exception {
                handler(session).handleMessage(session, message);
            }

            @Override
            public void afterConnectionClosed(org.springframework.web.socket.WebSocketSession session,
                                              org.springframework.web.socket.CloseStatus status) throws Exception {
                // If afterConnectionEstablished failed, no delegate was stored.
                // Silently skip cleanup in that case — nothing to release.
                Object delegate = session.getAttributes().get(SpringRealtimeController.class.getName());
                if (delegate instanceof WebSocketHandler webSocketHandler) {
                    webSocketHandler.afterConnectionClosed(session, status);
                }
            }

            @Override
            public void handleTransportError(org.springframework.web.socket.WebSocketSession session,
                                             Throwable exception) throws Exception {
                Object delegate = session.getAttributes().get(SpringRealtimeController.class.getName());
                if (delegate instanceof WebSocketHandler webSocketHandler) {
                    webSocketHandler.handleTransportError(session, exception);
                } else {
                    log.debug("Transport error on session {} with no delegate yet",
                            session.getId(), exception);
                }
            }
        };
    }

    HandshakeInterceptor pathVariablesInterceptor() {
        return new HandshakeInterceptor() {
            @Override
            public boolean beforeHandshake(org.springframework.http.server.ServerHttpRequest request,
                                           org.springframework.http.server.ServerHttpResponse response,
                                           WebSocketHandler wsHandler,
                                           java.util.Map<String, Object> attributes) {
                String path = request.getURI().getPath();
                Matcher m = REALTIME_PATH.matcher(path);
                if (!m.matches()) {
                    log.warn("Rejecting realtime handshake with unexpected path: {}", path);
                    response.setStatusCode(org.springframework.http.HttpStatus.BAD_REQUEST);
                    return false;
                }
                String tenantId = m.group(1);
                String personaId = m.group(2);
                if (tenantId.isBlank() || personaId.isBlank()) {
                    log.warn("Rejecting realtime handshake with blank tenant/persona: {}", path);
                    response.setStatusCode(org.springframework.http.HttpStatus.BAD_REQUEST);
                    return false;
                }
                // Auth do handshake: browsers não enviam Authorization em WS —
                // o token JWT vem no query param `token` (o path está fora do
                // filtro JWT justamente por isso).
                if (authEnabled) {
                    String token = org.springframework.web.util.UriComponentsBuilder
                            .fromUri(request.getURI()).build()
                            .getQueryParams().getFirst("token");
                    org.springframework.http.HttpStatus refusal = authorizeHandshake(token, tenantId);
                    if (refusal != null) {
                        log.warn("Rejecting realtime handshake ({}): {}", refusal.value(), path);
                        response.setStatusCode(refusal);
                        return false;
                    }
                }
                attributes.put("tenantId", tenantId);
                attributes.put("personaId", personaId);
                return true;
            }

            @Override
            public void afterHandshake(org.springframework.http.server.ServerHttpRequest request,
                                       org.springframework.http.server.ServerHttpResponse response,
                                       WebSocketHandler wsHandler,
                                       Exception exception) {
                // No-op.
            }
        };
    }

    /**
     * A mesma conferência que o {@link JwtAuthenticationFilter} faz numa chamada REST, com o tenant do
     * caminho no lugar do header. Antes só a assinatura e a validade do JWT eram conferidas: um
     * refresh token, um access token já revogado pelo logout e o token de outro tenant abriam a
     * sessão — e o tenant do caminho, escolhido pelo cliente, era o que seguia para o adapter.
     *
     * <ul>
     *   <li>{@link AuthService#getUserInfoFromToken}: só access token (refresh recusado), não
     *       revogado, usuário ainda ativo, com os papéis atuais;</li>
     *   <li>o tenant do caminho é o do token (sem a claim, o padrão), como o {@link ImpersonationFilter}
     *       exige do {@code X-Tenant-Id}. Outro tenant só para quem poderia personificá-lo por REST
     *       ({@code archflow.admin.impersonation.enabled} e papel admin) — o caminho faz aqui o papel
     *       do {@code X-Impersonate-Tenant}, que um WebSocket de browser não tem como mandar.</li>
     * </ul>
     *
     * @return {@code null} quando aceito; senão o status da recusa (401 sem identidade válida, 403
     *         tenant de outro, 503 store de revogação/usuários sem resposta)
     */
    org.springframework.http.HttpStatus authorizeHandshake(String token, String pathTenant) {
        if (jwtService == null || authService == null || token == null || token.isBlank()) {
            return org.springframework.http.HttpStatus.UNAUTHORIZED;
        }
        AuthService.UserInfo principal;
        String tokenTenant;
        try {
            principal = authService.getUserInfoFromToken(token);
            tokenTenant = jwtService.extractClaim(token, claims -> claims.get("tenantId", String.class));
        } catch (RuntimeException e) {
            if (!JwtAuthenticationFilter.isTokenRejection(e)) {
                // Store de revogação/usuários fora do ar: recusa, mas sem culpar o token (como o filtro).
                log.error("Realtime handshake refused — authentication store unavailable", e);
                return org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;
            }
            log.debug("Realtime handshake token refused: {}", e.getMessage());
            return org.springframework.http.HttpStatus.UNAUTHORIZED;
        }
        String expected = tokenTenant == null || tokenTenant.isBlank()
                ? TenantContext.DEFAULT_TENANT : tokenTenant.trim();
        if (expected.equals(pathTenant)) {
            return null;
        }
        if (impersonationEnabled && ImpersonationFilter.rolesMayImpersonate(principal.roleSet())) {
            log.info("Tenant impersonation (realtime): tenantId={} userId={}", pathTenant, principal.userId());
            return null;
        }
        log.warn("Rejecting realtime handshake for another tenant: path={} token={}", pathTenant, expected);
        return org.springframework.http.HttpStatus.FORBIDDEN;
    }

    private static WebSocketHandler handler(org.springframework.web.socket.WebSocketSession session) {
        Object handler = session.getAttributes().get(SpringRealtimeController.class.getName());
        if (handler instanceof WebSocketHandler webSocketHandler) {
            return webSocketHandler;
        }
        throw new IllegalStateException("missing realtime delegate");
    }

    private static String path(org.springframework.web.socket.WebSocketSession session, String name) {
        Object value = session.getAttributes().get(name);
        if (value instanceof String s && !s.isBlank()) {
            return s;
        }
        throw new IllegalStateException("missing path variable: " + name);
    }

    /** CloseStatus reasons are capped at ~123 bytes; truncate defensively. */
    private static String trimCloseReason(String raw) {
        if (raw == null) return "";
        return raw.length() > 100 ? raw.substring(0, 100) : raw;
    }
}
