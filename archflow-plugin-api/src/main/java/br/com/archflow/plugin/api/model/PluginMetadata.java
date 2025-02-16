package br.com.archflow.plugin.api.model;

import java.util.List;
import java.util.Set;

/**
 * Metadata completa de um plugin.
 * Versão objeto das anotações de metadata.
 */
public record PluginMetadata(
    String id,
    String name,
    String description,
    String version,
    Set<String> categories,
    Set<String> tags,
    String icon,
    VendorInfo vendor,
    List<OperationMetadata> operations,
    List<DependencyInfo> dependencies,
    List<ConfigurationParameter> configurations
) {
    /**
     * Valida se a metadata está correta e completa.
     *
     * @throws br.com.archflow.plugin.api.exception.PluginValidationException se houver erro
     */
    public void validate() {
        // Implementação da validação
    }
}