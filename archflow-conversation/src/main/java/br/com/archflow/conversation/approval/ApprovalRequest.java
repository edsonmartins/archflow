package br.com.archflow.conversation.approval;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Requisição de aprovação humana gerada quando um fluxo entra em AWAITING_APPROVAL.
 *
 * <p>O agente gera uma proposta (payload genérico) e suspende o fluxo
 * aguardando decisão humana. O motor mantém o mapeamento requestId → flowId
 * para retomada correta.
 *
 * @param requestId   ID único desta requisição de aprovação
 * @param tenantId    ID do tenant
 * @param flowId      ID do fluxo suspenso
 * @param stepId      ID do step que solicitou aprovação
 * @param proposal    Payload da proposta (o motor não interpreta — o produto define)
 * @param description Descrição legível da aprovação solicitada
 * @param timeout     Tempo máximo de espera pela decisão
 * @param createdAt   Timestamp de criação
 */
public record ApprovalRequest(
        String requestId,
        String tenantId,
        String flowId,
        String stepId,
        Object proposal,
        String description,
        Duration timeout,
        Instant createdAt
) {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(30);

    public ApprovalRequest {
        Objects.requireNonNull(tenantId, "tenantId is required");
        Objects.requireNonNull(flowId, "flowId is required");
        if (requestId == null) requestId = UUID.randomUUID().toString();
        if (timeout == null) timeout = DEFAULT_TIMEOUT;
        if (createdAt == null) createdAt = Instant.now();
    }

    /**
     * Verifica se esta requisição de aprovação expirou.
     */
    public boolean isExpired() {
        return Instant.now().isAfter(createdAt.plus(timeout));
    }

    /**
     * Retorna o instante em que esta requisição expira.
     */
    public Instant expiresAt() {
        return createdAt.plus(timeout);
    }

    public static ApprovalRequest of(String tenantId, String flowId, String stepId,
                                      Object proposal, String description) {
        return new ApprovalRequest(null, tenantId, flowId, stepId, proposal, description, null, null);
    }

    public static ApprovalRequest of(String tenantId, String flowId, String stepId,
                                      Object proposal, String description, Duration timeout) {
        return new ApprovalRequest(null, tenantId, flowId, stepId, proposal, description, timeout, null);
    }
}
