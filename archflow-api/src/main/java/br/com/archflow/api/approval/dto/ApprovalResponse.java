package br.com.archflow.api.approval.dto;

import java.time.Instant;

/**
 * Approval operation response.
 *
 * <p>Used by both list and detail endpoints. Additional fields
 * ({@code stepId}, {@code description}, {@code createdAt}) expose the
 * context a human operator needs to make an informed decision without a
 * second round-trip.
 *
 * <p>Os campos de decisão ({@code decidedBy}, {@code decidedAt}, {@code comment}) vêm do
 * estado durável que o motor gravou sob o lock da decisão — não do pedido HTTP. São nulos
 * enquanto o pedido está pendente. Sem eles nenhuma leitura respondia "quem autorizou isto"
 * (RCHFLW-38).
 *
 * @param requestId   approval request id
 * @param tenantId    owning tenant
 * @param flowId      flow that issued the request (= execution id)
 * @param stepId      step that triggered the approval (nullable)
 * @param status      PENDING | APPROVED | REJECTED | EDITED | EXPIRED
 * @param title       short title configured on the approval node (nullable)
 * @param description human-readable description set by the agent
 * @param proposal    proposal payload (agent-defined schema)
 * @param createdAt   when the request was registered
 * @param expiresAt   when the request auto-rejects via timeout
 * @param decidedBy   who decided — the authenticated user resolved by the server (nullable)
 * @param decidedAt   when the decision was recorded (nullable)
 * @param comment     the decider's justification (nullable)
 * @param flowName    nome do fluxo da execução, lido do registro de execuções (nullable). A fila
 *                    mostrava o id da execução e do passo no lugar do nome, e contava execuções
 *                    como "fluxos distintos" (RCHFLW-129)
 * @param editedPayload a proposta como quem decidiu a deixou, numa decisão {@code EDITED} (nullable) —
 *                    lida da entrada da decisão no histórico durável (RCHFLW-121)
 */
public record ApprovalResponse(
        String requestId,
        String tenantId,
        String flowId,
        String stepId,
        String status,
        String title,
        String description,
        Object proposal,
        Instant createdAt,
        Instant expiresAt,
        String decidedBy,
        Instant decidedAt,
        String comment,
        String flowName,
        Object editedPayload
) {
    /** Sem nome de fluxo nem proposta editada — a forma anterior a esses campos. */
    public ApprovalResponse(String requestId,
                            String tenantId,
                            String flowId,
                            String stepId,
                            String status,
                            String title,
                            String description,
                            Object proposal,
                            Instant createdAt,
                            Instant expiresAt,
                            String decidedBy,
                            Instant decidedAt,
                            String comment) {
        this(requestId, tenantId, flowId, stepId, status, title, description, proposal, createdAt,
                expiresAt, decidedBy, decidedAt, comment, null, null);
    }

    /** Pedido sem título e ainda sem decisão — a forma anterior aos campos de decisão. */
    public ApprovalResponse(String requestId,
                            String tenantId,
                            String flowId,
                            String stepId,
                            String status,
                            String description,
                            Object proposal,
                            Instant createdAt,
                            Instant expiresAt) {
        this(requestId, tenantId, flowId, stepId, status, null, description, proposal, createdAt,
                expiresAt, null, null, null, null, null);
    }

    /** Backward-compatible constructor used by legacy callers. */
    public ApprovalResponse(String requestId,
                            String tenantId,
                            String flowId,
                            String status,
                            Object proposal,
                            Instant expiresAt) {
        this(requestId, tenantId, flowId, null, status, null, proposal, null, expiresAt);
    }
}
