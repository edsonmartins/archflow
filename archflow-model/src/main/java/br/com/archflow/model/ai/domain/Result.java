package br.com.archflow.model.ai.domain;

import java.util.List;
import java.util.Map;

/**
 * Resultado de uma execução.
 */
public record Result(
    boolean success,
    Object output,
    Map<String, Object> metadata,
    List<String> messages
) {
    public static Result success(Object output) {
        return new Result(true, output, Map.of(), List.of());
    }

    public static Result failure(String message) {
        return new Result(false, null, Map.of(), List.of(message));
    }
}