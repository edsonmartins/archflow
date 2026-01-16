package br.com.archflow.api.auth.dto;

import java.time.Instant;

/**
 * Response DTO for token refresh.
 */
public record RefreshTokenResponse(
        String accessToken,
        String refreshToken,
        long expiresIn,
        Instant expiresAt
) {
}
