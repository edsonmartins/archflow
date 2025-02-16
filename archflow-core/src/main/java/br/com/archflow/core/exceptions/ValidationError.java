package br.com.archflow.core.exceptions;

import java.util.Map;

/**
 * Erro de validação específico.
 */
public record ValidationError(
    String field,
    String message,
    String code,
    Map<String, Object> context
) {}