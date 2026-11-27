package br.com.archflow.security.auth;

import br.com.archflow.model.security.User;
import br.com.archflow.security.jwt.JwtService;
import br.com.archflow.security.password.PasswordService;
import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;

/**
 * AuthService handles user authentication and token management.
 *
 * This service provides:
 * - User authentication (login)
 * - Token generation (access and refresh tokens)
 * - Token validation and refresh
 * - Password management
 *
 * <p>Sessão: o login abre uma sessão (claim {@link JwtService#SESSION_CLAIM}) que o access e o refresh
 * token compartilham, e que a rotação do refresh mantém. O logout revoga a sessão — o refresh token
 * dela para junto com o access, embora o cliente só apresente o access. O refresh token é de uso
 * único: cada refresh o consome, e apresentá-lo de novo é tratado como roubo e encerra a sessão.
 *
 * <p>Papéis: o access token carrega os papéis de quando foi emitido, mas quem autoriza
 * ({@link #getUserInfoFromToken}) relê o usuário a cada chamada — rebaixado, desativado ou removido,
 * o efeito é imediato, e não só quando o token expirar.
 */
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final JwtService jwtService;
    private final PasswordService passwordService;
    private final UserRepository userRepository;
    private final TokenBlacklist tokenBlacklist;

    /**
     * Creates an AuthService with an in-memory token blacklist (suitable for
     * single-instance deployments; clusters should inject a shared one).
     *
     * @param jwtService The JWT service for token operations
     * @param passwordService The password service for hashing/verification
     * @param userRepository The user repository for user lookups
     */
    public AuthService(JwtService jwtService, PasswordService passwordService, UserRepository userRepository) {
        this(jwtService, passwordService, userRepository, new InMemoryTokenBlacklist());
    }

    /**
     * Creates an AuthService with an explicit token blacklist implementation.
     */
    public AuthService(JwtService jwtService, PasswordService passwordService,
                       UserRepository userRepository, TokenBlacklist tokenBlacklist) {
        this.jwtService = jwtService;
        this.passwordService = passwordService;
        this.userRepository = userRepository;
        this.tokenBlacklist = tokenBlacklist;
    }

    /**
     * Authenticates a user with username and password.
     *
     * @param username The username
     * @param password The plain text password
     * @return AuthenticationResult containing tokens and user info
     * @throws AuthenticationException if authentication fails
     */
    public AuthenticationResult authenticate(String username, String password) {
        log.info("Authentication attempt for user: {}", username);

        // Find user
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new AuthenticationException("Invalid username or password"));

        // Convidado que ainda não aceitou: a mensagem diz o que fazer, em vez de "conta desativada".
        if (user.isInvitationPending()) {
            throw new AuthenticationException(
                    "Invitation not accepted yet: open the invitation link to set a password");
        }

        // Check if user is enabled
        if (!user.isEnabled()) {
            throw new AuthenticationException("User account is disabled");
        }

        // Check if account is locked
        if (!user.isAccountNonLocked()) {
            throw new AuthenticationException("User account is locked");
        }

        // Check if credentials are expired
        if (!user.isCredentialsNonExpired()) {
            throw new AuthenticationException("User credentials have expired");
        }

        // Verify password
        if (!passwordService.verify(password, user.getPasswordHash())) {
            throw new AuthenticationException("Invalid username or password");
        }

        // Update last login
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);

        // Generate tokens
        String[] roles = user.getRoles().stream()
                .map(role -> role.getName())
                .toArray(String[]::new);

        // O tenant do usuário vai no token: é o que o ImpersonationFilter lê como tenant da
        // requisição. Sem ele, um usuário convidado no workspace de um tenant entraria no padrão.
        String sessionId = UUID.randomUUID().toString();
        String accessToken = jwtService.generateTenantAccessToken(
                user.getId(), user.getUsername(), user.getTenantId(), roles, sessionId);
        String refreshToken = jwtService.generateRefreshToken(user.getId(), user.getUsername(), sessionId);

        log.info("User authenticated successfully: {}", username);

        return new AuthenticationResult(
                accessToken,
                refreshToken,
                user.getId(),
                user.getUsername(),
                user.getFullName(),
                user.getEmail(),
                roles,
                jwtService.getAccessTokenExpirationMs()
        );
    }

    /**
     * Refreshes an access token using a refresh token.
     *
     * <p>O refresh token apresentado é consumido (rotação): o par novo substitui o antigo, que deixa de
     * valer. Um refresh token já consumido que volta é sinal de que duas partes o têm — o cliente
     * legítimo e quem o copiou —, e não há como saber qual das duas apresentou agora; por isso a
     * sessão inteira é revogada (OAuth 2.0 Security BCP, §4.14.2), e não só esta tentativa recusada.
     *
     * @param refreshToken The refresh token
     * @return A new AuthenticationResult with a new access token
     * @throws AuthenticationException if refresh fails
     */
    public AuthenticationResult refreshToken(String refreshToken) {
        log.debug("Attempting to refresh token");

        // Validate refresh token
        try {
            // Assinatura, formato e expiração: um token expirado nem chega ao consumo abaixo.
            Claims claims = jwtService.validateToken(refreshToken);
            String userId = claims.getSubject();

            if (!"refresh".equals(claims.get("type", String.class))) {
                throw new AuthenticationException("Invalid token type");
            }

            // Refresh token de antes da claim de sessão: recusado, e o usuário entra de novo. Aceitá-lo
            // deixava o logout sem alcance sobre ele — o logout de um access token legado revoga só
            // aquele access, e o refresh legado ainda renovava num par novo, por até 7 dias depois do
            // deploy. O custo é um login a mais por sessão aberta no deploy, quando o access dela vence.
            String sessionId = claims.get(JwtService.SESSION_CLAIM, String.class);
            if (sessionId == null || sessionId.isBlank()) {
                throw new AuthenticationException("Refresh token predates login sessions — log in again");
            }
            if (tokenBlacklist.isRevoked(sessionKey(sessionId))) {
                throw new AuthenticationException("Session has been revoked");
            }

            // Consumo atômico: entre dois refreshes simultâneos do mesmo token, só um passa. Sem janela
            // de tolerância: uma resposta de refresh que o cliente não chegou a guardar (aba fechada
            // ou recarregada com o refresh em voo, resposta perdida) deixa o cliente com o token
            // consumido, e o próximo refresh dele encerra a sessão — volta ao login. Uma janela de
            // reuso aceitaria, durante ela, a cópia roubada também.
            if (!tokenBlacklist.revokeIfAbsent(refreshToken, claims.getExpiration().toInstant())) {
                revokeSession(sessionId);
                log.warn("Refresh token reused for user ID {} — session {} revoked", userId, sessionId);
                throw new AuthenticationException("Refresh token already used");
            }

            // Get user
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new AuthenticationException("User not found"));

            // Check if user is still enabled
            if (!user.isEnabled()) {
                throw new AuthenticationException("User account is disabled");
            }

            // Generate new tokens
            String[] roles = user.getRoles().stream()
                    .map(role -> role.getName())
                    .toArray(String[]::new);

            // A rotação continua a mesma sessão: o logout precisa alcançar o par novo.
            String newAccessToken = jwtService.generateTenantAccessToken(
                    user.getId(), user.getUsername(), user.getTenantId(), roles, sessionId);
            String newRefreshToken = jwtService.generateRefreshToken(
                    user.getId(), user.getUsername(), sessionId);

            log.info("Token refreshed for user: {}", user.getUsername());

            return new AuthenticationResult(
                    newAccessToken,
                    newRefreshToken,
                    user.getId(),
                    user.getUsername(),
                    user.getFullName(),
                    user.getEmail(),
                    roles,
                    jwtService.getAccessTokenExpirationMs()
            );

        } catch (JwtService.JwtException e) {
            throw new AuthenticationException("Invalid refresh token", e);
        }
    }

    /**
     * Validates an access token and returns the user ID.
     *
     * <p>Confere só o token (tipo, expiração, revogação). Para autorizar, use
     * {@link #getUserInfoFromToken}, que também confere o estado atual do usuário.
     *
     * @param accessToken The access token
     * @return The user ID if valid
     * @throws AuthenticationException if token is invalid
     */
    public String validateAccessToken(String accessToken) {
        try {
            Claims claims = jwtService.validateToken(accessToken);
            if (!"access".equals(claims.get("type", String.class))) {
                throw new AuthenticationException("Invalid token type");
            }

            if (claims.getExpiration().before(new java.util.Date())) {
                throw new AuthenticationException("Token expired");
            }

            // Token com sessão: o logout revoga a sessão. Token de antes da claim: o logout revogou o
            // próprio token. Uma consulta ao store por requisição, nos dois casos.
            String sessionId = claims.get(JwtService.SESSION_CLAIM, String.class);
            boolean revoked = sessionId != null
                    ? tokenBlacklist.isRevoked(sessionKey(sessionId))
                    : tokenBlacklist.isRevoked(accessToken);
            if (revoked) {
                throw new AuthenticationException("Token has been revoked");
            }

            return claims.getSubject();

        } catch (JwtService.JwtException e) {
            throw new AuthenticationException("Invalid access token", e);
        }
    }

    /**
     * Extracts user info from a valid access token.
     *
     * <p>Os papéis são os <b>atuais</b> do usuário, relidos do repositório — não os da claim
     * {@code roles}, que são os de quando o token foi emitido. Até aqui o filtro autorizava pela
     * claim: um ADMIN rebaixado ou removido seguia ADMIN até o token expirar (15 min), enquanto o
     * {@code /me} do mesmo token já respondia outra coisa. Usuário removido, desativado ou bloqueado:
     * o token deixa de valer na hora, como o refresh dele já deixava.
     *
     * @param accessToken The access token
     * @return UserInfo extracted from the token
     * @throws AuthenticationException if token is invalid
     */
    public UserInfo getUserInfoFromToken(String accessToken) {
        String userId = validateAccessToken(accessToken);
        return currentUser(userId, jwtService.extractUsername(accessToken));
    }

    /**
     * Reconfere uma sessão já aberta com {@code accessToken} — um WebSocket, que só apresenta o token no
     * handshake (RCHFLW-197). Confere o que o {@link #getUserInfoFromToken} confere a cada requisição REST
     * — sessão (ou o próprio token, se for de antes da claim) não revogada pelo logout, usuário ainda
     * ativo e os papéis atuais — <b>exceto a expiração</b>: o cliente não renova o token de um socket
     * aberto, e conferi-la derrubaria toda sessão de voz 15 min depois do login.
     *
     * @param userId      o usuário do handshake
     * @param sessionId   a claim de sessão do token do handshake, ou {@code null} num token de antes dela
     * @param accessToken o token do handshake (revogado pelo próprio texto quando não há sessão)
     * @return os dados atuais do usuário
     * @throws AuthenticationException sessão revogada, usuário removido, desativado ou bloqueado
     */
    public UserInfo getUserInfoOfOpenSession(String userId, String sessionId, String accessToken) {
        boolean revoked = sessionId != null
                ? tokenBlacklist.isRevoked(sessionKey(sessionId))
                : tokenBlacklist.isRevoked(accessToken);
        if (revoked) {
            throw new AuthenticationException("Session has been revoked");
        }
        return currentUser(userId, null);
    }

    /** O usuário como está no repositório agora; {@code username} nulo usa o do cadastro. */
    private UserInfo currentUser(String userId, String username) {
        // Get additional user info from repository
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthenticationException("User not found"));
        if (!user.isEnabled()) {
            throw new AuthenticationException("User account is disabled");
        }
        if (!user.isAccountNonLocked()) {
            throw new AuthenticationException("User account is locked");
        }
        String[] roles = user.getRoles().stream()
                .map(role -> role.getName())
                .toArray(String[]::new);

        return new UserInfo(
                userId,
                username != null ? username : user.getUsername(),
                user.getEmail(),
                roles,
                user.isEnabled(),
                user.getCreatedAt() != null ? user.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant() : null,
                user.getLastLoginAt() != null ? user.getLastLoginAt().atZone(java.time.ZoneId.systemDefault()).toInstant() : null
        );
    }

    /**
     * Logs out a user, ending the login session the access token belongs to.
     *
     * <p>A sessão inteira é revogada: o access token apresentado, os outros access da mesma sessão e
     * o refresh token dela — antes só o access era revogado, e o refresh token (7 dias) seguia
     * emitindo access novos depois do logout. O SPA manda só o access token; a claim de sessão é o
     * que liga os dois. Outras sessões do mesmo usuário (outro navegador) não são afetadas.
     *
     * <p>Token de antes da claim de sessão: só ele é revogado, como antes.
     *
     * @param accessToken The access token to invalidate
     */
    public void logout(String accessToken) {
        // Validate token first
        String userId = validateAccessToken(accessToken);
        String username = jwtService.extractUsername(accessToken);
        String sessionId = jwtService.extractSessionId(accessToken);

        if (sessionId != null) {
            Instant until = revokeSession(sessionId);
            log.info("User {} logged out — session revoked until {} (user ID: {})", username, until, userId);
            return;
        }
        Instant expiresAt = jwtService.validateToken(accessToken).getExpiration().toInstant();
        tokenBlacklist.revoke(accessToken, expiresAt);

        log.info("User {} logged out — token revoked until {} (user ID: {})",
                username, expiresAt, userId);
    }

    /**
     * Revoga a sessão até o último instante em que um token dela ainda poderia valer: cada rotação
     * emite um refresh token com validade cheia, então o mais novo vence no máximo daqui a um
     * período de refresh.
     */
    private Instant revokeSession(String sessionId) {
        Instant until = Instant.now().plusMillis(jwtService.getRefreshTokenExpirationMs());
        tokenBlacklist.revoke(sessionKey(sessionId), until);
        return until;
    }

    /**
     * Chave da sessão no {@link TokenBlacklist}. O prefixo separa o espaço das sessões do dos tokens
     * (que são revogados pelo próprio texto do JWT), no mesmo store.
     */
    private static String sessionKey(String sessionId) {
        return "session:" + sessionId;
    }

    /**
     * Hashes a plain text password.
     *
     * @param plainPassword The plain text password
     * @return The hashed password
     */
    public String hashPassword(String plainPassword) {
        return passwordService.hash(plainPassword);
    }

    /**
     * Result of a successful authentication.
     */
    public static class AuthenticationResult {
        private final String accessToken;
        private final String refreshToken;
        private final String userId;
        private final String username;
        private final String fullName;
        private final String email;
        private final String[] roles;
        private final long expiresIn;

        public AuthenticationResult(String accessToken, String refreshToken,
                                   String userId, String username, String fullName, String email,
                                   String[] roles, long expiresIn) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.userId = userId;
            this.username = username;
            this.fullName = fullName;
            this.email = email;
            this.roles = roles;
            this.expiresIn = expiresIn;
        }

        // Record-style accessors
        public String accessToken() {
            return accessToken;
        }

        public String refreshToken() {
            return refreshToken;
        }

        public String userId() {
            return userId;
        }

        public String username() {
            return username;
        }

        public String fullName() {
            return fullName;
        }

        public String email() {
            return email;
        }

        public String[] roles() {
            return roles;
        }

        public long expiresIn() {
            return expiresIn;
        }

        // Calculate expiresAt from expiresIn
        public java.time.Instant expiresAt() {
            return java.time.Instant.now().plusMillis(expiresIn);
        }

        // Backwards compatibility getters
        public String getAccessToken() {
            return accessToken;
        }

        public String getRefreshToken() {
            return refreshToken;
        }

        public String getUserId() {
            return userId;
        }

        public String getUsername() {
            return username;
        }

        public String getFullName() {
            return fullName;
        }

        public String getEmail() {
            return email;
        }

        public String[] getRoles() {
            return roles;
        }

        public long getExpiresIn() {
            return expiresIn;
        }
    }

    /**
     * User info extracted from a token.
     */
    public static class UserInfo {
        private final String userId;
        private final String username;
        private final String email;
        private final String[] roles;
        private final boolean enabled;
        private final Instant createdAt;
        private final Instant lastLoginAt;

        public UserInfo(String userId, String username, String email, String[] roles,
                       boolean enabled, Instant createdAt, Instant lastLoginAt) {
            this.userId = userId;
            this.username = username;
            this.email = email;
            this.roles = roles;
            this.enabled = enabled;
            this.createdAt = createdAt;
            this.lastLoginAt = lastLoginAt;
        }

        // Record-style accessors
        public String userId() {
            return userId;
        }

        public String username() {
            return username;
        }

        public String email() {
            return email;
        }

        public String[] roles() {
            return roles;
        }

        public Set<String> roleSet() {
            return new HashSet<>(java.util.Arrays.asList(roles));
        }

        public boolean enabled() {
            return enabled;
        }

        public Instant createdAt() {
            return createdAt;
        }

        public Instant lastLoginAt() {
            return lastLoginAt;
        }

        public boolean hasRole(String role) {
            for (String r : roles) {
                if (r.equals(role)) {
                    return true;
                }
            }
            return false;
        }

        // Backwards compatibility getters
        public String getUserId() {
            return userId;
        }

        public String getUsername() {
            return username;
        }

        public String getEmail() {
            return email;
        }

        public String[] getRoles() {
            return roles;
        }
    }

    /**
     * Exception thrown when authentication fails.
     */
    public static class AuthenticationException extends RuntimeException {
        public AuthenticationException(String message) {
            super(message);
        }

        public AuthenticationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
