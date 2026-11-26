package br.com.archflow.api.apikey.dto;

import br.com.archflow.model.security.ApiKeyScope;
import java.time.Instant;
import java.util.Set;

/**
 * Response DTO for API key information (without secret).
 */
public record ApiKeyResponse(
        String id,
        String keyId,          // Public identifier
        String name,
        Set<ApiKeyScope> scopes,
        Instant createdAt,
        Instant expiresAt,
        Instant lastUsedAt,
        boolean enabled,
        // Vencida pela validade: não autentica (ApiKeyService.authenticate) e não conta no resumo do
        // workspace, mas a tela a mostrava como "ativa" porque só olhava `enabled` (RCHFLW-195 item 12).
        boolean expired
) {
}
