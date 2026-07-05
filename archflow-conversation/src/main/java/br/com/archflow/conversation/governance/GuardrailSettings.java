package br.com.archflow.conversation.governance;

import java.util.List;

/**
 * Política de guardrails por tenant. Alimenta o {@code GuardrailChain} quando
 * plugado por tenant (follow-up).
 *
 * @since 1.0.0
 */
public record GuardrailSettings(
        boolean lgpdEnabled,
        List<String> promptInjectionMarkers,
        List<String> forbiddenOutputs,
        boolean moderationEnabled
) {
    public GuardrailSettings {
        promptInjectionMarkers = promptInjectionMarkers == null ? List.of() : List.copyOf(promptInjectionMarkers);
        forbiddenOutputs = forbiddenOutputs == null ? List.of() : List.copyOf(forbiddenOutputs);
    }

    public static GuardrailSettings defaults() {
        return new GuardrailSettings(
                true,
                List.of("ignore previous", "system:", "role:", "forget instructions"),
                List.of("select *", "insert into", "jdbc:", "api_key", "bearer "),
                false);
    }
}
