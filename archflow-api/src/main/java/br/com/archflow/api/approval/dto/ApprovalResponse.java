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
 * @param requestId   approval request id
 * @param tenantId    owning tenant
 * @param flowId      flow that issued the request
 * @param stepId      step that triggered the approval (nullable)
 * @param status      PENDING | APPROVED | REJECTED | EDITED | EXPIRED
 * @param description human-readable description set by the agent
 * @param proposal    proposal payload (agent-defined schema)
 * @param createdAt   when the request was registered
 * @param expiresAt   when the request auto-rejects via timeout
 */
public record ApprovalResponse(
        String requestId,
        String tenantId,
        String flowId,
        String stepId,
        String status,
        String description,
        Object proposal,
        Instant createdAt,
        Instant expiresAt
) {
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
