package br.com.archflow.security.auth;

import br.com.archflow.model.security.Role;
import br.com.archflow.model.security.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for authentication flow.
 *
 * <p>These tests verify:</p>
 * <ul>
 *   <li>User login with correct credentials</li>
 *   <li>User login with incorrect credentials</li>
 *   <li>Token generation and validation</li>
 *   <li>Token refresh flow</li>
 *   <li>User info extraction from token</li>
 * </ul>
 */
class AuthenticationIntegrationTest {

    private static final String SECRET_KEY = "test-secret-key-for-integration-testing-minimum-32-bytes";

    private AuthService authService;
    private InMemoryUserRepository userRepository;
    private br.com.archflow.security.jwt.JwtService jwtService;
    private br.com.archflow.security.password.PasswordService passwordService;

    @BeforeEach
    void setUp() {
        passwordService = new br.com.archflow.security.password.PasswordService(4); // Lower cost for tests
        jwtService = new br.com.archflow.security.jwt.JwtService(SECRET_KEY);
        userRepository = new InMemoryUserRepository();
        authService = new AuthService(jwtService, passwordService, userRepository);

        // Create test users
        setUpTestUsers();
    }

    private void setUpTestUsers() {
        // Admin user
        User admin = new User();
        admin.setId("user-admin-001");
        admin.setUsername("admin");
        admin.setEmail("admin@archflow.com");
        admin.setPasswordHash(passwordService.hash("admin123"));
        admin.setEnabled(true);
        admin.setAccountNonLocked(true);
        admin.setCreatedAt(LocalDateTime.now());

        Role adminRole = new Role("role-admin", "ADMIN", "Administrator");
        admin.addRole(adminRole);
        userRepository.save(admin);

        // Regular user
        User user = new User();
        user.setId("user-regular-001");
        user.setUsername("user1");
        user.setEmail("user1@archflow.com");
        user.setPasswordHash(passwordService.hash("user123"));
        user.setEnabled(true);
        user.setAccountNonLocked(true);
        user.setCreatedAt(LocalDateTime.now());

        Role userRole = new Role("role-user", "VIEWER", "Viewer");
        user.addRole(userRole);
        userRepository.save(user);
    }

    @Test
    void testSuccessfulLogin() {
        AuthService.AuthenticationResult result = authService.authenticate("admin", "admin123");

        assertNotNull(result);
        assertNotNull(result.accessToken());
        assertNotNull(result.refreshToken());
        assertEquals("admin", result.username());
        assertEquals("admin@archflow.com", result.email());
        assertTrue(result.expiresIn() > 0);
        assertArrayEquals(new String[]{"ADMIN"}, result.roles());
    }

    @Test
    void testLoginWithWrongPassword() {
        assertThrows(AuthService.AuthenticationException.class, () -> {
            authService.authenticate("admin", "wrongpassword");
        });
    }

    @Test
    void testLoginWithNonExistentUser() {
        assertThrows(AuthService.AuthenticationException.class, () -> {
            authService.authenticate("nonexistent", "password");
        });
    }

    @Test
    void testLoginWithDisabledAccount() {
        User disabledUser = new User();
        disabledUser.setId("user-disabled-001");
        disabledUser.setUsername("disabled");
        disabledUser.setEmail("disabled@archflow.com");
        disabledUser.setPasswordHash(passwordService.hash("password123"));
        disabledUser.setEnabled(false);
        disabledUser.setAccountNonLocked(true);
        userRepository.save(disabledUser);

        assertThrows(AuthService.AuthenticationException.class, () -> {
            authService.authenticate("disabled", "password123");
        });
    }

    @Test
    void testTokenValidation() {
        AuthService.AuthenticationResult result = authService.authenticate("admin", "admin123");

        String userId = authService.validateAccessToken(result.accessToken());

        assertEquals("user-admin-001", userId);
    }

    @Test
    void testInvalidToken() {
        assertThrows(AuthService.AuthenticationException.class, () -> {
            authService.validateAccessToken("invalid.token.here");
        });
    }

    @Test
    void testUserInfoExtraction() {
        AuthService.AuthenticationResult result = authService.authenticate("user1", "user123");

        AuthService.UserInfo userInfo = authService.getUserInfoFromToken(result.accessToken());

        assertEquals("user-regular-001", userInfo.userId());
        assertEquals("user1", userInfo.username());
        assertEquals("user1@archflow.com", userInfo.email());
        assertTrue(userInfo.enabled());
        assertTrue(userInfo.hasRole("VIEWER"));
        assertFalse(userInfo.hasRole("ADMIN"));
    }

    @Test
    void testTokenRefresh() {
        AuthService.AuthenticationResult result = authService.authenticate("admin", "admin123");

        AuthService.AuthenticationResult refreshed = authService.refreshToken(result.refreshToken());

        assertNotNull(refreshed);
        assertNotNull(refreshed.accessToken());
        assertNotNull(refreshed.refreshToken());
        assertEquals("admin", refreshed.username());
        // New access token should be different
        assertNotEquals(result.accessToken(), refreshed.accessToken());
    }

    @Test
    void testRefreshWithInvalidToken() {
        assertThrows(AuthService.AuthenticationException.class, () -> {
            authService.refreshToken("invalid.refresh.token");
        });
    }

    @Test
    void testLogout() {
        AuthService.AuthenticationResult result = authService.authenticate("admin", "admin123");

        // Logout should not throw exception
        assertDoesNotThrow(() -> authService.logout(result.accessToken()));

        // Token should still be valid after logout (stateless JWT)
        // In production, you'd implement a token blacklist
        String userId = authService.validateAccessToken(result.accessToken());
        assertEquals("user-admin-001", userId);
    }

    @Test
    void testPasswordHashing() {
        String plainPassword = "MySecurePassword123!";
        String hash = passwordService.hash(plainPassword);

        assertNotNull(hash);
        assertNotEquals(plainPassword, hash);
        assertTrue(passwordService.verify(plainPassword, hash));
        assertFalse(passwordService.verify("wrongpassword", hash));
    }

    @Test
    void testMultipleRoles() {
        User multiRoleUser = new User();
        multiRoleUser.setId("user-multi-001");
        multiRoleUser.setUsername("multirole");
        multiRoleUser.setEmail("multirole@archflow.com");
        multiRoleUser.setPasswordHash(passwordService.hash("password123"));
        multiRoleUser.setEnabled(true);
        multiRoleUser.setAccountNonLocked(true);

        Role designerRole = new Role("role-designer", "DESIGNER", "Designer");
        Role viewerRole = new Role("role-viewer", "VIEWER", "Viewer");
        multiRoleUser.addRole(designerRole);
        multiRoleUser.addRole(viewerRole);
        userRepository.save(multiRoleUser);

        AuthService.AuthenticationResult result = authService.authenticate("multirole", "password123");

        assertEquals(2, result.roles().length);
        assertTrue(Set.of(result.roles()).contains("DESIGNER"));
        assertTrue(Set.of(result.roles()).contains("VIEWER"));

        AuthService.UserInfo userInfo = authService.getUserInfoFromToken(result.accessToken());
        assertTrue(userInfo.hasRole("DESIGNER"));
        assertTrue(userInfo.hasRole("VIEWER"));
    }
}
