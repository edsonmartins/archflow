package br.com.archflow.agent.metrics;

import br.com.archflow.agent.config.AgentConfig;
import br.com.archflow.model.engine.ExecutionMetrics;
import br.com.archflow.model.flow.FlowStatus;
import br.com.archflow.model.metrics.StepMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MetricsCollector")
class MetricsCollectorTest {

    private MetricsCollector collector;

    @BeforeEach
    void setUp() {
        collector = new MetricsCollector(AgentConfig.builder().build());
    }

    @AfterEach
    void tearDown() {
        collector.close();
    }

    @Test
    void recordFlowStartIncrementsCounter() {
        collector.recordFlowStart("f1");
        AggregatedMetrics agg = collector.getAggregatedMetrics();
        assertThat(agg.counters().get("flows_started")).isEqualTo(1);
    }

    @Test
    void recordFlowCompletionTracksSuccessAndDuration() throws InterruptedException {
        collector.recordFlowStart("f1");
        Thread.sleep(10);

        ExecutionMetrics execMetrics = new ExecutionMetrics(100, 500, 0.05, Map.of());
        collector.recordFlowCompletion("f1", execMetrics, true);

        AggregatedMetrics agg = collector.getAggregatedMetrics();
        assertThat(agg.counters().get("flows_completed")).isEqualTo(1);
        assertThat(agg.counters().get("flows_succeeded")).isEqualTo(1);
        assertThat(agg.values().get("flow_duration")).isGreaterThan(0);
        assertThat(agg.values().get("flow_tokens")).isEqualTo(500d);
    }

    @Test
    void recordFlowCompletionTracksFailure() {
        collector.recordFlowStart("f1");
        ExecutionMetrics execMetrics = new ExecutionMetrics(50, 200, 0.01, Map.of());
        collector.recordFlowCompletion("f1", execMetrics, false);

        AggregatedMetrics agg = collector.getAggregatedMetrics();
        assertThat(agg.counters().get("flows_failed")).isEqualTo(1);
    }

    @Test
    void recordFlowCompletionWithStepMetrics() {
        collector.recordFlowStart("f1");
        StepMetrics stepM = new StepMetrics(80, 100, 1, Map.of("custom", 42.0));
        ExecutionMetrics execMetrics = new ExecutionMetrics(100, 200, 0.02, Map.of("step-1", stepM));
        collector.recordFlowCompletion("f1", execMetrics, true);

        AggregatedMetrics agg = collector.getAggregatedMetrics();
        assertThat(agg.values().get("step_duration")).isGreaterThan(0);
        assertThat(agg.values().get("step_tokens")).isEqualTo(100d);
        assertThat(agg.values().get("step_retries")).isEqualTo(1d);
        assertThat(agg.values().get("step_custom")).isEqualTo(42d);
    }

    @Test
    void recordFlowErrorIncrementsErrorCounters() {
        collector.recordFlowStart("f1");
        collector.recordFlowError("f1", new RuntimeException("boom"));

        AggregatedMetrics agg = collector.getAggregatedMetrics();
        assertThat(agg.counters().get("flows_errors")).isEqualTo(1);
        assertThat(agg.counters().get("errors_total")).isEqualTo(1);
    }

    @Test
    void recordFlowStatusIncrementsStatusCounter() {
        collector.recordFlowStart("f1");
        collector.recordFlowStatus("f1", FlowStatus.RUNNING);
        collector.recordFlowStatus("f1", FlowStatus.COMPLETED);

        AggregatedMetrics agg = collector.getAggregatedMetrics();
        assertThat(agg.counters().get("flow_status_running")).isEqualTo(1);
        assertThat(agg.counters().get("flow_status_completed")).isEqualTo(1);
    }

    @Test
    void aggregatedMetricsContainsStats() throws InterruptedException {
        collector.recordFlowStart("f1");
        Thread.sleep(5);
        ExecutionMetrics m1 = new ExecutionMetrics(100, 0, 0, Map.of());
        collector.recordFlowCompletion("f1", m1, true);

        collector.recordFlowStart("f2");
        Thread.sleep(5);
        ExecutionMetrics m2 = new ExecutionMetrics(200, 0, 0, Map.of());
        collector.recordFlowCompletion("f2", m2, true);

        AggregatedMetrics agg = collector.getAggregatedMetrics();
        // flow_duration is recorded via context.getDurationMillis(), not from ExecutionMetrics
        assertThat(agg.stats()).containsKey("flow_duration");
        MetricStats stats = agg.stats().get("flow_duration");
        assertThat(stats.count()).isEqualTo(2);
    }

    @Test
    void multipleFlowsAccumulate() {
        for (int i = 0; i < 5; i++) {
            String fid = "flow-" + i;
            collector.recordFlowStart(fid);
            collector.recordFlowCompletion(fid, new ExecutionMetrics(10, 10, 0, Map.of()), true);
        }

        AggregatedMetrics agg = collector.getAggregatedMetrics();
        assertThat(agg.counters().get("flows_started")).isEqualTo(5);
        assertThat(agg.counters().get("flows_completed")).isEqualTo(5);
    }

    @Test
    void completionOfUnknownFlowIsSafe() {
        collector.recordFlowCompletion("ghost", new ExecutionMetrics(0, 0, 0, Map.of()), true);
    }

    @Test
    void errorOnUnknownFlowIsSafe() {
        collector.recordFlowError("ghost", new RuntimeException("err"));
    }

    @Test
    void statusOnUnknownFlowIsSafe() {
        collector.recordFlowStatus("ghost", FlowStatus.COMPLETED);
    }

    @Test
    void closeIsIdempotent() {
        collector.close();
        collector.close();
    }
}
