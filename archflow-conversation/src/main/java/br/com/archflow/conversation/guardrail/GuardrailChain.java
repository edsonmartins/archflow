package br.com.archflow.conversation.guardrail;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Chain of {@link AgentGuardrail}s evaluated in order.
 *
 * <p>Semantics per action:
 * <ul>
 *   <li>{@link GuardrailResult.Action#ALLOW} — move to the next guardrail with the
 *       current text unchanged.</li>
 *   <li>{@link GuardrailResult.Action#BLOCK} — short-circuit; return the block
 *       immediately. The original text is discarded.</li>
 *   <li>{@link GuardrailResult.Action#REDACT} — replace the text with
 *       {@link GuardrailResult#replacement()} and pass the redacted version to
 *       subsequent guardrails. The final chain result keeps track of all applied
 *       redactions in {@link ChainResult#redactionReasons()}.</li>
 * </ul>
 */
public class GuardrailChain {

    private final List<AgentGuardrail> guardrails;

    public GuardrailChain(List<AgentGuardrail> guardrails) {
        this.guardrails = List.copyOf(guardrails);
    }

    /**
     * Outcome of running the full chain.
     *
     * @param finalText        Text after all applied redactions (or the original if none).
     * @param blocked          Whether a guardrail blocked the pipeline.
     * @param blockReason      Reason when blocked, else {@code null}.
     * @param blockMessage     User-facing replacement when blocked, else {@code null}.
     * @param redactionReasons Ordered list of redaction reasons applied (empty if none).
     */
    public record ChainResult(
            String finalText,
            boolean blocked,
            String blockReason,
            String blockMessage,
            List<String> redactionReasons
    ) {
        public boolean wasRedacted() {
            return !redactionReasons.isEmpty();
        }
    }

    /**
     * Run input evaluation through all guardrails.
     */
    public ChainResult evaluateInput(String message, Map<String, Object> context) {
        return run(message, context, true);
    }

    /**
     * Run output evaluation through all guardrails.
     */
    public ChainResult evaluateOutput(String response, Map<String, Object> context) {
        return run(response, context, false);
    }

    private ChainResult run(String text, Map<String, Object> context, boolean input) {
        String current = text;
        List<String> redactions = new ArrayList<>();
        for (AgentGuardrail g : guardrails) {
            GuardrailResult r = input
                    ? g.evaluateInput(current, context)
                    : g.evaluateOutput(current, context);
            switch (r.action()) {
                case ALLOW -> { /* continue */ }
                case BLOCK -> {
                    return new ChainResult(text, true, r.reason(), r.replacement(), List.copyOf(redactions));
                }
                case REDACT -> {
                    current = r.replacement();
                    redactions.add(r.reason());
                }
            }
        }
        return new ChainResult(current, false, null, null, List.copyOf(redactions));
    }

    public List<AgentGuardrail> getGuardrails() {
        return guardrails;
    }
}
