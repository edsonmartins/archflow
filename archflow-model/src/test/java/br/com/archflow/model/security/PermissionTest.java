package br.com.archflow.model.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Permission")
class PermissionTest {

    @Test
    @DisplayName("should create with constructor")
    void shouldCreateWithConstructor() {
        var perm = new Permission("p-1", "workflow", "read", "Read workflows");

        assertThat(perm.getId()).isEqualTo("p-1");
        assertThat(perm.getResource()).isEqualTo("workflow");
        assertThat(perm.getAction()).isEqualTo("read");
        assertThat(perm.getDescription()).isEqualTo("Read workflows");
    }

    @Test
    @DisplayName("should create from string")
    void shouldCreateFromString() {
        var perm = Permission.fromString("workflow:execute");

        assertThat(perm.getResource()).isEqualTo("workflow");
        assertThat(perm.getAction()).isEqualTo("execute");
        assertThat(perm.getId()).isNull();
    }

    @Test
    @DisplayName("should throw on invalid permission string")
    void shouldThrowOnInvalidString() {
        assertThatThrownBy(() -> Permission.fromString("invalid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("resource:action");

        assertThatThrownBy(() -> Permission.fromString("a:b:c"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("should return permission as string")
    void shouldReturnAsString() {
        var perm = new Permission(null, "agent", "manage", null);

        assertThat(perm.asString()).isEqualTo("agent:manage");
    }

    @Nested
    @DisplayName("matches")
    class MatchesTest {

        @Test
        @DisplayName("should match exact permission")
        void shouldMatchExact() {
            var perm = new Permission(null, "workflow", "read", null);

            assertThat(perm.matches("workflow:read")).isTrue();
        }

        @Test
        @DisplayName("should match wildcard resource")
        void shouldMatchWildcardResource() {
            var perm = new Permission(null, "workflow", "read", null);

            assertThat(perm.matches("*:read")).isTrue();
        }

        @Test
        @DisplayName("should match wildcard action")
        void shouldMatchWildcardAction() {
            var perm = new Permission(null, "workflow", "read", null);

            assertThat(perm.matches("workflow:*")).isTrue();
        }

        @Test
        @DisplayName("should match full wildcard")
        void shouldMatchFullWildcard() {
            var perm = new Permission(null, "workflow", "read", null);

            assertThat(perm.matches("*:*")).isTrue();
            assertThat(perm.matches("*")).isTrue();
        }

        @Test
        @DisplayName("should not match different resource")
        void shouldNotMatchDifferentResource() {
            var perm = new Permission(null, "workflow", "read", null);

            assertThat(perm.matches("agent:read")).isFalse();
        }

        @Test
        @DisplayName("should not match different action")
        void shouldNotMatchDifferentAction() {
            var perm = new Permission(null, "workflow", "read", null);

            assertThat(perm.matches("workflow:delete")).isFalse();
        }

        @Test
        @DisplayName("should not match invalid pattern")
        void shouldNotMatchInvalidPattern() {
            var perm = new Permission(null, "workflow", "read", null);

            assertThat(perm.matches("invalid")).isFalse();
        }
    }

    @Test
    @DisplayName("should support equality based on resource and action")
    void shouldSupportEquality() {
        var p1 = new Permission("id-1", "workflow", "read", "desc1");
        var p2 = new Permission("id-2", "workflow", "read", "desc2");

        assertThat(p1).isEqualTo(p2);
        assertThat(p1.hashCode()).isEqualTo(p2.hashCode());
    }

    @Test
    @DisplayName("should not equal different permissions")
    void shouldNotEqualDifferent() {
        var p1 = new Permission(null, "workflow", "read", null);
        var p2 = new Permission(null, "workflow", "write", null);

        assertThat(p1).isNotEqualTo(p2);
    }
}
