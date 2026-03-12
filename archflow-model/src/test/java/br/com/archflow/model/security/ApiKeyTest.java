package br.com.archflow.model.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

@DisplayName("ApiKey")
class ApiKeyTest {

    @Test
    @DisplayName("should create with factory method")
    void shouldCreateWithFactory() {
        var scopes = Set.of(ApiKeyScope.WORKFLOW_READ, ApiKeyScope.WORKFLOW_EXECUTE);
        var apiKey = ApiKey.create("Test Key", "owner-1", scopes);

        assertThat(apiKey.getId()).isNotNull();
        assertThat(apiKey.getKeyId()).isNotNull();
        assertThat(apiKey.getName()).isEqualTo("Test Key");
        assertThat(apiKey.getOwnerId()).isEqualTo("owner-1");
        assertThat(apiKey.getScopes()).containsExactlyInAnyOrder(ApiKeyScope.WORKFLOW_READ, ApiKeyScope.WORKFLOW_EXECUTE);
        assertThat(apiKey.isEnabled()).isTrue();
        assertThat(apiKey.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("should generate unique key secrets")
    void shouldGenerateUniqueSecrets() {
        var secret1 = ApiKey.generateKeySecret();
        var secret2 = ApiKey.generateKeySecret();

        assertThat(secret1).startsWith("ak_");
        assertThat(secret2).startsWith("ak_");
        assertThat(secret1).isNotEqualTo(secret2);
    }

    @Test
    @DisplayName("should be valid when enabled and not expired")
    void shouldBeValidWhenEnabledAndNotExpired() {
        var apiKey = ApiKey.create("Key", "owner", Set.of(ApiKeyScope.WORKFLOW_READ));

        assertThat(apiKey.isValid()).isTrue();
    }

    @Test
    @DisplayName("should be invalid when disabled")
    void shouldBeInvalidWhenDisabled() {
        var apiKey = ApiKey.create("Key", "owner", Set.of(ApiKeyScope.WORKFLOW_READ));
        apiKey.setEnabled(false);

        assertThat(apiKey.isValid()).isFalse();
    }

    @Test
    @DisplayName("should be invalid when expired")
    void shouldBeInvalidWhenExpired() {
        var apiKey = ApiKey.create("Key", "owner", Set.of(ApiKeyScope.WORKFLOW_READ));
        apiKey.setExpiresAt(LocalDateTime.now().minusDays(1));

        assertThat(apiKey.isValid()).isFalse();
        assertThat(apiKey.isExpired()).isTrue();
    }

    @Test
    @DisplayName("should be valid when expiry is in the future")
    void shouldBeValidWhenExpiryInFuture() {
        var apiKey = ApiKey.create("Key", "owner", Set.of(ApiKeyScope.WORKFLOW_READ));
        apiKey.setExpiresAt(LocalDateTime.now().plusDays(30));

        assertThat(apiKey.isValid()).isTrue();
        assertThat(apiKey.isExpired()).isFalse();
    }

    @Test
    @DisplayName("should be valid when no expiry set")
    void shouldBeValidWhenNoExpiry() {
        var apiKey = ApiKey.create("Key", "owner", Set.of(ApiKeyScope.WORKFLOW_READ));

        assertThat(apiKey.isExpired()).isFalse();
        assertThat(apiKey.isValid()).isTrue();
    }

    @Test
    @DisplayName("should check scope")
    void shouldCheckScope() {
        var apiKey = ApiKey.create("Key", "owner",
                Set.of(ApiKeyScope.WORKFLOW_READ, ApiKeyScope.WORKFLOW_EXECUTE));

        assertThat(apiKey.hasScope(ApiKeyScope.WORKFLOW_READ)).isTrue();
        assertThat(apiKey.hasScope(ApiKeyScope.WORKFLOW_EXECUTE)).isTrue();
        assertThat(apiKey.hasScope(ApiKeyScope.WORKFLOW_DELETE)).isFalse();
    }

    @Test
    @DisplayName("admin scope should grant all scopes")
    void adminScopeShouldGrantAll() {
        var apiKey = ApiKey.create("Admin Key", "owner", Set.of(ApiKeyScope.ADMIN));

        assertThat(apiKey.hasScope(ApiKeyScope.WORKFLOW_READ)).isTrue();
        assertThat(apiKey.hasScope(ApiKeyScope.WORKFLOW_DELETE)).isTrue();
        assertThat(apiKey.hasScope(ApiKeyScope.AGENT_MANAGE)).isTrue();
    }

    @Test
    @DisplayName("should check permission with resource and action")
    void shouldCheckPermission() {
        var apiKey = ApiKey.create("Key", "owner", Set.of(ApiKeyScope.WORKFLOW_READ));

        assertThat(apiKey.hasPermission("workflow", "read")).isTrue();
        assertThat(apiKey.hasPermission("workflow", "delete")).isFalse();
    }

    @Test
    @DisplayName("should check permission from string")
    void shouldCheckPermissionFromString() {
        var apiKey = ApiKey.create("Key", "owner", Set.of(ApiKeyScope.WORKFLOW_EXECUTE));

        assertThat(apiKey.hasPermission("workflow:execute")).isTrue();
        assertThat(apiKey.hasPermission("workflow:delete")).isFalse();
        assertThat(apiKey.hasPermission("invalid")).isFalse();
    }

    @Test
    @DisplayName("admin scope should grant all permissions")
    void adminScopeShouldGrantAllPermissions() {
        var apiKey = ApiKey.create("Admin Key", "owner", Set.of(ApiKeyScope.ADMIN));

        assertThat(apiKey.hasPermission("workflow", "read")).isTrue();
        assertThat(apiKey.hasPermission("anything", "anything")).isTrue();
    }

    @Test
    @DisplayName("should mark as used")
    void shouldMarkAsUsed() {
        var apiKey = ApiKey.create("Key", "owner", Set.of(ApiKeyScope.WORKFLOW_READ));
        assertThat(apiKey.getLastUsedAt()).isNull();

        apiKey.markAsUsed();
        assertThat(apiKey.getLastUsedAt()).isNotNull();
    }

    @Test
    @DisplayName("should support equality based on id")
    void shouldSupportEquality() {
        var k1 = new ApiKey();
        k1.setId("key-1");
        var k2 = new ApiKey();
        k2.setId("key-1");

        assertThat(k1).isEqualTo(k2);
        assertThat(k1.hashCode()).isEqualTo(k2.hashCode());
    }
}
