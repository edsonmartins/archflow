package br.com.archflow.model.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.assertj.core.api.Assertions.*;

@DisplayName("ApiKeyScope")
class ApiKeyScopeTest {

    @Test
    @DisplayName("should have all expected values")
    void shouldHaveAllValues() {
        assertThat(ApiKeyScope.values()).hasSize(11);
    }

    @Test
    @DisplayName("should return permission string")
    void shouldReturnPermissionString() {
        assertThat(ApiKeyScope.WORKFLOW_READ.asString()).isEqualTo("workflow:read");
        assertThat(ApiKeyScope.WORKFLOW_EXECUTE.asString()).isEqualTo("workflow:execute");
        assertThat(ApiKeyScope.ADMIN.asString()).isEqualTo("*:*");
    }

    @Test
    @DisplayName("should create from permission string")
    void shouldCreateFromPermissionString() {
        assertThat(ApiKeyScope.fromPermission("workflow:read")).isEqualTo(ApiKeyScope.WORKFLOW_READ);
        assertThat(ApiKeyScope.fromPermission("*:*")).isEqualTo(ApiKeyScope.ADMIN);
    }

    @Test
    @DisplayName("should throw on unknown permission")
    void shouldThrowOnUnknownPermission() {
        assertThatThrownBy(() -> ApiKeyScope.fromPermission("unknown:scope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown permission");
    }

    @Test
    @DisplayName("should detect admin scope")
    void shouldDetectAdminScope() {
        assertThat(ApiKeyScope.ADMIN.isAdmin()).isTrue();
        assertThat(ApiKeyScope.WORKFLOW_READ.isAdmin()).isFalse();
    }

    @Test
    @DisplayName("should have description for all scopes")
    void shouldHaveDescription() {
        for (ApiKeyScope scope : ApiKeyScope.values()) {
            assertThat(scope.getDescription()).isNotBlank();
        }
    }
}
