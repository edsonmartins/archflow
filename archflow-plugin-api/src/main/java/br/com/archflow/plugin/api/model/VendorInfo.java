package br.com.archflow.plugin.api.model;

/**
 * Informações do vendor do plugin.
 */
public record VendorInfo(
    String name,
    String url,
    String email,
    String license
) {}