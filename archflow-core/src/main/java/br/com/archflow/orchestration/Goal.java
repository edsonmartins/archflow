package br.com.archflow.orchestration;

import java.util.Map;

/** A high-level objective handed to {@link Orchestrator#plan} for decomposition. */
public record Goal(String description, Map<String, Object> inputs) {

    public static Goal of(String description) {
        return new Goal(description, Map.of());
    }
}
