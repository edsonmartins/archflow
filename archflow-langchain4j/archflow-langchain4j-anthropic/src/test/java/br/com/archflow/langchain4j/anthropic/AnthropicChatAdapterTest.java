package br.com.archflow.langchain4j.anthropic;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("AnthropicChatAdapter")
class AnthropicChatAdapterTest {

    private AnthropicChatAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new AnthropicChatAdapter();
    }

    @Nested
    @DisplayName("validate")
    class ValidateTest {

        @Test
        @DisplayName("should reject null properties")
        void shouldRejectNullProperties() {
            assertThatThrownBy(() -> adapter.validate(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Properties cannot be null");
        }

        @Test
        @DisplayName("should reject missing api key")
        void shouldRejectMissingApiKey() {
            assertThatThrownBy(() -> adapter.validate(Map.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Anthropic API key is required");
        }

        @Test
        @DisplayName("should reject empty api key")
        void shouldRejectEmptyApiKey() {
            assertThatThrownBy(() -> adapter.validate(Map.of("api.key", "  ")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Anthropic API key is required");
        }

        @Test
        @DisplayName("should accept valid api key")
        void shouldAcceptValidApiKey() {
            assertThatNoException().isThrownBy(() ->
                    adapter.validate(Map.of("api.key", "sk-test-key")));
        }

        @Test
        @DisplayName("should reject non-number temperature")
        void shouldRejectNonNumberTemperature() {
            var props = new HashMap<String, Object>();
            props.put("api.key", "sk-test");
            props.put("temperature", "hot");

            assertThatThrownBy(() -> adapter.validate(props))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Temperature must be a number");
        }

        @Test
        @DisplayName("should reject temperature below 0")
        void shouldRejectTemperatureBelowZero() {
            var props = new HashMap<String, Object>();
            props.put("api.key", "sk-test");
            props.put("temperature", -0.1);

            assertThatThrownBy(() -> adapter.validate(props))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("between 0.0 and 1.0");
        }

        @Test
        @DisplayName("should reject temperature above 1")
        void shouldRejectTemperatureAboveOne() {
            var props = new HashMap<String, Object>();
            props.put("api.key", "sk-test");
            props.put("temperature", 1.5);

            assertThatThrownBy(() -> adapter.validate(props))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("between 0.0 and 1.0");
        }

        @Test
        @DisplayName("should accept valid temperature")
        void shouldAcceptValidTemperature() {
            var props = new HashMap<String, Object>();
            props.put("api.key", "sk-test");
            props.put("temperature", 0.7);

            assertThatNoException().isThrownBy(() -> adapter.validate(props));
        }

        @Test
        @DisplayName("should reject empty model name when explicitly set")
        void shouldRejectEmptyModelName() {
            var props = new HashMap<String, Object>();
            props.put("api.key", "sk-test");
            props.put("model.name", "  ");

            assertThatThrownBy(() -> adapter.validate(props))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Model name cannot be empty");
        }
    }

    @Nested
    @DisplayName("execute")
    class ExecuteTest {

        @Test
        @DisplayName("should throw when not configured")
        void shouldThrowWhenNotConfigured() {
            assertThatThrownBy(() -> adapter.execute("generate", "hello", null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not configured");
        }
    }

    @Nested
    @DisplayName("shutdown")
    class ShutdownTest {

        @Test
        @DisplayName("should clear model reference")
        void shouldClearModelReference() {
            adapter.shutdown();

            // After shutdown, execute should fail with not configured
            assertThatThrownBy(() -> adapter.execute("generate", "hello", null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not configured");
        }
    }
}
