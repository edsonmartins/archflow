package br.com.archflow.security.jwt;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for JwtService.
 *
 * <p>Covers token generation, validation, claim extraction,
 * token type classification, and edge cases such as expiry and tampering.</p>
 */
class JwtServiceTest {

    // 32+ chars — valid for HS256 without padding
    private static final String SECRET_KEY = "test-secret-key-minimum-32-chars!";

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        // 15-minute access tokens, 7-day refresh tokens
        jwtService = new JwtService(SECRET_KEY);
    }

    // ========== generateAccessToken ==========

    @Test
    void generateAccessToken_returnsNonNullToken() {
        String token = jwtService.generateAccessToken("user-1", "alice", "ADMIN");

        assertNotNull(token);
        assertFalse(token.isBlank());
    }

    @Test
    void generateAccessToken_embeds_userId_and_username() {
        String token = jwtService.generateAccessToken("user-42", "bob", "VIEWER");

        assertEquals("user-42", jwtService.extractUserId(token));
        assertEquals("bob", jwtService.extractUsername(token));
    }

    @Test
    void generateAccessToken_isAccessToken() {
        String token = jwtService.generateAccessToken("user-1", "alice", "ADMIN");

        assertTrue(jwtService.isAccessToken(token));
        assertFalse(jwtService.isRefreshToken(token));
    }

    // ========== generateRefreshToken ==========

    @Test
    void generateRefreshToken_returnsNonNullToken() {
        String token = jwtService.generateRefreshToken("user-1", "alice");

        assertNotNull(token);
        assertFalse(token.isBlank());
    }

    @Test
    void generateRefreshToken_embeds_userId_and_username() {
        String token = jwtService.generateRefreshToken("user-99", "charlie");

        assertEquals("user-99", jwtService.extractUserId(token));
        assertEquals("charlie", jwtService.extractUsername(token));
    }

    @Test
    void generateRefreshToken_isRefreshToken() {
        String token = jwtService.generateRefreshToken("user-1", "alice");

        assertTrue(jwtService.isRefreshToken(token));
        assertFalse(jwtService.isAccessToken(token));
    }

    // ========== validateToken ==========

    @Test
    void validateToken_returnsClaimsForValidToken() {
        String token = jwtService.generateAccessToken("user-5", "diana", "DESIGNER");

        Claims claims = jwtService.validateToken(token);

        assertNotNull(claims);
        assertEquals("user-5", claims.getSubject());
    }

    @Test
    void validateToken_throwsForExpiredToken() {
        // 1 ms expiration — token will have already expired by the time validate is called
        JwtService shortLivedService = new JwtService(SECRET_KEY, 1L, 1L);
        String token = shortLivedService.generateAccessToken("user-1", "alice");

        // Give the JVM a moment so the 1 ms expiry passes reliably
        try {
            Thread.sleep(10);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        assertThrows(JwtService.JwtException.class, () -> shortLivedService.validateToken(token));
    }

    @Test
    void validateToken_throwsForTamperedToken() {
        String token = jwtService.generateAccessToken("user-1", "alice");
        // Corrupt the signature portion (last segment of the JWT)
        String tampered = token.substring(0, token.length() - 5) + "XXXXX";

        // Fixed: io.jsonwebtoken.security.SecurityException is now caught and wrapped
        var ex = assertThrows(JwtService.JwtException.class, () -> jwtService.validateToken(tampered));
        assertEquals("Invalid token signature", ex.getMessage());
    }

    @Test
    void validateToken_throwsForTokenSignedWithDifferentKey() {
        JwtService otherService = new JwtService("completely-different-key-for-signing!!");
        String token = otherService.generateAccessToken("user-1", "alice");

        // Fixed: signature mismatch is now correctly caught and wrapped in JwtException
        var ex = assertThrows(JwtService.JwtException.class, () -> jwtService.validateToken(token));
        assertEquals("Invalid token signature", ex.getMessage());
    }

    // ========== isTokenValid ==========

    @Test
    void isTokenValid_returnsTrueForValidToken() {
        String token = jwtService.generateAccessToken("user-1", "alice");

        assertTrue(jwtService.isTokenValid(token));
    }

    @Test
    void isTokenValid_returnsFalseForExpiredToken() {
        JwtService shortLivedService = new JwtService(SECRET_KEY, 1L, 1L);
        String token = shortLivedService.generateAccessToken("user-1", "alice");

        try {
            Thread.sleep(10);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        assertFalse(shortLivedService.isTokenValid(token));
    }

    @Test
    void isTokenValid_returnsFalseForTamperedToken() {
        // Fixed: tampered signature is now correctly caught by validateToken,
        // so isTokenValid returns false instead of throwing SignatureException
        String token = jwtService.generateAccessToken("user-1", "alice");
        String tampered = token.substring(0, token.length() - 4) + "ZZZZ";

        assertFalse(jwtService.isTokenValid(tampered));
    }

    // ========== extractUserId / extractUsername ==========

    @Test
    void extractUserId_returnsCorrectId() {
        String token = jwtService.generateAccessToken("user-id-123", "eve");

        assertEquals("user-id-123", jwtService.extractUserId(token));
    }

    @Test
    void extractUsername_returnsCorrectUsername() {
        String token = jwtService.generateAccessToken("user-1", "frank");

        assertEquals("frank", jwtService.extractUsername(token));
    }

    // ========== isAccessToken / isRefreshToken ==========

    @Test
    void isAccessToken_returnsTrueForAccessToken() {
        String access = jwtService.generateAccessToken("user-1", "alice");
        String refresh = jwtService.generateRefreshToken("user-1", "alice");

        assertTrue(jwtService.isAccessToken(access));
        assertFalse(jwtService.isAccessToken(refresh));
    }

    @Test
    void isRefreshToken_returnsTrueForRefreshToken() {
        String access = jwtService.generateAccessToken("user-1", "alice");
        String refresh = jwtService.generateRefreshToken("user-1", "alice");

        assertTrue(jwtService.isRefreshToken(refresh));
        assertFalse(jwtService.isRefreshToken(access));
    }

    // ========== generateToken with custom claims ==========

    @Test
    void generateToken_preservesCustomClaims() {
        Map<String, Object> claims = Map.of(
                "type", "access",
                "customKey", "customValue",
                "numericKey", 42
        );

        String token = jwtService.generateToken("user-1", "grace", 60_000L, claims);

        Claims extracted = jwtService.validateToken(token);
        assertEquals("customValue", extracted.get("customKey", String.class));
        assertEquals(42, extracted.get("numericKey", Integer.class));
    }

    @Test
    void generateToken_includesIssuedAtAndExpiration() {
        // JWT timestamps are stored in whole seconds; allow a 1-second rounding margin.
        long beforeMs = System.currentTimeMillis() - 1_000L;
        String token = jwtService.generateToken("user-1", "hank", 60_000L, Map.of("type", "access"));
        long afterMs = System.currentTimeMillis() + 1_000L;

        Date issuedAt = jwtService.getIssuedAt(token);
        Date expiration = jwtService.getExpiration(token);

        assertNotNull(issuedAt);
        assertNotNull(expiration);
        // issuedAt should fall within the measured window (±1 s for JWT second-rounding)
        assertTrue(issuedAt.getTime() >= beforeMs,
                "issuedAt " + issuedAt.getTime() + " should be >= " + beforeMs);
        assertTrue(issuedAt.getTime() <= afterMs,
                "issuedAt " + issuedAt.getTime() + " should be <= " + afterMs);
        // expiration = issuedAt + 60 s (±1 s rounding)
        long diff = expiration.getTime() - issuedAt.getTime();
        assertTrue(diff >= 59_000L && diff <= 61_000L,
                "expiration diff should be ~60s but was " + diff + " ms");
    }

    // ========== Constructor edge case ==========

    @Test
    void constructor_withShortSecretKey_doesNotThrow() {
        // Key shorter than 32 chars — must be padded by the service
        assertDoesNotThrow(() -> new JwtService("short"));
    }

    @Test
    void constructor_withShortSecretKey_producesWorkingService() {
        JwtService service = new JwtService("short");
        String token = service.generateAccessToken("user-1", "ivy");

        assertTrue(service.isTokenValid(token));
        assertEquals("user-1", service.extractUserId(token));
    }

    // ========== generateSecretKey ==========

    @Test
    void generateSecretKey_returnsNonNullNonBlankString() {
        String key = JwtService.generateSecretKey();

        assertNotNull(key);
        assertFalse(key.isBlank());
    }

    @Test
    void generateSecretKey_returnsDifferentValuesEachCall() {
        String key1 = JwtService.generateSecretKey();
        String key2 = JwtService.generateSecretKey();

        assertNotEquals(key1, key2);
    }
}
