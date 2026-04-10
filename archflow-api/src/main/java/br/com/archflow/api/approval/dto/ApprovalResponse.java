package br.com.archflow.api.approval.dto;

import java.time.Instant;

/**
 * Resposta de operações de aprovação.
 *
 * @param requestId ID da requisição de aprovação
 * @param tenantId  ID do tenant
 * @param flowId    ID do fluxo associado
 * @param status    Status (PENDING, APPROVED, REJECTED, EDITED, EXPIRED)
 * @param proposal  Proposta original (em listagens)
 * @param expiresAt Timestamp de expiração
 */
public record ApprovalResponse(
        String requestId,
        String tenantId,
        String flowId,
        String status,
        Object proposal,
        Instant expiresAt
) {}
