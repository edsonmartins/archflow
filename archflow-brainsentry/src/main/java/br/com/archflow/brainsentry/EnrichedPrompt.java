package br.com.archflow.brainsentry;

import java.util.List;

/**
 * Result of Brain Sentry prompt interception.
 *
 * @param enhanced Whether the prompt was enriched with context
 * @param originalPrompt The original prompt text
 * @param injectedContext The context injected by Brain Sentry
 * @param memoriesUsed IDs of memories that contributed to the context
 * @param latencyMs Time taken for interception in milliseconds
 */
public record EnrichedPrompt(
        boolean enhanced,
        String originalPrompt,
        String injectedContext,
        List<String> memoriesUsed,
        long latencyMs
) {
    public EnrichedPrompt {
        memoriesUsed = memoriesUsed == null ? List.of() : List.copyOf(memoriesUsed);
    }

    /**
     * Returns the full prompt: injected context + original prompt.
     */
    public String fullPrompt() {
        if (!enhanced || injectedContext == null || injectedContext.isBlank()) {
            return originalPrompt;
        }
        return injectedContext + "\n\n" + originalPrompt;
    }
}
