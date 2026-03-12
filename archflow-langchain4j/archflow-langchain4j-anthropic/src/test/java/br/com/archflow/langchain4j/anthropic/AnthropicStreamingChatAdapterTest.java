package br.com.archflow.langchain4j.anthropic;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("AnthropicStreamingChatAdapter")
class AnthropicStreamingChatAdapterTest {

    private AnthropicStreamingChatAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new AnthropicStreamingChatAdapter();
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
        @DisplayName("should reject temperature out of range")
        void shouldRejectTemperatureOutOfRange() {
            var props = new HashMap<String, Object>();
            props.put("api.key", "sk-test");
            props.put("temperature", 1.5);

            assertThatThrownBy(() -> adapter.validate(props))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("between 0.0 and 1.0");
        }

        @Test
        @DisplayName("should reject non-number maxTokens")
        void shouldRejectNonNumberMaxTokens() {
            var props = new HashMap<String, Object>();
            props.put("api.key", "sk-test");
            props.put("maxTokens", "many");

            assertThatThrownBy(() -> adapter.validate(props))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("MaxTokens must be a positive number");
        }

        @Test
        @DisplayName("should reject zero maxTokens")
        void shouldRejectZeroMaxTokens() {
            var props = new HashMap<String, Object>();
            props.put("api.key", "sk-test");
            props.put("maxTokens", 0);

            assertThatThrownBy(() -> adapter.validate(props))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("MaxTokens must be a positive number");
        }

        @Test
        @DisplayName("should reject negative maxTokens")
        void shouldRejectNegativeMaxTokens() {
            var props = new HashMap<String, Object>();
            props.put("api.key", "sk-test");
            props.put("maxTokens", -100);

            assertThatThrownBy(() -> adapter.validate(props))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("MaxTokens must be a positive number");
        }

        @Test
        @DisplayName("should accept valid maxTokens")
        void shouldAcceptValidMaxTokens() {
            var props = new HashMap<String, Object>();
            props.put("api.key", "sk-test");
            props.put("maxTokens", 4096);

            assertThatNoException().isThrownBy(() -> adapter.validate(props));
        }
    }

    @Nested
    @DisplayName("execute")
    class ExecuteTest {

        @Test
        @DisplayName("should throw when not configured")
        void shouldThrowWhenNotConfigured() {
            assertThatThrownBy(() -> adapter.execute("generateStream", "hello", null))
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

            assertThatThrownBy(() -> adapter.execute("generateStream", "hello", null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not configured");
        }
    }
}
