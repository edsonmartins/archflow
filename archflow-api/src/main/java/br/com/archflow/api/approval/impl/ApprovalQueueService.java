package br.com.archflow.api.approval.impl;

import br.com.archflow.api.approval.dto.ApprovalResponse;
import br.com.archflow.api.approval.dto.ApprovalSubmitRequest;
import br.com.archflow.conversation.approval.ApprovalRegistry;
import br.com.archflow.conversation.approval.ApprovalRequest;
import br.com.archflow.conversation.approval.Decision;
import br.com.archflow.conversation.approval.HumanDecisionEvent;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * Application-level facade on top of
 * {@link ApprovalRegistry} that exposes the queue of pending
 * human-in-the-loop approvals to the admin UI.
 *
 * <p>The underlying registry already owns the single source of truth
 * (ConcurrentHashMap + expiration). This service only adds:
 * <ul>
 *   <li>DTO mapping tailored for the REST layer.</li>
 *   <li>Submission of {@link HumanDecisionEvent}s built from
 *       {@link ApprovalSubmitRequest}.</li>
 *   <li>A {@link #pendingCount(String)} helper for the navbar badge.</li>
 * </ul>
 *
 * <p>The service is deliberately framework-agnostic — it's plugged into
 * the binding layer (Spring WebFlux, Jetty, ...) through a thin
 * controller implementation.
 */
public class ApprovalQueueService {

    private final ApprovalRegistry registry;

    public ApprovalQueueService(ApprovalRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * Returns pending approvals for the given tenant, sorted by creation
     * time (oldest first so the UI surfaces the ones that have been
     * waiting longest).
     */
    public List<ApprovalResponse> listPending(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        return registry.listPendingByTenant(tenantId).stream()
                .sorted((a, b) -> a.createdAt().compareTo(b.createdAt()))
                .map(r -> toResponse(r, "PENDING"))
                .toList();
    }

    /**
     * Returns the full detail of a single approval request. Throws
     * {@link NoSuchElementException} when the id is unknown or already
     * decided.
     */
    public ApprovalResponse getDetail(String requestId) {
        Objects.requireNonNull(requestId, "requestId");
        Optional<ApprovalRequest> pending = registry.getPending(requestId);
        if (pending.isPresent()) {
            return toResponse(pending.get(), "PENDING");
        }
        throw new NoSuchElementException("Approval not found or already decided: " + requestId);
    }

    /**
     * Submits a decision to the registry. Converts the string form of the
     * {@link Decision} and wraps the call in a single atomic operation.
     */
    public ApprovalResponse submitDecision(String requestId, ApprovalSubmitRequest request) {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(request, "request");

        Decision decision;
        try {
            decision = Decision.valueOf(request.decision().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown decision '" + request.decision() + "' — expected APPROVED|REJECTED|EDITED");
        }

        HumanDecisionEvent event = new HumanDecisionEvent(
                requestId,
                request.tenantId(),
                decision,
                request.editedPayload(),
                request.responderId(),
                null);

        Optional<ApprovalRequest> result;
        try {
            result = registry.submitDecision(event);
        } catch (IllegalStateException alreadyDecided) {
            throw new IllegalStateException("Approval already decided: " + requestId);
        } catch (IllegalArgumentException notFound) {
            throw new NoSuchElementException("Approval not found: " + requestId);
        }

        ApprovalRequest original = result.orElseThrow(() ->
                new IllegalStateException("Approval expired: " + requestId));
        return toResponse(original, decision.name());
    }

    /**
     * Number of pending approvals for a tenant — used by the navbar
     * badge via short-polling or SSE refresh.
     */
    public int pendingCount(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        return registry.listPendingByTenant(tenantId).size();
    }

    // ── helpers ──────────────────────────────────────────────────

    private static ApprovalResponse toResponse(ApprovalRequest request, String status) {
        return new ApprovalResponse(
                request.requestId(),
                request.tenantId(),
                request.flowId(),
                request.stepId(),
                status,
                request.description(),
                request.proposal(),
                request.createdAt(),
                request.expiresAt());
    }
}
