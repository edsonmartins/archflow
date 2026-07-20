package br.com.archflow.langchain4j.vectorstore.pgvector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

class PgVectorStoreAdapterTest {

    private PgVectorStoreAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new PgVectorStoreAdapter();
    }

    // -- Helper to build valid properties --

    private Map<String, Object> validProperties() {
        var props = new HashMap<String, Object>();
        props.put("pgvector.jdbcUrl", "jdbc:postgresql://localhost:5432/test");
        props.put("pgvector.username", "postgres");
        props.put("pgvector.password", "secret");
        props.put("pgvector.dimension", 1536);
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
        @DisplayName("throws on missing JDBC URL")
        void missingJdbcUrl() {
            var props = validProperties();
            props.remove("pgvector.jdbcUrl");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> adapter.validate(props))
                    .withMessageContaining("JDBC URL is required");
        }

        @Test
        @DisplayName("throws on blank JDBC URL")
        void blankJdbcUrl() {
            var props = validProperties();
            props.put("pgvector.jdbcUrl", "   ");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> adapter.validate(props))
                    .withMessageContaining("JDBC URL is required");
        }

        @Test
        @DisplayName("throws on missing username")
        void missingUsername() {
            var props = validProperties();
            props.remove("pgvector.username");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> adapter.validate(props))
                    .withMessageContaining("username is required");
        }

        @Test
        @DisplayName("throws on blank username")
        void blankUsername() {
            var props = validProperties();
            props.put("pgvector.username", "  ");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> adapter.validate(props))
                    .withMessageContaining("username is required");
        }

        @Test
        @DisplayName("throws on missing password")
        void missingPassword() {
            var props = validProperties();
            props.remove("pgvector.password");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> adapter.validate(props))
                    .withMessageContaining("password is required");
        }

        @Test
        @DisplayName("throws on empty table name when provided")
        void emptyTableName() {
            var props = validProperties();
            props.put("pgvector.table", "   ");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> adapter.validate(props))
                    .withMessageContaining("table name cannot be empty");
        }

        @Test
        @DisplayName("throws on missing dimension")
        void missingDimension() {
            var props = validProperties();
            props.remove("pgvector.dimension");

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> adapter.validate(props))
                    .withMessageContaining("dimension is required");
        }

        @Test
        @DisplayName("throws on zero dimension")
        void zeroDimension() {
            var props = validProperties();
            props.put("pgvector.dimension", 0);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> adapter.validate(props))
                    .withMessageContaining("dimension is required and must be a positive number");
        }

        @Test
        @DisplayName("throws on negative dimension")
        void negativeDimension() {
            var props = validProperties();
            props.put("pgvector.dimension", -10);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> adapter.validate(props))
                    .withMessageContaining("dimension is required and must be a positive number");
        }

        @Test
        @DisplayName("throws on non-numeric dimension")
        void nonNumericDimension() {
            var props = validProperties();
            props.put("pgvector.dimension", "not-a-number");

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
        @DisplayName("accepts valid properties with optional table name")
        void validPropertiesWithTable() {
            var props = validProperties();
            props.put("pgvector.table", "custom_embeddings");

            assertThatCode(() -> adapter.validate(props))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("accepts empty password (only null is rejected)")
        void emptyPasswordAllowed() {
            var props = validProperties();
            props.put("pgvector.password", "");

            assertThatCode(() -> adapter.validate(props))
                    .doesNotThrowAnyException();
        }
    }

    // ========== vectorToString / parseVector (private, via reflection) ==========

    @Nested
    @DisplayName("vectorToString()")
    class VectorToStringTests {

        @Test
        @DisplayName("formats a single-element vector")
        void singleElement() throws Exception {
            var result = invokeVectorToString(new float[]{1.5f});
            assertThat(result).isEqualTo("[1.5]");
        }

        @Test
        @DisplayName("formats a multi-element vector")
        void multiElement() throws Exception {
            var result = invokeVectorToString(new float[]{0.1f, 0.2f, 0.3f});
            assertThat(result).startsWith("[").endsWith("]");
            // Parse back to verify round-trip
            var parsed = invokeParseVector(result);
            assertThat(parsed).containsExactly(0.1f, 0.2f, 0.3f);
        }

        @Test
        @DisplayName("formats an empty vector")
        void emptyVector() throws Exception {
            var result = invokeVectorToString(new float[]{});
            assertThat(result).isEqualTo("[]");
        }
    }

    @Nested
    @DisplayName("parseVector()")
    class ParseVectorTests {

        @Test
        @DisplayName("parses a standard vector string")
        void standardVector() throws Exception {
            var result = invokeParseVector("[1.0,2.0,3.0]");
            assertThat(result).containsExactly(1.0f, 2.0f, 3.0f);
        }

        @Test
        @DisplayName("parses a vector with spaces")
        void vectorWithSpaces() throws Exception {
            var result = invokeParseVector("[1.0, 2.0, 3.0]");
            assertThat(result).containsExactly(1.0f, 2.0f, 3.0f);
        }

        @Test
        @DisplayName("round-trips vectorToString -> parseVector")
        void roundTrip() throws Exception {
            var original = new float[]{-0.5f, 0.0f, 1.23456f};
            var str = invokeVectorToString(original);
            var parsed = invokeParseVector(str);
            assertThat(parsed).containsExactly(original);
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
    @DisplayName("PgVectorStoreAdapterFactory")
    class FactoryTests {

        private final PgVectorStoreAdapterFactory factory = new PgVectorStoreAdapterFactory();

        @Test
        @DisplayName("getProvider returns 'pgvector'")
        void getProvider() {
            assertThat(factory.getProvider()).isEqualTo("pgvector");
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

    private String invokeVectorToString(float[] vector) throws Exception {
        var method = PgVectorStoreAdapter.class.getDeclaredMethod("vectorToString", float[].class);
        method.setAccessible(true);
        return (String) method.invoke(adapter, vector);
    }

    private float[] invokeParseVector(String vectorStr) throws Exception {
        var method = PgVectorStoreAdapter.class.getDeclaredMethod("parseVector", String.class);
        method.setAccessible(true);
        return (float[]) method.invoke(adapter, vectorStr);
    }

}
