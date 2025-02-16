package br.com.archflow.model.ai.domain;

import java.util.List;
import java.util.Map;

/**
 * Resposta gerada por um assistente.
 */
public record Response(
    String content,
    Map<String, Object> metadata,
    List<Action> actions
) {}