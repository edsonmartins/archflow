package br.com.archflow.performance.cache;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;

/**
 * Caching decorator for API key service operations.
 *
 * <p>Provides Spring-managed caching for frequent API key lookups
 * with automatic invalidation on revocation.
 *
 * <p>Cache keys are scoped by tenant AND include a hash of the supplied
 * secret: without the tenant a multi-tenant deployment could serve one
 * tenant's entry to another, and without the secret hash a lookup with a
 * WRONG secret would return the cached successful validation.
 */
@Service
public class CachingApiKeyService {

    /**
     * Functional interface for API key validation delegation.
     */
    @FunctionalInterface
    public interface ApiKeyValidator {
        Object validate(String tenantId, String keyId, String keySecret);
    }

    /**
     * Functional interface for API key listing delegation.
     */
    @FunctionalInterface
    public interface ApiKeyLister {
        List<Object> list(String tenantId, String ownerId);
    }

    private ApiKeyValidator validator;
    private ApiKeyLister lister;

    public void setValidator(ApiKeyValidator validator) {
        this.validator = validator;
    }

    public void setLister(ApiKeyLister lister) {
        this.lister = lister;
    }

    @Cacheable(value = "apikeys",
            key = "#tenantId + ':' + #keyId + ':' "
                    + "+ T(br.com.archflow.performance.cache.CachingApiKeyService).secretHash(#keySecret)")
    public Object validateApiKey(String tenantId, String keyId, String keySecret) {
        if (validator != null) {
            return validator.validate(tenantId, keyId, keySecret);
        }
        return null;
    }

    @Cacheable(value = "apikeys-by-owner", key = "#tenantId + ':' + #ownerId")
    public List<Object> listApiKeys(String tenantId, String ownerId) {
        if (lister != null) {
            return lister.list(tenantId, ownerId);
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

    /**
     * SHA-256 (Base64, truncado) do secret para compor a chave de cache sem
     * armazenar o secret em claro no Redis/Caffeine.
     */
    public static String secretHash(String secret) {
        if (secret == null) {
            return "null";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(secret.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
