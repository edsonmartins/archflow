package br.com.archflow.langchain4j.vectorstore.redis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class RedisVectorStoreAdapterTest {

    private final RedisVectorStoreAdapter adapter = new RedisVectorStoreAdapter();

    // --- helpers ---

    private Map<String, Object> validProps() {
        var props = new HashMap<String, Object>();
        props.put("redis.host", "localhost");
        props.put("redis.port", 6379);
        props.put("redis.dimension", 1536);
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
        @DisplayName("missing host throws IllegalArgumentException")
        void missingHost() {
            var props = new HashMap<String, Object>();
            props.put("redis.port", 6379);
            props.put("redis.dimension", 1536);

            assertThatThrownBy(() -> adapter.validate(props))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Redis host is required");
        }

        @Test
        @DisplayName("blank host throws IllegalArgumentException")
        void blankHost() {
            var props = new HashMap<String, Object>();
            props.put("redis.host", "   ");
            props.put("redis.port", 6379);
            props.put("redis.dimension", 1536);

            assertThatThrownBy(() -> adapter.validate(props))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Redis host is required");
        }

        @Test
        @DisplayName("invalid port type throws IllegalArgumentException")
        void invalidPortType() {
            var props = new HashMap<String, Object>();
            props.put("redis.host", "localhost");
            props.put("redis.port", "not-a-number");
            props.put("redis.dimension", 1536);

            assertThatThrownBy(() -> adapter.validate(props))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Redis port must be a number");
        }

        @Test
        @DisplayName("missing dimension throws IllegalArgumentException")
        void missingDimension() {
            var props = new HashMap<String, Object>();
            props.put("redis.host", "localhost");
            props.put("redis.port", 6379);

            assertThatThrownBy(() -> adapter.validate(props))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Vector dimension is required and must be a number");
        }

        @Test
        @DisplayName("invalid dimension type throws IllegalArgumentException")
        void invalidDimensionType() {
            var props = new HashMap<String, Object>();
            props.put("redis.host", "localhost");
            props.put("redis.port", 6379);
            props.put("redis.dimension", "big");

            assertThatThrownBy(() -> adapter.validate(props))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Vector dimension is required and must be a number");
        }

        @Test
        @DisplayName("valid properties pass validation")
        void validProperties() {
            adapter.validate(validProps());
        }

        @Test
        @DisplayName("valid properties without optional port pass validation")
        void validWithoutPort() {
            var props = new HashMap<String, Object>();
            props.put("redis.host", "redis.example.com");
            props.put("redis.dimension", 768);

            adapter.validate(props);
        }
    }

    // ========== Factory ==========

    @Nested
    @DisplayName("Factory (inner class)")
    class FactoryTest {

        private final RedisVectorStoreAdapter.Factory factory = new RedisVectorStoreAdapter.Factory();

        @Test
        @DisplayName("getProvider() returns 'redis'")
        void getProvider() {
            assertThat(factory.getProvider()).isEqualTo("redis");
        }

        @Test
        @DisplayName("supports('vectorstore') returns true")
        void supportsVectorstore() {
            assertThat(factory.supports("vectorstore")).isTrue();
        }

        @Test
        @DisplayName("supports('chat') returns false")
        void supportsChat() {
            assertThat(factory.supports("chat")).isFalse();
        }

        @Test
        @DisplayName("supports('embedding') returns false")
        void supportsEmbedding() {
            assertThat(factory.supports("embedding")).isFalse();
        }

        @Test
        @DisplayName("supports(null) returns false")
        void supportsNull() {
            assertThat(factory.supports(null)).isFalse();
        }

        @Test
        @DisplayName("supports empty string returns false")
        void supportsEmpty() {
            assertThat(factory.supports("")).isFalse();
        }
    }

    // ========== Standalone Factory ==========

    @Nested
    @DisplayName("RedisVectorStoreAdapterFactory (standalone)")
    class StandaloneFactoryTest {

        private final RedisVectorStoreAdapterFactory factory = new RedisVectorStoreAdapterFactory();

        @Test
        @DisplayName("getProvider() returns 'redis'")
        void getProvider() {
            assertThat(factory.getProvider()).isEqualTo("redis");
        }

        @Test
        @DisplayName("supports('vectorstore') returns true")
        void supportsVectorstore() {
            assertThat(factory.supports("vectorstore")).isTrue();
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

    // ========== Private utility methods via reflection ==========

    @Nested
    @DisplayName("cosineSimilarity()")
    class CosineSimilarityTest {

        private double invokeCosineSimilarity(float[] a, float[] b) throws Exception {
            var method = RedisVectorStoreAdapter.class.getDeclaredMethod("cosineSimilarity", float[].class, float[].class);
            method.setAccessible(true);
            return (double) method.invoke(adapter, a, b);
        }

        @Test
        @DisplayName("identical vectors return 1.0")
        void identicalVectors() throws Exception {
            var v = new float[]{1.0f, 2.0f, 3.0f};
            var result = invokeCosineSimilarity(v, v);

            assertThat(result).isCloseTo(1.0, within(1e-6));
        }

        @Test
        @DisplayName("orthogonal vectors return 0.0")
        void orthogonalVectors() throws Exception {
            var a = new float[]{1.0f, 0.0f};
            var b = new float[]{0.0f, 1.0f};
            var result = invokeCosineSimilarity(a, b);

            assertThat(result).isCloseTo(0.0, within(1e-6));
        }

        @Test
        @DisplayName("opposite vectors return -1.0")
        void oppositeVectors() throws Exception {
            var a = new float[]{1.0f, 0.0f};
            var b = new float[]{-1.0f, 0.0f};
            var result = invokeCosineSimilarity(a, b);

            assertThat(result).isCloseTo(-1.0, within(1e-6));
        }

        @Test
        @DisplayName("zero vector returns 0.0")
        void zeroVector() throws Exception {
            var a = new float[]{0.0f, 0.0f};
            var b = new float[]{1.0f, 2.0f};
            var result = invokeCosineSimilarity(a, b);

            assertThat(result).isCloseTo(0.0, within(1e-6));
        }

        @Test
        @DisplayName("mismatched dimensions throw IllegalArgumentException")
        void mismatchedDimensions() {
            var a = new float[]{1.0f, 2.0f};
            var b = new float[]{1.0f};

            assertThatThrownBy(() -> invokeCosineSimilarity(a, b))
                    .hasCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("vectorToString() / stringToVector()")
    class VectorSerializationTest {

        private String invokeVectorToString(float[] vector) throws Exception {
            var method = RedisVectorStoreAdapter.class.getDeclaredMethod("vectorToString", float[].class);
            method.setAccessible(true);
            return (String) method.invoke(adapter, vector);
        }

        private float[] invokeStringToVector(String str) throws Exception {
            var method = RedisVectorStoreAdapter.class.getDeclaredMethod("stringToVector", String.class);
            method.setAccessible(true);
            return (float[]) method.invoke(adapter, str);
        }

        @Test
        @DisplayName("vectorToString produces comma-separated floats")
        void vectorToString() throws Exception {
            var vector = new float[]{1.0f, 2.5f, 3.75f};
            var result = invokeVectorToString(vector);

            assertThat(result).isEqualTo("1.0,2.5,3.75");
        }

        @Test
        @DisplayName("stringToVector parses comma-separated string")
        void stringToVector() throws Exception {
            var result = invokeStringToVector("1.0,2.5,3.75");

            assertThat(result).containsExactly(1.0f, 2.5f, 3.75f);
        }

        @Test
        @DisplayName("round-trip preserves vector values")
        void roundTrip() throws Exception {
            var original = new float[]{0.123f, -4.56f, 7.89f, 0.0f};
            var serialized = invokeVectorToString(original);
            var deserialized = invokeStringToVector(serialized);

            assertThat(deserialized).containsExactly(original);
        }

        @Test
        @DisplayName("single-element vector round-trips correctly")
        void singleElement() throws Exception {
            var original = new float[]{42.0f};
            var serialized = invokeVectorToString(original);
            var deserialized = invokeStringToVector(serialized);

            assertThat(deserialized).containsExactly(42.0f);
        }
    }
}
