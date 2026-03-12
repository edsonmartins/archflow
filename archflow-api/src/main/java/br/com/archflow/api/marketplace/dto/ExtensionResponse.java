package br.com.archflow.api.marketplace.dto;

import java.util.Set;

/**
 * Response DTO for extension information.
 */
public record ExtensionResponse(
        String id,
        String name,
        String version,
        String displayName,
        String author,
        String description,
        String type,
        Set<String> permissions,
        boolean installed
) {
}
