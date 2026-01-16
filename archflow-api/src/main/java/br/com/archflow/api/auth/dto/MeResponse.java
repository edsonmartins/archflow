package br.com.archflow.api.auth.dto;

import java.time.Instant;
import java.util.Set;

/**
 * Response DTO for current user info (/api/auth/me).
 */
public record MeResponse(
        String id,
        String username,
        String email,
        Set<String> roles,
        boolean enabled,
        Instant createdAt,
        Instant lastLoginAt
) {
}
