package br.com.archflow.model.security;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Role represents a collection of permissions assigned to users.
 *
 * Built-in roles:
 * - ADMIN: Full system access
 * - DESIGNER: Can create and edit workflows
 * - EXECUTOR: Can execute workflows only
 * - VIEWER: Read-only access
 */
public class Role {

    private String id;
    private String name;
    private String description;
    private Set<Permission> permissions = new HashSet<>();

    public Role() {
    }

    public Role(String id, String name, String description) {
        this.id = id;
        this.name = name;
        this.description = description;
    }

    // Built-in roles

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_DESIGNER = "DESIGNER";
    public static final String ROLE_EXECUTOR = "EXECUTOR";
    public static final String ROLE_VIEWER = "VIEWER";

    /**
     * Creates the ADMIN role with all permissions
     */
    public static Role createAdminRole() {
        Role admin = new Role("role-admin", ROLE_ADMIN, "Full system access");
        admin.addPermission(new Permission(null, "*", "*", "All permissions"));
        return admin;
    }

    /**
     * Creates the DESIGNER role with workflow management permissions
     */
    public static Role createDesignerRole() {
        Role designer = new Role("role-designer", ROLE_DESIGNER, "Can create and edit workflows");
        designer.addPermission(new Permission(null, "workflow", "read", "Read workflows"));
        designer.addPermission(new Permission(null, "workflow", "create", "Create workflows"));
        designer.addPermission(new Permission(null, "workflow", "update", "Update workflows"));
        designer.addPermission(new Permission(null, "workflow", "delete", "Delete workflows"));
        designer.addPermission(new Permission(null, "workflow", "execute", "Execute workflows"));
        designer.addPermission(new Permission(null, "agent", "read", "Read agents"));
        return designer;
    }

    /**
     * Creates the EXECUTOR role with workflow execution permissions only
     */
    public static Role createExecutorRole() {
        Role executor = new Role("role-executor", ROLE_EXECUTOR, "Can execute workflows only");
        executor.addPermission(new Permission(null, "workflow", "read", "Read workflows"));
        executor.addPermission(new Permission(null, "workflow", "execute", "Execute workflows"));
        return executor;
    }

    /**
     * Creates the VIEWER role with read-only permissions
     */
    public static Role createViewerRole() {
        Role viewer = new Role("role-viewer", ROLE_VIEWER, "Read-only access");
        viewer.addPermission(new Permission(null, "workflow", "read", "Read workflows"));
        viewer.addPermission(new Permission(null, "agent", "read", "Read agents"));
        viewer.addPermission(new Permission(null, "execution", "read", "Read execution history"));
        return viewer;
    }

    // Permission management

    public void addPermission(Permission permission) {
        this.permissions.add(permission);
    }

    public void removePermission(Permission permission) {
        this.permissions.remove(permission);
    }

    public boolean hasPermission(String resource, String action) {
        return permissions.stream()
                .anyMatch(p -> p.matches(resource + ":" + action) ||
                               p.matches(resource + ":*") ||
                               p.matches("*:" + action) ||
                               p.matches("*:*"));
    }

    public boolean hasPermission(String permissionString) {
        String[] parts = permissionString.split(":");
        if (parts.length != 2) {
            return false;
        }
        return hasPermission(parts[0], parts[1]);
    }

    // Getters and Setters

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Set<Permission> getPermissions() {
        return permissions;
    }

    public void setPermissions(Set<Permission> permissions) {
        this.permissions = permissions;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Role role = (Role) o;
        return Objects.equals(name, role.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    @Override
    public String toString() {
        return "Role{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", description='" + description + '\'' +
                ", permissions=" + permissions.size() +
                '}';
    }
}
