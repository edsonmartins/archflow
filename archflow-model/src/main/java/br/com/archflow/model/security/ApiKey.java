package br.com.archflow.model.security;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * ApiKey represents an API key for programmatic access to the system.
 *
 * API keys are used for service-to-service communication or CLI tools.
 */
public class ApiKey {

    private String id;
    private String keyId;
    private String keySecret; // Hashed, never plaintext
    private String name;
    private String ownerId;
    private Set<ApiKeyScope> scopes = new HashSet<>();
    private boolean enabled = true;
    private LocalDateTime expiresAt;
    private LocalDateTime lastUsedAt;
    private LocalDateTime createdAt;
    private String createdBy;

    public ApiKey() {
        this.createdAt = LocalDateTime.now();
    }

    /**
     * Creates a new API key with a random ID
     */
    public static ApiKey create(String name, String ownerId, Set<ApiKeyScope> scopes) {
        ApiKey apiKey = new ApiKey();
        apiKey.setId(UUID.randomUUID().toString());
        apiKey.setKeyId(UUID.randomUUID().toString());
        apiKey.setName(name);
        apiKey.setOwnerId(ownerId);
        apiKey.setScopes(scopes);
        apiKey.setCreatedAt(LocalDateTime.now());
        return apiKey;
    }

    /**
     * Generates a new key secret (should be hashed before storing)
     */
    public static String generateKeySecret() {
        return "ak_" + UUID.randomUUID().toString().replace("-", "") +
               UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * Checks if the API key is valid (enabled and not expired)
     */
    public boolean isValid() {
        if (!enabled) {
            return false;
        }
        if (expiresAt != null && LocalDateTime.now().isAfter(expiresAt)) {
            return false;
        }
        return true;
    }

    /**
     * Checks if the API key has a specific scope
     */
    public boolean hasScope(ApiKeyScope scope) {
        if (scopes.contains(ApiKeyScope.ADMIN)) {
            return true;
        }
        return scopes.contains(scope);
    }

    /**
     * Checks if the API key has permission for a specific action
     */
    public boolean hasPermission(String resource, String action) {
        if (scopes.contains(ApiKeyScope.ADMIN)) {
            return true;
        }
        String permission = resource + ":" + action;
        return scopes.stream()
                .anyMatch(scope -> scope.asString().equals(permission));
    }

    /**
     * Checks if the API key has permission from a permission string
     */
    public boolean hasPermission(String permissionString) {
        String[] parts = permissionString.split(":");
        if (parts.length != 2) {
            return false;
        }
        return hasPermission(parts[0], parts[1]);
    }

    /**
     * Updates the last used timestamp
     */
    public void markAsUsed() {
        this.lastUsedAt = LocalDateTime.now();
    }

    // Getters and Setters

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    /**
     * Gets the key secret (hashed).
     * WARNING: This should return the hashed value, not the plaintext secret.
     */
    public String getKeySecret() {
        return keySecret;
    }

    public void setKeySecret(String keySecret) {
        this.keySecret = keySecret;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public Set<ApiKeyScope> getScopes() {
        return scopes;
    }

    public void setScopes(Set<ApiKeyScope> scopes) {
        this.scopes = scopes;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }

    public LocalDateTime getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(LocalDateTime lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    /**
     * Checks if the API key is expired
     */
    public boolean isExpired() {
        return expiresAt != null && LocalDateTime.now().isAfter(expiresAt);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ApiKey apiKey = (ApiKey) o;
        return Objects.equals(id, apiKey.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "ApiKey{" +
                "id='" + id + '\'' +
                ", keyId='" + keyId + '\'' +
                ", name='" + name + '\'' +
                ", ownerId='" + ownerId + '\'' +
                ", scopes=" + scopes +
                ", enabled=" + enabled +
                ", expiresAt=" + expiresAt +
                ", createdAt=" + createdAt +
                '}';
    }
}
