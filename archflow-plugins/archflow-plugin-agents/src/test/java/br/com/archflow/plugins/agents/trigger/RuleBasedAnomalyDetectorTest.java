package br.com.archflow.plugins.agents.trigger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("RuleBasedAnomalyDetector")
class RuleBasedAnomalyDetectorTest {

    private RuleBasedAnomalyDetector detector;
    private final Map<String, Double> fakeMetrics = new java.util.HashMap<>();

    @BeforeEach
    void setUp() {
        fakeMetrics.clear();
        MetricProvider provider = (tenantId, metricId, context) ->
                fakeMetrics.getOrDefault(metricId, 0.0);
        detector = new RuleBasedAnomalyDetector(provider);
    }

    @Test
    @DisplayName("should detect anomaly when rule is breached")
    void shouldDetectAnomaly() {
        detector.addRule(ThresholdRule.of("t1", "r1", "churn",
                ThresholdRule.Operator.GT, 0.20, "alert-agent"));

        assertThat(detector.isAnomaly("churn", 0.25, List.of())).isTrue();
        assertThat(detector.isAnomaly("churn", 0.15, List.of())).isFalse();
    }

    @Test
    @DisplayName("should not detect anomaly for unregistered metric")
    void shouldNotDetectForUnregisteredMetric() {
        detector.addRule(ThresholdRule.of("t1", "r1", "churn",
                ThresholdRule.Operator.GT, 0.20, "a"));

        assertThat(detector.isAnomaly("cpu_usage", 99.0, List.of())).isFalse();
    }

    @Test
    @DisplayName("should evaluate rules for tenant via MetricProvider")
    void shouldEvaluateRulesForTenant() {
        detector.addRule(ThresholdRule.of("t1", "r1", "churn",
                ThresholdRule.Operator.GT, 0.20, "alert-agent"));
        detector.addRule(ThresholdRule.of("t1", "r2", "score",
                ThresholdRule.Operator.LT, 0.70, "score-agent"));
        detector.addRule(ThresholdRule.of("t2", "r1", "churn",
                ThresholdRule.Operator.GT, 0.30, "other-agent"));

        fakeMetrics.put("churn", 0.25);  // breaches t1/r1 (>0.20) but not t2/r1 (>0.30)
        fakeMetrics.put("score", 0.80); // does NOT breach t1/r2 (<0.70)

        List<ThresholdRule> breached = detector.evaluateRules("t1");
        assertThat(breached).hasSize(1);
        assertThat(breached.get(0).ruleId()).isEqualTo("r1");
    }

    @Test
    @DisplayName("should list rules by tenant")
    void shouldListRulesByTenant() {
        detector.addRule(ThresholdRule.of("t1", "r1", "m", ThresholdRule.Operator.GT, 1, "a"));
        detector.addRule(ThresholdRule.of("t1", "r2", "m", ThresholdRule.Operator.LT, 1, "a"));
        detector.addRule(ThresholdRule.of("t2", "r1", "m", ThresholdRule.Operator.GT, 1, "a"));

        assertThat(detector.listRules("t1")).hasSize(2);
        assertThat(detector.listRules("t2")).hasSize(1);
        assertThat(detector.listRules("t3")).isEmpty();
    }

    @Test
    @DisplayName("should remove rule")
    void shouldRemoveRule() {
        detector.addRule(ThresholdRule.of("t1", "r1", "m", ThresholdRule.Operator.GT, 1, "a"));

        assertThat(detector.removeRule("t1", "r1")).isTrue();
        assertThat(detector.listRules("t1")).isEmpty();
        assertThat(detector.removeRule("t1", "r1")).isFalse();
    }

    @Test
    @DisplayName("should respect cooldown between triggers")
    void shouldRespectCooldown() {
        // Rule with 300s cooldown
        detector.addRule(ThresholdRule.of("t1", "r1", "churn",
                ThresholdRule.Operator.GT, 0.20, "a"));

        // First breach triggers
        assertThat(detector.isAnomaly("churn", 0.25, List.of())).isTrue();
        // Second breach within cooldown should NOT trigger
        assertThat(detector.isAnomaly("churn", 0.30, List.of())).isFalse();
    }
}
