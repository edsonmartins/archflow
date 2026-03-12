package br.com.archflow.api.marketplace.dto;

/**
 * Request DTO for installing an extension.
 */
public record InstallExtensionRequest(
        String manifestUrl,
        boolean verifySignature
) {
}
