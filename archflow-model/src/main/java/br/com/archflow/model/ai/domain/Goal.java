package br.com.archflow.model.ai.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Representa um objetivo que um agente deve alcançar.
 */
public record Goal(
    String id,
    String description,
    List<String> successCriteria,
    Map<String, Object> context,
    GoalPriority priority
) {
    public enum GoalPriority {
        LOW, MEDIUM, HIGH, CRITICAL
    }

    public static Goal of(String description, List<String> successCriteria) {
        return new Goal(
            UUID.randomUUID().toString(),
            description,
            successCriteria,
            Map.of(),
            GoalPriority.MEDIUM
        );
    }

    public static Goal of(String description, String... successCriteria) {
        return of(description, Arrays.asList(successCriteria));
    }
}