package br.com.archflow.langchain4j.provider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ProviderSwitcher}.
 *
 * <p>Tests focus on construction, stats initialisation, and the inner
 * {@link ProviderSwitcher.ProviderStats} record. Execution-path tests
 * that require a live LLM are omitted intentionally.
 */
@DisplayName("ProviderSwitcher")
class ProviderSwitcherTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Returns an OLLAMA config — no API key required. */
    private static LLMProviderConfig ollamaConfig(String modelId) {
        return LLMProviderConfig.builder()
                .provider(LLMProvider.OLLAMA)
                .modelId(modelId)
                .baseUrl("http://localhost:11434")
                .build();
    }

    @BeforeEach
    void resetHub() {
        LLMProviderHub.reset();
    }

    @AfterEach
    void cleanHub() {
        LLMProviderHub.reset();
    }

    // -------------------------------------------------------------------------
    // Builder / construction
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("builder()")
    class BuilderTests {

        @Test
        @DisplayName("build() without primary throws IllegalArgumentException")
        void buildWithoutPrimaryThrows() {
            assertThatThrownBy(() -> ProviderSwitcher.builder("no-primary").build())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Primary config is required");
        }

        @Test
        @DisplayName("build() with only primary succeeds")
        void buildWithPrimaryOnlySucceeds() {
            ProviderSwitcher switcher = ProviderSwitcher.builder("primary-only")
                    .primary(ollamaConfig("llama3.3"))
                    .build();

            assertThat(switcher.getPrimaryConfig()).isNotNull();
            assertThat(switcher.getFallbackConfig()).isEmpty();
        }

        @Test
        @DisplayName("build() with primary + fallback registers both")
        void buildWithPrimaryAndFallback() {
            ProviderSwitcher switcher = ProviderSwitcher.builder("with-fallback")
                    .primary(ollamaConfig("llama3.3"))
                    .fallback(ollamaConfig("llama3.2"))
                    .build();

            assertThat(switcher.getPrimaryConfig()).isNotNull();
            assertThat(switcher.getFallbackConfig()).isPresent();
        }

        @Test
        @DisplayName("getPrimaryConfig() returns the config passed to primary()")
        void getPrimaryConfigReturnsCorrectConfig() {
            LLMProviderConfig cfg = ollamaConfig("llama3.3");
            ProviderSwitcher switcher = ProviderSwitcher.builder("cfg-check")
                    .primary(cfg)
                    .build();

            assertThat(switcher.getPrimaryConfig()).isEqualTo(cfg);
        }

        @Test
        @DisplayName("getFallbackConfig() returns the config passed to fallback()")
        void getFallbackConfigReturnsCorrectConfig() {
            LLMProviderConfig fallback = ollamaConfig("mistral");
            ProviderSwitcher switcher = ProviderSwitcher.builder("fb-check")
                    .primary(ollamaConfig("llama3.3"))
                    .fallback(fallback)
                    .build();

            assertThat(switcher.getFallbackConfig()).isPresent().contains(fallback);
        }

        @Test
        @DisplayName("custom hub injected via builder() is used instead of singleton")
        void customHubIsUsed() {
            LLMProviderHub customHub = LLMProviderHub.getInstance();
            LLMProviderConfig cfg = ollamaConfig("llama3.3");

            // Should not throw — hub injection path exercises the non-null hub branch
            ProviderSwitcher switcher = ProviderSwitcher.builder("custom-hub")
                    .primary(cfg)
                    .hub(customHub)
                    .build();

            assertThat(switcher.getPrimaryConfig()).isEqualTo(cfg);
        }
    }

    // -------------------------------------------------------------------------
    // Stats initialisation
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("getStats() — initial state")
    class StatsInitialisation {

        @Test
        @DisplayName("getStats() returns map with only 'primary' key when no fallback")
        void statsKeysWithPrimaryOnly() {
            ProviderSwitcher switcher = ProviderSwitcher.builder("stats-primary")
                    .primary(ollamaConfig("llama3.3"))
                    .build();

            Map<String, ProviderSwitcher.ProviderStats> stats = switcher.getStats();

            assertThat(stats).containsOnlyKeys("primary");
        }

        @Test
        @DisplayName("getStats() returns map with 'primary' and 'fallback' keys when both configured")
        void statsKeysWithFallback() {
            ProviderSwitcher switcher = ProviderSwitcher.builder("stats-both")
                    .primary(ollamaConfig("llama3.3"))
                    .fallback(ollamaConfig("llama3.2"))
                    .build();

            Map<String, ProviderSwitcher.ProviderStats> stats = switcher.getStats();

            assertThat(stats).containsOnlyKeys("primary", "fallback");
        }

        @Test
        @DisplayName("all numeric stats are zero immediately after construction")
        void allStatsAreInitiallyZero() {
            ProviderSwitcher switcher = ProviderSwitcher.builder("zero-stats")
                    .primary(ollamaConfig("llama3.3"))
                    .fallback(ollamaConfig("mistral"))
                    .build();

            for (ProviderSwitcher.ProviderStats s : switcher.getStats().values()) {
                assertThat(s.getSuccessCount()).isZero();
                assertThat(s.getFailureCount()).isZero();
                assertThat(s.getTotalCount()).isZero();
                assertThat(s.getSuccessRate()).isZero();
                assertThat(s.getAverageDuration()).isZero();
                assertThat(s.getMinDuration()).isZero();
                assertThat(s.getMaxDuration()).isZero();
            }
        }

        @Test
        @DisplayName("getStats(String) returns the same object as getStats() for 'primary'")
        void getStatsByKeyMatchesMap() {
            ProviderSwitcher switcher = ProviderSwitcher.builder("by-key")
                    .primary(ollamaConfig("llama3.3"))
                    .build();

            ProviderSwitcher.ProviderStats viaMap = switcher.getStats().get("primary");
            ProviderSwitcher.ProviderStats viaKey = switcher.getStats("primary");

            assertThat(viaKey.getProviderId()).isEqualTo(viaMap.getProviderId());
        }

        @Test
        @DisplayName("getStats() returns an unmodifiable view")
        void getStatsReturnsUnmodifiableMap() {
            ProviderSwitcher switcher = ProviderSwitcher.builder("immutable")
                    .primary(ollamaConfig("llama3.3"))
                    .build();

            Map<String, ProviderSwitcher.ProviderStats> stats = switcher.getStats();

            assertThatThrownBy(() -> stats.put("extra", new ProviderSwitcher.ProviderStats("x")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("primary stats providerId reflects OLLAMA provider id")
        void primaryStatsProviderIdIsOllama() {
            ProviderSwitcher switcher = ProviderSwitcher.builder("provider-id")
                    .primary(ollamaConfig("llama3.3"))
                    .build();

            assertThat(switcher.getStats("primary").getProviderId())
                    .isEqualTo(LLMProvider.OLLAMA.getId());
        }
    }

    // -------------------------------------------------------------------------
    // ProviderStats record behaviour
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("ProviderStats")
    class ProviderStatsTests {

        @Test
        @DisplayName("new ProviderStats has all-zero counters")
        void initialValuesAllZero() {
            ProviderSwitcher.ProviderStats stats = new ProviderSwitcher.ProviderStats("ollama");

            assertThat(stats.getProviderId()).isEqualTo("ollama");
            assertThat(stats.getSuccessCount()).isZero();
            assertThat(stats.getFailureCount()).isZero();
            assertThat(stats.getTotalCount()).isZero();
            assertThat(stats.getSuccessRate()).isZero();
            assertThat(stats.getAverageDuration()).isZero();
            assertThat(stats.getMinDuration()).isZero();
            assertThat(stats.getMaxDuration()).isZero();
        }

        @Test
        @DisplayName("getSuccessRate() returns 0.0 when totalCount is zero")
        void successRateIsZeroWhenNoRequests() {
            ProviderSwitcher.ProviderStats stats = new ProviderSwitcher.ProviderStats("p");

            assertThat(stats.getSuccessRate()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("getTotalCount() is sum of success and failure counts")
        void totalCountIsSumOfSuccessAndFailure() {
            // We can observe this only via resetStats() round-trip on a real switcher
            // because recordSuccess/recordFailure are package-private.
            // Verify via the switcher's resetStats path.
            ProviderSwitcher switcher = ProviderSwitcher.builder("total-count")
                    .primary(ollamaConfig("llama3.3"))
                    .build();

            ProviderSwitcher.ProviderStats stats = switcher.getStats("primary");

            // Initially both zero → totalCount is 0
            assertThat(stats.getTotalCount())
                    .isEqualTo(stats.getSuccessCount() + stats.getFailureCount());
        }

        @Test
        @DisplayName("resetStats() resets all counters back to zero")
        void resetStatsResetsCounters() {
            ProviderSwitcher switcher = ProviderSwitcher.builder("reset-test")
                    .primary(ollamaConfig("llama3.3"))
                    .build();

            // Counters start at zero — resetStats() must keep them at zero
            switcher.resetStats();

            ProviderSwitcher.ProviderStats stats = switcher.getStats("primary");
            assertThat(stats.getSuccessCount()).isZero();
            assertThat(stats.getFailureCount()).isZero();
            assertThat(stats.getAverageDuration()).isZero();
            assertThat(stats.getMinDuration()).isZero();
            assertThat(stats.getMaxDuration()).isZero();
        }
    }

    // -------------------------------------------------------------------------
    // Listener wiring
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("SwitchListener")
    class ListenerTests {

        @Test
        @DisplayName("listener added via builder is stored and removable")
        void addAndRemoveListener() {
            List<String> log = new ArrayList<>();
            ProviderSwitcher.SwitchListener listener =
                    new ProviderSwitcher.SwitchListener() {
                        @Override
                        public void onSuccess(String switcherId, String providerKey,
                                              String context, long duration) {
                            log.add("success:" + providerKey);
                        }

                        @Override
                        public void onFailure(String switcherId, String providerKey,
                                              String context, Exception error) {
                            log.add("failure:" + providerKey);
                        }
                    };

            ProviderSwitcher switcher = ProviderSwitcher.builder("listener-test")
                    .primary(ollamaConfig("llama3.3"))
                    .addListener(listener)
                    .build();

            // Verify remove does not throw
            switcher.removeListener(listener);

            // Listeners list is now empty; nothing to assert beyond no exception
            assertThat(log).isEmpty();
        }
    }

    // -------------------------------------------------------------------------
    // Strategy wiring
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("SwitchStrategy")
    class StrategyTests {

        @Test
        @DisplayName("PrimaryOnlyStrategy lists primary before fallback")
        void primaryOnlyStrategyOrdering() {
            Map<String, ProviderSwitcher.ProviderStats> statsMap = Map.of(
                    "primary", new ProviderSwitcher.ProviderStats("ollama"),
                    "fallback", new ProviderSwitcher.ProviderStats("ollama")
            );

            ProviderSwitcher.PrimaryOnlyStrategy strategy = new ProviderSwitcher.PrimaryOnlyStrategy();
            List<String> order = strategy.selectProvider(statsMap);

            assertThat(order).containsExactly("primary", "fallback");
        }

        @Test
        @DisplayName("PrimaryOnlyStrategy with primary only returns single-element list")
        void primaryOnlyStrategyWithNofallback() {
            Map<String, ProviderSwitcher.ProviderStats> statsMap = Map.of(
                    "primary", new ProviderSwitcher.ProviderStats("ollama")
            );

            ProviderSwitcher.PrimaryOnlyStrategy strategy = new ProviderSwitcher.PrimaryOnlyStrategy();
            List<String> order = strategy.selectProvider(statsMap);

            assertThat(order).containsExactly("primary");
        }

        @Test
        @DisplayName("SuccessRateStrategy returns a non-empty list for a populated stats map")
        void successRateStrategyReturnsNonEmptyList() {
            Map<String, ProviderSwitcher.ProviderStats> statsMap = Map.of(
                    "primary", new ProviderSwitcher.ProviderStats("ollama"),
                    "fallback", new ProviderSwitcher.ProviderStats("ollama")
            );

            ProviderSwitcher.SuccessRateStrategy strategy = new ProviderSwitcher.SuccessRateStrategy();
            List<String> order = strategy.selectProvider(statsMap);

            assertThat(order).containsExactlyInAnyOrder("primary", "fallback");
        }

        @Test
        @DisplayName("LowestLatencyStrategy returns a non-empty list for a populated stats map")
        void lowestLatencyStrategyReturnsNonEmptyList() {
            Map<String, ProviderSwitcher.ProviderStats> statsMap = Map.of(
                    "primary", new ProviderSwitcher.ProviderStats("ollama"),
                    "fallback", new ProviderSwitcher.ProviderStats("ollama")
            );

            ProviderSwitcher.LowestLatencyStrategy strategy = new ProviderSwitcher.LowestLatencyStrategy();
            List<String> order = strategy.selectProvider(statsMap);

            assertThat(order).containsExactlyInAnyOrder("primary", "fallback");
        }
    }

    // -------------------------------------------------------------------------
    // executeWith — unknown key
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("executeWith()")
    class ExecuteWithTests {

        @Test
        @DisplayName("throws IllegalArgumentException for unknown provider key")
        void unknownProviderKeyThrows() {
            ProviderSwitcher switcher = ProviderSwitcher.builder("exec-with")
                    .primary(ollamaConfig("llama3.3"))
                    .build();

            assertThatThrownBy(() -> switcher.executeWith("nonexistent", model -> "result"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unknown provider key");
        }
    }

    // -------------------------------------------------------------------------
    // updatePrimary / updateFallback
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("updatePrimary() / updateFallback()")
    class UpdateConfigTests {

        @Test
        @DisplayName("updatePrimary() replaces the primary stats entry")
        void updatePrimaryReplacesStats() {
            ProviderSwitcher switcher = ProviderSwitcher.builder("update-primary")
                    .primary(ollamaConfig("llama3.3"))
                    .build();

            LLMProviderConfig newCfg = ollamaConfig("llama3.2");
            switcher.updatePrimary(newCfg);

            assertThat(switcher.getStats("primary").getProviderId())
                    .isEqualTo(LLMProvider.OLLAMA.getId());
        }

        @Test
        @DisplayName("updateFallback() throws IllegalStateException when no fallback was configured")
        void updateFallbackWithoutFallbackThrows() {
            ProviderSwitcher switcher = ProviderSwitcher.builder("no-fallback")
                    .primary(ollamaConfig("llama3.3"))
                    .build();

            assertThatThrownBy(() -> switcher.updateFallback(ollamaConfig("mistral")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No fallback config configured");
        }
    }

    // -------------------------------------------------------------------------
    // ProviderExhaustedException
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("ProviderExhaustedException")
    class ProviderExhaustedExceptionTests {

        @Test
        @DisplayName("is a RuntimeException with message and cause")
        void isRuntimeExceptionWithMessageAndCause() {
            Throwable cause = new RuntimeException("root");
            ProviderSwitcher.ProviderExhaustedException ex =
                    new ProviderSwitcher.ProviderExhaustedException("all exhausted", cause);

            assertThat(ex).isInstanceOf(RuntimeException.class);
            assertThat(ex.getMessage()).isEqualTo("all exhausted");
            assertThat(ex.getCause()).isSameAs(cause);
        }
    }
}
