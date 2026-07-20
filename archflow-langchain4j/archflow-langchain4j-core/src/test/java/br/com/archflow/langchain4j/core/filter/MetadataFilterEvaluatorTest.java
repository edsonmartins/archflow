package br.com.archflow.langchain4j.core.filter;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.ContainsString;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import dev.langchain4j.store.embedding.filter.comparison.IsGreaterThan;
import dev.langchain4j.store.embedding.filter.comparison.IsGreaterThanOrEqualTo;
import dev.langchain4j.store.embedding.filter.comparison.IsIn;
import dev.langchain4j.store.embedding.filter.comparison.IsLessThan;
import dev.langchain4j.store.embedding.filter.comparison.IsLessThanOrEqualTo;
import dev.langchain4j.store.embedding.filter.comparison.IsNotEqualTo;
import dev.langchain4j.store.embedding.filter.comparison.IsNotIn;
import dev.langchain4j.store.embedding.filter.logical.And;
import dev.langchain4j.store.embedding.filter.logical.Not;
import dev.langchain4j.store.embedding.filter.logical.Or;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetadataFilterEvaluatorTest {

    private static Metadata metadata() {
        return new Metadata(Map.of(
                "segment", "tenant-a",
                "category", "news",
                "year", 2024,
                "score", 4.5
        ));
    }

    @Nested
    @DisplayName("comparison filters")
    class ComparisonFilters {

        @Test
        @DisplayName("IsEqualTo matches equal string value")
        void isEqualToMatches() {
            assertThat(MetadataFilterEvaluator.matches(new IsEqualTo("segment", "tenant-a"), metadata())).isTrue();
        }

        @Test
        @DisplayName("IsEqualTo does not match different value (no cross-segment leak)")
        void isEqualToRejects() {
            assertThat(MetadataFilterEvaluator.matches(new IsEqualTo("segment", "tenant-b"), metadata())).isFalse();
        }

        @Test
        @DisplayName("IsEqualTo does not match missing key")
        void isEqualToMissingKey() {
            assertThat(MetadataFilterEvaluator.matches(new IsEqualTo("missing", "x"), metadata())).isFalse();
        }

        @Test
        @DisplayName("IsNotEqualTo matches different value")
        void isNotEqualTo() {
            assertThat(MetadataFilterEvaluator.matches(new IsNotEqualTo("segment", "tenant-b"), metadata())).isTrue();
            assertThat(MetadataFilterEvaluator.matches(new IsNotEqualTo("segment", "tenant-a"), metadata())).isFalse();
        }

        @Test
        @DisplayName("numeric comparisons work with int values")
        void numericComparisons() {
            assertThat(MetadataFilterEvaluator.matches(new IsGreaterThan("year", 2020), metadata())).isTrue();
            assertThat(MetadataFilterEvaluator.matches(new IsGreaterThan("year", 2024), metadata())).isFalse();
            assertThat(MetadataFilterEvaluator.matches(new IsGreaterThanOrEqualTo("year", 2024), metadata())).isTrue();
            assertThat(MetadataFilterEvaluator.matches(new IsLessThan("year", 2025), metadata())).isTrue();
            assertThat(MetadataFilterEvaluator.matches(new IsLessThanOrEqualTo("year", 2023), metadata())).isFalse();
        }

        @Test
        @DisplayName("numeric comparisons work with double values")
        void doubleComparisons() {
            assertThat(MetadataFilterEvaluator.matches(new IsGreaterThan("score", 4.0), metadata())).isTrue();
            assertThat(MetadataFilterEvaluator.matches(new IsLessThan("score", 4.0), metadata())).isFalse();
        }

        @Test
        @DisplayName("IsIn / IsNotIn")
        void isInAndNotIn() {
            assertThat(MetadataFilterEvaluator.matches(new IsIn("category", List.of("news", "blog")), metadata())).isTrue();
            assertThat(MetadataFilterEvaluator.matches(new IsIn("category", List.of("blog")), metadata())).isFalse();
            assertThat(MetadataFilterEvaluator.matches(new IsNotIn("category", List.of("blog")), metadata())).isTrue();
        }

        @Test
        @DisplayName("ContainsString")
        void containsString() {
            assertThat(MetadataFilterEvaluator.matches(new ContainsString("segment", "tenant"), metadata())).isTrue();
            assertThat(MetadataFilterEvaluator.matches(new ContainsString("segment", "other"), metadata())).isFalse();
        }
    }

    @Nested
    @DisplayName("logical filters")
    class LogicalFilters {

        @Test
        @DisplayName("And requires both sides")
        void and() {
            var filter = new And(new IsEqualTo("segment", "tenant-a"), new IsGreaterThan("year", 2020));
            assertThat(MetadataFilterEvaluator.matches(filter, metadata())).isTrue();

            var failing = new And(new IsEqualTo("segment", "tenant-b"), new IsGreaterThan("year", 2020));
            assertThat(MetadataFilterEvaluator.matches(failing, metadata())).isFalse();
        }

        @Test
        @DisplayName("Or requires either side")
        void or() {
            var filter = new Or(new IsEqualTo("segment", "tenant-b"), new IsEqualTo("category", "news"));
            assertThat(MetadataFilterEvaluator.matches(filter, metadata())).isTrue();
        }

        @Test
        @DisplayName("Not inverts the result")
        void not() {
            var filter = new Not(new IsEqualTo("segment", "tenant-b"));
            assertThat(MetadataFilterEvaluator.matches(filter, metadata())).isTrue();
        }

        @Test
        @DisplayName("nested And/Or/Not")
        void nested() {
            var filter = new And(
                    new Or(new IsEqualTo("category", "news"), new IsEqualTo("category", "blog")),
                    new Not(new IsEqualTo("segment", "tenant-b")));
            assertThat(MetadataFilterEvaluator.matches(filter, metadata())).isTrue();
        }
    }

    @Nested
    @DisplayName("edge cases")
    class EdgeCases {

        @Test
        @DisplayName("null filter always matches")
        void nullFilter() {
            assertThat(MetadataFilterEvaluator.matches(null, metadata())).isTrue();
        }

        @Test
        @DisplayName("null metadata is treated as empty")
        void nullMetadata() {
            assertThat(MetadataFilterEvaluator.matches(new IsEqualTo("k", "v"), null)).isFalse();
            assertThat(MetadataFilterEvaluator.matches(new IsNotEqualTo("k", "v"), null)).isTrue();
        }

        @Test
        @DisplayName("unsupported custom filter type throws UnsupportedOperationException")
        void unsupportedFilter() {
            Filter custom = object -> true;
            assertThatThrownBy(() -> MetadataFilterEvaluator.matches(custom, metadata()))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("Unsupported filter type");
        }

        @Test
        @DisplayName("unsupported filter nested inside And is also rejected")
        void unsupportedNestedFilter() {
            Filter custom = object -> true;
            Filter filter = new And(new IsEqualTo("segment", "tenant-a"), custom);
            assertThatThrownBy(() -> MetadataFilterEvaluator.matches(filter, metadata()))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("Unsupported filter type");
        }
    }
}
