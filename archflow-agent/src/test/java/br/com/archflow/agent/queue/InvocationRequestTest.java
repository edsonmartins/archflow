package br.com.archflow.agent.queue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("InvocationRequest")
class InvocationRequestTest {

    @Test
    @DisplayName("root should create request with depth 0")
    void rootShouldCreateDepthZero() {
        var req = InvocationRequest.root("t1", "agent-1", Map.of("k", "v"));

        assertThat(req.tenantId()).isEqualTo("t1");
        assertThat(req.agentId()).isEqualTo("agent-1");
        assertThat(req.recursionDepth()).isZero();
        assertThat(req.parentExecutionId()).isNull();
        assertThat(req.requestId()).isNotNull();
        assertThat(req.payload()).containsEntry("k", "v");
    }

    @Test
    @DisplayName("childInvocation should increment depth and set parent")
    void childShouldIncrementDepth() {
        var root = InvocationRequest.root("t1", "a1", Map.of());
        var child = root.childInvocation("a2", Map.of("child", "data"));

        assertThat(child.recursionDepth()).isEqualTo(1);
        assertThat(child.parentExecutionId()).isEqualTo(root.requestId());
        assertThat(child.tenantId()).isEqualTo("t1");
        assertThat(child.agentId()).isEqualTo("a2");
        assertThat(child.payload()).containsEntry("child", "data");
    }

    @Test
    @DisplayName("should reject null tenantId")
    void shouldRejectNullTenantId() {
        assertThatThrownBy(() -> InvocationRequest.root(null, "a1", Map.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("should reject null agentId")
    void shouldRejectNullAgentId() {
        assertThatThrownBy(() -> InvocationRequest.root("t1", null, Map.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("should default payload to empty map")
    void shouldDefaultPayload() {
        var req = InvocationRequest.root("t1", "a1", null);
        assertThat(req.payload()).isEmpty();
    }

    @Test
    @DisplayName("chain of child invocations should track depth correctly")
    void chainShouldTrackDepth() {
        var root = InvocationRequest.root("t", "a1", Map.of());
        var c1 = root.childInvocation("a2", Map.of());
        var c2 = c1.childInvocation("a3", Map.of());
        var c3 = c2.childInvocation("a4", Map.of());

        assertThat(c3.recursionDepth()).isEqualTo(3);
        assertThat(c3.parentExecutionId()).isEqualTo(c2.requestId());
    }
}
