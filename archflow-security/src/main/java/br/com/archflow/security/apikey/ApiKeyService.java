package br.com.archflow.security.apikey;

import br.com.archflow.model.security.ApiKey;
import br.com.archflow.model.security.ApiKeyScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * Service for managing API keys.
 *
 * <p>This service handles:
 * <ul>
 *   <li>Creating new API keys with secure random generation</li>
 *   <li>Retrieving API keys by owner</li>
 *   <li>Revoking/disabling API keys</li>
 * </ul>
 */
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String KEY_PREFIX = "ak_";
    private static final int KEY_ID_LENGTH = 16;
    private static final int KEY_SECRET_LENGTH = 32;

    private final ApiKeyRepository apiKeyRepository;

    public ApiKeyService(ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
    }

    /**
     * Creates a new API key for a user.
     *
     * @param ownerId The ID of the user who will own the key
     * @param name The name of the API key
     * @param scopes The scopes (permissions) for the key
     * @param expiresAt Optional expiration date
     * @return The created API key with the secret (only shown once)
     */
    public ApiKeyWithSecret createApiKey(String ownerId, String name,
                                         Set<ApiKeyScope> scopes, LocalDateTime expiresAt) {
        log.info("Creating API key for user: {} with scopes: {}", ownerId, scopes);

        String keyId = generateKeyId();
        String keySecret = generateKeySecret();
        String hashedSecret = hashSecret(keySecret);

        ApiKey apiKey = new ApiKey();
        apiKey.setId(generateId());
        apiKey.setKeyId(keyId);
        apiKey.setKeySecret(hashedSecret);
        apiKey.setOwnerId(ownerId);
        apiKey.setName(name);
        apiKey.setScopes(scopes);
        apiKey.setEnabled(true);
        apiKey.setCreatedAt(LocalDateTime.now());
        apiKey.setExpiresAt(expiresAt);

        ApiKey saved = apiKeyRepository.save(apiKey);

        log.info("API key created: {} for user: {}", keyId, ownerId);

        return new ApiKeyWithSecret(saved, keySecret);
    }

    /**
     * Lists all API keys for a user.
     *
     * @param ownerId The ID of the user
     * @return List of API keys
     */
    public List<ApiKey> listApiKeys(String ownerId) {
        return apiKeyRepository.findByOwnerId(ownerId);
    }

    /**
     * Gets an API key by its ID.
     *
     * @param keyId The public key ID
     * @param ownerId The ID of the user (for ownership verification)
     * @return The API key
     * @throws ApiKeyNotFoundException if the key doesn't exist or doesn't belong to the user
     */
    public ApiKey getApiKey(String keyId, String ownerId) {
        return apiKeyRepository.findByKeyId(keyId)
                .filter(key -> key.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new ApiKeyNotFoundException("API key not found: " + keyId));
    }

    /**
     * Revokes (disables) an API key.
     *
     * @param keyId The public key ID
     * @param ownerId The ID of the user (for ownership verification)
     * @throws ApiKeyNotFoundException if the key doesn't exist or doesn't belong to the user
     */
    public void revokeApiKey(String keyId, String ownerId) {
        ApiKey apiKey = getApiKey(keyId, ownerId);
        apiKey.setEnabled(false);
        apiKeyRepository.save(apiKey);

        log.info("API key revoked: {} for user: {}", keyId, ownerId);
    }

    /**
     * Validates an API key by checking the hash.
     *
     * @param keyId The public key ID
     * @param keySecret The secret to verify
     * @return The API key if valid
     * @throws ApiKeyNotFoundException if the key doesn't exist or the secret is invalid
     */
    public ApiKey validateApiKey(String keyId, String keySecret) {
        ApiKey apiKey = apiKeyRepository.findByKeyId(keyId)
                .orElseThrow(() -> new ApiKeyNotFoundException("API key not found: " + keyId));

        // Comparação constant-time: equals() de String vaza o prefixo
        // coincidente pelo tempo de resposta (timing attack).
        byte[] expected = apiKey.getKeySecret().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] actual = hashSecret(keySecret).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (!java.security.MessageDigest.isEqual(expected, actual)) {
            throw new ApiKeyNotFoundException("Invalid API key secret");
        }

        return apiKey;
    }

    // Private helper methods

    private String generateKeyId() {
        byte[] bytes = new byte[KEY_ID_LENGTH];
        RANDOM.nextBytes(bytes);
        return KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String generateKeySecret() {
        byte[] bytes = new byte[KEY_SECRET_LENGTH];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String generateId() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashSecret(String secret) {
        // SHA-256 sem salt é adequado AQUI (e é a prática de GitHub/Stripe para
        // tokens de API): o segredo é aleatório de 192 bits gerado por CSPRNG —
        // dicionário/rainbow são inviáveis e um KDF caro (BCrypt/Argon2) só
        // adicionaria ~100ms a CADA request autenticado. KDF caro é para senhas
        // escolhidas por humanos (ver PasswordService). A comparação no
        // validateApiKey é constant-time.
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    // Inner classes

    /**
     * Wrapper for API key that includes the secret (only shown once).
     */
    public static class ApiKeyWithSecret {
        private final ApiKey apiKey;
        private final String secret;

        public ApiKeyWithSecret(ApiKey apiKey, String secret) {
            this.apiKey = apiKey;
            this.secret = secret;
        }

        public ApiKey apiKey() {
            return apiKey;
        }

        public String secret() {
            return secret;
        }

        public ApiKey getApiKey() {
            return apiKey;
        }

        public String getSecret() {
            return secret;
        }
    }

    /**
     * Exception thrown when an API key is not found.
     */
    public static class ApiKeyNotFoundException extends RuntimeException {
        public ApiKeyNotFoundException(String message) {
            super(message);
        }
    }

    /**
     * Repository interface for API key persistence.
     */
    public interface ApiKeyRepository {
        ApiKey save(ApiKey apiKey);
        List<ApiKey> findByOwnerId(String ownerId);
        java.util.Optional<ApiKey> findByKeyId(String keyId);
        java.util.Optional<ApiKey> findById(String id);
        void delete(ApiKey apiKey);
    }
}
