package br.com.archflow.model.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.assertj.core.api.Assertions.*;

@DisplayName("User")
class UserTest {

    private User user;

    @BeforeEach
    void setUp() {
        user = new User("u-1", "john", "john@test.com", "hashed-password");
    }

    @Test
    @DisplayName("should create with constructor and set timestamps")
    void shouldCreateWithConstructor() {
        assertThat(user.getId()).isEqualTo("u-1");
        assertThat(user.getUsername()).isEqualTo("john");
        assertThat(user.getEmail()).isEqualTo("john@test.com");
        assertThat(user.getPasswordHash()).isEqualTo("hashed-password");
        assertThat(user.getCreatedAt()).isNotNull();
        assertThat(user.getUpdatedAt()).isNotNull();
        assertThat(user.isEnabled()).isTrue();
        assertThat(user.isAccountNonExpired()).isTrue();
        assertThat(user.isAccountNonLocked()).isTrue();
        assertThat(user.isCredentialsNonExpired()).isTrue();
    }

    @Test
    @DisplayName("should add and remove roles")
    void shouldAddAndRemoveRoles() {
        var adminRole = Role.createAdminRole();
        user.addRole(adminRole);

        assertThat(user.getRoles()).hasSize(1);
        assertThat(user.hasRole("ADMIN")).isTrue();

        user.removeRole(adminRole);
        assertThat(user.getRoles()).isEmpty();
        assertThat(user.hasRole("ADMIN")).isFalse();
    }

    @Test
    @DisplayName("should check permissions through roles")
    void shouldCheckPermissionsThroughRoles() {
        user.addRole(Role.createDesignerRole());

        assertThat(user.hasPermission("workflow", "read")).isTrue();
        assertThat(user.hasPermission("workflow", "create")).isTrue();
        assertThat(user.hasPermission("system", "admin")).isFalse();
    }

    @Test
    @DisplayName("should check permission from string format")
    void shouldCheckPermissionFromString() {
        user.addRole(Role.createExecutorRole());

        assertThat(user.hasPermission("workflow:execute")).isTrue();
        assertThat(user.hasPermission("workflow:delete")).isFalse();
        assertThat(user.hasPermission("invalid-format")).isFalse();
    }

    @Test
    @DisplayName("should detect admin user")
    void shouldDetectAdmin() {
        assertThat(user.isAdmin()).isFalse();

        user.addRole(Role.createAdminRole());
        assertThat(user.isAdmin()).isTrue();
    }

    @Test
    @DisplayName("should return full name when available")
    void shouldReturnFullName() {
        user.setFirstName("John");
        user.setLastName("Doe");

        assertThat(user.getFullName()).isEqualTo("John Doe");
    }

    @Test
    @DisplayName("should return username when name not set")
    void shouldReturnUsernameWhenNameNotSet() {
        assertThat(user.getFullName()).isEqualTo("john");
    }

    @Test
    @DisplayName("should update timestamp")
    void shouldUpdateTimestamp() {
        var before = user.getUpdatedAt();
        user.updateTimestamp();
        var after = user.getUpdatedAt();

        assertThat(after).isAfterOrEqualTo(before);
    }

    @Test
    @DisplayName("should support equality based on id or username")
    void shouldSupportEquality() {
        var sameId = new User("u-1", "different", "other@test.com", "hash");
        var sameUsername = new User("u-2", "john", "another@test.com", "hash");

        assertThat(user).isEqualTo(sameId);
        assertThat(user).isEqualTo(sameUsername);
    }

    @Test
    @DisplayName("should not equal different user")
    void shouldNotEqualDifferent() {
        var other = new User("u-2", "jane", "jane@test.com", "hash");

        assertThat(user).isNotEqualTo(other);
    }

    @Test
    @DisplayName("should have meaningful toString")
    void shouldHaveMeaningfulToString() {
        assertThat(user.toString()).contains("john", "john@test.com");
        assertThat(user.toString()).doesNotContain("hashed-password");
    }
}
