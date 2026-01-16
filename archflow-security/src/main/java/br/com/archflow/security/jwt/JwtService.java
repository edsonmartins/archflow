package br.com.archflow.security.jwt;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * JWT Service for creating and validating JSON Web Tokens.
 *
 * Supports:
 * - Access tokens (short-lived, 15 minutes default)
 * - Refresh tokens (long-lived, 7 days default)
 * - Custom claims (userId, username, roles)
 */
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private final SecretKey signingKey;
    private final long accessTokenExpirationMs;
    private final long refreshTokenExpirationMs;
    private final JwtParser jwtParser;

    // Default values
    private static final long DEFAULT_ACCESS_TOKEN_EXPIRATION = 15 * 60 * 1000; // 15 minutes
    private static final long DEFAULT_REFRESH_TOKEN_EXPIRATION = 7 * 24 * 60 * 60 * 1000L; // 7 days

    /**
     * Creates a JwtService with a secret key.
     *
     * @param secretKey The secret key for signing tokens (must be at least 256 bits for HS256)
     */
    public JwtService(String secretKey) {
        this(secretKey, DEFAULT_ACCESS_TOKEN_EXPIRATION, DEFAULT_REFRESH_TOKEN_EXPIRATION);
    }

    /**
     * Creates a JwtService with custom expiration times.
     */
    public JwtService(String secretKey, long accessTokenExpirationMs, long refreshTokenExpirationMs) {
        // Ensure the key is properly encoded for HS256
        byte[] keyBytes = secretKey.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            // Pad the key if it's too short
            byte[] paddedKey = new byte[32];
            System.arraycopy(keyBytes, 0, paddedKey, 0, keyBytes.length);
            keyBytes = paddedKey;
            log.warn("JWT secret key was too short, padded to 256 bits");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.accessTokenExpirationMs = accessTokenExpirationMs;
        this.refreshTokenExpirationMs = refreshTokenExpirationMs;
        this.jwtParser = Jwts.parser().verifyWith(signingKey).build();
    }

    /**
     * Generates a random secret key suitable for HS256.
     */
    public static String generateSecretKey() {
        return Base64.getEncoder().encodeToString(UUID.randomUUID().toString().getBytes());
    }

    // ========== Token Generation ==========

    /**
     * Generates an access token for a user.
     *
     * @param userId The user ID
     * @param username The username
     * @param roles The user's roles
     * @return The JWT access token
     */
    public String generateAccessToken(String userId, String username, String... roles) {
        return generateToken(userId, username, accessTokenExpirationMs, Map.of(
                "type", "access",
                "roles", roles
        ));
    }

    /**
     * Generates a refresh token for a user.
     *
     * @param userId The user ID
     * @param username The username
     * @return The JWT refresh token
     */
    public String generateRefreshToken(String userId, String username) {
        return generateToken(userId, username, refreshTokenExpirationMs, Map.of(
                "type", "refresh"
        ));
    }

    /**
     * Generates a token with custom claims.
     */
    public String generateToken(String userId, String username, long expirationMs, Map<String, Object> claims) {
        Map<String, Object> combinedClaims = new HashMap<>();
        combinedClaims.put("sub", userId);
        combinedClaims.put("username", username);
        combinedClaims.put("jti", UUID.randomUUID().toString());
        combinedClaims.putAll(claims);

        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .claims(combinedClaims)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(signingKey)
                .compact();
    }

    // ========== Token Validation ==========

    /**
     * Validates a token and returns the claims if valid.
     *
     * @param token The JWT token
     * @return The claims if valid
     * @throws JwtException if the token is invalid
     */
    public Claims validateToken(String token) throws JwtException {
        try {
            return jwtParser.parseSignedClaims(token).getPayload();
        } catch (ExpiredJwtException e) {
            throw new JwtException("Token expired", e);
        } catch (MalformedJwtException e) {
            throw new JwtException("Invalid token format", e);
        } catch (SecurityException e) {
            throw new JwtException("Invalid token signature", e);
        } catch (IllegalArgumentException e) {
            throw new JwtException("Token is null or empty", e);
        }
    }

    /**
     * Checks if a token is valid without throwing exceptions.
     */
    public boolean isTokenValid(String token) {
        try {
            validateToken(token);
            return true;
        } catch (JwtException e) {
            return false;
        }
    }

    /**
     * Checks if a token is expired.
     */
    public boolean isTokenExpired(String token) {
        try {
            Date expiration = extractClaim(token, Claims::getExpiration);
            return expiration.before(new Date());
        } catch (JwtException e) {
            return true;
        }
    }

    // ========== Claim Extraction ==========

    /**
     * Extracts the user ID from a token.
     */
    public String extractUserId(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    /**
     * Extracts the username from a token.
     */
    public String extractUsername(String token) {
        return extractClaim(token, claims -> claims.get("username", String.class));
    }

    /**
     * Extracts the token type (access or refresh) from a token.
     */
    public String extractTokenType(String token) {
        return extractClaim(token, claims -> claims.get("type", String.class));
    }

    /**
     * Extracts roles from a token.
     */
    @SuppressWarnings("unchecked")
    public String[] extractRoles(String token) {
        Claims claims = validateToken(token);
        Object roles = claims.get("roles");
        if (roles instanceof String[]) {
            return (String[]) roles;
        }
        if (roles instanceof java.util.Collection) {
            return ((java.util.Collection<?>) roles).toArray(new String[0]);
        }
        return new String[0];
    }

    /**
     * Extracts a specific claim from a token.
     */
    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        Claims claims = validateToken(token);
        return claimsResolver.apply(claims);
    }

    // ========== Token Info ==========

    /**
     * Gets the expiration time from a token.
     */
    public Date getExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    /**
     * Gets the issued-at time from a token.
     */
    public Date getIssuedAt(String token) {
        return extractClaim(token, Claims::getIssuedAt);
    }

    /**
     * Checks if a token is an access token.
     */
    public boolean isAccessToken(String token) {
        return "access".equals(extractTokenType(token));
    }

    /**
     * Checks if a token is a refresh token.
     */
    public boolean isRefreshToken(String token) {
        return "refresh".equals(extractTokenType(token));
    }

    // ========== Getters ==========

    public long getAccessTokenExpirationMs() {
        return accessTokenExpirationMs;
    }

    public long getRefreshTokenExpirationMs() {
        return refreshTokenExpirationMs;
    }

    /**
     * Custom JwtException for clearer error messages.
     */
    public static class JwtException extends RuntimeException {
        public JwtException(String message) {
            super(message);
        }

        public JwtException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
