package br.com.archflow.api.apikey.dto;

import br.com.archflow.model.security.ApiKeyScope;
import java.time.Instant;
import java.util.Set;

/**
 * Response DTO for creating a new API key.
 *
 * <p>The keySecret is only shown once during creation. Store it securely.</p>
 *
 * <p>{@code apiKey} é a credencial completa ({@code <keyId>.<keySecret>}) que o cliente envia em
 * {@code Authorization: Bearer <apiKey>} — o segredo sozinho não autentica, porque a busca é pelo id
 * público. Como o segredo, só aparece nesta resposta (RCHFLW-84).</p>
 */
public record CreateApiKeyResponse(
        String id,
        String keyId,          // Public identifier
        String keySecret,      // Full secret (shown only once)
        String apiKey,         // Credential for "Authorization: Bearer" (shown only once)
        String name,
        Set<ApiKeyScope> scopes,
        Instant createdAt,
        Instant expiresAt,
        Instant lastUsedAt,
        boolean enabled
) {
}
