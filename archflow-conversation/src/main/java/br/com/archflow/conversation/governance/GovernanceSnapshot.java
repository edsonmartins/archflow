package br.com.archflow.conversation.governance;

import br.com.archflow.model.config.LLMConfigPatch;

import java.time.Instant;

/**
 * Visão resolvida da governança de um tenant num instante. Carrega de onde veio
 * ({@code fromDatabase}) e faz a ponte com o D2 via {@link #llmPatch()}.
 *
 * @since 1.0.0
 */
public record GovernanceSnapshot(
        String profileId,
        String tenantId,
        GovernanceSettings settings,
        boolean fromDatabase,
        Instant resolvedAt
) {
    public GovernanceSnapshot {
        if (settings == null) {
            settings = GovernanceSettings.defaults();
        }
    }

    /** Patch de LLM do tier {@code tenant} para a cadeia de resolução (D2). */
    public LLMConfigPatch llmPatch() {
        return settings.llm().patch();
    }
}
