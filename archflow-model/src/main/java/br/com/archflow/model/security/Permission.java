package br.com.archflow.model.security;

import java.util.Objects;

/**
 * Permission represents a specific action that can be performed on a resource.
 *
 * Permissions follow the pattern: resource:action (e.g., "workflow:read", "workflow:execute")
 */
public class Permission {

    private String id;
    private String resource;
    private String action;
    private String description;

    public Permission() {
    }

    public Permission(String id, String resource, String action, String description) {
        this.id = id;
        this.resource = resource;
        this.action = action;
        this.description = description;
    }

    /**
     * Creates a permission from a string in the format "resource:action"
     */
    public static Permission fromString(String permissionString) {
        String[] parts = permissionString.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Permission must be in format 'resource:action'");
        }
        return new Permission(null, parts[0], parts[1], null);
    }

    /**
     * Returns the full permission string in format "resource:action"
     */
    public String asString() {
        return resource + ":" + action;
    }

    /**
     * Checks if this permission matches a wildcard pattern (e.g., "workflow:*")
     */
    public boolean matches(String permissionPattern) {
        if (permissionPattern.equals("*")) {
            return true;
        }
        String[] parts = permissionPattern.split(":");
        if (parts.length != 2) {
            return false;
        }
        String resourcePattern = parts[0];
        String actionPattern = parts[1];

        boolean resourceMatches = resourcePattern.equals("*") || resourcePattern.equals(resource);
        boolean actionMatches = actionPattern.equals("*") || actionPattern.equals(action);

        return resourceMatches && actionMatches;
    }

    // Getters and Setters

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getResource() {
        return resource;
    }

    public void setResource(String resource) {
        this.resource = resource;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Permission that = (Permission) o;
        return Objects.equals(resource, that.resource) && Objects.equals(action, that.action);
    }

    @Override
    public int hashCode() {
        return Objects.hash(resource, action);
    }

    @Override
    public String toString() {
        return "Permission{" +
                "id='" + id + '\'' +
                ", resource='" + resource + '\'' +
                ", action='" + action + '\'' +
                ", description='" + description + '\'' +
                '}';
    }
}
