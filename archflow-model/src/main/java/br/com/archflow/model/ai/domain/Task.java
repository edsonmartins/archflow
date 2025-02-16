package br.com.archflow.model.ai.domain;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Representa uma tarefa a ser executada por um agente.
 */
public record Task(
    String id,
    String type,
    Map<String, Object> parameters,
    TaskPriority priority,
    TaskConstraints constraints
) {
    public enum TaskPriority {
        LOW, MEDIUM, HIGH, CRITICAL
    }

    public record TaskConstraints(
        Duration timeout,
        List<String> requiredCapabilities,
        Map<String, Object> limits
    ) {}

    public static Task of(String type, Map<String, Object> parameters) {
        return new Task(
            UUID.randomUUID().toString(),
            type,
            parameters,
            TaskPriority.MEDIUM,
            new TaskConstraints(Duration.ofMinutes(5), List.of(), Map.of())
        );
    }
}