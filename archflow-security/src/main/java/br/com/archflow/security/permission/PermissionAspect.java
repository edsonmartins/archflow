package br.com.archflow.security.permission;

import br.com.archflow.security.auth.AuthService;
import br.com.archflow.security.auth.AuthService.UserInfo;
import br.com.archflow.security.auth.AuthService.AuthenticationException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * Aspect for enforcing permission requirements based on {@link RequiresPermission} annotations.
 *
 * This AspectJ aspect intercepts methods annotated with @RequiresPermission and checks
 * if the current authenticated user has the required permissions before allowing execution.
 *
 * Works with JWT tokens from AuthService to extract user information and permissions.
 */
@Aspect
public class PermissionAspect {

    private static final Logger log = LoggerFactory.getLogger(PermissionAspect.class);

    private final AuthService authService;

    /**
     * Creates a new PermissionAspect.
     *
     * @param authService The auth service for token validation and user info extraction
     */
    public PermissionAspect(AuthService authService) {
        this.authService = authService;
    }

    /**
     * Around advice for methods annotated with @RequiresPermission.
     *
     * Checks if the current user has the required permissions before executing the method.
     *
     * @param joinPoint The join point representing the method execution
     * @param requiresPermission The permission annotation
     * @return The result of the method execution
     * @throws Throwable if permission check fails or method execution throws
     */
    @Around("@annotation(requiresPermission)")
    public Object checkPermission(ProceedingJoinPoint joinPoint, RequiresPermission requiresPermission)
            throws Throwable {

        // Get the current authentication token from ThreadLocal or context
        String accessToken = getCurrentAccessToken();

        if (accessToken == null) {
            log.warn("No authentication token found for protected resource");
            throw new PermissionDeniedException(
                    "Authentication required",
                    requiresPermission.resource(),
                    requiresPermission.action()
            );
        }

        try {
            // Extract user info from token
            UserInfo userInfo = authService.getUserInfoFromToken(accessToken);

            // Check if user has required permission
            if (!hasPermission(userInfo, requiresPermission.resource(), requiresPermission.action())) {
                log.warn("Permission denied for user {} on {}:{}",
                        userInfo.getUsername(), requiresPermission.resource(), requiresPermission.action());
                throw new PermissionDeniedException(
                        requiresPermission.message(),
                        requiresPermission.resource(),
                        requiresPermission.action()
                );
            }

            log.debug("Permission granted for user {} on {}:{}",
                    userInfo.getUsername(), requiresPermission.resource(), requiresPermission.action());

            // Permission check passed, proceed with method execution
            return joinPoint.proceed();

        } catch (AuthService.AuthenticationException e) {
            log.warn("Authentication failed during permission check: {}", e.getMessage());
            throw new PermissionDeniedException(
                    "Invalid authentication",
                    requiresPermission.resource(),
                    requiresPermission.action()
            );
        }
    }

    /**
     * Gets the current access token from the security context.
     *
     * This method should be overridden or configured based on the actual
     * security context implementation (e.g., Spring Security's SecurityContextHolder).
     *
     * @return The current access token, or null if not authenticated
     */
    protected String getCurrentAccessToken() {
        // Try to get from ThreadLocal first (custom implementation)
        String token = AuthTokenHolder.getAccessToken();
        if (token != null) {
            return token;
        }

        // Try to get from Spring Security context if available (using reflection)
        try {
            Class<?> securityContextHolderClass = Class.forName("org.springframework.security.core.context.SecurityContextHolder");
            Object context = securityContextHolderClass.getMethod("getContext").invoke(null);
            if (context != null) {
                Object authentication = context.getClass().getMethod("getAuthentication").invoke(context);
                if (authentication != null) {
                    Object principal = authentication.getClass().getMethod("getPrincipal").invoke(authentication);
                    if (principal instanceof String) {
                        return (String) principal;
                    }
                }
            }
        } catch (ClassNotFoundException e) {
            // Spring Security not available
            log.debug("Spring Security not available, skipping token extraction from SecurityContextHolder");
        } catch (Exception e) {
            log.trace("Exception while trying to extract token from Spring Security: {}", e.getMessage());
        }

        return null;
    }

    /**
     * Checks if a user has a specific permission.
     *
     * @param userInfo The user info containing roles
     * @param resource The resource being accessed
     * @param action The action being performed
     * @return true if the user has permission
     */
    private boolean hasPermission(UserInfo userInfo, String resource, String action) {
        // Check for admin role (has all permissions)
        if (userInfo.hasRole("ADMIN")) {
            return true;
        }

        // Extract role-based permissions
        // In a real implementation, this would query a PermissionService
        // that maps roles to permissions. For now, we check common patterns.

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
        // This is a simplified check - real implementation would use a PermissionService
        return hasRolePermission(userInfo, permission);
    }

    /**
     * Checks if user has any permission on a resource.
     */
    private boolean hasPermissionForResource(UserInfo userInfo, String resource) {
        // Common permission patterns
        String[] resourcePermissions = {
                resource + ":read",
                resource + ":create",
                resource + ":update",
                resource + ":delete",
                resource + ":execute"
        };

        return Arrays.stream(resourcePermissions)
                .anyMatch(perm -> hasRolePermission(userInfo, perm));
    }

    /**
     * Checks if user has permission based on role.
     *
     * Role-based permission mapping:
     * - ADMIN: All permissions
     * - DESIGNER: workflow:*, agent:read
     * - EXECUTOR: workflow:read, workflow:execute
     * - VIEWER: workflow:read, agent:read, execution:read
     */
    private boolean hasRolePermission(UserInfo userInfo, String permission) {
        for (String role : userInfo.getRoles()) {
            if (hasPermissionForRole(role, permission)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if a role has a specific permission.
     */
    private boolean hasPermissionForRole(String role, String permission) {
        return switch (role) {
            case "ADMIN" -> true; // Admin has all permissions
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

    /**
     * ThreadLocal holder for authentication tokens.
     */
    public static class AuthTokenHolder {

        private static final ThreadLocal<String> TOKEN_HOLDER = new ThreadLocal<>();

        public static void setAccessToken(String token) {
            TOKEN_HOLDER.set(token);
        }

        public static String getAccessToken() {
            return TOKEN_HOLDER.get();
        }

        public static void clear() {
            TOKEN_HOLDER.remove();
        }
    }

    /**
     * Exception thrown when permission check fails.
     */
    public static class PermissionDeniedException extends RuntimeException {
        private final String resource;
        private final String action;

        public PermissionDeniedException(String message, String resource, String action) {
            super(message);
            this.resource = resource;
            this.action = action;
        }

        public String getResource() {
            return resource;
        }

        public String getAction() {
            return action;
        }
    }
}
