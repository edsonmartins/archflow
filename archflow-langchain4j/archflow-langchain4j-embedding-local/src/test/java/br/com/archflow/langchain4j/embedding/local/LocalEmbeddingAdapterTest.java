package br.com.archflow.langchain4j.embedding.local;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalEmbeddingAdapterTest {

    private final LocalEmbeddingAdapter adapter = new LocalEmbeddingAdapter();

    // --- helpers ---

    private Map<String, Object> validProps() {
        var props = new HashMap<String, Object>();
        props.put("local.model.path", "/path/to/model.onnx");
        props.put("local.vocab.path", "/path/to/vocab.model");
        props.put("local.dimension", 384);
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
        @DisplayName("missing model-path throws IllegalArgumentException")
        void missingModelPath() {
            var props = new HashMap<String, Object>();
            props.put("local.vocab.path", "/path/to/vocab.model");
            props.put("local.dimension", 384);

            assertThatThrownBy(() -> adapter.validate(props))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Local model path is required");
        }

        @Test
        @DisplayName("blank model-path throws IllegalArgumentException")
        void blankModelPath() {
            var props = new HashMap<String, Object>();
            props.put("local.model.path", "  ");
            props.put("local.vocab.path", "/path/to/vocab.model");
            props.put("local.dimension", 384);

            assertThatThrownBy(() -> adapter.validate(props))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Local model path is required");
        }

        @Test
        @DisplayName("missing vocab-path throws IllegalArgumentException")
        void missingVocabPath() {
            var props = new HashMap<String, Object>();
            props.put("local.model.path", "/path/to/model.onnx");
            props.put("local.dimension", 384);

            assertThatThrownBy(() -> adapter.validate(props))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Local vocabulary path is required");
        }

        @Test
        @DisplayName("blank vocab-path throws IllegalArgumentException")
        void blankVocabPath() {
            var props = new HashMap<String, Object>();
            props.put("local.model.path", "/path/to/model.onnx");
            props.put("local.vocab.path", "   ");
            props.put("local.dimension", 384);

            assertThatThrownBy(() -> adapter.validate(props))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Local vocabulary path is required");
        }

        @Test
        @DisplayName("missing dimension throws IllegalArgumentException")
        void missingDimension() {
            var props = new HashMap<String, Object>();
            props.put("local.model.path", "/path/to/model.onnx");
            props.put("local.vocab.path", "/path/to/vocab.model");

            assertThatThrownBy(() -> adapter.validate(props))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Vector dimension is required and must be a positive number");
        }

        @Test
        @DisplayName("invalid dimension type throws IllegalArgumentException")
        void invalidDimensionType() {
            var props = new HashMap<String, Object>();
            props.put("local.model.path", "/path/to/model.onnx");
            props.put("local.vocab.path", "/path/to/vocab.model");
            props.put("local.dimension", "three-eighty-four");

            assertThatThrownBy(() -> adapter.validate(props))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Vector dimension is required and must be a positive number");
        }

        @Test
        @DisplayName("negative dimension throws IllegalArgumentException")
        void negativeDimension() {
            var props = new HashMap<String, Object>();
            props.put("local.model.path", "/path/to/model.onnx");
            props.put("local.vocab.path", "/path/to/vocab.model");
            props.put("local.dimension", -1);

            assertThatThrownBy(() -> adapter.validate(props))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Vector dimension is required and must be a positive number");
        }

        @Test
        @DisplayName("zero dimension throws IllegalArgumentException")
        void zeroDimension() {
            var props = new HashMap<String, Object>();
            props.put("local.model.path", "/path/to/model.onnx");
            props.put("local.vocab.path", "/path/to/vocab.model");
            props.put("local.dimension", 0);

            assertThatThrownBy(() -> adapter.validate(props))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Vector dimension is required and must be a positive number");
        }

        @Test
        @DisplayName("negative batchSize throws IllegalArgumentException")
        void negativeBatchSize() {
            var props = validProps();
            props.put("local.batchSize", -1);

            assertThatThrownBy(() -> adapter.validate(props))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Batch size must be a positive number");
        }

        @Test
        @DisplayName("zero batchSize throws IllegalArgumentException")
        void zeroBatchSize() {
            var props = validProps();
            props.put("local.batchSize", 0);

            assertThatThrownBy(() -> adapter.validate(props))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Batch size must be a positive number");
        }

        @Test
        @DisplayName("invalid batchSize type throws IllegalArgumentException")
        void invalidBatchSizeType() {
            var props = validProps();
            props.put("local.batchSize", "big");

            assertThatThrownBy(() -> adapter.validate(props))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Batch size must be a positive number");
        }

        @Test
        @DisplayName("negative maxLength throws IllegalArgumentException")
        void negativeMaxLength() {
            var props = validProps();
            props.put("local.maxLength", -10);

            assertThatThrownBy(() -> adapter.validate(props))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Max length must be a positive number");
        }

        @Test
        @DisplayName("invalid maxLength type throws IllegalArgumentException")
        void invalidMaxLengthType() {
            var props = validProps();
            props.put("local.maxLength", "long");

            assertThatThrownBy(() -> adapter.validate(props))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Max length must be a positive number");
        }

        @Test
        @DisplayName("invalid useGpu type throws IllegalArgumentException")
        void invalidUseGpuType() {
            var props = validProps();
            props.put("local.useGpu", "yes");

            assertThatThrownBy(() -> adapter.validate(props))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("useGpu must be a boolean");
        }

        @Test
        @DisplayName("negative gpuDeviceId throws IllegalArgumentException")
        void negativeGpuDeviceId() {
            var props = validProps();
            props.put("local.gpuDeviceId", -1);

            assertThatThrownBy(() -> adapter.validate(props))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("gpuDeviceId must be a non-negative number");
        }

        @Test
        @DisplayName("invalid usePooling type throws IllegalArgumentException")
        void invalidUsePoolingType() {
            var props = validProps();
            props.put("local.usePooling", "true");

            assertThatThrownBy(() -> adapter.validate(props))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("usePooling must be a boolean");
        }

        @Test
        @DisplayName("invalid useCache type throws IllegalArgumentException")
        void invalidUseCacheType() {
            var props = validProps();
            props.put("local.useCache", 1);

            assertThatThrownBy(() -> adapter.validate(props))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("useCache must be a boolean");
        }

        @Test
        @DisplayName("valid minimal properties passes validation")
        void validMinimalProperties() {
            adapter.validate(validProps());
        }

        @Test
        @DisplayName("valid full properties passes validation")
        void validFullProperties() {
            var props = validProps();
            props.put("local.maxLength", 256);
            props.put("local.batchSize", 64);
            props.put("local.useGpu", true);
            props.put("local.gpuDeviceId", 0);
            props.put("local.usePooling", true);
            props.put("local.useCache", false);

            adapter.validate(props);
        }
    }

    // ========== Factory ==========

    @Nested
    @DisplayName("Factory (inner class)")
    class FactoryTest {

        private final LocalEmbeddingAdapter.Factory factory = new LocalEmbeddingAdapter.Factory();

        @Test
        @DisplayName("getProvider() returns 'local'")
        void getProvider() {
            assertThat(factory.getProvider()).isEqualTo("local");
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

    // ========== Standalone Factory ==========

    @Nested
    @DisplayName("LocalEmbeddingAdapterFactory (standalone)")
    class StandaloneFactoryTest {

        private final LocalEmbeddingAdapterFactory factory = new LocalEmbeddingAdapterFactory();

        @Test
        @DisplayName("getProvider() returns 'local'")
        void getProvider() {
            assertThat(factory.getProvider()).isEqualTo("local");
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
