package br.com.archflow.api.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO for refreshing an access token.
 */
public record RefreshTokenRequest(
        @NotBlank(message = "Refresh token is required")
        String refreshToken
) {
}
