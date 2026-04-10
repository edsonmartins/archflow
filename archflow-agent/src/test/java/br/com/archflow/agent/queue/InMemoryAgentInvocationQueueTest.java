package br.com.archflow.agent.queue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("InMemoryAgentInvocationQueue")
class InMemoryAgentInvocationQueueTest {

    private InMemoryAgentInvocationQueue queue;

    @BeforeEach
    void setUp() {
        queue = new InMemoryAgentInvocationQueue(3, 100);
    }

    @Test
    @DisplayName("should submit and poll requests")
    void shouldSubmitAndPoll() {
        var request = InvocationRequest.root("tenant-1", "agent-1", Map.of("key", "value"));
        queue.submit(request);

        assertThat(queue.size()).isEqualTo(1);
        var polled = queue.poll();
        assertThat(polled).isPresent();
        assertThat(polled.get().agentId()).isEqualTo("agent-1");
        assertThat(polled.get().tenantId()).isEqualTo("tenant-1");
        assertThat(queue.size()).isZero();
    }

    @Test
    @DisplayName("should return empty when queue is empty")
    void shouldReturnEmptyWhenEmpty() {
        assertThat(queue.poll()).isEmpty();
    }

    @Test
    @DisplayName("should reject requests exceeding max recursion depth")
    void shouldRejectExcessiveRecursion() {
        var root = InvocationRequest.root("t", "a1", Map.of());
        var child1 = root.childInvocation("a2", Map.of());
        var child2 = child1.childInvocation("a3", Map.of());
        var child3 = child2.childInvocation("a4", Map.of());

        queue.submit(root);     // depth 0
        queue.submit(child1);   // depth 1
        queue.submit(child2);   // depth 2
        queue.submit(child3);   // depth 3

        var child4 = child3.childInvocation("a5", Map.of()); // depth 4 > max 3

        assertThatThrownBy(() -> queue.submit(child4))
                .isInstanceOf(AgentInvocationQueue.MaxRecursionDepthException.class);
    }

    @Test
    @DisplayName("should track recursion depth in child invocations")
    void shouldTrackRecursionDepth() {
        var root = InvocationRequest.root("t", "a1", Map.of());
        assertThat(root.recursionDepth()).isZero();

        var child = root.childInvocation("a2", Map.of());
        assertThat(child.recursionDepth()).isEqualTo(1);
        assertThat(child.parentExecutionId()).isEqualTo(root.requestId());

        var grandchild = child.childInvocation("a3", Map.of());
        assertThat(grandchild.recursionDepth()).isEqualTo(2);
    }

    @Test
    @DisplayName("should list pending requests without removing them")
    void shouldListPending() {
        queue.submit(InvocationRequest.root("t", "a1", Map.of()));
        queue.submit(InvocationRequest.root("t", "a2", Map.of()));

        var pending = queue.pending();
        assertThat(pending).hasSize(2);
        assertThat(queue.size()).isEqualTo(2); // not removed
    }

    @Test
    @DisplayName("should respect FIFO order")
    void shouldRespectFifoOrder() {
        queue.submit(InvocationRequest.root("t", "first", Map.of()));
        queue.submit(InvocationRequest.root("t", "second", Map.of()));

        assertThat(queue.poll().get().agentId()).isEqualTo("first");
        assertThat(queue.poll().get().agentId()).isEqualTo("second");
    }

    @Test
    @DisplayName("should return configured max recursion depth")
    void shouldReturnMaxRecursionDepth() {
        assertThat(queue.getMaxRecursionDepth()).isEqualTo(3);
    }
}
