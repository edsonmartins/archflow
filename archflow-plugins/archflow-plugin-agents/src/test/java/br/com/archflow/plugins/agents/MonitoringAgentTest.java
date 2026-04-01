package br.com.archflow.plugins.agents;

import br.com.archflow.model.ai.domain.*;
import br.com.archflow.model.engine.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("MonitoringAgent")
class MonitoringAgentTest {

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        context = mock(ExecutionContext.class);
    }

    @Test
    @DisplayName("should collect metrics from sources")
    void shouldCollectMetricsFromSources() {
        MonitoringAgent agent = MonitoringAgent.builder()
                .metricSource("cpu", name -> new MonitoringAgent.MetricSnapshot(
                        name, 55.0, Instant.now(), Map.of()))
                .metricSource("memory", name -> new MonitoringAgent.MetricSnapshot(
                        name, 72.0, Instant.now(), Map.of()))
                .build();
        agent.initialize(Map.of());

        MonitoringAgent.MetricSnapshot cpuSnapshot = agent.collectMetric("cpu");
        MonitoringAgent.MetricSnapshot memSnapshot = agent.collectMetric("memory");

        assertThat(cpuSnapshot).isNotNull();
        assertThat(cpuSnapshot.name()).isEqualTo("cpu");
        assertThat(cpuSnapshot.value()).isEqualTo(55.0);
        assertThat(memSnapshot).isNotNull();
        assertThat(memSnapshot.name()).isEqualTo("memory");
        assertThat(memSnapshot.value()).isEqualTo(72.0);
    }

    @Test
    @DisplayName("should detect anomaly when value exceeds threshold")
    void shouldDetectAnomalyWhenValueExceedsThreshold() {
        MonitoringAgent.ThresholdAnomalyDetector detector =
                new MonitoringAgent.ThresholdAnomalyDetector(0, 80.0);

        boolean normalResult = detector.isAnomaly("cpu", 50.0, List.of(40.0, 45.0, 50.0));
        boolean anomalyUpper = detector.isAnomaly("cpu", 95.0, List.of(40.0, 45.0, 50.0));
        boolean anomalyLower = detector.isAnomaly("cpu", -5.0, List.of(40.0, 45.0, 50.0));

        assertThat(normalResult).isFalse();
        assertThat(anomalyUpper).isTrue();
        assertThat(anomalyLower).isTrue();
    }

    @Test
    @DisplayName("should not alert when values are normal")
    void shouldNotAlertWhenValuesAreNormal() {
        List<MonitoringAgent.Alert> dispatchedAlerts = new ArrayList<>();
        MonitoringAgent agent = MonitoringAgent.builder()
                .metricSource("cpu", name -> new MonitoringAgent.MetricSnapshot(
                        name, 50.0, Instant.now(), Map.of()))
                .anomalyDetector(new MonitoringAgent.ThresholdAnomalyDetector(0, 80.0))
                .alertDispatcher(dispatchedAlerts::add)
                .build();
        agent.initialize(Map.of());

        Task task = Task.of("monitor", Map.of());
        Result result = agent.executeTask(task, context);

        assertThat(result.success()).isTrue();
        @SuppressWarnings("unchecked")
        var output = (Map<String, Object>) result.output();
        assertThat(output.get("alerts")).isEqualTo(0);
        assertThat(output.get("status")).isEqualTo("healthy");
        assertThat(dispatchedAlerts).isEmpty();
    }

    @Test
    @DisplayName("should dispatch alert on anomaly")
    void shouldDispatchAlertOnAnomaly() {
        List<MonitoringAgent.Alert> dispatchedAlerts = new ArrayList<>();
        MonitoringAgent agent = MonitoringAgent.builder()
                .metricSource("cpu", name -> new MonitoringAgent.MetricSnapshot(
                        name, 95.0, Instant.now(), Map.of()))
                .anomalyDetector(new MonitoringAgent.ThresholdAnomalyDetector(0, 80.0))
                .alertDispatcher(dispatchedAlerts::add)
                .build();
        agent.initialize(Map.of());

        Task task = Task.of("monitor", Map.of());
        Result result = agent.executeTask(task, context);

        assertThat(result.success()).isTrue();
        @SuppressWarnings("unchecked")
        var output = (Map<String, Object>) result.output();
        assertThat(output.get("alerts")).isEqualTo(1);
        assertThat(output.get("status")).isEqualTo("anomalies_detected");
        assertThat(dispatchedAlerts).hasSize(1);
        assertThat(dispatchedAlerts.get(0).metricName()).isEqualTo("cpu");
        assertThat(dispatchedAlerts.get(0).severity()).isEqualTo(MonitoringAgent.AlertSeverity.WARNING);
        assertThat(dispatchedAlerts.get(0).currentValue()).isEqualTo(95.0);
        assertThat(dispatchedAlerts.get(0).threshold()).isEqualTo(80.0);
    }

    @Test
    @DisplayName("should maintain metric history")
    void shouldMaintainMetricHistory() {
        MonitoringAgent agent = MonitoringAgent.builder()
                .metricSource("cpu", name -> new MonitoringAgent.MetricSnapshot(
                        name, 50.0, Instant.now(), Map.of()))
                .historySize(3)
                .build();
        agent.initialize(Map.of());

        // Collect 5 readings to verify history is capped at 3
        for (int i = 1; i <= 5; i++) {
            final double value = i * 10.0;
            // Override source for each reading to vary the value
            MonitoringAgent agentWithValue = MonitoringAgent.builder()
                    .metricSource("cpu", name -> new MonitoringAgent.MetricSnapshot(
                            name, value, Instant.now(), Map.of()))
                    .historySize(3)
                    .build();
            agentWithValue.initialize(Map.of());
            agentWithValue.collectMetric("cpu");
            // Use the original agent to record history manually via collectMetric
        }

        // Use a single agent and collect multiple times with different values
        double[] values = {10.0, 20.0, 30.0, 40.0, 50.0};
        int callIndex[] = {0};
        MonitoringAgent histAgent = MonitoringAgent.builder()
                .metricSource("cpu", name -> new MonitoringAgent.MetricSnapshot(
                        name, values[callIndex[0]++], Instant.now(), Map.of()))
                .historySize(3)
                .build();
        histAgent.initialize(Map.of());

        for (int i = 0; i < 5; i++) {
            histAgent.collectMetric("cpu");
        }

        List<Double> history = histAgent.getHistory("cpu");
        assertThat(history).hasSize(3);
        assertThat(history).containsExactly(30.0, 40.0, 50.0);
    }

    @Test
    @DisplayName("should analyze health check request")
    void shouldAnalyzeHealthCheckRequest() {
        MonitoringAgent agent = MonitoringAgent.builder()
                .metricSource("cpu", name -> new MonitoringAgent.MetricSnapshot(
                        name, 45.0, Instant.now(), Map.of()))
                .metricSource("memory", name -> new MonitoringAgent.MetricSnapshot(
                        name, 60.0, Instant.now(), Map.of()))
                .anomalyDetector(new MonitoringAgent.ThresholdAnomalyDetector(0, 80.0))
                .build();
        agent.initialize(Map.of());

        Result result = agent.analyzeRequest("is the system healthy?");

        assertThat(result.success()).isTrue();
        @SuppressWarnings("unchecked")
        var output = (Map<String, Object>) result.output();
        assertThat(output.get("type")).isEqualTo("health_check");
        assertThat(output.get("healthy")).isEqualTo(true);
        assertThat(output.get("query")).isEqualTo("is the system healthy?");
    }

    @Test
    @DisplayName("should execute monitoring task")
    void shouldExecuteMonitoringTask() {
        MonitoringAgent agent = MonitoringAgent.builder()
                .metricSource("cpu", name -> new MonitoringAgent.MetricSnapshot(
                        name, 42.0, Instant.now(), Map.of()))
                .metricSource("disk", name -> new MonitoringAgent.MetricSnapshot(
                        name, 70.0, Instant.now(), Map.of()))
                .anomalyDetector(new MonitoringAgent.ThresholdAnomalyDetector(0, 90.0))
                .mode(MonitoringAgent.MonitoringMode.ON_DEMAND)
                .build();
        agent.initialize(Map.of());

        Task task = Task.of("monitor", Map.of("scope", "all"));
        Result result = agent.executeTask(task, context);

        assertThat(result.success()).isTrue();
        @SuppressWarnings("unchecked")
        var output = (Map<String, Object>) result.output();
        assertThat(output.get("task_type")).isEqualTo("monitor");
        assertThat(output.get("mode")).isEqualTo("ON_DEMAND");
        assertThat(output.get("metrics_collected")).isEqualTo(2);
        assertThat(output.get("status")).isEqualTo("healthy");
    }

    @Test
    @DisplayName("should handle missing metric source gracefully")
    void shouldHandleMissingMetricSourceGracefully() {
        MonitoringAgent agent = MonitoringAgent.builder()
                .metricSource("cpu", name -> new MonitoringAgent.MetricSnapshot(
                        name, 50.0, Instant.now(), Map.of()))
                .build();
        agent.initialize(Map.of());

        MonitoringAgent.MetricSnapshot snapshot = agent.collectMetric("nonexistent");

        assertThat(snapshot).isNull();
        assertThat(agent.getHistory("nonexistent")).isEmpty();
    }
}
