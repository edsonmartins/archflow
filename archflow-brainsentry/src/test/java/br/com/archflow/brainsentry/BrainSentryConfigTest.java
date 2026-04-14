package br.com.archflow.brainsentry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link BrainSentryConfig}, {@link EnrichedPrompt}, and {@link Memory}
 * value objects.
 *
 * <p>These are pure data-structure tests — no I/O, no mocks required.
 */
@DisplayName("BrainSentryConfig")
class BrainSentryConfigTest {

    // ---------------------------------------------------------------------------
    // BrainSentryConfig
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("factory method BrainSentryConfig.of(baseUrl)")
    class SingleArgFactory {

        @Test
        @DisplayName("stores baseUrl correctly")
        void storesBaseUrl() {
            var config = BrainSentryConfig.of("http://example.com/api");
            assertThat(config.baseUrl()).isEqualTo("http://example.com/api");
        }

        @Test
        @DisplayName("defaults maxTokenBudget to 2000")
        void defaultMaxTokenBudget() {
            var config = BrainSentryConfig.of("http://example.com/api");
            assertThat(config.maxTokenBudget()).isEqualTo(2000);
        }

        @Test
        @DisplayName("defaults deepAnalysisEnabled to false")
        void defaultDeepAnalysis() {
            var config = BrainSentryConfig.of("http://example.com/api");
            assertThat(config.deepAnalysisEnabled()).isFalse();
        }

        @Test
        @DisplayName("defaults timeout to 10 seconds")
        void defaultTimeout() {
            var config = BrainSentryConfig.of("http://example.com/api");
            assertThat(config.timeout()).isEqualTo(Duration.ofSeconds(10));
        }

        @Test
        @DisplayName("apiKey is null by default")
        void defaultApiKeyNull() {
            var config = BrainSentryConfig.of("http://example.com/api");
            assertThat(config.apiKey()).isNull();
        }

        @Test
        @DisplayName("tenantId is null by default")
        void defaultTenantIdNull() {
            var config = BrainSentryConfig.of("http://example.com/api");
            assertThat(config.tenantId()).isNull();
        }
    }

    @Nested
    @DisplayName("factory method BrainSentryConfig.of(baseUrl, apiKey, tenantId)")
    class ThreeArgFactory {

        @Test
        @DisplayName("stores all three fields")
        void storesAllFields() {
            var config = BrainSentryConfig.of("http://example.com/api", "key-abc", "tenant-42");
            assertThat(config.baseUrl()).isEqualTo("http://example.com/api");
            assertThat(config.apiKey()).isEqualTo("key-abc");
            assertThat(config.tenantId()).isEqualTo("tenant-42");
        }

        @Test
        @DisplayName("retains default maxTokenBudget")
        void retainsDefaultBudget() {
            var config = BrainSentryConfig.of("http://example.com/api", "k", "t");
            assertThat(config.maxTokenBudget()).isEqualTo(2000);
        }

        @Test
        @DisplayName("retains default timeout")
        void retainsDefaultTimeout() {
            var config = BrainSentryConfig.of("http://example.com/api", "k", "t");
            assertThat(config.timeout()).isEqualTo(Duration.ofSeconds(10));
        }
    }

    @Nested
    @DisplayName("canonical constructor guards")
    class ConstructorGuards {

        @Test
        @DisplayName("rejects null baseUrl")
        void rejectsNullBaseUrl() {
            assertThatThrownBy(() -> BrainSentryConfig.of(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("coerces non-positive maxTokenBudget to 2000")
        void coercesNonPositiveBudget() {
            var config = new BrainSentryConfig("http://x.com", null, null, 0, false, null);
            assertThat(config.maxTokenBudget()).isEqualTo(2000);
        }

        @Test
        @DisplayName("coerces negative maxTokenBudget to 2000")
        void coercesNegativeBudget() {
            var config = new BrainSentryConfig("http://x.com", null, null, -100, false, null);
            assertThat(config.maxTokenBudget()).isEqualTo(2000);
        }

        @Test
        @DisplayName("coerces null timeout to 10-second default")
        void coercesNullTimeout() {
            var config = new BrainSentryConfig("http://x.com", null, null, 2000, false, null);
            assertThat(config.timeout()).isEqualTo(Duration.ofSeconds(10));
        }

        @Test
        @DisplayName("preserves explicit positive budget")
        void preservesPositiveBudget() {
            var config = new BrainSentryConfig("http://x.com", null, null, 5000, false, Duration.ofSeconds(5));
            assertThat(config.maxTokenBudget()).isEqualTo(5000);
        }

        @Test
        @DisplayName("preserves explicit timeout")
        void preservesExplicitTimeout() {
            var config = new BrainSentryConfig("http://x.com", null, null, 1000, false, Duration.ofSeconds(30));
            assertThat(config.timeout()).isEqualTo(Duration.ofSeconds(30));
        }

        @Test
        @DisplayName("preserves deepAnalysisEnabled=true")
        void preservesDeepAnalysisEnabled() {
            var config = new BrainSentryConfig("http://x.com", "k", "t", 2000, true, null);
            assertThat(config.deepAnalysisEnabled()).isTrue();
        }
    }

    // ---------------------------------------------------------------------------
    // EnrichedPrompt
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("EnrichedPrompt")
    class EnrichedPromptTests {

        @Test
        @DisplayName("fullPrompt returns concatenation when enhanced")
        void fullPromptWhenEnhanced() {
            var ep = new EnrichedPrompt(true, "original", "injected context", java.util.List.of("m1"), 50);
            assertThat(ep.fullPrompt()).isEqualTo("injected context\n\noriginal");
        }

        @Test
        @DisplayName("fullPrompt returns original when not enhanced")
        void fullPromptWhenNotEnhanced() {
            var ep = new EnrichedPrompt(false, "original", "injected context", java.util.List.of(), 0);
            assertThat(ep.fullPrompt()).isEqualTo("original");
        }

        @Test
        @DisplayName("fullPrompt returns original when injectedContext is null")
        void fullPromptNullContext() {
            var ep = new EnrichedPrompt(true, "original", null, java.util.List.of(), 0);
            assertThat(ep.fullPrompt()).isEqualTo("original");
        }

        @Test
        @DisplayName("fullPrompt returns original when injectedContext is blank")
        void fullPromptBlankContext() {
            var ep = new EnrichedPrompt(true, "original", "   ", java.util.List.of(), 0);
            assertThat(ep.fullPrompt()).isEqualTo("original");
        }

        @Test
        @DisplayName("memoriesUsed is empty list when null passed to constructor")
        void memoriesUsedNullBecomesEmpty() {
            var ep = new EnrichedPrompt(false, "p", null, null, 0);
            assertThat(ep.memoriesUsed()).isEmpty();
        }

        @Test
        @DisplayName("memoriesUsed is a defensive (immutable) copy")
        void memoriesUsedDefensiveCopy() {
            var list = new java.util.ArrayList<String>();
            list.add("m1");
            var ep = new EnrichedPrompt(true, "p", "ctx", list, 10);
            list.add("m2");
            assertThat(ep.memoriesUsed()).containsExactly("m1");
        }

        @Test
        @DisplayName("records all fields correctly")
        void recordFields() {
            var ep = new EnrichedPrompt(true, "orig", "ctx", java.util.List.of("x"), 123L);
            assertThat(ep.enhanced()).isTrue();
            assertThat(ep.originalPrompt()).isEqualTo("orig");
            assertThat(ep.injectedContext()).isEqualTo("ctx");
            assertThat(ep.memoriesUsed()).containsExactly("x");
            assertThat(ep.latencyMs()).isEqualTo(123L);
        }
    }

    // ---------------------------------------------------------------------------
    // Memory
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("Memory")
    class MemoryTests {

        @Test
        @DisplayName("records all fields correctly")
        void recordFields() {
            var now = java.time.Instant.now();
            var mem = new Memory("id-1", "Some content", "summary text",
                    "KNOWLEDGE", "CRITICAL", "SEMANTIC",
                    java.util.List.of("tag1", "tag2"),
                    java.util.Map.of("key", "value"),
                    now);
            assertThat(mem.id()).isEqualTo("id-1");
            assertThat(mem.content()).isEqualTo("Some content");
            assertThat(mem.summary()).isEqualTo("summary text");
            assertThat(mem.category()).isEqualTo("KNOWLEDGE");
            assertThat(mem.importance()).isEqualTo("CRITICAL");
            assertThat(mem.memoryType()).isEqualTo("SEMANTIC");
            assertThat(mem.tags()).containsExactly("tag1", "tag2");
            assertThat(mem.metadata()).containsEntry("key", "value");
            assertThat(mem.createdAt()).isEqualTo(now);
        }

        @Test
        @DisplayName("null tags becomes empty list")
        void nullTagsBecomesEmptyList() {
            var mem = new Memory("id", "content", null, null, null, null, null, null, null);
            assertThat(mem.tags()).isEmpty();
        }

        @Test
        @DisplayName("null metadata becomes empty map")
        void nullMetadataBecomesEmptyMap() {
            var mem = new Memory("id", "content", null, null, null, null, null, null, null);
            assertThat(mem.metadata()).isEmpty();
        }

        @Test
        @DisplayName("tags is a defensive copy — mutation of source list not reflected")
        void tagsDefensiveCopy() {
            var tags = new java.util.ArrayList<String>();
            tags.add("a");
            var mem = new Memory("id", "c", null, null, null, null, tags, null, null);
            tags.add("b");
            assertThat(mem.tags()).containsExactly("a");
        }

        @Test
        @DisplayName("metadata is a defensive copy — mutation of source map not reflected")
        void metadataDefensiveCopy() {
            var meta = new java.util.HashMap<String, Object>();
            meta.put("k", "v");
            var mem = new Memory("id", "c", null, null, null, null, null, meta, null);
            meta.put("k2", "v2");
            assertThat(mem.metadata()).hasSize(1);
        }

        @Test
        @DisplayName("allows null createdAt")
        void allowsNullCreatedAt() {
            var mem = new Memory("id", "c", null, null, null, null, null, null, null);
            assertThat(mem.createdAt()).isNull();
        }
    }
}
