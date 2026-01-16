package br.com.archflow.api.auth.dto;

import java.time.Instant;
import java.util.Set;

/**
 * Response DTO for successful login.
 */
public record LoginResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,        // seconds until access token expires
        Instant expiresAt,
        String userId,
        String username,
        String email,
        Set<String> roles
) {
    public LoginResponse {
        if (tokenType == null || tokenType.isEmpty()) {
            tokenType = "Bearer";
        }
    }
}
