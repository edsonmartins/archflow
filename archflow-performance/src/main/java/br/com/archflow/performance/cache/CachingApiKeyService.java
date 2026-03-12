package br.com.archflow.performance.cache;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Caching decorator for API key service operations.
 *
 * <p>Provides Spring-managed caching for frequent API key lookups
 * with automatic invalidation on revocation.
 */
@Service
public class CachingApiKeyService {

    /**
     * Functional interface for API key validation delegation.
     */
    @FunctionalInterface
    public interface ApiKeyValidator {
        Object validate(String keyId, String keySecret);
    }

    /**
     * Functional interface for API key listing delegation.
     */
    @FunctionalInterface
    public interface ApiKeyLister {
        List<Object> list(String ownerId);
    }

    private ApiKeyValidator validator;
    private ApiKeyLister lister;

    public void setValidator(ApiKeyValidator validator) {
        this.validator = validator;
    }

    public void setLister(ApiKeyLister lister) {
        this.lister = lister;
    }

    @Cacheable(value = "apikeys", key = "#keyId")
    public Object validateApiKey(String keyId, String keySecret) {
        if (validator != null) {
            return validator.validate(keyId, keySecret);
        }
        return null;
    }

    @Cacheable(value = "apikeys-by-owner", key = "#ownerId")
    public List<Object> listApiKeys(String ownerId) {
        if (lister != null) {
            return lister.list(ownerId);
        }
        return List.of();
    }

    @CacheEvict(value = {"apikeys", "apikeys-by-owner"}, allEntries = true)
    public void onApiKeyRevoked(String keyId) {
        // Cache eviction trigger
    }

    @CacheEvict(value = {"apikeys", "apikeys-by-owner"}, allEntries = true)
    public void onApiKeyCreated(String ownerId) {
        // Cache eviction trigger
    }
}
