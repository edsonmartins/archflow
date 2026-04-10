package br.com.archflow.api.admin.dto;

public record ApiKeyDto(
        String id,
        String name,
        String type,
        String prefix,
        String maskedKey,
        String createdAt,
        String lastUsedAt
) {
    public record CreateApiKeyRequest(
            String name,
            String type
    ) {}

    public record CreateApiKeyResponse(
            String id,
            String name,
            String type,
            String prefix,
            String maskedKey,
            String fullKey,
            String createdAt
    ) {}
}
