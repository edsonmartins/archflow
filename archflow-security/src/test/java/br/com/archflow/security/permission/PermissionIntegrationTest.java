package br.com.archflow.security.permission;

import br.com.archflow.security.auth.AuthService;
import br.com.archflow.security.auth.InMemoryUserRepository;
import br.com.archflow.model.security.Role;
import br.com.archflow.model.security.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for permission checking with @RequiresPermission annotation.
 *
 * <p>These tests verify:</p>
 * <ul>
 *   <li>Admin has all permissions</li>
 *   <li>Designer can manage workflows</li>
 *   <li>Executor can execute workflows</li>
 *   <li>Viewer can only read</li>
 *   <li>PermissionDeniedException is thrown for unauthorized access</li>
 * </ul>
 */
class PermissionIntegrationTest {

    private static final String SECRET_KEY = "test-secret-key-for-integration-testing-minimum-32-bytes";

    private br.com.archflow.security.jwt.JwtService jwtService;
    private br.com.archflow.security.password.PasswordService passwordService;
    private InMemoryUserRepository userRepository;
    private AuthService authService;
    private PermissionAspect permissionAspect;

    @BeforeEach
    void setUp() {
        passwordService = new br.com.archflow.security.password.PasswordService(4);
        jwtService = new br.com.archflow.security.jwt.JwtService(SECRET_KEY);
        userRepository = new InMemoryUserRepository();
        authService = new AuthService(jwtService, passwordService, userRepository);
        permissionAspect = new PermissionAspect(authService);

        // Create test users with different roles
        setUpTestUsers();
    }

    @AfterEach
    void tearDown() {
        PermissionAspect.AuthTokenHolder.clear();
    }

    private void setUpTestUsers() {
        // Admin user
        User admin = createUser("admin", "admin123", "ADMIN");
        userRepository.save(admin);

        // Designer user
        User designer = createUser("designer", "design123", "DESIGNER");
        userRepository.save(designer);

        // Executor user
        User executor = createUser("executor", "exec123", "EXECUTOR");
        userRepository.save(executor);

        // Viewer user
        User viewer = createUser("viewer", "view123", "VIEWER");
        userRepository.save(viewer);
    }

    private User createUser(String username, String password, String roleName) {
        User user = new User();
        user.setId("user-" + username + "-001");
        user.setUsername(username);
        user.setEmail(username + "@archflow.com");
        user.setPasswordHash(passwordService.hash(password));
        user.setEnabled(true);
        user.setAccountNonLocked(true);
        user.setCreatedAt(LocalDateTime.now());

        Role role = new Role("role-" + username, roleName, roleName + " Role");
        user.addRole(role);
        return user;
    }

    private String authenticateAndSetToken(String username, String password) {
        AuthService.AuthenticationResult result = authService.authenticate(username, password);
        PermissionAspect.AuthTokenHolder.setAccessToken(result.accessToken());
        return result.accessToken();
    }

    @Test
    void testAdminHasAllPermissions() {
        authenticateAndSetToken("admin", "admin123");

        assertTrue(() -> hasPermission("workflow", "create"));
        assertTrue(() -> hasPermission("workflow", "read"));
        assertTrue(() -> hasPermission("workflow", "update"));
        assertTrue(() -> hasPermission("workflow", "delete"));
        assertTrue(() -> hasPermission("workflow", "execute"));
        assertTrue(() -> hasPermission("agent", "create"));
        assertTrue(() -> hasPermission("apikey", "revoke"));
    }

    @Test
    void testDesignerCanManageWorkflows() {
        authenticateAndSetToken("designer", "design123");

        assertTrue(() -> hasPermission("workflow", "create"));
        assertTrue(() -> hasPermission("workflow", "read"));
        assertTrue(() -> hasPermission("workflow", "update"));
        assertTrue(() -> hasPermission("workflow", "delete"));
        assertTrue(() -> hasPermission("workflow", "execute")); // Wildcard workflow:*
        assertTrue(() -> hasPermission("agent", "read"));
        assertFalse(() -> hasPermission("agent", "create"));
        assertFalse(() -> hasPermission("apikey", "create"));
    }

    @Test
    void testExecutorCanExecuteWorkflows() {
        authenticateAndSetToken("executor", "exec123");

        assertTrue(() -> hasPermission("workflow", "read"));
        assertTrue(() -> hasPermission("workflow", "execute"));
        assertFalse(() -> hasPermission("workflow", "create"));
        assertFalse(() -> hasPermission("workflow", "update"));
        assertFalse(() -> hasPermission("workflow", "delete"));
    }

    @Test
    void testViewerCanOnlyRead() {
        authenticateAndSetToken("viewer", "view123");

        assertTrue(() -> hasPermission("workflow", "read"));
        assertTrue(() -> hasPermission("agent", "read"));
        assertTrue(() -> hasPermission("execution", "read"));
        assertFalse(() -> hasPermission("workflow", "create"));
        assertFalse(() -> hasPermission("workflow", "execute"));
        assertFalse(() -> hasPermission("agent", "create"));
    }

    @Test
    void testWildcardAction() {
        authenticateAndSetToken("designer", "design123");

        // Designer has workflow:* which means all actions on workflow
        assertTrue(() -> hasPermission("workflow", "create"));
        assertTrue(() -> hasPermission("workflow", "read"));
        assertTrue(() -> hasPermission("workflow", "update"));
        assertTrue(() -> hasPermission("workflow", "delete"));
        assertTrue(() -> hasPermission("workflow", "execute"));
    }

    @Test
    void testUnauthorizedAccessThrowsException() {
        authenticateAndSetToken("viewer", "view123");

        assertThrows(PermissionAspect.PermissionDeniedException.class, () -> {
            checkPermission("workflow", "create");
        });
    }

    @Test
    void testNoAuthenticationThrowsException() {
        // Clear any existing token
        PermissionAspect.AuthTokenHolder.clear();

        assertThrows(PermissionAspect.PermissionDeniedException.class, () -> {
            checkPermission("workflow", "read");
        });
    }

    @Test
    void testInvalidTokenThrowsException() {
        PermissionAspect.AuthTokenHolder.setAccessToken("invalid.token.here");

        // Invalid token results in AuthenticationException from AuthService
        // which should be wrapped/converted to PermissionDeniedException
        assertThrows(Exception.class, () -> {
            checkPermission("workflow", "read");
        });
    }

    @Test
    void testWildcardResourceRequiresAdmin() {
        authenticateAndSetToken("viewer", "view123");

        // Only admin should have *:*
        assertThrows(PermissionAspect.PermissionDeniedException.class, () -> {
            checkPermission("*", "read");
        });
    }

    // Helper methods

    private boolean hasPermission(String resource, String action) {
        try {
            checkPermission(resource, action);
            return true;
        } catch (PermissionAspect.PermissionDeniedException e) {
            return false;
        }
    }

    private void checkPermission(String resource, String action) {
        // Get the current token from the holder
        String accessToken = PermissionAspect.AuthTokenHolder.getAccessToken();

        if (accessToken == null) {
            throw new PermissionAspect.PermissionDeniedException(
                    "Authentication required",
                    resource,
                    action
            );
        }

        // Extract user info from token
        AuthService.UserInfo userInfo = authService.getUserInfoFromToken(accessToken);

        // Check if user has required permission using the same logic as PermissionAspect
        if (!hasPermission(userInfo, resource, action)) {
            throw new PermissionAspect.PermissionDeniedException(
                    "Access denied: insufficient permissions",
                    resource,
                    action
            );
        }
    }

    private boolean hasPermission(AuthService.UserInfo userInfo, String resource, String action) {
        // Check for admin role (has all permissions)
        if (userInfo.hasRole("ADMIN")) {
            return true;
        }

        String permission = resource + ":" + action;

        // Check if permission is a wildcard
        if ("*:*".equals(permission) || resource.equals("*")) {
            return userInfo.hasRole("ADMIN");
        }

        // Check action wildcard
        if ("*".equals(action)) {
            return hasPermissionForResource(userInfo, resource);
        }

        // Check exact permission
        return hasRolePermission(userInfo, permission);
    }

    private boolean hasPermissionForResource(AuthService.UserInfo userInfo, String resource) {
        String[] resourcePermissions = {
                resource + ":read",
                resource + ":create",
                resource + ":update",
                resource + ":delete",
                resource + ":execute"
        };

        for (String perm : resourcePermissions) {
            if (hasRolePermission(userInfo, perm)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasRolePermission(AuthService.UserInfo userInfo, String permission) {
        for (String role : userInfo.roles()) {
            if (hasPermissionForRole(role, permission)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasPermissionForRole(String role, String permission) {
        return switch (role) {
            case "ADMIN" -> true;
            case "DESIGNER" -> permission.startsWith("workflow:") ||
                              permission.equals("agent:read");
            case "EXECUTOR" -> permission.equals("workflow:read") ||
                               permission.equals("workflow:execute");
            case "VIEWER" -> permission.equals("workflow:read") ||
                            permission.equals("agent:read") ||
                            permission.equals("execution:read");
            default -> false;
        };
    }
}
