package br.com.archflow.model.config;

import br.com.archflow.model.enums.LogLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Config Records")
class RecordConfigTest {

    @Nested
    @DisplayName("LLMConfig")
    class LLMConfigTest {

        @Test
        @DisplayName("should create with all fields")
        void shouldCreateWithAllFields() {
            var config = new LLMConfig("gpt-4", 0.7, 2000, 30000L, Map.of("top_p", 0.9));

            assertThat(config.model()).isEqualTo("gpt-4");
            assertThat(config.temperature()).isEqualTo(0.7);
            assertThat(config.maxTokens()).isEqualTo(2000);
            assertThat(config.timeout()).isEqualTo(30000L);
            assertThat(config.additionalConfig()).containsEntry("top_p", 0.9);
        }

        @Test
        @DisplayName("should support equality")
        void shouldSupportEquality() {
            var c1 = new LLMConfig("gpt-4", 0.7, 2000, 30000L, Map.of());
            var c2 = new LLMConfig("gpt-4", 0.7, 2000, 30000L, Map.of());

            assertThat(c1).isEqualTo(c2);
        }

        @Test
        @DisplayName("should allow empty additional config")
        void shouldAllowEmptyAdditionalConfig() {
            var config = new LLMConfig("claude-3", 0.5, 1000, 60000L, Map.of());

            assertThat(config.additionalConfig()).isEmpty();
        }
    }

    @Nested
    @DisplayName("RetryPolicy")
    class RetryPolicyTest {

        @Test
        @DisplayName("should create with all fields")
        void shouldCreateWithAllFields() {
            var policy = new RetryPolicy(3, 1000L, 2.0, Set.of(RuntimeException.class));

            assertThat(policy.maxAttempts()).isEqualTo(3);
            assertThat(policy.delay()).isEqualTo(1000L);
            assertThat(policy.multiplier()).isEqualTo(2.0);
            assertThat(policy.retryableExceptions()).containsExactly(RuntimeException.class);
        }

        @Test
        @DisplayName("should support empty retryable exceptions")
        void shouldSupportEmptyRetryableExceptions() {
            var policy = new RetryPolicy(1, 500L, 1.0, Set.of());

            assertThat(policy.retryableExceptions()).isEmpty();
        }

        @Test
        @DisplayName("should support equality")
        void shouldSupportEquality() {
            var p1 = new RetryPolicy(3, 1000L, 2.0, Set.of());
            var p2 = new RetryPolicy(3, 1000L, 2.0, Set.of());

            assertThat(p1).isEqualTo(p2);
        }
    }

    @Nested
    @DisplayName("MonitoringConfig")
    class MonitoringConfigTest {

        @Test
        @DisplayName("should create with all fields")
        void shouldCreateWithAllFields() {
            var config = new MonitoringConfig(true, false, LogLevel.DEBUG, Map.of("env", "test"));

            assertThat(config.detailedMetrics()).isTrue();
            assertThat(config.fullHistory()).isFalse();
            assertThat(config.logLevel()).isEqualTo(LogLevel.DEBUG);
            assertThat(config.tags()).containsEntry("env", "test");
        }

        @Test
        @DisplayName("should support all log levels")
        void shouldSupportAllLogLevels() {
            for (LogLevel level : LogLevel.values()) {
                var config = new MonitoringConfig(false, false, level, Map.of());
                assertThat(config.logLevel()).isEqualTo(level);
            }
        }
    }
}
