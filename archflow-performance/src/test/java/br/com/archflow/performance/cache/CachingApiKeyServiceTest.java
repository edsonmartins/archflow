package br.com.archflow.performance.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CachingApiKeyService")
class CachingApiKeyServiceTest {

    @Test
    @DisplayName("should return null when no validator set")
    void shouldReturnNullWithoutValidator() {
        CachingApiKeyService service = new CachingApiKeyService();
        assertNull(service.validateApiKey("tenant1", "key1", "secret1"));
    }

    @Test
    @DisplayName("should return empty list when no lister set")
    void shouldReturnEmptyListWithoutLister() {
        CachingApiKeyService service = new CachingApiKeyService();
        assertTrue(service.listApiKeys("tenant1", "user1").isEmpty());
    }

    @Test
    @DisplayName("should delegate to validator when set")
    void shouldDelegateToValidator() {
        CachingApiKeyService service = new CachingApiKeyService();
        service.setValidator((tenantId, keyId, keySecret) -> "validated:" + tenantId + ":" + keyId);

        Object result = service.validateApiKey("tenant1", "key1", "secret1");
        assertEquals("validated:tenant1:key1", result);
    }

    @Test
    @DisplayName("should delegate to lister when set")
    void shouldDelegateToLister() {
        CachingApiKeyService service = new CachingApiKeyService();
        service.setLister((tenantId, ownerId) -> java.util.List.of("key1", "key2"));

        var result = service.listApiKeys("tenant1", "user1");
        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("should not throw on key revoked event")
    void shouldNotThrowOnKeyRevoked() {
        CachingApiKeyService service = new CachingApiKeyService();
        assertDoesNotThrow(() -> service.onApiKeyRevoked("key1"));
    }

    @Test
    @DisplayName("should not throw on key created event")
    void shouldNotThrowOnKeyCreated() {
        CachingApiKeyService service = new CachingApiKeyService();
        assertDoesNotThrow(() -> service.onApiKeyCreated("user1"));
    }

    @Test
    @DisplayName("secretHash is deterministic, secret-sensitive and does not expose the secret")
    void secretHashProperties() {
        String hash1 = CachingApiKeyService.secretHash("super-secret");
        String hash2 = CachingApiKeyService.secretHash("super-secret");
        String hashOther = CachingApiKeyService.secretHash("wrong-secret");

        assertEquals(hash1, hash2);
        assertNotEquals(hash1, hashOther);
        assertFalse(hash1.contains("super-secret"));
        assertEquals("null", CachingApiKeyService.secretHash(null));
    }
}
