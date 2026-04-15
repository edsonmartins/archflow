package br.com.archflow.langchain4j.embedding.openai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiEmbeddingAdapterTest {

    private final OpenAiEmbeddingAdapter adapter = new OpenAiEmbeddingAdapter();

    // --- helpers ---

    private Map<String, Object> validProps() {
        var props = new HashMap<String, Object>();
        props.put("openai.api.key", "sk-test-key-123");
        return props;
    }

    // ========== validate() ==========

    @Nested
    @DisplayName("validate()")
    class Validate {

        @Test
        @DisplayName("null properties throws IllegalArgumentException")
        void nullProperties() {
            assertThatThrownBy(() -> adapter.validate(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Properties cannot be null");
        }

        @Test
        @DisplayName("missing api-key throws IllegalArgumentException")
        void missingApiKey() {
            var props = Map.<String, Object>of();

            assertThatThrownBy(() -> adapter.validate(props))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("OpenAI API key is required");
        }

        @Test
        @DisplayName("blank api-key throws IllegalArgumentException")
        void blankApiKey() {
            var props = new HashMap<String, Object>();
            props.put("openai.api.key", "   ");

            assertThatThrownBy(() -> adapter.validate(props))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("OpenAI API key is required");
        }

        @Test
        @DisplayName("invalid timeout type throws IllegalArgumentException")
        void invalidTimeoutType() {
            var props = validProps();
            props.put("openai.timeout", "thirty");

            assertThatThrownBy(() -> adapter.validate(props))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Timeout must be a positive number");
        }

        @Test
        @DisplayName("negative timeout throws IllegalArgumentException")
        void negativeTimeout() {
            var props = validProps();
            props.put("openai.timeout", -5);

            assertThatThrownBy(() -> adapter.validate(props))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Timeout must be a positive number");
        }

        @Test
        @DisplayName("zero timeout throws IllegalArgumentException")
        void zeroTimeout() {
            var props = validProps();
            props.put("openai.timeout", 0);

            assertThatThrownBy(() -> adapter.validate(props))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Timeout must be a positive number");
        }

        @Test
        @DisplayName("invalid maxRetries type throws IllegalArgumentException")
        void invalidMaxRetriesType() {
            var props = validProps();
            props.put("openai.maxRetries", "three");

            assertThatThrownBy(() -> adapter.validate(props))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Max retries must be a number between 0 and 10");
        }

        @Test
        @DisplayName("maxRetries > 10 throws IllegalArgumentException")
        void maxRetriesExceedsLimit() {
            var props = validProps();
            props.put("openai.maxRetries", 11);

            assertThatThrownBy(() -> adapter.validate(props))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Max retries must be a number between 0 and 10");
        }

        @Test
        @DisplayName("negative maxRetries throws IllegalArgumentException")
        void negativeMaxRetries() {
            var props = validProps();
            props.put("openai.maxRetries", -1);

            assertThatThrownBy(() -> adapter.validate(props))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Max retries must be a number between 0 and 10");
        }

        @Test
        @DisplayName("valid properties with only api-key passes validation")
        void validMinimalProperties() {
            adapter.validate(validProps());
            // no exception = success
        }

        @Test
        @DisplayName("valid properties with all optional fields passes validation")
        void validFullProperties() {
            var props = validProps();
            props.put("openai.timeout", 60);
            props.put("openai.maxRetries", 5);
            props.put("openai.model", "text-embedding-3-small");

            adapter.validate(props);
            // no exception = success
        }

        @Test
        @DisplayName("maxRetries at boundary 0 passes validation")
        void maxRetriesZero() {
            var props = validProps();
            props.put("openai.maxRetries", 0);

            adapter.validate(props);
        }

        @Test
        @DisplayName("maxRetries at boundary 10 passes validation")
        void maxRetriesTen() {
            var props = validProps();
            props.put("openai.maxRetries", 10);

            adapter.validate(props);
        }
    }

    // ========== Factory ==========

    @Nested
    @DisplayName("Factory")
    class FactoryTest {

        private final OpenAiEmbeddingAdapter.Factory factory = new OpenAiEmbeddingAdapter.Factory();

        @Test
        @DisplayName("getProvider() returns 'openai'")
        void getProvider() {
            assertThat(factory.getProvider()).isEqualTo("openai");
        }

        @Test
        @DisplayName("supports('embedding') returns true")
        void supportsEmbedding() {
            assertThat(factory.supports("embedding")).isTrue();
        }

        @Test
        @DisplayName("supports('chat') returns false")
        void supportsChat() {
            assertThat(factory.supports("chat")).isFalse();
        }

        @Test
        @DisplayName("supports(null) returns false")
        void supportsNull() {
            assertThat(factory.supports(null)).isFalse();
        }

        @Test
        @DisplayName("supports('vectorstore') returns false")
        void supportsVectorstore() {
            assertThat(factory.supports("vectorstore")).isFalse();
        }

        @Test
        @DisplayName("supports empty string returns false")
        void supportsEmpty() {
            assertThat(factory.supports("")).isFalse();
        }
    }

    // ========== Standalone Factory class ==========

    @Nested
    @DisplayName("OpenAiEmbeddingAdapterFactory (standalone)")
    class StandaloneFactoryTest {

        private final OpenAiEmbeddingAdapterFactory factory = new OpenAiEmbeddingAdapterFactory();

        @Test
        @DisplayName("getProvider() returns 'openai'")
        void getProvider() {
            assertThat(factory.getProvider()).isEqualTo("openai");
        }

        @Test
        @DisplayName("supports('embedding') returns true")
        void supportsEmbedding() {
            assertThat(factory.supports("embedding")).isTrue();
        }

        @Test
        @DisplayName("supports('chat') returns false")
        void supportsChat() {
            assertThat(factory.supports("chat")).isFalse();
        }

        @Test
        @DisplayName("supports(null) returns false")
        void supportsNull() {
            assertThat(factory.supports(null)).isFalse();
        }
    }
}
