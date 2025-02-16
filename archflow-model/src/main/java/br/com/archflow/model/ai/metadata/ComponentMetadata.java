package br.com.archflow.model.ai.metadata;

import br.com.archflow.model.ai.type.ComponentType;
import java.util.Map;
import java.util.Set;
import java.util.List;

/**
 * Metadados de um componente de IA.
 */
public record ComponentMetadata(
    String id,
    String name,
    String description,
    ComponentType type,
    String version,
    Set<String> capabilities,
    List<OperationMetadata> operations,
    Map<String, Object> properties,
    Set<String> tags
) {
    /**
     * Metadados de uma operação do componente
     */
    public record OperationMetadata(
        String id,
        String name,
        String description,
        List<ParameterMetadata> inputs,
        List<ParameterMetadata> outputs
    ) {}

    /**
     * Metadados de um parâmetro
     */
    public record ParameterMetadata(
        String name,
        String type,
        String description,
        boolean required
    ) {}

    /**
     * Valida se os metadados estão corretos.
     */
    public void validate() {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("ID é obrigatório");
        }
        if (type == null) {
            throw new IllegalArgumentException("Tipo é obrigatório");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("Versão é obrigatória");
        }
        
        // Valida operações
        if (operations != null) {
            operations.forEach(op -> {
                if (op.id() == null || op.id().isBlank()) {
                    throw new IllegalArgumentException("ID da operação é obrigatório");
                }
            });
        }
    }
}