package br.com.archflow.model.escalation;

/**
 * Channel to hand a conversation off to a human operator when an
 * agent decides it cannot (or should not) proceed automatically.
 *
 * <p>Implementations are integration-specific (Linktor, Zendesk,
 * Freshdesk…). The archflow core never talks to them directly; it
 * obtains one from {@link EscalationChannels#getDefault()} and
 * invokes {@link #escalate} when an agent emits an
 * {@code "escalate"} decision.</p>
 */
public interface EscalationChannel {

    /**
     * Assigns a conversation to a human (or marks it for pickup) and
     * returns silently on success. Failures propagate as
     * {@link EscalationException} so agents/steps can log/retry.
     *
     * @param request scope + target information; {@code tenantId} and
     *                {@code conversationId} are required
     */
    void escalate(EscalationRequest request) throws EscalationException;

    /** Short identifier for logging/metrics (e.g. {@code "linktor"}). */
    String id();
}
