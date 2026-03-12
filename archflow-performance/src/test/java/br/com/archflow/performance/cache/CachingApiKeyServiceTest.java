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
        assertNull(service.validateApiKey("key1", "secret1"));
    }

    @Test
    @DisplayName("should return empty list when no lister set")
    void shouldReturnEmptyListWithoutLister() {
        CachingApiKeyService service = new CachingApiKeyService();
        assertTrue(service.listApiKeys("user1").isEmpty());
    }

    @Test
    @DisplayName("should delegate to validator when set")
    void shouldDelegateToValidator() {
        CachingApiKeyService service = new CachingApiKeyService();
        service.setValidator((keyId, keySecret) -> "validated:" + keyId);

        Object result = service.validateApiKey("key1", "secret1");
        assertEquals("validated:key1", result);
    }

    @Test
    @DisplayName("should delegate to lister when set")
    void shouldDelegateToLister() {
        CachingApiKeyService service = new CachingApiKeyService();
        service.setLister(ownerId -> java.util.List.of("key1", "key2"));

        var result = service.listApiKeys("user1");
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
}
