package br.com.archflow.model.engine;

import br.com.archflow.model.flow.FlowState;
import br.com.archflow.model.flow.FlowStatus;
import br.com.archflow.model.metrics.StepMetrics;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("DefaultExecutionContext")
class DefaultExecutionContextTest {

    private ChatMemory chatMemory;
    private DefaultExecutionContext context;

    @BeforeEach
    void setUp() {
        chatMemory = new TestChatMemory();
        context = new DefaultExecutionContext(chatMemory);
    }

    @Test
    @DisplayName("should store and retrieve variables")
    void shouldStoreAndRetrieveVariables() {
        context.set("key1", "value1");
        context.set("key2", 42);

        assertThat(context.get("key1")).contains("value1");
        assertThat(context.get("key2")).contains(42);
    }

    @Test
    @DisplayName("should return empty optional for missing keys")
    void shouldReturnEmptyForMissingKeys() {
        assertThat(context.get("nonexistent")).isEmpty();
    }

    @Test
    @DisplayName("should return chat memory")
    void shouldReturnChatMemory() {
        assertThat(context.getChatMemory()).isSameAs(chatMemory);
    }

    @Test
    @DisplayName("should return execution metrics with elapsed time")
    void shouldReturnMetricsWithElapsedTime() {
        var metrics = context.getMetrics();

        assertThat(metrics.executionTime()).isGreaterThanOrEqualTo(0);
        assertThat(metrics.tokensUsed()).isZero();
        assertThat(metrics.estimatedCost()).isZero();
        assertThat(metrics.stepMetrics()).isEmpty();
    }

    @Test
    @DisplayName("should accumulate step metrics")
    void shouldAccumulateStepMetrics() {
        var stepMetrics1 = new StepMetrics(100L, 50, 0, Map.of());
        var stepMetrics2 = new StepMetrics(200L, 30, 1, Map.of());

        context.addStepMetrics("step-1", stepMetrics1);
        context.addStepMetrics("step-2", stepMetrics2);

        var metrics = context.getMetrics();
        assertThat(metrics.tokensUsed()).isEqualTo(80);
        assertThat(metrics.stepMetrics()).hasSize(2);
        assertThat(metrics.stepMetrics()).containsKeys("step-1", "step-2");
    }

    @Test
    @DisplayName("should calculate estimated cost from tokens")
    void shouldCalculateEstimatedCost() {
        var stepMetrics = new StepMetrics(100L, 100, 0, Map.of());
        context.addStepMetrics("step-1", stepMetrics);

        var metrics = context.getMetrics();
        assertThat(metrics.estimatedCost()).isEqualTo(100 * 0.002);
    }

    @Test
    @DisplayName("should set and get flow state")
    void shouldSetAndGetFlowState() {
        var state = FlowState.builder()
                .flowId("flow-1")
                .status(FlowStatus.RUNNING)
                .build();

        context.setState(state);

        assertThat(context.getState()).isSameAs(state);
        assertThat(context.getState().getFlowId()).isEqualTo("flow-1");
    }

    @Test
    @DisplayName("should return null state when not set")
    void shouldReturnNullStateWhenNotSet() {
        assertThat(context.getState()).isNull();
    }

    @Test
    @DisplayName("should reset start time")
    void shouldResetStartTime() {
        var metricsBefore = context.getMetrics();
        context.resetStartTime();
        var metricsAfter = context.getMetrics();

        assertThat(metricsAfter.executionTime()).isLessThanOrEqualTo(metricsBefore.executionTime());
    }

    @Test
    @DisplayName("should update estimated cost directly")
    void shouldUpdateEstimatedCost() {
        context.updateEstimatedCost(5.50);

        assertThat(context.getMetrics().estimatedCost()).isEqualTo(5.50);
    }

    @Test
    @DisplayName("should overwrite variable with same key")
    void shouldOverwriteVariable() {
        context.set("key", "original");
        context.set("key", "updated");

        assertThat(context.get("key")).contains("updated");
    }

    private static class TestChatMemory implements ChatMemory {
        @Override
        public Object id() {
            return "test-memory";
        }

        @Override
        public void add(ChatMessage message) {
        }

        @Override
        public List<ChatMessage> messages() {
            return List.of();
        }

        @Override
        public void clear() {
        }
    }
}
