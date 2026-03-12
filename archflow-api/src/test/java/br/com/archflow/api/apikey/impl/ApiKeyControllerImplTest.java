package br.com.archflow.api.apikey.impl;

import br.com.archflow.api.apikey.ApiKeyController;
import br.com.archflow.api.apikey.dto.CreateApiKeyRequest;
import br.com.archflow.model.security.ApiKey;
import br.com.archflow.model.security.ApiKeyScope;
import br.com.archflow.security.apikey.ApiKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ApiKeyControllerImpl")
class ApiKeyControllerImplTest {

    @Mock
    private ApiKeyService apiKeyService;

    private ApiKeyControllerImpl controller;

    @BeforeEach
    void setUp() {
        controller = new ApiKeyControllerImpl(apiKeyService);
    }

    private ApiKey createMockApiKey(String id, String keyId, String name) {
        var apiKey = new ApiKey();
        apiKey.setId(id);
        apiKey.setKeyId(keyId);
        apiKey.setName(name);
        apiKey.setOwnerId("owner-1");
        apiKey.setScopes(Set.of(ApiKeyScope.WORKFLOW_READ));
        apiKey.setEnabled(true);
        apiKey.setCreatedAt(LocalDateTime.now());
        return apiKey;
    }

    @Nested
    @DisplayName("createApiKey")
    class CreateApiKeyTest {

        @Test
        @DisplayName("should create api key successfully")
        void shouldCreateSuccessfully() {
            var request = new CreateApiKeyRequest("My Key", Set.of(ApiKeyScope.WORKFLOW_READ), null);
            var apiKey = createMockApiKey("id-1", "key-1", "My Key");
            var result = new ApiKeyService.ApiKeyWithSecret(apiKey, "secret-123");

            when(apiKeyService.createApiKey("owner-1", "My Key", Set.of(ApiKeyScope.WORKFLOW_READ), null))
                    .thenReturn(result);

            var response = controller.createApiKey("owner-1", request);

            assertThat(response.id()).isEqualTo("id-1");
            assertThat(response.keyId()).isEqualTo("key-1");
            assertThat(response.keySecret()).isEqualTo("secret-123");
            assertThat(response.name()).isEqualTo("My Key");
            assertThat(response.enabled()).isTrue();
        }

        @Test
        @DisplayName("should pass expiration to service")
        void shouldPassExpiration() {
            var expiry = LocalDateTime.now().plusDays(30);
            var request = new CreateApiKeyRequest("Key", Set.of(ApiKeyScope.ADMIN), expiry);
            var apiKey = createMockApiKey("id-1", "key-1", "Key");
            var result = new ApiKeyService.ApiKeyWithSecret(apiKey, "secret");

            when(apiKeyService.createApiKey("owner-1", "Key", Set.of(ApiKeyScope.ADMIN), expiry))
                    .thenReturn(result);

            controller.createApiKey("owner-1", request);

            verify(apiKeyService).createApiKey("owner-1", "Key", Set.of(ApiKeyScope.ADMIN), expiry);
        }
    }

    @Nested
    @DisplayName("listApiKeys")
    class ListApiKeysTest {

        @Test
        @DisplayName("should list all keys for user")
        void shouldListKeys() {
            var keys = List.of(
                    createMockApiKey("id-1", "key-1", "Key 1"),
                    createMockApiKey("id-2", "key-2", "Key 2")
            );
            when(apiKeyService.listApiKeys("owner-1")).thenReturn(keys);

            var response = controller.listApiKeys("owner-1");

            assertThat(response).hasSize(2);
            assertThat(response.get(0).name()).isEqualTo("Key 1");
            assertThat(response.get(1).name()).isEqualTo("Key 2");
        }

        @Test
        @DisplayName("should return empty list when no keys")
        void shouldReturnEmptyList() {
            when(apiKeyService.listApiKeys("owner-1")).thenReturn(List.of());

            var response = controller.listApiKeys("owner-1");

            assertThat(response).isEmpty();
        }
    }

    @Nested
    @DisplayName("getApiKey")
    class GetApiKeyTest {

        @Test
        @DisplayName("should return key by id")
        void shouldReturnKey() {
            var apiKey = createMockApiKey("id-1", "key-1", "My Key");
            when(apiKeyService.getApiKey("key-1", "owner-1")).thenReturn(apiKey);

            var response = controller.getApiKey("owner-1", "key-1");

            assertThat(response.keyId()).isEqualTo("key-1");
            assertThat(response.name()).isEqualTo("My Key");
        }

        @Test
        @DisplayName("should throw NotFoundException when key not found")
        void shouldThrowWhenNotFound() {
            when(apiKeyService.getApiKey("missing", "owner-1"))
                    .thenThrow(new ApiKeyService.ApiKeyNotFoundException("Not found"));

            assertThatThrownBy(() -> controller.getApiKey("owner-1", "missing"))
                    .isInstanceOf(ApiKeyController.NotFoundException.class);
        }
    }

    @Nested
    @DisplayName("revokeApiKey")
    class RevokeApiKeyTest {

        @Test
        @DisplayName("should revoke key successfully")
        void shouldRevokeSuccessfully() {
            assertThatNoException().isThrownBy(() -> controller.revokeApiKey("owner-1", "key-1"));
            verify(apiKeyService).revokeApiKey("key-1", "owner-1");
        }

        @Test
        @DisplayName("should throw NotFoundException when key not found")
        void shouldThrowWhenNotFound() {
            doThrow(new ApiKeyService.ApiKeyNotFoundException("Not found"))
                    .when(apiKeyService).revokeApiKey("missing", "owner-1");

            assertThatThrownBy(() -> controller.revokeApiKey("owner-1", "missing"))
                    .isInstanceOf(ApiKeyController.NotFoundException.class);
        }
    }
}
