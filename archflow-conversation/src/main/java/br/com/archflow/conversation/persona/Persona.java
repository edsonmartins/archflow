package br.com.archflow.conversation.persona;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Definition of an agent persona — a specialized "mode" of the agent
 * with its own system prompt, allowed tools, and intent classification rules.
 *
 * <p>Mirrors the SAC agent's {@code AgentType} enum but generalized so any
 * product can register its own personas. Examples from SAC: ORDER_TRACKING,
 * CUSTOMER_SUPPORT, PRODUCT_RECOMMENDATION, PRICE_NEGOTIATION.
 *
 * @param id              Unique persona identifier (e.g., "order_tracking")
 * @param label           Human-readable label
 * @param description     Description of what this persona does
 * @param promptId        Reference to the system prompt template (in PromptRegistry)
 * @param allowedTools    List of tool names this persona can invoke
 * @param keywordPatterns Regex patterns for keyword-based intent detection
 */
public record Persona(
        String id,
        String label,
        String description,
        String promptId,
        List<String> allowedTools,
        List<Pattern> keywordPatterns
) {
    public Persona {
        Objects.requireNonNull(id, "id is required");
        Objects.requireNonNull(label, "label is required");
        if (description == null) description = label;
        if (allowedTools == null) allowedTools = List.of();
        if (keywordPatterns == null) keywordPatterns = List.of();
    }

    /**
     * Builder for keyword-based persona setup.
     */
    public static Persona of(String id, String label, String promptId,
                              List<String> allowedTools, String... keywordRegexes) {
        List<Pattern> patterns = java.util.Arrays.stream(keywordRegexes)
                .map(r -> Pattern.compile(r, Pattern.CASE_INSENSITIVE))
                .toList();
        return new Persona(id, label, label, promptId, allowedTools, patterns);
    }

    /**
     * Returns true if any of this persona's keyword patterns match the message.
     */
    public boolean matchesKeywords(String message) {
        if (message == null) return false;
        return keywordPatterns.stream().anyMatch(p -> p.matcher(message).find());
    }
}
