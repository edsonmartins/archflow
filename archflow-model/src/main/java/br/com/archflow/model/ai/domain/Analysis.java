package br.com.archflow.model.ai.domain;

import java.util.List;
import java.util.Map;

/**
 * Resultado de uma análise de requisição.
 */
public record Analysis(
    String intent,
    Map<String, Object> entities,
    double confidence,
    List<String> suggestedActions
) {}