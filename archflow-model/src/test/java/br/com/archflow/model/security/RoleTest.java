package br.com.archflow.model.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Role")
class RoleTest {

    @Test
    @DisplayName("should create with constructor")
    void shouldCreateWithConstructor() {
        var role = new Role("r-1", "ADMIN", "Full access");

        assertThat(role.getId()).isEqualTo("r-1");
        assertThat(role.getName()).isEqualTo("ADMIN");
        assertThat(role.getDescription()).isEqualTo("Full access");
        assertThat(role.getPermissions()).isEmpty();
    }

    @Test
    @DisplayName("should add and remove permissions")
    void shouldAddAndRemovePermissions() {
        var role = new Role("r-1", "TEST", "Test role");
        var perm = new Permission(null, "workflow", "read", null);

        role.addPermission(perm);
        assertThat(role.getPermissions()).hasSize(1);

        role.removePermission(perm);
        assertThat(role.getPermissions()).isEmpty();
    }

    @Nested
    @DisplayName("Built-in roles")
    class BuiltInRolesTest {

        @Test
        @DisplayName("admin role should have wildcard permission")
        void adminRoleShouldHaveWildcard() {
            var admin = Role.createAdminRole();

            assertThat(admin.getName()).isEqualTo("ADMIN");
            assertThat(admin.hasPermission("workflow", "read")).isTrue();
            assertThat(admin.hasPermission("agent", "delete")).isTrue();
            assertThat(admin.hasPermission("anything", "any")).isTrue();
        }

        @Test
        @DisplayName("designer role should have workflow CRUD permissions")
        void designerRoleShouldHaveWorkflowPermissions() {
            var designer = Role.createDesignerRole();

            assertThat(designer.getName()).isEqualTo("DESIGNER");
            assertThat(designer.hasPermission("workflow", "read")).isTrue();
            assertThat(designer.hasPermission("workflow", "create")).isTrue();
            assertThat(designer.hasPermission("workflow", "update")).isTrue();
            assertThat(designer.hasPermission("workflow", "delete")).isTrue();
            assertThat(designer.hasPermission("workflow", "execute")).isTrue();
            assertThat(designer.hasPermission("agent", "read")).isTrue();
            assertThat(designer.hasPermission("agent", "delete")).isFalse();
        }

        @Test
        @DisplayName("executor role should only execute and read workflows")
        void executorRoleShouldOnlyExecute() {
            var executor = Role.createExecutorRole();

            assertThat(executor.getName()).isEqualTo("EXECUTOR");
            assertThat(executor.hasPermission("workflow", "read")).isTrue();
            assertThat(executor.hasPermission("workflow", "execute")).isTrue();
            assertThat(executor.hasPermission("workflow", "create")).isFalse();
            assertThat(executor.hasPermission("workflow", "delete")).isFalse();
        }

        @Test
        @DisplayName("viewer role should have read-only permissions")
        void viewerRoleShouldBeReadOnly() {
            var viewer = Role.createViewerRole();

            assertThat(viewer.getName()).isEqualTo("VIEWER");
            assertThat(viewer.hasPermission("workflow", "read")).isTrue();
            assertThat(viewer.hasPermission("agent", "read")).isTrue();
            assertThat(viewer.hasPermission("execution", "read")).isTrue();
            assertThat(viewer.hasPermission("workflow", "create")).isFalse();
            assertThat(viewer.hasPermission("workflow", "execute")).isFalse();
        }
    }

    @Test
    @DisplayName("hasPermission with string should parse resource:action")
    void hasPermissionWithStringShouldParse() {
        var role = Role.createDesignerRole();

        assertThat(role.hasPermission("workflow:read")).isTrue();
        assertThat(role.hasPermission("workflow:delete")).isTrue();
        assertThat(role.hasPermission("invalid")).isFalse();
    }

    @Test
    @DisplayName("should support equality based on name")
    void shouldSupportEquality() {
        var r1 = new Role("r-1", "ADMIN", "desc1");
        var r2 = new Role("r-2", "ADMIN", "desc2");

        assertThat(r1).isEqualTo(r2);
        assertThat(r1.hashCode()).isEqualTo(r2.hashCode());
    }

    @Test
    @DisplayName("should have meaningful toString")
    void shouldHaveMeaningfulToString() {
        var role = Role.createAdminRole();
        assertThat(role.toString()).contains("ADMIN");
    }
}
