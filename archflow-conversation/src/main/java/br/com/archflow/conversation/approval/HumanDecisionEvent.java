package br.com.archflow.conversation.approval;

import java.time.Instant;
import java.util.Objects;

/**
 * Evento publicado pelo produto (ex: VendaX) para retomar um fluxo suspenso
 * em AWAITING_APPROVAL.
 *
 * <p>O motor recebe este evento, localiza o fluxo suspenso via requestId
 * e retoma a execução com base na decisão humana.
 *
 * @param requestId     ID da requisição de aprovação original
 * @param tenantId      ID do tenant
 * @param decision      Decisão: APPROVED, REJECTED ou EDITED
 * @param editedPayload Payload editado (preenchido quando decision=EDITED)
 * @param responderId   ID do usuário que tomou a decisão (opcional)
 * @param respondedAt   Timestamp da decisão
 */
public record HumanDecisionEvent(
        String requestId,
        String tenantId,
        Decision decision,
        Object editedPayload,
        String responderId,
        Instant respondedAt
) {
    public HumanDecisionEvent {
        Objects.requireNonNull(requestId, "requestId is required");
        Objects.requireNonNull(tenantId, "tenantId is required");
        Objects.requireNonNull(decision, "decision is required");
        if (respondedAt == null) respondedAt = Instant.now();
    }

    public static HumanDecisionEvent approved(String requestId, String tenantId) {
        return new HumanDecisionEvent(requestId, tenantId, Decision.APPROVED, null, null, null);
    }

    public static HumanDecisionEvent rejected(String requestId, String tenantId) {
        return new HumanDecisionEvent(requestId, tenantId, Decision.REJECTED, null, null, null);
    }

    public static HumanDecisionEvent edited(String requestId, String tenantId, Object editedPayload) {
        return new HumanDecisionEvent(requestId, tenantId, Decision.EDITED, editedPayload, null, null);
    }
}
