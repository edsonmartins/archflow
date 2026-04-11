package br.com.archflow.conversation.guardrail;

/**
 * Result of a guardrail evaluation with three possible actions:
 *
 * <ul>
 *   <li>{@link Action#ALLOW} — input/output is safe, continue the pipeline unchanged.</li>
 *   <li>{@link Action#BLOCK} — stop processing immediately. The {@code replacement}
 *       holds the message to return to the user instead of the original content.</li>
 *   <li>{@link Action#REDACT} — processing continues but the text is rewritten to
 *       {@code replacement}. Subsequent guardrails see the redacted text, and the
 *       final caller receives the redacted version.</li>
 * </ul>
 *
 * <p>The distinction matters: PII redaction should not abort the conversation,
 * while profanity and missing identification should.
 *
 * @param action      The action the chain should take
 * @param reason      Machine-readable reason ({@code null} when {@link Action#ALLOW})
 * @param replacement Replacement text (required for BLOCK and REDACT; {@code null} for ALLOW)
 */
public record GuardrailResult(Action action, String reason, String replacement) {

    public enum Action {
        ALLOW,
        BLOCK,
        REDACT
    }

    public GuardrailResult {
        if (action == null) throw new IllegalArgumentException("action is required");
        if (action != Action.ALLOW && replacement == null) {
            throw new IllegalArgumentException("replacement is required for " + action);
        }
    }

    public static GuardrailResult ok() {
        return new GuardrailResult(Action.ALLOW, null, null);
    }

    public static GuardrailResult blocked(String reason, String replacement) {
        return new GuardrailResult(Action.BLOCK, reason, replacement);
    }

    public static GuardrailResult redacted(String reason, String replacement) {
        return new GuardrailResult(Action.REDACT, reason, replacement);
    }

    public boolean isAllowed() {
        return action == Action.ALLOW;
    }

    public boolean isBlocked() {
        return action == Action.BLOCK;
    }

    public boolean isRedacted() {
        return action == Action.REDACT;
    }
}
