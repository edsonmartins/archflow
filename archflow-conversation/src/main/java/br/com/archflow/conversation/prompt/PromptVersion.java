package br.com.archflow.conversation.prompt;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Versioned system prompt template.
 *
 * <p>A {@code PromptVersion} is an immutable snapshot of a prompt template at
 * a specific version. Templates support {@code {{variable}}} placeholders that
 * are resolved at render time.
 *
 * <p>Inspired by SAC agent's {@code SystemPromptVersionEntity} but without
 * the JPA dependency — products can persist this however they want.
 *
 * @param promptId  Logical identifier (e.g., "sac.order_tracking")
 * @param tenantId  Owner tenant
 * @param version   Version number (monotonic)
 * @param template  Template string with {@code {{var}}} placeholders
 * @param active    Whether this version is the active one for the prompt
 * @param createdAt Creation timestamp
 */
public record PromptVersion(
        String promptId,
        String tenantId,
        int version,
        String template,
        boolean active,
        Instant createdAt
) {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([\\w.-]+)\\s*}}");

    public PromptVersion {
        Objects.requireNonNull(promptId, "promptId is required");
        Objects.requireNonNull(tenantId, "tenantId is required");
        Objects.requireNonNull(template, "template is required");
        if (version < 1) throw new IllegalArgumentException("version must be >= 1");
        if (createdAt == null) createdAt = Instant.now();
    }

    /**
     * Render the template with the provided variables.
     * Unresolved placeholders are left as-is.
     */
    public String render(Map<String, Object> variables) {
        if (variables == null || variables.isEmpty()) return template;
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            Object value = variables.get(key);
            String replacement = value != null ? value.toString() : m.group(0);
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Returns a copy with active flag changed.
     */
    public PromptVersion withActive(boolean newActive) {
        return new PromptVersion(promptId, tenantId, version, template, newActive, createdAt);
    }
}
