package br.com.archflow.api.apikey.dto;

import br.com.archflow.model.security.ApiKeyScope;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * Response DTO for creating a new API key.
 *
 * <p>The keySecret is only shown once during creation. Store it securely.</p>
 */
public record CreateApiKeyResponse(
        String id,
        String keyId,          // Public identifier
        String keySecret,      // Full secret (shown only once)
        String name,
        Set<ApiKeyScope> scopes,
        LocalDateTime createdAt,
        LocalDateTime expiresAt,
        LocalDateTime lastUsedAt,
        boolean enabled
) {
}
