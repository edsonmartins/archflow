package br.com.archflow.security.apikey;

import br.com.archflow.model.security.ApiKey;
import br.com.archflow.model.security.ApiKeyScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ApiKeyService.
 *
 * <p>Uses a simple in-memory {@link ApiKeyService.ApiKeyRepository} stub so the
 * service can be exercised without any framework dependencies.</p>
 */
class ApiKeyServiceTest {

    private InMemoryApiKeyRepository repository;
    private ApiKeyService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryApiKeyRepository();
        service = new ApiKeyService(repository);
    }

    // ========== createApiKey ==========

    @Test
    void createApiKey_returnsApiKeyWithSecretWithAkPrefix() {
        ApiKeyService.ApiKeyWithSecret result = service.createApiKey(
                "owner-1", "My Key", Set.of(ApiKeyScope.WORKFLOW_READ), null);

        assertNotNull(result);
        String keyId = result.apiKey().getKeyId();
        assertTrue(keyId.startsWith("ak_"),
                "keyId should start with 'ak_' but was: " + keyId);
    }

    @Test
    void createApiKey_secretIsNonNull() {
        ApiKeyService.ApiKeyWithSecret result = service.createApiKey(
                "owner-1", "My Key", Set.of(ApiKeyScope.WORKFLOW_READ), null);

        assertNotNull(result.secret());
        assertFalse(result.secret().isBlank());
    }

    @Test
    void createApiKey_storesKeyInRepository() {
        ApiKeyService.ApiKeyWithSecret result = service.createApiKey(
                "owner-1", "Stored Key", Set.of(ApiKeyScope.WORKFLOW_READ), null);

        String keyId = result.apiKey().getKeyId();
        Optional<ApiKey> stored = repository.findByKeyId(keyId);
        assertTrue(stored.isPresent());
    }

    @Test
    void createApiKey_storedKeyHasCorrectOwnerAndName() {
        ApiKeyService.ApiKeyWithSecret result = service.createApiKey(
                "owner-42", "Integration Key", Set.of(ApiKeyScope.WORKFLOW_EXECUTE), null);

        ApiKey stored = repository.findByKeyId(result.apiKey().getKeyId()).orElseThrow();
        assertEquals("owner-42", stored.getOwnerId());
        assertEquals("Integration Key", stored.getName());
    }

    @Test
    void createApiKey_storedKeyIsEnabled() {
        ApiKeyService.ApiKeyWithSecret result = service.createApiKey(
                "owner-1", "Active Key", Set.of(ApiKeyScope.AGENT_READ), null);

        ApiKey stored = repository.findByKeyId(result.apiKey().getKeyId()).orElseThrow();
        assertTrue(stored.isEnabled());
    }

    @Test
    void createApiKey_differentCallsProduceDifferentSecrets() {
        ApiKeyService.ApiKeyWithSecret first = service.createApiKey(
                "owner-1", "Key A", Set.of(ApiKeyScope.WORKFLOW_READ), null);
        ApiKeyService.ApiKeyWithSecret second = service.createApiKey(
                "owner-1", "Key B", Set.of(ApiKeyScope.WORKFLOW_READ), null);

        assertNotEquals(first.secret(), second.secret());
        assertNotEquals(first.apiKey().getKeyId(), second.apiKey().getKeyId());
    }

    // ========== listApiKeys ==========

    @Test
    void listApiKeys_returnsKeysForOwner() {
        service.createApiKey("owner-A", "Key 1", Set.of(ApiKeyScope.WORKFLOW_READ), null);
        service.createApiKey("owner-A", "Key 2", Set.of(ApiKeyScope.WORKFLOW_EXECUTE), null);
        service.createApiKey("owner-B", "Key 3", Set.of(ApiKeyScope.AGENT_READ), null);

        List<ApiKey> ownerAKeys = service.listApiKeys("owner-A");

        assertEquals(2, ownerAKeys.size());
        ownerAKeys.forEach(k -> assertEquals("owner-A", k.getOwnerId()));
    }

    @Test
    void listApiKeys_returnsEmptyListForUnknownOwner() {
        List<ApiKey> keys = service.listApiKeys("owner-unknown");

        assertNotNull(keys);
        assertTrue(keys.isEmpty());
    }

    // ========== getApiKey ==========

    @Test
    void getApiKey_returnsKeyForCorrectOwner() {
        ApiKeyService.ApiKeyWithSecret result = service.createApiKey(
                "owner-5", "My Key", Set.of(ApiKeyScope.WORKFLOW_READ), null);
        String keyId = result.apiKey().getKeyId();

        ApiKey found = service.getApiKey(keyId, "owner-5");

        assertNotNull(found);
        assertEquals(keyId, found.getKeyId());
    }

    @Test
    void getApiKey_throwsForWrongOwner() {
        ApiKeyService.ApiKeyWithSecret result = service.createApiKey(
                "owner-5", "My Key", Set.of(ApiKeyScope.WORKFLOW_READ), null);
        String keyId = result.apiKey().getKeyId();

        assertThrows(ApiKeyService.ApiKeyNotFoundException.class,
                () -> service.getApiKey(keyId, "attacker"));
    }

    @Test
    void getApiKey_throwsForNonExistentKey() {
        assertThrows(ApiKeyService.ApiKeyNotFoundException.class,
                () -> service.getApiKey("ak_doesnotexist", "owner-5"));
    }

    // ========== revokeApiKey ==========

    @Test
    void revokeApiKey_disablesTheKey() {
        ApiKeyService.ApiKeyWithSecret result = service.createApiKey(
                "owner-6", "Revoke Me", Set.of(ApiKeyScope.WORKFLOW_READ), null);
        String keyId = result.apiKey().getKeyId();

        service.revokeApiKey(keyId, "owner-6");

        ApiKey revoked = repository.findByKeyId(keyId).orElseThrow();
        assertFalse(revoked.isEnabled());
    }

    @Test
    void revokeApiKey_throwsForWrongOwner() {
        ApiKeyService.ApiKeyWithSecret result = service.createApiKey(
                "owner-6", "Revoke Me", Set.of(ApiKeyScope.WORKFLOW_READ), null);
        String keyId = result.apiKey().getKeyId();

        assertThrows(ApiKeyService.ApiKeyNotFoundException.class,
                () -> service.revokeApiKey(keyId, "intruder"));
    }

    // ========== validateApiKey ==========

    @Test
    void validateApiKey_succeedsWithCorrectSecret() {
        ApiKeyService.ApiKeyWithSecret created = service.createApiKey(
                "owner-7", "Valid Key", Set.of(ApiKeyScope.EXECUTION_READ), null);

        ApiKey validated = service.validateApiKey(
                created.apiKey().getKeyId(), created.secret());

        assertNotNull(validated);
        assertEquals(created.apiKey().getKeyId(), validated.getKeyId());
    }

    @Test
    void validateApiKey_throwsForWrongSecret() {
        ApiKeyService.ApiKeyWithSecret created = service.createApiKey(
                "owner-7", "Valid Key", Set.of(ApiKeyScope.EXECUTION_READ), null);

        assertThrows(ApiKeyService.ApiKeyNotFoundException.class,
                () -> service.validateApiKey(created.apiKey().getKeyId(), "wrong-secret"));
    }

    @Test
    void validateApiKey_throwsForNonExistentKey() {
        assertThrows(ApiKeyService.ApiKeyNotFoundException.class,
                () -> service.validateApiKey("ak_ghost", "any-secret"));
    }

    // ========== ApiKeyWithSecret accessors ==========

    @Test
    void apiKeyWithSecret_bothAccessorStylesReturnSameValues() {
        ApiKeyService.ApiKeyWithSecret result = service.createApiKey(
                "owner-8", "Accessor Test", Set.of(ApiKeyScope.METRICS_READ), null);

        assertSame(result.apiKey(), result.getApiKey());
        assertEquals(result.secret(), result.getSecret());
    }

    // ========== Minimal in-memory stub ==========

    /**
     * Simple in-memory implementation of {@link ApiKeyService.ApiKeyRepository}
     * used exclusively for testing — no production logic, no framework.
     */
    private static class InMemoryApiKeyRepository implements ApiKeyService.ApiKeyRepository {

        private final Map<String, ApiKey> store = new HashMap<>();

        @Override
        public ApiKey save(ApiKey apiKey) {
            store.put(apiKey.getKeyId(), apiKey);
            return apiKey;
        }

        @Override
        public List<ApiKey> findByOwnerId(String ownerId) {
            List<ApiKey> result = new ArrayList<>();
            for (ApiKey k : store.values()) {
                if (ownerId.equals(k.getOwnerId())) {
                    result.add(k);
                }
            }
            return result;
        }

        @Override
        public Optional<ApiKey> findByKeyId(String keyId) {
            return Optional.ofNullable(store.get(keyId));
        }

        @Override
        public Optional<ApiKey> findById(String id) {
            return store.values().stream()
                    .filter(k -> id.equals(k.getId()))
                    .findFirst();
        }

        @Override
        public void delete(ApiKey apiKey) {
            store.remove(apiKey.getKeyId());
        }
    }
}
