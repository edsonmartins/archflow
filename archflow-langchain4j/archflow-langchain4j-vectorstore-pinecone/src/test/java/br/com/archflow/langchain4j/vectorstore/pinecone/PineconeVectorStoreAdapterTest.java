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

    // ========== Request body construction (no network) ==========

    @Nested
    @DisplayName("request bodies")
    class RequestBodyTests {

        private final com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();

        @Test
        @DisplayName("upsert body persists ALL segment metadata plus text")
        void upsertBodyIncludesAllMetadata() {
            var segment = dev.langchain4j.data.segment.TextSegment.from(
                    "hello world",
                    new dev.langchain4j.data.document.Metadata(Map.of("segment", "tenant-a", "year", 2024)));
            var metadata = PineconeVectorStoreAdapter.buildVectorMetadata(segment);
            var vectors = List.of(new PineconeVectorStoreAdapter.VectorData(
                    "id-1", new float[]{0.1f, 0.2f}, metadata));

            var body = PineconeVectorStoreAdapter.buildUpsertRequestBody(vectors, "ns");

            assertThat(body).containsEntry("namespace", "ns");
            @SuppressWarnings("unchecked")
            var vectorList = (List<Map<String, Object>>) body.get("vectors");
            assertThat(vectorList).hasSize(1);
            var vector = vectorList.get(0);
            assertThat(vector).containsEntry("id", "id-1");
            var meta = asMap(vector.get("metadata"));
            assertThat(meta)
                    .containsEntry("text", "hello world")
                    .containsEntry("segment", "tenant-a")
                    .containsEntry("year", 2024);
        }

        @Test
        @DisplayName("upsert body omits metadata when segment is null")
        void upsertBodyWithoutSegment() {
            var vectors = List.of(new PineconeVectorStoreAdapter.VectorData(
                    "id-1", new float[]{0.1f}, PineconeVectorStoreAdapter.buildVectorMetadata(null)));

            var body = PineconeVectorStoreAdapter.buildUpsertRequestBody(vectors, "ns");

            @SuppressWarnings("unchecked")
            var vectorList = (List<Map<String, Object>>) body.get("vectors");
            assertThat(vectorList.get(0)).doesNotContainKey("metadata");
        }

        @Test
        @DisplayName("delete-by-ids body serializes to {\"ids\": [...]} JSON")
        void deleteByIdsBody() throws Exception {
            var body = PineconeVectorStoreAdapter.buildDeleteByIdsRequestBody(
                    List.of("a", "b"), "ns");

            assertThat(body).containsEntry("namespace", "ns");
            assertThat(body.get("ids")).isEqualTo(List.of("a", "b"));

            var json = mapper.writeValueAsString(body);
            assertThat(json).contains("\"ids\":[\"a\",\"b\"]");
            assertThat(json).doesNotContain("deleteAll");
        }

        @Test
        @DisplayName("delete-all body serializes to {\"deleteAll\": true} JSON")
        void deleteAllBody() throws Exception {
            var body = PineconeVectorStoreAdapter.buildDeleteAllRequestBody("ns");

            var json = mapper.writeValueAsString(body);
            assertThat(json).contains("\"deleteAll\":true");
            assertThat(json).contains("\"namespace\":\"ns\"");
        }
    }

    // ========== Match parsing (no network) ==========

    @Nested
    @DisplayName("toEmbeddingMatch()")
    class ToEmbeddingMatchTests {

        @Test
        @DisplayName("parses integer score without ClassCastException")
        void integerScore() {
            var match = new HashMap<String, Object>();
            match.put("id", "id-1");
            match.put("score", 1); // JSON integer, not Double
            match.put("values", List.of(1, 2)); // integers too

            var result = PineconeVectorStoreAdapter.toEmbeddingMatch(match);

            assertThat(result.score()).isEqualTo(1.0);
            assertThat(result.embedding().vector()).containsExactly(1.0f, 2.0f);
        }

        @Test
        @DisplayName("parses double score and reconstructs segment metadata")
        void doubleScoreWithMetadata() {
            var match = new HashMap<String, Object>();
            match.put("id", "id-2");
            match.put("score", 0.87);
            match.put("values", List.of(0.5, 0.25));
            match.put("metadata", Map.of("text", "hello", "segment", "tenant-a", "year", 2024));

            var result = PineconeVectorStoreAdapter.toEmbeddingMatch(match);

            assertThat(result.score()).isEqualTo(0.87);
            assertThat(result.embeddingId()).isEqualTo("id-2");
            assertThat(result.embedded().text()).isEqualTo("hello");
            assertThat(result.embedded().metadata().toMap())
                    .containsEntry("segment", "tenant-a")
                    .containsEntry("year", 2024)
                    .doesNotContainKey("text");
        }

        @Test
        @DisplayName("match without metadata yields null segment")
        void noMetadata() {
            var match = new HashMap<String, Object>();
            match.put("id", "id-3");
            match.put("score", 0.5);
            match.put("values", List.of(1.0));

            var result = PineconeVectorStoreAdapter.toEmbeddingMatch(match);

            assertThat(result.embedded()).isNull();
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
