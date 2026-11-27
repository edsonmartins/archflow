package br.com.archflow.api.config;

import br.com.archflow.api.admin.impl.TenantControllerImpl;
import br.com.archflow.api.realtime.SpringRealtimeController;
import br.com.archflow.model.security.ApiKeyScope;
import br.com.archflow.security.auth.AuthService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Predicate;
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
    /** {@code true} para o id de um tenant suspenso no cadastro — lido a cada handshake. */
    private final Predicate<String> tenantSuspended;
    /**
     * De quanto em quanto tempo, no máximo, uma sessão aberta é reconferida (RCHFLW-197). A conferência
     * roda na mensagem que chegar depois do intervalo: o Voice Playground manda ~190 mensagens de áudio
     * por segundo, e reconferir cada uma seriam centenas de consultas ao banco por segundo por usuário.
     */
    private final Duration reauthInterval;
    private final Clock clock;

    /** Atributo da sessão com a identidade conferida no handshake (só com a autenticação ligada). */
    static final String IDENTITY_ATTRIBUTE = WebSocketConfiguration.class.getName() + ".identity";

    // SpringRealtimeController only exists when a RealtimeAdapter is configured
    // (e.g. the dev profile); a hard constructor dependency would fail the boot
    // of every profile without one, including prod.
    @org.springframework.beans.factory.annotation.Autowired
    public WebSocketConfiguration(
            org.springframework.beans.factory.ObjectProvider<SpringRealtimeController> realtimeController,
            org.springframework.beans.factory.ObjectProvider<br.com.archflow.security.jwt.JwtService> jwtService,
            org.springframework.beans.factory.ObjectProvider<AuthService> authService,
            org.springframework.beans.factory.ObjectProvider<TenantControllerImpl> tenants,
            @org.springframework.beans.factory.annotation.Value(
                    "${archflow.cors.allowed-origins:http://localhost:5173,http://localhost:5174}")
            String[] allowedOrigins,
            @org.springframework.beans.factory.annotation.Value(
                    "${archflow.security.auth.enabled:false}") boolean authEnabled,
            @org.springframework.beans.factory.annotation.Value(
                    "${archflow.admin.impersonation.enabled:false}") boolean impersonationEnabled,
            @org.springframework.beans.factory.annotation.Value(
                    "${archflow.realtime.reauth-interval:PT5S}") Duration reauthInterval) {
        this(realtimeController.getIfAvailable(), jwtService.getIfAvailable(), authService.getIfAvailable(),
                allowedOrigins, authEnabled, impersonationEnabled, tenantId -> {
                    // O mesmo cadastro que o TenantSuspensionInterceptor lê: suspender e reativar valem na hora.
                    TenantControllerImpl cadastro = tenants.getIfAvailable();
                    return cadastro != null && cadastro.isSuspended(tenantId);
                }, reauthInterval, Clock.systemUTC());
    }

    WebSocketConfiguration(SpringRealtimeController realtimeController,
                           br.com.archflow.security.jwt.JwtService jwtService, AuthService authService,
                           String[] allowedOrigins, boolean authEnabled, boolean impersonationEnabled,
                           Predicate<String> tenantSuspended) {
        this(realtimeController, jwtService, authService, allowedOrigins, authEnabled, impersonationEnabled,
                tenantSuspended, Duration.ofSeconds(5), Clock.systemUTC());
    }

    WebSocketConfiguration(SpringRealtimeController realtimeController,
                           br.com.archflow.security.jwt.JwtService jwtService, AuthService authService,
                           String[] allowedOrigins, boolean authEnabled, boolean impersonationEnabled,
                           Predicate<String> tenantSuspended, Duration reauthInterval, Clock clock) {
        this.realtimeController = realtimeController;
        this.jwtService = jwtService;
        this.authService = authService;
        this.allowedOrigins = allowedOrigins;
        this.authEnabled = authEnabled;
        this.impersonationEnabled = impersonationEnabled;
        this.tenantSuspended = tenantSuspended;
        this.reauthInterval = reauthInterval;
        this.clock = clock;
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

    WebSocketHandler delegatingHandler() {
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
                org.springframework.http.HttpStatus refusal = reauthorize(session.getAttributes());
                if (refusal != null) {
                    log.warn("Closing realtime session {} — open session no longer authorized ({})",
                            session.getId(), refusal.value());
                    session.close(refusal == org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE
                            ? CloseStatus.SERVICE_OVERLOAD.withReason("AUTH_UNAVAILABLE")
                            : CloseStatus.POLICY_VIOLATION.withReason(
                                    refusal == org.springframework.http.HttpStatus.UNAUTHORIZED
                                            ? "SESSION_REVOKED" : "FORBIDDEN"));
                    return;
                }
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
                    org.springframework.http.HttpStatus refusal = authorizeHandshake(token, tenantId, attributes);
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
     *   <li>os papéis atuais concedem {@code workflow:execute}: a sessão de voz põe o agente da persona
     *       para responder no tenant, a mesma ação do {@code POST /ag-ui/agent}, que o
     *       {@link WorkflowPermissionInterceptor} recusa ao VIEWER. O handshake não passa pelos
     *       interceptors do MVC, então sem esta conferência o VIEWER conversava com o agente por aqui.</li>
     *   <li>o tenant da identidade não está suspenso, como o {@link TenantSuspensionInterceptor} exige
     *       de toda requisição REST — pelo mesmo motivo, ele não alcança o handshake.</li>
     * </ul>
     *
     * @return {@code null} quando aceito; senão o status da recusa (401 sem identidade válida, 403
     *         tenant de outro, papel sem {@code workflow:execute} ou tenant suspenso, 503 store de
     *         revogação/usuários sem resposta)
     */
    org.springframework.http.HttpStatus authorizeHandshake(String token, String pathTenant,
                                                           Map<String, Object> attributes) {
        if (jwtService == null || authService == null || token == null || token.isBlank()) {
            return org.springframework.http.HttpStatus.UNAUTHORIZED;
        }
        AuthService.UserInfo principal;
        String tokenTenant;
        String sessionId;
        try {
            principal = authService.getUserInfoFromToken(token);
            tokenTenant = jwtService.extractClaim(token, claims -> claims.get("tenantId", String.class));
            sessionId = jwtService.extractSessionId(token);
        } catch (RuntimeException e) {
            return refusalOf(e, "handshake");
        }
        String expected = tokenTenant == null || tokenTenant.isBlank()
                ? TenantContext.DEFAULT_TENANT : tokenTenant.trim();
        org.springframework.http.HttpStatus refusal = authorize(principal, expected, pathTenant);
        if (refusal == null) {
            if (!expected.equals(pathTenant)) {
                log.info("Tenant impersonation (realtime): tenantId={} userId={}", pathTenant, principal.userId());
            }
            attributes.put(IDENTITY_ATTRIBUTE,
                    new Identity(principal.userId(), sessionId, token, expected, pathTenant, clock.instant()));
        }
        return refusal;
    }

    /**
     * Reconfere uma sessão aberta (RCHFLW-197): o handshake era a única conferência, e uma sessão de voz
     * aberta seguia conversando com o agente depois do logout, do rebaixamento para VIEWER ou da suspensão
     * do tenant — o REST confere isso a cada requisição. Aqui vale a mesma regra do handshake, com a
     * sessão no lugar do token: {@link AuthService#getUserInfoOfOpenSession} (revogação, usuário ativo,
     * papéis atuais — sem a expiração, que o cliente não renova num socket aberto) e
     * {@link #authorize} (tenant suspenso, {@code workflow:execute}, tenant do caminho). No máximo uma vez
     * por {@code archflow.realtime.reauth-interval}.
     *
     * @return {@code null} quando a sessão segue autorizada (ou a autenticação está desligada); senão o
     *         status da recusa, com o mesmo sentido do handshake
     */
    org.springframework.http.HttpStatus reauthorize(Map<String, Object> attributes) {
        if (!(attributes.get(IDENTITY_ATTRIBUTE) instanceof Identity identity)) {
            // Sem identidade só quando a autenticação está desligada: o handshake não abre a sessão sem ela.
            return authEnabled ? org.springframework.http.HttpStatus.UNAUTHORIZED : null;
        }
        Instant now = clock.instant();
        if (now.isBefore(identity.lastCheck().plus(reauthInterval))) {
            return null;
        }
        AuthService.UserInfo principal;
        try {
            principal = authService.getUserInfoOfOpenSession(identity.userId(), identity.sessionId(),
                    identity.token());
        } catch (RuntimeException e) {
            return refusalOf(e, "open session");
        }
        org.springframework.http.HttpStatus refusal =
                authorize(principal, identity.identityTenant(), identity.pathTenant());
        if (refusal == null) {
            attributes.put(IDENTITY_ATTRIBUTE, identity.checkedAt(now));
        }
        return refusal;
    }

    /** A identidade aceita no handshake e o instante da última conferência dela. */
    record Identity(String userId, String sessionId, String token, String identityTenant, String pathTenant,
                    Instant lastCheck) {
        Identity checkedAt(Instant instant) {
            return new Identity(userId, sessionId, token, identityTenant, pathTenant, instant);
        }

        /** Sem o token: um log da identidade não pode levar a credencial junto. */
        @Override
        public String toString() {
            return "Identity[userId=" + userId + ", identityTenant=" + identityTenant
                    + ", pathTenant=" + pathTenant + ", lastCheck=" + lastCheck + "]";
        }
    }

    private static org.springframework.http.HttpStatus refusalOf(RuntimeException e, String what) {
        if (!JwtAuthenticationFilter.isTokenRejection(e)) {
            // Store de revogação/usuários fora do ar: recusa, mas sem culpar o token (como o filtro).
            log.error("Realtime {} refused — authentication store unavailable", what, e);
            return org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;
        }
        log.debug("Realtime {} refused: {}", what, e.getMessage());
        return org.springframework.http.HttpStatus.UNAUTHORIZED;
    }

    /** O que o handshake e a reconferência exigem dos papéis e do tenant atuais da identidade. */
    private org.springframework.http.HttpStatus authorize(AuthService.UserInfo principal, String expected,
                                                          String pathTenant) {
        // Como no REST: o tenant conferido é o da identidade, não o do caminho — o superadmin que
        // personifica um tenant suspenso ainda precisa entrar nele para reativá-lo.
        if (tenantSuspended != null && tenantSuspended.test(expected)) {
            log.warn("Rejecting realtime session — tenant {} is suspended", expected);
            return org.springframework.http.HttpStatus.FORBIDDEN;
        }
        String execute = ApiKeyScope.WORKFLOW_EXECUTE.getPermission();
        if (!WorkflowPermissionInterceptor.permits(principal.roleSet(), execute)) {
            log.warn("Rejecting realtime session — roles {} do not grant {}", principal.roleSet(), execute);
            return org.springframework.http.HttpStatus.FORBIDDEN;
        }
        if (expected.equals(pathTenant)) {
            return null;
        }
        if (impersonationEnabled && ImpersonationFilter.rolesMayImpersonate(principal.roleSet())) {
            return null;
        }
        log.warn("Rejecting realtime session for another tenant: path={} token={}", pathTenant, expected);
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
