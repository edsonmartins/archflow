package br.com.archflow.api.apikey.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

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
         * Optional expiration (ISO-8601). Com fuso ou {@code Z} ({@code 2026-12-31T23:59:00Z}) é aquele
         * instante; sem fuso, a hora local do servidor — o que valia antes. Era um {@code LocalDateTime}:
         * o {@code Z} era descartado e uma validade enviada em UTC valia com o atraso do fuso do servidor
         * (RCHFLW-195 item 11). Nula: a chave não vence.
         */
        String expiresAt
) {
}
