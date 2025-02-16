package br.com.archflow.model.ai.domain;

import java.util.Map;

/**
 * Representa uma ação que pode ser executada por um componente.
 */
public record Action(
    String type,
    String name,
    Map<String, Object> parameters,
    boolean immediate
) {
    public static Action of(String type, String name) {
        return new Action(type, name, Map.of(), true);
    }

    public static Action of(String type, String name, Map<String, Object> parameters) {
        return new Action(type, name, parameters, true);
    }
}