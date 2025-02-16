package br.com.archflow.model.ai.domain;

import java.util.List;

/**
 * Descrição de um parâmetro de ferramenta.
 */
public record ParameterDescription(
    String name,
    String type,
    String description,
    boolean required,
    Object defaultValue,
    List<String> allowedValues
) {}