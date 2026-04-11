package br.com.archflow.api.approval;

import br.com.archflow.api.approval.dto.ApprovalResponse;
import br.com.archflow.api.approval.dto.ApprovalSubmitRequest;
import br.com.archflow.api.approval.impl.ApprovalQueueService;
import br.com.archflow.conversation.approval.ApprovalRegistry;
import br.com.archflow.conversation.approval.ApprovalRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApprovalQueueServiceTest {

    private ApprovalRegistry registry;
    private ApprovalQueueService service;

    @BeforeEach
    void setUp() {
        registry = new ApprovalRegistry();
        service = new ApprovalQueueService(registry);
    }

    private ApprovalRequest sample(String requestId, String tenant, String flowId, String stepId) {
        return new ApprovalRequest(
                requestId,
                tenant,
                flowId,
                stepId,
                Map.of("text", "Draft reply"),
                "Approve outgoing draft",
                null,
                null);
    }

    @Test
    @DisplayName("listPending returns tenant-scoped requests sorted oldest first")
    void listPending() throws InterruptedException {
        registry.register(sample("req-1", "acme", "flow-a", "step-1"));
        Thread.sleep(5);
        registry.register(sample("req-2", "acme", "flow-b", "step-2"));
        Thread.sleep(5);
        registry.register(sample("req-3", "beta", "flow-c", "step-3"));

        List<ApprovalResponse> list = service.listPending("acme");

        assertThat(list).hasSize(2);
        assertThat(list.get(0).requestId()).isEqualTo("req-1");
        assertThat(list.get(1).requestId()).isEqualTo("req-2");
        // Tenant isolation: beta's request must not leak
        assertThat(list).noneMatch(r -> "req-3".equals(r.requestId()));
        // DTO carries description, stepId, createdAt
        assertThat(list.get(0).description()).isEqualTo("Approve outgoing draft");
        assertThat(list.get(0).stepId()).isEqualTo("step-1");
        assertThat(list.get(0).createdAt()).isNotNull();
    }

    @Test
    @DisplayName("pendingCount returns non-expired requests for a tenant")
    void pendingCount() {
        registry.register(sample("req-1", "acme", "flow-a", "step-1"));
        registry.register(sample("req-2", "acme", "flow-a", "step-2"));
        registry.register(sample("req-3", "beta", "flow-b", "step-1"));

        assertThat(service.pendingCount("acme")).isEqualTo(2);
        assertThat(service.pendingCount("beta")).isEqualTo(1);
        assertThat(service.pendingCount("ghost")).isZero();
    }

    @Test
    @DisplayName("getDetail returns a pending request")
    void getDetail() {
        registry.register(sample("req-1", "acme", "flow-a", "step-1"));

        ApprovalResponse detail = service.getDetail("req-1");

        assertThat(detail.requestId()).isEqualTo("req-1");
        assertThat(detail.status()).isEqualTo("PENDING");
        assertThat(detail.flowId()).isEqualTo("flow-a");
        assertThat(detail.proposal()).isInstanceOf(Map.class);
    }

    @Test
    @DisplayName("getDetail throws NoSuchElementException for unknown id")
    void getDetailUnknown() {
        assertThatThrownBy(() -> service.getDetail("ghost"))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("submitDecision forwards APPROVED decision to the registry")
    void submitApproved() {
        registry.register(sample("req-1", "acme", "flow-a", "step-1"));

        ApprovalResponse resp = service.submitDecision("req-1",
                new ApprovalSubmitRequest("acme", "APPROVED", null, "alice"));

        assertThat(resp.status()).isEqualTo("APPROVED");
        // Request was consumed — pendingCount drops to zero
        assertThat(service.pendingCount("acme")).isZero();
    }

    @Test
    @DisplayName("submitDecision handles REJECTED")
    void submitRejected() {
        registry.register(sample("req-1", "acme", "flow-a", "step-1"));

        ApprovalResponse resp = service.submitDecision("req-1",
                new ApprovalSubmitRequest("acme", "rejected", null, "bob"));

        assertThat(resp.status()).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("submitDecision handles EDITED with payload")
    void submitEdited() {
        registry.register(sample("req-1", "acme", "flow-a", "step-1"));

        ApprovalResponse resp = service.submitDecision("req-1",
                new ApprovalSubmitRequest("acme", "EDITED", Map.of("text", "Edited draft"), "carol"));

        assertThat(resp.status()).isEqualTo("EDITED");
    }

    @Test
    @DisplayName("submitDecision rejects unknown decision values")
    void submitUnknownDecision() {
        registry.register(sample("req-1", "acme", "flow-a", "step-1"));

        assertThatThrownBy(() ->
                service.submitDecision("req-1",
                        new ApprovalSubmitRequest("acme", "MAYBE", null, "x")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("submitDecision throws NoSuchElementException for unknown request")
    void submitUnknownRequest() {
        assertThatThrownBy(() ->
                service.submitDecision("ghost",
                        new ApprovalSubmitRequest("acme", "APPROVED", null, "x")))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    @DisplayName("submitDecision twice on the same request throws IllegalStateException")
    void submitTwice() {
        registry.register(sample("req-1", "acme", "flow-a", "step-1"));
        service.submitDecision("req-1",
                new ApprovalSubmitRequest("acme", "APPROVED", null, "x"));

        assertThatThrownBy(() ->
                service.submitDecision("req-1",
                        new ApprovalSubmitRequest("acme", "REJECTED", null, "x")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("constructor rejects null registry")
    void nullRegistry() {
        assertThatThrownBy(() -> new ApprovalQueueService(null))
                .isInstanceOf(NullPointerException.class);
    }
}
