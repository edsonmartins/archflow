package br.com.archflow.model.escalation;

import java.util.Map;
import java.util.Objects;

/**
 * Request payload for {@link EscalationChannel#escalate}.
 *
 * @param tenantId       multi-tenant scope (required)
 * @param conversationId id of the conversation in the external system
 *                       (required)
 * @param targetUserId   specific human agent to assign to, or
 *                       {@code null} to let the external system
 *                       auto-assign based on its own rules
 * @param reason         human-readable justification logged alongside
 *                       the assignment (e.g. {@code "low confidence"})
 * @param context        optional metadata forwarded as-is to the
 *                       external system (intent, confidence score,
 *                       last user message, etc.)
 */
public record EscalationRequest(
        String tenantId,
        String conversationId,
        String targetUserId,
        String reason,
        Map<String, Object> context) {

    public EscalationRequest {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(conversationId, "conversationId");
        if (context == null) context = Map.of();
    }

    public static EscalationRequest of(String tenantId, String conversationId, String reason) {
        return new EscalationRequest(tenantId, conversationId, null, reason, Map.of());
    }
}
