package br.com.archflow.api.approval.dto;

import java.util.Objects;

/**
 * Request para submissão de decisão de aprovação.
 *
 * @param tenantId      ID do tenant
 * @param decision      Decisão: APPROVED, REJECTED ou EDITED
 * @param editedPayload Payload editado (quando decision=EDITED)
 * @param responderId   ID do usuário que tomou a decisão
 */
public record ApprovalSubmitRequest(
        String tenantId,
        String decision,
        Object editedPayload,
        String responderId
) {
    public ApprovalSubmitRequest {
        Objects.requireNonNull(tenantId, "tenantId is required");
        Objects.requireNonNull(decision, "decision is required");
    }
}
