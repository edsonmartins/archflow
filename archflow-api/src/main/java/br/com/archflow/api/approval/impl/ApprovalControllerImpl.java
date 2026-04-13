package br.com.archflow.api.approval.impl;

import br.com.archflow.api.approval.ApprovalController;
import br.com.archflow.api.approval.dto.ApprovalResponse;
import br.com.archflow.api.approval.dto.ApprovalSubmitRequest;

import java.util.List;
import java.util.Objects;

/**
 * Framework-agnostic implementation of {@link ApprovalController}.
 *
 * <p>Delegates the four HITL endpoints to {@link ApprovalQueueService},
 * which already owns the approval registry, DTO mapping and decision
 * submission. This class exists so bindings (Spring, Jetty, ...) can
 * inject a single controller bean and forward HTTP requests without
 * knowing about the underlying registry.
 */
public class ApprovalControllerImpl implements ApprovalController {

    private final ApprovalQueueService service;

    public ApprovalControllerImpl(ApprovalQueueService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public ApprovalResponse submitDecision(String requestId, ApprovalSubmitRequest request) {
        return service.submitDecision(requestId, request);
    }

    @Override
    public List<ApprovalResponse> listPending(String tenantId) {
        return service.listPending(tenantId);
    }

    @Override
    public ApprovalResponse getApproval(String requestId) {
        return service.getDetail(requestId);
    }
}
