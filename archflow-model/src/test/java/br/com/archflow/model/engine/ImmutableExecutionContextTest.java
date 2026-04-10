package br.com.archflow.model.engine;

import br.com.archflow.model.flow.FlowState;
import br.com.archflow.model.flow.FlowStatus;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("ImmutableExecutionContext (RFC-005 v2 record)")
class ImmutableExecutionContextTest {

    private final ChatMemory testMemory = new ChatMemory() {
        @Override public Object id() { return "test"; }
        @Override public void add(ChatMessage message) {}
        @Override public List<ChatMessage> messages() { return List.of(); }
        @Override public void clear() {}
    };

    @Test
    @DisplayName("should create with builder and all required fields")
    void shouldCreateWithBuilder() {
        var ctx = ImmutableExecutionContext.builder()
                .tenantId("tenant-1")
                .userId("user-1")
                .sessionId("session-1")
                .chatMemory(testMemory)
                .variable("key", "value")
                .build();

        assertThat(ctx.tenantId()).isEqualTo("tenant-1");
        assertThat(ctx.getTenantId()).isEqualTo("tenant-1");
        assertThat(ctx.userId()).isEqualTo("user-1");
        assertThat(ctx.sessionId()).isEqualTo("session-1");
        assertThat(ctx.requestId()).isNotNull();
        assertThat(ctx.getChatMemory()).isSameAs(testMemory);
        assertThat(ctx.get("key")).contains("value");
        assertThat(ctx.getVariables()).containsEntry("key", "value");
    }

    @Test
    @DisplayName("should reject null tenantId")
    void shouldRejectNullTenantId() {
        assertThatThrownBy(() -> ImmutableExecutionContext.builder()
                .tenantId(null)
                .build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("tenantId");
    }

    @Test
    @DisplayName("should default tenantId to SYSTEM")
    void shouldDefaultTenantId() {
        var ctx = ImmutableExecutionContext.builder().build();
        assertThat(ctx.tenantId()).isEqualTo("SYSTEM");
    }

    @Test
    @DisplayName("should auto-generate requestId")
    void shouldAutoGenerateRequestId() {
        var ctx1 = ImmutableExecutionContext.builder().build();
        var ctx2 = ImmutableExecutionContext.builder().build();
        assertThat(ctx1.requestId()).isNotEqualTo(ctx2.requestId());
    }

    @Test
    @DisplayName("withVariable should return new instance without mutating original")
    void withVariableShouldReturnNewInstance() {
        var original = ImmutableExecutionContext.builder()
                .tenantId("t1")
                .variable("existing", "value")
                .build();

        var updated = original.withVariable("added", "new");

        assertThat(updated).isNotSameAs(original);
        assertThat(updated.get("added")).contains("new");
        assertThat(updated.get("existing")).contains("value");
        assertThat(original.get("added")).isEmpty();
        assertThat(updated.getTenantId()).isEqualTo("t1");
    }

    @Test
    @DisplayName("withState should return new instance with updated FlowState")
    void withStateShouldReturnNewInstance() {
        var original = ImmutableExecutionContext.builder()
                .tenantId("t1")
                .build();

        var state = FlowState.builder()
                .flowId("flow-1")
                .status(FlowStatus.RUNNING)
                .build();

        var updated = original.withState(state);

        assertThat(updated).isNotSameAs(original);
        assertThat(updated.getState()).isSameAs(state);
        assertThat(updated.flowState()).isSameAs(state);
        assertThat(original.getState()).isNull();
    }

    @Test
    @DisplayName("set() should throw UnsupportedOperationException")
    void setShouldThrow() {
        var ctx = ImmutableExecutionContext.builder().build();
        assertThatThrownBy(() -> ctx.set("key", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("setState() should throw UnsupportedOperationException")
    void setStateShouldThrow() {
        var ctx = ImmutableExecutionContext.builder().build();
        var state = FlowState.builder().flowId("f1").build();
        assertThatThrownBy(() -> ctx.setState(state))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("variables should be unmodifiable")
    void variablesShouldBeUnmodifiable() {
        var ctx = ImmutableExecutionContext.builder()
                .variable("k", "v")
                .build();

        assertThatThrownBy(() -> ctx.getVariables().put("new", "val"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("snapshot should return self (already immutable)")
    void snapshotShouldReturnSelf() {
        var ctx = ImmutableExecutionContext.builder().build();
        assertThat(ctx.snapshot()).isSameAs(ctx);
    }

    @Test
    @DisplayName("withMetrics should return new instance")
    void withMetricsShouldReturnNew() {
        var ctx = ImmutableExecutionContext.builder().build();
        var metrics = new ExecutionMetrics(100L, 50, 0.1, Map.of());
        var updated = ctx.withMetrics(metrics);

        assertThat(updated.getMetrics()).isEqualTo(metrics);
        assertThat(ctx.getMetrics()).isNotEqualTo(metrics);
    }

    @Test
    @DisplayName("withChatMemory should return new instance")
    void withChatMemoryShouldReturnNew() {
        var ctx = ImmutableExecutionContext.builder().build();
        var updated = ctx.withChatMemory(testMemory);

        assertThat(updated.getChatMemory()).isSameAs(testMemory);
        assertThat(ctx.getChatMemory()).isNull();
    }

    @Test
    @DisplayName("should implement ExecutionContext interface")
    void shouldImplementInterface() {
        ExecutionContext ctx = ImmutableExecutionContext.builder()
                .tenantId("t1")
                .userId("u1")
                .sessionId("s1")
                .chatMemory(testMemory)
                .build();

        assertThat(ctx.getTenantId()).isEqualTo("t1");
        assertThat(ctx.getUserId()).isEqualTo("u1");
        assertThat(ctx.getSessionId()).isEqualTo("s1");
        assertThat(ctx.getChatMemory()).isSameAs(testMemory);
    }

    @Test
    @DisplayName("withVariables should replace all variables")
    void withVariablesShouldReplaceAll() {
        var ctx = ImmutableExecutionContext.builder()
                .variable("old", "value")
                .build();

        var updated = ctx.withVariables(Map.of("new1", "a", "new2", "b"));

        assertThat(updated.get("old")).isEmpty();
        assertThat(updated.get("new1")).contains("a");
        assertThat(updated.get("new2")).contains("b");
    }
}
