package br.com.archflow.marketplace.security;

import br.com.archflow.marketplace.manifest.ExtensionManifest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * Validates extension permissions against a configurable allow/deny policy.
 *
 * <p>Checks requested permissions for dangerous operations and enforces
 * that only explicitly allowed permissions are granted.</p>
 */
public class PermissionValidator {

    private static final Logger log = LoggerFactory.getLogger(PermissionValidator.class);

    /**
     * Permissions considered dangerous and requiring explicit approval.
     */
    public static final Set<String> DANGEROUS_PERMISSIONS = Set.of(
            "SYSTEM_EXEC",
            "FILE_WRITE_ROOT",
            "NETWORK_UNRESTRICTED",
            "exec:*",
            "file:*",
            "network:*"
    );

    private final Set<String> allowedPermissions;
    private boolean denyDangerousPermissions;

    public PermissionValidator() {
        this.allowedPermissions = new HashSet<>();
        this.denyDangerousPermissions = true;
    }

    /**
     * Adds a permission to the allowed set.
     *
     * @param permission The permission to allow
     */
    public void addAllowedPermission(String permission) {
        allowedPermissions.add(permission);
    }

    /**
     * Sets whether dangerous permissions should be automatically denied.
     *
     * @param deny true to deny dangerous permissions, false to allow them
     */
    public void setDenyDangerousPermissions(boolean deny) {
        this.denyDangerousPermissions = deny;
    }

    /**
     * Validates the permissions requested by an extension manifest.
     *
     * @param manifest The extension manifest to validate
     * @return ValidationResult indicating success or failure with details
     */
    public ValidationResult validate(ExtensionManifest manifest) {
        Set<String> requestedPermissions = manifest.getPermissions();

        if (requestedPermissions == null || requestedPermissions.isEmpty()) {
            return ValidationResult.success("No permissions requested");
        }

        // Check for dangerous permissions
        if (denyDangerousPermissions) {
            for (String permission : requestedPermissions) {
                if (DANGEROUS_PERMISSIONS.contains(permission)) {
                    log.warn("Extension {} requests dangerous permission: {}",
                            manifest.getId(), permission);
                    return ValidationResult.failure(
                            "Dangerous permission denied: " + permission);
                }
            }
        }

        // If allowed permissions are configured, check against them
        if (!allowedPermissions.isEmpty()) {
            for (String permission : requestedPermissions) {
                if (!isPermissionAllowed(permission)) {
                    log.warn("Extension {} requests non-allowed permission: {}",
                            manifest.getId(), permission);
                    return ValidationResult.failure(
                            "Permission not allowed: " + permission);
                }
            }
        }

        log.debug("All permissions validated for extension {}", manifest.getId());
        return ValidationResult.success("All permissions validated");
    }

    /**
     * Checks if a specific permission is allowed, considering wildcard patterns.
     */
    private boolean isPermissionAllowed(String permission) {
        if (allowedPermissions.contains(permission)) {
            return true;
        }

        // Check wildcard patterns (e.g., "network:https:*" allows "network:https://api.example.com")
        String prefix = permission.contains(":") ? permission.substring(0, permission.indexOf(':')) : permission;
        String wildcardPattern = prefix + ":*";
        return allowedPermissions.contains(wildcardPattern);
    }

    /**
     * Result of a permission validation.
     */
    public record ValidationResult(boolean valid, String message) {
        public static ValidationResult success(String message) {
            return new ValidationResult(true, message);
        }

        public static ValidationResult failure(String message) {
            return new ValidationResult(false, message);
        }
    }
}
