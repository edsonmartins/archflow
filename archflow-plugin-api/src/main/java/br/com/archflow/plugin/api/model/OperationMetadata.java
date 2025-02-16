package br.com.archflow.plugin.api.model;

import br.com.archflow.plugin.api.type.OperationType;

import java.util.List;

/**
 * Metadata de uma operação do plugin.
 */
public record OperationMetadata(
    String id,
    String name,
    String description,
    OperationType type,
    List<ParameterMetadata> inputs,
    List<ParameterMetadata> outputs,
    String example
) {}