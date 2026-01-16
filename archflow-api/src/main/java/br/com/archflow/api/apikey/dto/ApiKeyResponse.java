package br.com.archflow.api.apikey.dto;

import br.com.archflow.model.security.ApiKeyScope;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * Response DTO for API key information (without secret).
 */
public record ApiKeyResponse(
        String id,
        String keyId,          // Public identifier
        String name,
        Set<ApiKeyScope> scopes,
        LocalDateTime createdAt,
        LocalDateTime expiresAt,
        LocalDateTime lastUsedAt,
        boolean enabled
) {
}
