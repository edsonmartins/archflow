package br.com.archflow.model.security;

/**
 * Scopes that can be granted to API keys.
 * Each scope represents a specific permission level.
 */
public enum ApiKeyScope {

    /**
     * Read-only access to workflows
     */
    WORKFLOW_READ("workflow:read", "Read workflows"),

    /**
     * Create new workflows
     */
    WORKFLOW_CREATE("workflow:create", "Create workflows"),

    /**
     * Update existing workflows
     */
    WORKFLOW_UPDATE("workflow:update", "Update workflows"),

    /**
     * Delete workflows
     */
    WORKFLOW_DELETE("workflow:delete", "Delete workflows"),

    /**
     * Execute workflows
     */
    WORKFLOW_EXECUTE("workflow:execute", "Execute workflows"),

    /**
     * Read agents
     */
    AGENT_READ("agent:read", "Read agents"),

    /**
     * Create and manage agents
     */
    AGENT_MANAGE("agent:manage", "Manage agents"),

    /**
     * Read execution history and logs
     */
    EXECUTION_READ("execution:read", "Read execution history"),

    /**
     * Cancel running executions
     */
    EXECUTION_MANAGE("execution:manage", "Manage executions"),

    /**
     * Read system metrics
     */
    METRICS_READ("metrics:read", "Read metrics"),

    /**
     * Full admin access (all scopes)
     */
    ADMIN("*:*", "Full admin access");

    private final String permission;
    private final String description;

    ApiKeyScope(String permission, String description) {
        this.permission = permission;
        this.description = description;
    }

    public String getPermission() {
        return permission;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Gets the permission string (e.g., "workflow:read")
     */
    public String asString() {
        return permission;
    }

    /**
     * Creates an ApiKeyScope from a permission string
     */
    public static ApiKeyScope fromPermission(String permission) {
        for (ApiKeyScope scope : values()) {
            if (scope.permission.equals(permission)) {
                return scope;
            }
        }
        throw new IllegalArgumentException("Unknown permission: " + permission);
    }

    /**
     * Checks if this scope grants admin access
     */
    public boolean isAdmin() {
        return this == ADMIN;
    }
}
