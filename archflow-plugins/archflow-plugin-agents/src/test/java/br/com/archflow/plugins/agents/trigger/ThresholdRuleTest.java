package br.com.archflow.plugins.agents.trigger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@DisplayName("ThresholdRule")
class ThresholdRuleTest {

    @Test
    @DisplayName("should detect breach with GT operator")
    void shouldDetectBreachGT() {
        var rule = ThresholdRule.of("t", "r1", "churn", ThresholdRule.Operator.GT, 0.20, "agent-1");

        assertThat(rule.isBreached(0.25)).isTrue();
        assertThat(rule.isBreached(0.20)).isFalse();
        assertThat(rule.isBreached(0.15)).isFalse();
    }

    @Test
    @DisplayName("should detect breach with LT operator")
    void shouldDetectBreachLT() {
        var rule = ThresholdRule.of("t", "r1", "score", ThresholdRule.Operator.LT, 0.70, "agent-1");

        assertThat(rule.isBreached(0.65)).isTrue();
        assertThat(rule.isBreached(0.70)).isFalse();
        assertThat(rule.isBreached(0.80)).isFalse();
    }

    @Test
    @DisplayName("should detect breach with GTE operator")
    void shouldDetectBreachGTE() {
        var rule = ThresholdRule.of("t", "r1", "m", ThresholdRule.Operator.GTE, 100, "a");

        assertThat(rule.isBreached(100)).isTrue();
        assertThat(rule.isBreached(101)).isTrue();
        assertThat(rule.isBreached(99)).isFalse();
    }

    @Test
    @DisplayName("should reject null required fields")
    void shouldRejectNulls() {
        assertThatThrownBy(() -> ThresholdRule.of(null, "r", "m", ThresholdRule.Operator.GT, 1, "a"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ThresholdRule.of("t", null, "m", ThresholdRule.Operator.GT, 1, "a"))
                .isInstanceOf(NullPointerException.class);
    }
}
