package br.com.archflow.api.config;

import br.com.archflow.model.security.ApiKey;
import br.com.archflow.model.security.ApiKeyScope;
import br.com.archflow.security.apikey.ApiKeyService;
import br.com.archflow.security.auth.AuthService;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsUtils;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Validates {@code Authorization: Bearer <token>} headers, populates
 * request attributes with the user / role / tenant claims, and rejects
 * requests to protected endpoints when the token is missing or invalid.
 *
 * <p>The bearer credential is either a JWT access token or an API key
 * ({@code ak_<id>.<secret>}, see {@link ApiKeyService#isApiKeyCredential}). A key
 * is validated by {@link ApiKeyService}; its owner and tenant become the request
 * identity and its scopes bound what it may reach ({@link ApiKeyScopeRequirement}).
 *
 * <p>Only requests to a protected API prefix ({@link #PROTECTED_PREFIXES})
 * require a token; the public paths ({@link #DEFAULT_PUBLIC_PATHS}) are carved
 * out even within those prefixes. Everything <em>outside</em> the API prefixes
 * — the bundled SPA's {@code index.html}, its {@code /assets/*} bundle, and its
 * client-side routes ({@code /workflows}, {@code /login}, …) — is served
 * without a token, because the archflow-api process also serves the frontend
 * as static files and a {@code BrowserRouter} SPA needs those paths to reach
 * {@code index.html}. The SPA authenticates by calling {@code /api/*}, which
 * this filter still guards. Protecting the whole path space instead would 401
 * the login screen itself, making the deployment unreachable.
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
    /** Id público da chave de API que autenticou a requisição (ausente quando foi um JWT). */
    public static final String ATTR_API_KEY_ID     = "archflow.apiKeyId";
    /** Escopos ({@code ApiKeyScope}) da chave de API que autenticou a requisição. */
    public static final String ATTR_API_KEY_SCOPES = "archflow.apiKeyScopes";

    private static final List<String> DEFAULT_PUBLIC_PATHS = List.of(
            "/api/auth/login",
            "/api/auth/refresh",
            "/api/auth/logout",
            // Aceite de convite (RCHFLW-85): quem aceita ainda não tem credencial; o token de uso
            // único do link é o que o controller confere.
            "/api/auth/invitations/",
            "/api/health",
            "/actuator/",
            "/realtime/",
            // Handshake do WS de voz: browsers não enviam Authorization em
            // WebSocket — o gate real é a validação do token (query param)
            // no interceptor do WebSocketConfiguration.
            "/api/realtime/",
            "/ws/",
            // Assist (IA síncrona, ADR-0004): protegido por chave estática
            // opcional (X-ArchFlow-Key) no próprio controller, não por JWT.
            "/archflow/assist/",
            // Acionamento de agente vindo do VendaX Core (RFC-007): chamada de
            // máquina, que não tem sessão para renovar um JWT de usuário. O gate
            // é a chave de serviço conferida no próprio controller, que recusa
            // (503) enquanto ela não estiver configurada.
            "/api/agents/invoke"
    );

    /**
     * Prefixos que carregam dados/ações e exigem token (menos os públicos acima).
     * Cobre todos os {@code @RequestMapping} dos controllers da API. Requests
     * fora destes prefixos são tratados como recursos estáticos / rotas do SPA
     * e passam sem token — a autenticação do SPA acontece nas chamadas a estes
     * prefixos. Novos grupos de endpoint que não caiam sob um destes precisam
     * ser adicionados aqui.
     */
    private static final List<String> PROTECTED_PREFIXES = List.of(
            "/api/",
            "/archflow/",
            "/mcp",
            "/ag-ui"
    );

    private final JwtService jwtService;
    private final AuthService authService;
    private final ApiKeyService apiKeyService;
    private final boolean enabled;
    private final Set<String> publicPaths;
    private final ObjectMapper jsonWriter = new ObjectMapper();

    @Autowired
    public JwtAuthenticationFilter(
            JwtService jwtService,
            AuthService authService,
            ApiKeyService apiKeyService,
            @Value("${archflow.security.auth.enabled:false}") boolean enabled,
            @Value("${archflow.security.auth.publicPaths:}") String additionalPublicPaths) {
        this.jwtService = jwtService;
        this.authService = authService;
        this.apiKeyService = apiKeyService;
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

    public JwtAuthenticationFilter(JwtService jwtService, AuthService authService, boolean enabled,
                                   String additionalPublicPaths) {
        this(jwtService, authService, null, enabled, additionalPublicPaths);
    }

    JwtAuthenticationFilter(JwtService jwtService, boolean enabled, String additionalPublicPaths) {
        this(jwtService, null, null, enabled, additionalPublicPaths);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest http) || !(response instanceof HttpServletResponse httpResp)) {
            chain.doFilter(request, response);
            return;
        }

        if (CorsUtils.isPreFlightRequest(http)) {
            // Preflight CORS (OPTIONS + Origin + Access-Control-Request-Method): o navegador nunca
            // manda Authorization nele, então exigir token aqui dava 401 MISSING_TOKEN, sem
            // cabeçalhos CORS, a todo cliente de outra origem — o <archflow-designer> embutido e o
            // Vite com auth ligada não passavam do preflight. Segue sem identidade: o DispatcherServlet
            // responde um preflight com o CorsProcessor (origens de archflow.cors.allowed-origins)
            // e nunca chama o controller, e os interceptors de papel já deixam OPTIONS passar. Só o
            // preflight de verdade: um OPTIONS sem esses cabeçalhos continua exigindo token.
            chain.doFilter(request, response);
            return;
        }

        String path = http.getRequestURI();
        boolean isPublic = isPublicPath(path);

        String authHeader = http.getHeader("Authorization");
        String token = stripBearer(authHeader);

        if (token != null && ApiKeyService.isApiKeyCredential(token)) {
            // O mesmo "Bearer" que o Publicar ensina carrega também chaves de API (RCHFLW-84). Antes
            // toda credencial ia para o JWT e a chave era sempre "token inválido": nenhuma
            // integração autenticava.
            if (!authenticateApiKey(http, httpResp, token, enabled && !isPublic && isProtectedPath(path),
                    isPublic || !isProtectedPath(path))) {
                return;
            }
        } else if (token != null) {
            try {
                Claims claims = jwtService.validateToken(token);
                if (!"access".equals(claims.get("type", String.class))) {
                    if (!enabled || isPublic || !isProtectedPath(path)) {
                        // permissive: just skip populating attributes
                    } else {
                        reject(httpResp, "INVALID_TOKEN_TYPE", "Refresh tokens cannot be used for API calls");
                        return;
                    }
                } else {
                    List<String> currentRoles = null;
                    if (enabled && authService != null) {
                        // Os papéis que valem são os de agora, relidos do usuário — não a claim, que
                        // é a de quando o token foi emitido: um ADMIN rebaixado ou removido seguia
                        // ADMIN até o token expirar. Removido/desativado → exceção → 401 abaixo.
                        currentRoles = List.of(authService.getUserInfoFromToken(token).roles());
                    }
                    populateAttributes(http, claims, currentRoles);
                }
            } catch (RuntimeException e) {
                if (enabled && !isPublic && isProtectedPath(path) && !isTokenRejection(e)) {
                    // O store de revogação ou o de usuários não respondeu: sem resposta não há como
                    // afirmar que o token vale, e a requisição não passa — mas 503, não 401. O SPA
                    // trata 401 como sessão perdida e apaga os tokens: uma oscilação do banco
                    // deslogaria todo mundo e o status culparia a credencial.
                    log.error("Rejected request to {} — authentication store unavailable", path, e);
                    reject(httpResp, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "AUTH_UNAVAILABLE",
                            "Authentication is temporarily unavailable");
                    return;
                }
                if (enabled && !isPublic && isProtectedPath(path)) {
                    log.debug("Rejected request to {} — invalid token: {}", path, e.getMessage());
                    reject(httpResp, "INVALID_TOKEN", "Invalid or expired access token");
                    return;
                }
                // Permissive mode: log and continue without auth attributes.
                log.debug("Ignoring invalid token (auth disabled, public path or static/SPA): {}", e.getMessage());
            }
        } else if (enabled && !isPublic && isProtectedPath(path)) {
            reject(httpResp, "MISSING_TOKEN", "Authorization header is required");
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * Autentica a requisição por uma chave de API e aplica os escopos dela.
     *
     * <p>Válida: dono e tenant da chave viram a identidade da requisição, nos mesmos atributos que
     * um JWT preenche — o {@link ImpersonationFilter} trata o tenant como o de um token (o
     * {@code X-Tenant-Id} só pode repeti-lo) e quem lê o usuário (auditoria) vê o dono. Não há roles:
     * uma chave não é admin de nada. E a chave só alcança o que um escopo dela cobre
     * ({@link ApiKeyScopeRequirement}) <em>e</em> o papel atual do dono ainda concede; fora disso,
     * {@code 403}. O escopo vale mesmo com a autenticação desligada: quem se apresenta como a chave
     * não recebe a identidade dela sem os limites dela.
     *
     * <p>Revogada, expirada, desconhecida, malformada ou com o dono desativado/removido: {@code 401}
     * onde a autenticação é exigida; fora disso (autenticação desligada) segue anônima, como um JWT
     * inválido.
     *
     * @param enforce   a autenticação é exigida neste caminho
     * @param unguarded caminho público ou fora da API (estático/SPA): a chave não é considerada
     * @return {@code false} quando a resposta já foi escrita (recusa)
     */
    private boolean authenticateApiKey(HttpServletRequest http, HttpServletResponse resp, String credential,
                                       boolean enforce, boolean unguarded) throws IOException {
        if (unguarded) {
            return true;
        }
        if (apiKeyService == null) {
            if (enforce) {
                reject(resp, HttpServletResponse.SC_UNAUTHORIZED, "INVALID_API_KEY",
                        "API key authentication is not available on this server");
                return false;
            }
            return true;
        }
        ApiKeyService.AuthenticatedApiKey authenticated;
        try {
            authenticated = apiKeyService.authenticate(credential);
        } catch (ApiKeyService.InvalidApiKeyException e) {
            if (enforce) {
                // Só o motivo; nunca a credencial recebida.
                log.debug("Rejected request to {} — API key {}", http.getRequestURI(), e.reason());
                reject(resp, HttpServletResponse.SC_UNAUTHORIZED, "INVALID_API_KEY", e.getMessage());
                return false;
            }
            log.debug("Ignoring invalid API key (auth disabled): {}", e.reason());
            return true;
        }
        ApiKey apiKey = authenticated.apiKey();
        Optional<ApiKeyScope> required = ApiKeyScopeRequirement.requiredScope(http.getMethod(),
                http.getRequestURI());
        if (required.isEmpty()) {
            reject(resp, HttpServletResponse.SC_FORBIDDEN, "API_KEY_ENDPOINT_NOT_ALLOWED",
                    "API keys cannot access " + http.getMethod() + " " + http.getRequestURI());
            return false;
        }
        if (!apiKey.hasScope(required.get())) {
            reject(resp, HttpServletResponse.SC_FORBIDDEN, "INSUFFICIENT_SCOPE",
                    "This API key lacks the scope " + required.get().name() + " required for "
                            + http.getMethod() + " " + http.getRequestURI());
            return false;
        }
        if (!authenticated.ownerPermits(required.get())) {
            // O escopo foi concedido quando o papel do dono permitia; o papel atual não permite mais.
            reject(resp, HttpServletResponse.SC_FORBIDDEN, "INSUFFICIENT_OWNER_PERMISSION",
                    "The owner of this API key no longer has the permission " + required.get().getPermission()
                            + " required for " + http.getMethod() + " " + http.getRequestURI());
            return false;
        }
        http.setAttribute(ATTR_USER_ID, apiKey.getOwnerId());
        http.setAttribute(ATTR_TENANT, apiKey.getTenantId());
        if (http.getAttribute(ImpersonationFilter.ATTR_TENANT_ID) == null) {
            http.setAttribute(ImpersonationFilter.ATTR_TENANT_ID, apiKey.getTenantId());
        }
        http.setAttribute(ATTR_API_KEY_ID, apiKey.getKeyId());
        http.setAttribute(ATTR_API_KEY_SCOPES, Set.copyOf(apiKey.getScopes()));
        return true;
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

    /**
     * True when the path targets a protected API prefix. Everything else
     * (static assets, SPA client-side routes) is served without a token.
     */
    private boolean isProtectedPath(String path) {
        if (path == null) return false;
        for (String prefix : PROTECTED_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param currentRoles os papéis atuais do usuário, quando conferidos no repositório; {@code null}
     *                     (autenticação desligada, sem {@link AuthService}) usa a claim do token
     */
    private void populateAttributes(HttpServletRequest http, Claims claims, List<String> currentRoles) {
        http.setAttribute(ATTR_USER_ID, claims.getSubject());
        Object username = claims.get("username");
        if (username != null) http.setAttribute(ATTR_USERNAME, String.valueOf(username));
        Object roles = claims.get("roles");
        if (currentRoles != null) {
            http.setAttribute(ATTR_ROLES, currentRoles);
        } else if (roles instanceof java.util.Collection<?> coll) {
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

    /**
     * A exceção diz algo do token (inválido, expirado, revogado, de usuário removido/desativado) — e
     * não que o store de revogação ou o de usuários falhou. Só a primeira vira {@code 401}.
     */
    static boolean isTokenRejection(RuntimeException e) {
        return e instanceof JwtService.JwtException
                || e instanceof AuthService.AuthenticationException
                || e instanceof io.jsonwebtoken.JwtException;
    }

    private void reject(HttpServletResponse resp, String code, String message) throws IOException {
        reject(resp, HttpServletResponse.SC_UNAUTHORIZED, code, message);
    }

    private void reject(HttpServletResponse resp, int status, String code, String message) throws IOException {
        resp.setStatus(status);
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
