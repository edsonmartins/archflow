package br.com.archflow.plugin.api.model;

import br.com.archflow.plugin.api.type.ParameterType;

import java.util.List;

/**
 * Metadata de um parâmetro.
 */
public record ParameterMetadata(
    String id,
    String name,
    String description,
    ParameterType type,
    boolean required,
    String defaultValue,
    List<String> allowedValues,
    String validation
) {}