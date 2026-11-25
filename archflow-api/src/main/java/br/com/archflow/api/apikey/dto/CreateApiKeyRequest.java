package br.com.archflow.api.apikey.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * Request DTO for creating a new API key.
 *
 * <p>{@code scopes} chega como texto e é resolvido pelo controller contra
 * {@link br.com.archflow.model.security.ApiKeyScope} (o nome da constante, a lista de
 * {@code GET /api/apikeys/scopes}). Com o enum direto no DTO, um escopo desconhecido virava o 400
 * genérico da desserialização, sem dizer qual valor estava errado nem quais valiam (RCHFLW-83).
 */
public record CreateApiKeyRequest(
        @NotBlank(message = "Name is required")
        String name,

        @NotNull(message = "Scopes are required")
        Set<String> scopes,

        /**
         * Optional expiration date. If null, the key never expires.
         */
        LocalDateTime expiresAt
) {
}
