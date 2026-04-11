package br.com.archflow.conversation.guardrail.builtin;

import br.com.archflow.conversation.guardrail.AgentGuardrail;
import br.com.archflow.conversation.guardrail.GuardrailResult;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Built-in identification guardrail. Blocks business operations until the
 * customer is identified (e.g., CNPJ, account number, customer id provided).
 *
 * <p>Mirrors the SAC agent's identification guardrail. Greetings, confirmations,
 * and short messages are bypassed so the guardrail never kicks in on small talk.
 *
 * <p>Fully configurable: all regex patterns (bypass + business) are injectable,
 * so the class is usable for any locale. A static {@link #portuguese()} factory
 * ships Brazilian-Portuguese defaults for SAC-style agents.
 *
 * <p>The {@code context} map must contain a boolean under the configured
 * {@code identifiedKey} indicating whether the customer is already identified.
 */
public class IdentificationGuardrail implements AgentGuardrail {

    public static final String DEFAULT_IDENTIFIED_KEY = "identified";

    private final List<Pattern> bypassPatterns;
    private final List<Pattern> businessPatterns;
    private final int minLengthToInspect;
    private final String identifiedKey;
    private final String replacement;

    private IdentificationGuardrail(Builder b) {
        this.bypassPatterns = List.copyOf(b.bypassPatterns);
        this.businessPatterns = List.copyOf(b.businessPatterns);
        this.minLengthToInspect = b.minLengthToInspect;
        this.identifiedKey = b.identifiedKey;
        this.replacement = b.replacement;
    }

    /**
     * Default constructor: Brazilian Portuguese SAC patterns.
     */
    public IdentificationGuardrail() {
        this(portugueseBuilder());
    }

    /**
     * Explicit constructor retained for backward compatibility. Prefer
     * {@link #builder()} or {@link #portuguese()} in new code.
     */
    public IdentificationGuardrail(List<Pattern> businessPatterns, String identifiedKey, String replacement) {
        this(portugueseBuilder()
                .businessPatterns(businessPatterns)
                .identifiedKey(identifiedKey)
                .replacement(replacement));
    }

    /**
     * Ships a Portuguese (Brazilian SAC) configuration.
     */
    public static IdentificationGuardrail portuguese() {
        return portugueseBuilder().build();
    }

    /**
     * Starts a builder with empty patterns — caller must provide language-specific rules.
     */
    public static Builder builder() {
        return new Builder();
    }

    private static Builder portugueseBuilder() {
        return new Builder()
                .bypassPatterns(List.of(
                        Pattern.compile("^(oi|olá|ola|bom dia|boa tarde|boa noite)$", Pattern.CASE_INSENSITIVE),
                        Pattern.compile("^(ok|obrigado|obrigada|valeu|tchau|até logo)$", Pattern.CASE_INSENSITIVE)
                ))
                .businessPatterns(List.of(
                        Pattern.compile("rastrear", Pattern.CASE_INSENSITIVE),
                        Pattern.compile("consultar", Pattern.CASE_INSENSITIVE),
                        Pattern.compile("\\bpedido\\b", Pattern.CASE_INSENSITIVE),
                        Pattern.compile("nota fiscal", Pattern.CASE_INSENSITIVE),
                        Pattern.compile("\\bboleto\\b", Pattern.CASE_INSENSITIVE),
                        Pattern.compile("\\bentrega\\b", Pattern.CASE_INSENSITIVE),
                        Pattern.compile("reclamação", Pattern.CASE_INSENSITIVE),
                        Pattern.compile("reclamar", Pattern.CASE_INSENSITIVE)
                ))
                .minLengthToInspect(3)
                .identifiedKey(DEFAULT_IDENTIFIED_KEY)
                .replacement("Para te ajudar, por favor informe seu CNPJ.");
    }

    @Override
    public String getName() {
        return "identification";
    }

    @Override
    public GuardrailResult evaluateInput(String message, Map<String, Object> context) {
        if (message == null || message.isBlank()) return GuardrailResult.ok();

        String trimmed = message.trim();

        for (Pattern p : bypassPatterns) {
            if (p.matcher(trimmed).matches()) return GuardrailResult.ok();
        }

        if (trimmed.length() < minLengthToInspect) return GuardrailResult.ok();

        boolean isBusinessRequest = businessPatterns.stream()
                .anyMatch(p -> p.matcher(message).find());
        if (!isBusinessRequest) return GuardrailResult.ok();

        Object identifiedObj = context.get(identifiedKey);
        boolean identified = identifiedObj instanceof Boolean && (Boolean) identifiedObj;

        if (!identified) {
            return GuardrailResult.blocked("missing_identification", replacement);
        }
        return GuardrailResult.ok();
    }

    public static final class Builder {
        private List<Pattern> bypassPatterns = List.of();
        private List<Pattern> businessPatterns = List.of();
        private int minLengthToInspect = 3;
        private String identifiedKey = DEFAULT_IDENTIFIED_KEY;
        private String replacement = "Please provide your identification to proceed.";

        public Builder bypassPatterns(List<Pattern> patterns) {
            this.bypassPatterns = patterns;
            return this;
        }

        public Builder businessPatterns(List<Pattern> patterns) {
            this.businessPatterns = patterns;
            return this;
        }

        public Builder minLengthToInspect(int min) {
            if (min < 0) throw new IllegalArgumentException("min must be >= 0");
            this.minLengthToInspect = min;
            return this;
        }

        public Builder identifiedKey(String key) {
            this.identifiedKey = key;
            return this;
        }

        public Builder replacement(String text) {
            this.replacement = text;
            return this;
        }

        public IdentificationGuardrail build() {
            return new IdentificationGuardrail(this);
        }
    }
}
