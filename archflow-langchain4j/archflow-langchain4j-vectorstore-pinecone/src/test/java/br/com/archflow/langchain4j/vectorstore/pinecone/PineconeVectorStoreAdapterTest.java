package br.com.archflow.langchain4j.vectorstore.pinecone;

import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.*;
import dev.langchain4j.store.embedding.filter.logical.And;
import dev.langchain4j.store.embedding.filter.logical.Not;
import dev.langchain4j.store.embedding.filter.logical.Or;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

class PineconeVectorStoreAdapterTest {

    private PineconeVectorStoreAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new PineconeVectorStoreAdapter();
    }

    // -- Helper to build valid properties --

    private Map<String, Object> validProperties() {
        var props = new HashMap<String, Object>();
        props.put("pinecone.apiKey", "pc-test-key-123");
        props.put("pinecone.apiUrl", "https://my-index.svc.pinecone.io");
        props.put("pinecone.indexName", "embeddings");
        props.put("pinecone.dimension", 1536);
        return props;
    }

    // ========== Validate Tests ==========

    @Nested
    @DisplayName("validate()")
    class ValidateTests {

        @Test
        @DisplayName("throws on null properties")
        void nullProperties() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> adapter.validate(null))
                    .withMessageContaining("Properties cannot be null");
        }

        @Test
        @DisplayName("throws on missing API key")
        void missingApiKey() {
            var props = validProperties();
            props.remove("pinecone.apiKey");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> adapter.validate(props))
                    .withMessageContaining("API key is required");
        }

        @Test
        @DisplayName("throws on blank API key")
        void blankApiKey() {
            var props = validProperties();
            props.put("pinecone.apiKey", "   ");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> adapter.validate(props))
                    .withMessageContaining("API key is required");
        }

        @Test
        @DisplayName("throws on missing API URL")
        void missingApiUrl() {
            var props = validProperties();
            props.remove("pinecone.apiUrl");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> adapter.validate(props))
                    .withMessageContaining("API URL is required");
        }

        @Test
        @DisplayName("throws on blank API URL")
        void blankApiUrl() {
            var props = validProperties();
            props.put("pinecone.apiUrl", "  ");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> adapter.validate(props))
                    .withMessageContaining("API URL is required");
        }

        @Test
        @DisplayName("throws on empty index name when provided")
        void emptyIndexName() {
            var props = validProperties();
            props.put("pinecone.indexName", "   ");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> adapter.validate(props))
                    .withMessageContaining("index name cannot be empty");
        }

        @Test
        @DisplayName("throws on missing dimension")
        void missingDimension() {
            var props = validProperties();
            props.remove("pinecone.dimension");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> adapter.validate(props))
                    .withMessageContaining("dimension is required");
        }

        @Test
        @DisplayName("throws on zero dimension")
        void zeroDimension() {
            var props = validProperties();
            props.put("pinecone.dimension", 0);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> adapter.validate(props))
                    .withMessageContaining("dimension is required and must be a positive number");
        }

        @Test
        @DisplayName("throws on negative dimension")
        void negativeDimension() {
            var props = validProperties();
            props.put("pinecone.dimension", -5);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> adapter.validate(props))
                    .withMessageContaining("dimension is required and must be a positive number");
        }

        @Test
        @DisplayName("throws on non-numeric dimension")
        void nonNumericDimension() {
            var props = validProperties();
            props.put("pinecone.dimension", "big");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> adapter.validate(props))
                    .withMessageContaining("dimension is required and must be a positive number");
        }

        @Test
        @DisplayName("accepts valid properties without error")
        void validPropertiesPass() {
            assertThatCode(() -> adapter.validate(validProperties()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("accepts valid properties without optional indexName")
        void validPropertiesWithoutIndex() {
            var props = validProperties();
            props.remove("pinecone.indexName");

            assertThatCode(() -> adapter.validate(props))
                    .doesNotThrowAnyException();
        }
    }

    // ========== buildFilterCondition (private, via reflection) ==========

    @Nested
    @DisplayName("buildFilterCondition()")
    class BuildFilterConditionTests {

        @Test
        @DisplayName("returns empty map for null filter")
        void nullFilter() throws Exception {
            var result = invokeBuildFilterCondition(null);
            assertThat(result).isInstanceOf(Map.class);
            assertThat((Map<?, ?>) result).isEmpty();
        }

        @Test
        @DisplayName("builds IsEqualTo filter")
        void isEqualTo() throws Exception {
            var filter = new IsEqualTo("category", "news");
            var result = asMap(invokeBuildFilterCondition(filter));

            assertThat(result).containsKey("category");
            var inner = asMap(result.get("category"));
            assertThat(inner).containsEntry("$eq", "news");
        }

        @Test
        @DisplayName("builds IsNotEqualTo filter")
        void isNotEqualTo() throws Exception {
            var filter = new IsNotEqualTo("status", "deleted");
            var result = asMap(invokeBuildFilterCondition(filter));

            assertThat(result).containsKey("status");
            var inner = asMap(result.get("status"));
            assertThat(inner).containsEntry("$ne", "deleted");
        }

        @Test
        @DisplayName("builds IsGreaterThan filter")
        void isGreaterThan() throws Exception {
            var filter = new IsGreaterThan("age", 18);
            var result = asMap(invokeBuildFilterCondition(filter));

            var inner = asMap(result.get("age"));
            assertThat(inner).containsEntry("$gt", 18);
        }

        @Test
        @DisplayName("builds IsLessThan filter")
        void isLessThan() throws Exception {
            var filter = new IsLessThan("price", 50.0);
            var result = asMap(invokeBuildFilterCondition(filter));

            var inner = asMap(result.get("price"));
            assertThat(inner).containsEntry("$lt", 50.0);
        }

        @Test
        @DisplayName("builds IsGreaterThanOrEqualTo filter")
        void isGreaterThanOrEqualTo() throws Exception {
            var filter = new IsGreaterThanOrEqualTo("rating", 4);
            var result = asMap(invokeBuildFilterCondition(filter));

            var inner = asMap(result.get("rating"));
            assertThat(inner).containsEntry("$gte", 4);
        }

        @Test
        @DisplayName("builds IsLessThanOrEqualTo filter")
        void isLessThanOrEqualTo() throws Exception {
            var filter = new IsLessThanOrEqualTo("weight", 100);
            var result = asMap(invokeBuildFilterCondition(filter));

            var inner = asMap(result.get("weight"));
            assertThat(inner).containsEntry("$lte", 100);
        }

        @Test
        @DisplayName("builds IsIn filter")
        void isIn() throws Exception {
            var values = List.of("red", "blue", "green");
            var filter = new IsIn("color", values);
            var result = asMap(invokeBuildFilterCondition(filter));

            var inner = asMap(result.get("color"));
            assertThat(inner).containsKey("$in");
            @SuppressWarnings("unchecked")
            var inValues = (Collection<Object>) inner.get("$in");
            assertThat(inValues).containsAll(values);
        }

        @Test
        @DisplayName("builds And filter with $and key")
        void andFilter() throws Exception {
            var left = new IsEqualTo("category", "news");
            var right = new IsGreaterThan("score", 5);
            var filter = new And(left, right);

            var result = asMap(invokeBuildFilterCondition(filter));
            assertThat(result).containsKey("$and");

            var conditions = (List<?>) result.get("$and");
            assertThat(conditions).hasSize(2);

            var leftResult = asMap(conditions.get(0));
            assertThat(leftResult).containsKey("category");

            var rightResult = asMap(conditions.get(1));
            assertThat(rightResult).containsKey("score");
        }

        @Test
        @DisplayName("builds Or filter with $or key")
        void orFilter() throws Exception {
            var left = new IsEqualTo("type", "A");
            var right = new IsEqualTo("type", "B");
            var filter = new Or(left, right);

            var result = asMap(invokeBuildFilterCondition(filter));
            assertThat(result).containsKey("$or");

            var conditions = (List<?>) result.get("$or");
            assertThat(conditions).hasSize(2);
        }

        @Test
        @DisplayName("builds Not filter with $not key")
        void notFilter() throws Exception {
            var inner = new IsEqualTo("deleted", true);
            var filter = new Not(inner);

            var result = asMap(invokeBuildFilterCondition(filter));
            assertThat(result).containsKey("$not");
        }

        @Test
        @DisplayName("builds nested And/Or filter")
        void nestedFilter() throws Exception {
            var a = new IsEqualTo("x", 1);
            var b = new IsEqualTo("y", 2);
            var c = new IsGreaterThan("z", 0);
            var filter = new And(new Or(a, b), c);

            var result = asMap(invokeBuildFilterCondition(filter));
            assertThat(result).containsKey("$and");

            var andConditions = (List<?>) result.get("$and");
            assertThat(andConditions).hasSize(2);

            var orPart = asMap(andConditions.get(0));
            assertThat(orPart).containsKey("$or");

            var zPart = asMap(andConditions.get(1));
            assertThat(zPart).containsKey("z");
        }

        @Test
        @DisplayName("builds IsEqualTo with numeric value")
        void isEqualToNumeric() throws Exception {
            var filter = new IsEqualTo("count", 42);
            var result = asMap(invokeBuildFilterCondition(filter));

            var inner = asMap(result.get("count"));
            assertThat(inner).containsEntry("$eq", 42);
        }

        @Test
        @DisplayName("builds IsEqualTo with boolean value")
        void isEqualToBoolean() throws Exception {
            var filter = new IsEqualTo("active", true);
            var result = asMap(invokeBuildFilterCondition(filter));

            var inner = asMap(result.get("active"));
            assertThat(inner).containsEntry("$eq", true);
        }
    }

    // ========== generateIds ==========

    @Nested
    @DisplayName("generateIds()")
    class GenerateIdsTests {

        @Test
        @DisplayName("generates correct count of IDs")
        void correctCount() {
            var ids = adapter.generateIds(5);
            assertThat(ids).hasSize(5);
        }

        @Test
        @DisplayName("generates unique IDs")
        void uniqueIds() {
            var ids = adapter.generateIds(100);
            assertThat(new HashSet<>(ids)).hasSameSizeAs(ids);
        }

        @Test
        @DisplayName("generates valid UUIDs")
        void validUuids() {
            var ids = adapter.generateIds(3);
            for (var id : ids) {
                assertThatCode(() -> UUID.fromString(id)).doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("generates empty list for zero count")
        void zeroCount() {
            assertThat(adapter.generateIds(0)).isEmpty();
        }
    }

    // ========== Factory Tests ==========

    @Nested
    @DisplayName("PineconeVectorStoreAdapterFactory")
    class FactoryTests {

        private final PineconeVectorStoreAdapterFactory factory = new PineconeVectorStoreAdapterFactory();

        @Test
        @DisplayName("getProvider returns 'pinecone'")
        void getProvider() {
            assertThat(factory.getProvider()).isEqualTo("pinecone");
        }

        @Test
        @DisplayName("supports 'vectorstore' returns true")
        void supportsVectorstore() {
            assertThat(factory.supports("vectorstore")).isTrue();
        }

        @Test
        @DisplayName("supports 'chat' returns false")
        void doesNotSupportChat() {
            assertThat(factory.supports("chat")).isFalse();
        }

        @Test
        @DisplayName("supports 'embedding' returns false")
        void doesNotSupportEmbedding() {
            assertThat(factory.supports("embedding")).isFalse();
        }

        @Test
        @DisplayName("supports null returns false")
        void doesNotSupportNull() {
            assertThat(factory.supports(null)).isFalse();
        }

        @Test
        @DisplayName("supports empty string returns false")
        void doesNotSupportEmpty() {
            assertThat(factory.supports("")).isFalse();
        }
    }

    // ========== Reflection Helpers ==========

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object obj) {
        return (Map<String, Object>) obj;
    }

    private Object invokeBuildFilterCondition(Filter filter) throws Exception {
        var method = PineconeVectorStoreAdapter.class.getDeclaredMethod("buildFilterCondition", Filter.class);
        method.setAccessible(true);
        try {
            return method.invoke(adapter, filter);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
    }
}
