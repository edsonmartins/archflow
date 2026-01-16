package br.com.archflow.security.auth;

import br.com.archflow.model.security.User;
import br.com.archflow.security.jwt.JwtService;
import br.com.archflow.security.password.PasswordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 * AuthService handles user authentication and token management.
 *
 * This service provides:
 * - User authentication (login)
 * - Token generation (access and refresh tokens)
 * - Token validation and refresh
 * - Password management
 */
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final JwtService jwtService;
    private final PasswordService passwordService;
    private final UserRepository userRepository;

    /**
     * Creates an AuthService.
     *
     * @param jwtService The JWT service for token operations
     * @param passwordService The password service for hashing/verification
     * @param userRepository The user repository for user lookups
     */
    public AuthService(JwtService jwtService, PasswordService passwordService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.passwordService = passwordService;
        this.userRepository = userRepository;
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

        String accessToken = jwtService.generateAccessToken(user.getId(), user.getUsername(), roles);
        String refreshToken = jwtService.generateRefreshToken(user.getId(), user.getUsername());

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
     * @param refreshToken The refresh token
     * @return A new AuthenticationResult with a new access token
     * @throws AuthenticationException if refresh fails
     */
    public AuthenticationResult refreshToken(String refreshToken) {
        log.debug("Attempting to refresh token");

        // Validate refresh token
        try {
            String userId = jwtService.extractUserId(refreshToken);

            if (!jwtService.isRefreshToken(refreshToken)) {
                throw new AuthenticationException("Invalid token type");
            }

            // Check if token is expired
            if (jwtService.isTokenExpired(refreshToken)) {
                throw new AuthenticationException("Refresh token expired");
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

            String newAccessToken = jwtService.generateAccessToken(user.getId(), user.getUsername(), roles);
            String newRefreshToken = jwtService.generateRefreshToken(user.getId(), user.getUsername());

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
     * @param accessToken The access token
     * @return The user ID if valid
     * @throws AuthenticationException if token is invalid
     */
    public String validateAccessToken(String accessToken) {
        try {
            if (!jwtService.isAccessToken(accessToken)) {
                throw new AuthenticationException("Invalid token type");
            }

            if (jwtService.isTokenExpired(accessToken)) {
                throw new AuthenticationException("Token expired");
            }

            return jwtService.extractUserId(accessToken);

        } catch (JwtService.JwtException e) {
            throw new AuthenticationException("Invalid access token", e);
        }
    }

    /**
     * Extracts user info from a valid access token.
     *
     * @param accessToken The access token
     * @return UserInfo extracted from the token
     * @throws AuthenticationException if token is invalid
     */
    public UserInfo getUserInfoFromToken(String accessToken) {
        String userId = validateAccessToken(accessToken);
        String username = jwtService.extractUsername(accessToken);
        String[] roles = jwtService.extractRoles(accessToken);

        // Get additional user info from repository
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthenticationException("User not found"));

        return new UserInfo(
                userId,
                username,
                user.getEmail(),
                roles,
                user.isEnabled(),
                user.getCreatedAt() != null ? user.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant() : null,
                user.getLastLoginAt() != null ? user.getLastLoginAt().atZone(java.time.ZoneId.systemDefault()).toInstant() : null
        );
    }

    /**
     * Logs out a user by invalidating their access token.
     *
     * <p>Note: For JWT-based stateless authentication, tokens cannot be directly invalidated.
     * This method is a placeholder for future token blacklist functionality.
     * Clients should discard their tokens on logout.</p>
     *
     * @param accessToken The access token to invalidate
     */
    public void logout(String accessToken) {
        // Validate token first
        String userId = validateAccessToken(accessToken);
        String username = jwtService.extractUsername(accessToken);

        log.info("User {} logged out (user ID: {})", username, userId);

        // In a full implementation, this would add the token to a blacklist
        // For now, tokens remain valid until they expire (stateless JWT)
        // Token blacklist can be implemented using Redis or similar
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
