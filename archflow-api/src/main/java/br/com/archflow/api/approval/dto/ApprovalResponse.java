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
        String comment
) {
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
                expiresAt, null, null, null);
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
