package br.com.archflow.model.engine;

import br.com.archflow.model.metrics.StepMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("ExecutionMetrics")
class ExecutionMetricsTest {

    @Test
    @DisplayName("should create record with all fields")
    void shouldCreateWithAllFields() {
        var stepMetrics = new StepMetrics(100L, 50, 0, Map.of());
        var metrics = new ExecutionMetrics(500L, 100, 0.5, Map.of("step-1", stepMetrics));

        assertThat(metrics.executionTime()).isEqualTo(500L);
        assertThat(metrics.tokensUsed()).isEqualTo(100);
        assertThat(metrics.estimatedCost()).isEqualTo(0.5);
        assertThat(metrics.stepMetrics()).hasSize(1);
    }

    @Test
    @DisplayName("should support empty step metrics")
    void shouldSupportEmptyStepMetrics() {
        var metrics = new ExecutionMetrics(0L, 0, 0.0, Map.of());

        assertThat(metrics.stepMetrics()).isEmpty();
    }

    @Test
    @DisplayName("should support record equality")
    void shouldSupportEquality() {
        var m1 = new ExecutionMetrics(100L, 50, 0.1, Map.of());
        var m2 = new ExecutionMetrics(100L, 50, 0.1, Map.of());

        assertThat(m1).isEqualTo(m2);
        assertThat(m1.hashCode()).isEqualTo(m2.hashCode());
    }
}
