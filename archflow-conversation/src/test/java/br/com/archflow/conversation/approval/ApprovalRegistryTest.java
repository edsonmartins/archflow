package br.com.archflow.conversation.approval;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("ApprovalRegistry")
class ApprovalRegistryTest {

    private ApprovalRegistry registry;
    private List<String> learningEvents;

    @BeforeEach
    void setUp() {
        learningEvents = new ArrayList<>();
        LearningCallback callback = new LearningCallback() {
            @Override
            public void onRejected(String tenantId, String requestId, Object proposal) {
                learningEvents.add("REJECTED:" + requestId);
            }

            @Override
            public void onEdited(String tenantId, String requestId, Object original, Object edited) {
                learningEvents.add("EDITED:" + requestId);
            }
        };
        registry = new ApprovalRegistry(callback);
    }

    @Test
    @DisplayName("should register and retrieve pending approval")
    void shouldRegisterAndRetrieve() {
        var request = ApprovalRequest.of("t1", "flow-1", "step-1", "proposal data", "Please approve");
        registry.register(request);

        assertThat(registry.pendingCount()).isEqualTo(1);
        var retrieved = registry.getPending(request.requestId());
        assertThat(retrieved).isPresent();
        assertThat(retrieved.get().flowId()).isEqualTo("flow-1");
        assertThat(retrieved.get().proposal()).isEqualTo("proposal data");
    }

    @Test
    @DisplayName("should reject duplicate registration")
    void shouldRejectDuplicate() {
        var request = ApprovalRequest.of("t1", "f1", "s1", "p", "d");
        registry.register(request);

        assertThatThrownBy(() -> registry.register(request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("should submit APPROVED decision and return original request")
    void shouldSubmitApproved() {
        var request = ApprovalRequest.of("t1", "f1", "s1", "proposal", "desc");
        registry.register(request);

        var event = HumanDecisionEvent.approved(request.requestId(), "t1");
        var result = registry.submitDecision(event);

        assertThat(result).isPresent();
        assertThat(result.get().flowId()).isEqualTo("f1");
        assertThat(registry.pendingCount()).isZero();
        assertThat(learningEvents).isEmpty(); // No learning for APPROVED
    }

    @Test
    @DisplayName("should submit REJECTED decision and notify LearningCallback")
    void shouldSubmitRejected() {
        var request = ApprovalRequest.of("t1", "f1", "s1", "bad proposal", "desc");
        registry.register(request);

        var event = HumanDecisionEvent.rejected(request.requestId(), "t1");
        registry.submitDecision(event);

        assertThat(learningEvents).hasSize(1);
        assertThat(learningEvents.get(0)).startsWith("REJECTED:");
    }

    @Test
    @DisplayName("should submit EDITED decision and notify LearningCallback")
    void shouldSubmitEdited() {
        var request = ApprovalRequest.of("t1", "f1", "s1", "original", "desc");
        registry.register(request);

        var event = HumanDecisionEvent.edited(request.requestId(), "t1", "edited version");
        registry.submitDecision(event);

        assertThat(learningEvents).hasSize(1);
        assertThat(learningEvents.get(0)).startsWith("EDITED:");
    }

    @Test
    @DisplayName("should throw when submitting decision for non-existent request")
    void shouldThrowForNonExistent() {
        var event = HumanDecisionEvent.approved("no-such-id", "t1");

        assertThatThrownBy(() -> registry.submitDecision(event))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("should throw when submitting decision twice")
    void shouldThrowOnDuplicateDecision() {
        var request = ApprovalRequest.of("t1", "f1", "s1", "p", "d");
        registry.register(request);

        registry.submitDecision(HumanDecisionEvent.approved(request.requestId(), "t1"));

        assertThatThrownBy(() ->
                registry.submitDecision(HumanDecisionEvent.approved(request.requestId(), "t1")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("should list pending approvals by tenant")
    void shouldListByTenant() {
        registry.register(ApprovalRequest.of("t1", "f1", "s1", "p1", "d1"));
        registry.register(ApprovalRequest.of("t1", "f2", "s1", "p2", "d2"));
        registry.register(ApprovalRequest.of("t2", "f3", "s1", "p3", "d3"));

        assertThat(registry.listPendingByTenant("t1")).hasSize(2);
        assertThat(registry.listPendingByTenant("t2")).hasSize(1);
        assertThat(registry.listPendingByTenant("t3")).isEmpty();
    }

    @Test
    @DisplayName("should detect expired approval")
    void shouldDetectExpired() {
        var request = new ApprovalRequest(null, "t1", "f1", "s1", "p", "d",
                Duration.ofMillis(1), null);

        // Wait for it to expire
        try { Thread.sleep(5); } catch (InterruptedException e) { /* ignore */ }

        assertThat(request.isExpired()).isTrue();
    }

    @Test
    @DisplayName("should return empty when submitting decision for expired request")
    void shouldReturnEmptyForExpired() {
        var request = new ApprovalRequest(null, "t1", "f1", "s1", "p", "d",
                Duration.ofMillis(1), null);
        registry.register(request);

        try { Thread.sleep(5); } catch (InterruptedException e) { /* ignore */ }

        var event = HumanDecisionEvent.approved(request.requestId(), "t1");
        var result = registry.submitDecision(event);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("should work without LearningCallback")
    void shouldWorkWithoutCallback() {
        var registryNoCallback = new ApprovalRegistry();
        var request = ApprovalRequest.of("t1", "f1", "s1", "p", "d");
        registryNoCallback.register(request);

        var event = HumanDecisionEvent.rejected(request.requestId(), "t1");
        var result = registryNoCallback.submitDecision(event);

        assertThat(result).isPresent(); // Should not throw
    }

    @Test
    @DisplayName("processExpired should apply default REJECTED decision on timeout")
    void processExpiredShouldApplyDefaultDecision() throws InterruptedException {
        var request = new ApprovalRequest(null, "t1", "f1", "s1", "my-proposal", "desc",
                Duration.ofMillis(1), null);
        registry.register(request);

        Thread.sleep(5);

        var timeoutDecisions = registry.processExpired();

        assertThat(timeoutDecisions).hasSize(1);
        assertThat(timeoutDecisions.get(0).decision()).isEqualTo(Decision.REJECTED);
        assertThat(timeoutDecisions.get(0).tenantId()).isEqualTo("t1");
        assertThat(timeoutDecisions.get(0).responderId()).isEqualTo("SYSTEM_TIMEOUT");
        assertThat(registry.pendingCount()).isZero();
        // LearningCallback should have been notified
        assertThat(learningEvents).hasSize(1);
        assertThat(learningEvents.get(0)).startsWith("REJECTED:");
    }

    @Test
    @DisplayName("processExpired should notify timeoutHandler")
    void processExpiredShouldNotifyTimeoutHandler() throws InterruptedException {
        List<HumanDecisionEvent> handledTimeouts = new ArrayList<>();
        var registryWithHandler = new ApprovalRegistry(null, Decision.REJECTED, handledTimeouts::add);

        var request = new ApprovalRequest(null, "t1", "f1", "s1", "p", "d",
                Duration.ofMillis(1), null);
        registryWithHandler.register(request);

        Thread.sleep(5);
        registryWithHandler.processExpired();

        assertThat(handledTimeouts).hasSize(1);
        assertThat(handledTimeouts.get(0).requestId()).isEqualTo(request.requestId());
    }
}
