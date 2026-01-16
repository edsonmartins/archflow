package br.com.archflow.api.apikey.dto;

import br.com.archflow.model.security.ApiKeyScope;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * Request DTO for creating a new API key.
 */
public record CreateApiKeyRequest(
        @NotBlank(message = "Name is required")
        String name,

        @NotNull(message = "Scopes are required")
        Set<ApiKeyScope> scopes,

        /**
         * Optional expiration date. If null, the key never expires.
         */
        LocalDateTime expiresAt
) {
}
