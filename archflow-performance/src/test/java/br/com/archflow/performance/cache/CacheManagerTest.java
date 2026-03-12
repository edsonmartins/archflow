package br.com.archflow.performance.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CacheManager")
class CacheManagerTest {

    private CacheManager cacheManager;

    @BeforeEach
    void setUp() {
        CacheManager.reset();
        cacheManager = CacheManager.getInstance();
    }

    @AfterEach
    void tearDown() {
        CacheManager.reset();
    }

    @Nested
    @DisplayName("getOrCreateCache")
    class GetOrCreateCache {

        @Test
        @DisplayName("should create a new cache with the given configuration")
        void shouldCreateNewCache() {
            // Arrange
            CacheManager.CacheConfig config = CacheManager.CacheConfig.builder()
                    .maximumSize(100)
                    .expireAfterWrite(Duration.ofMinutes(5))
                    .build();

            // Act
            Cache<String, String> cache = cacheManager.getOrCreateCache("test-cache", config);

            // Assert
            assertThat(cache).isNotNull();
            assertThat(cacheManager.hasCache("test-cache")).isTrue();
        }

        @Test
        @DisplayName("should return existing cache if already created")
        void shouldReturnExistingCache() {
            // Arrange
            CacheManager.CacheConfig config = CacheManager.CacheConfig.builder()
                    .maximumSize(100)
                    .build();

            Cache<String, String> first = cacheManager.getOrCreateCache("test-cache", config);

            // Act
            Cache<String, String> second = cacheManager.getOrCreateCache("test-cache", config);

            // Assert
            assertThat(second).isSameAs(first);
        }

        @Test
        @DisplayName("should create cache with expireAfterAccess")
        void shouldCreateCacheWithExpireAfterAccess() {
            // Arrange
            CacheManager.CacheConfig config = CacheManager.CacheConfig.builder()
                    .maximumSize(50)
                    .expireAfterAccess(Duration.ofMinutes(10))
                    .build();

            // Act
            Cache<String, String> cache = cacheManager.getOrCreateCache("access-cache", config);
            cache.put("key", "value");

            // Assert
            assertThat(cache.getIfPresent("key")).isEqualTo("value");
        }

        @Test
        @DisplayName("should create cache with recordStats enabled")
        void shouldCreateCacheWithStats() {
            // Arrange
            CacheManager.CacheConfig config = CacheManager.CacheConfig.builder()
                    .maximumSize(100)
                    .recordStats(true)
                    .build();

            // Act
            Cache<String, String> cache = cacheManager.getOrCreateCache("stats-cache", config);
            cache.getIfPresent("missing-key");

            // Assert
            CacheStats stats = cacheManager.getStats("stats-cache");
            assertThat(stats.missCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("should store configuration for the cache")
        void shouldStoreCacheConfig() {
            // Arrange
            CacheManager.CacheConfig config = CacheManager.CacheConfig.builder()
                    .maximumSize(200)
                    .expireAfterWrite(Duration.ofMinutes(15))
                    .recordStats(true)
                    .build();

            // Act
            cacheManager.getOrCreateCache("config-cache", config);

            // Assert
            CacheManager.CacheConfig storedConfig = cacheManager.getCacheConfig("config-cache");
            assertThat(storedConfig).isNotNull();
            assertThat(storedConfig.maximumSize()).isEqualTo(200);
            assertThat(storedConfig.expireAfterWrite()).isEqualTo(Duration.ofMinutes(15));
            assertThat(storedConfig.recordStats()).isTrue();
        }
    }

    @Nested
    @DisplayName("getCache")
    class GetCache {

        @Test
        @DisplayName("should return existing cache by name")
        void shouldReturnExistingCache() {
            // Arrange
            CacheManager.CacheConfig config = CacheManager.CacheConfig.builder()
                    .maximumSize(100)
                    .build();
            Cache<String, String> created = cacheManager.getOrCreateCache("my-cache", config);

            // Act
            Cache<String, String> retrieved = cacheManager.getCache("my-cache");

            // Assert
            assertThat(retrieved).isSameAs(created);
        }

        @Test
        @DisplayName("should throw IllegalArgumentException for non-existent cache")
        void shouldThrowForNonExistentCache() {
            // Act & Assert
            assertThatThrownBy(() -> cacheManager.getCache("non-existent"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Cache not found: non-existent");
        }
    }

    @Nested
    @DisplayName("hasCache")
    class HasCache {

        @Test
        @DisplayName("should return true for existing cache")
        void shouldReturnTrueForExistingCache() {
            // Arrange
            CacheManager.CacheConfig config = CacheManager.CacheConfig.builder()
                    .maximumSize(10)
                    .build();
            cacheManager.getOrCreateCache("exists", config);

            // Act & Assert
            assertThat(cacheManager.hasCache("exists")).isTrue();
        }

        @Test
        @DisplayName("should return false for non-existing cache")
        void shouldReturnFalseForNonExistingCache() {
            // Act & Assert
            assertThat(cacheManager.hasCache("does-not-exist")).isFalse();
        }
    }

    @Nested
    @DisplayName("getOrCompute")
    class GetOrCompute {

        @Test
        @DisplayName("should compute and cache value when absent")
        void shouldComputeAndCacheValue() {
            // Arrange
            CacheManager.CacheConfig config = CacheManager.CacheConfig.builder()
                    .maximumSize(100)
                    .build();
            cacheManager.getOrCreateCache("compute-cache", config);
            AtomicInteger callCount = new AtomicInteger(0);

            // Act
            String result1 = cacheManager.getOrCompute("compute-cache", "key1",
                    k -> {
                        callCount.incrementAndGet();
                        return "computed-" + k;
                    });
            String result2 = cacheManager.getOrCompute("compute-cache", "key1",
                    k -> {
                        callCount.incrementAndGet();
                        return "computed-" + k;
                    });

            // Assert
            assertThat(result1).isEqualTo("computed-key1");
            assertThat(result2).isEqualTo("computed-key1");
            assertThat(callCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("should use compute function directly when cache does not exist")
        void shouldComputeDirectlyWhenCacheNotExists() {
            // Act
            String result = cacheManager.getOrCompute("missing-cache", "key",
                    k -> "fallback-" + k);

            // Assert
            assertThat(result).isEqualTo("fallback-key");
        }
    }

    @Nested
    @DisplayName("invalidate")
    class Invalidate {

        @Test
        @DisplayName("should invalidate all entries in a specific cache")
        void shouldInvalidateSpecificCache() {
            // Arrange
            CacheManager.CacheConfig config = CacheManager.CacheConfig.builder()
                    .maximumSize(100)
                    .build();
            Cache<String, String> cache = cacheManager.getOrCreateCache("inv-cache", config);
            cache.put("k1", "v1");
            cache.put("k2", "v2");

            // Act
            cacheManager.invalidate("inv-cache");

            // Assert
            assertThat(cache.getIfPresent("k1")).isNull();
            assertThat(cache.getIfPresent("k2")).isNull();
        }

        @Test
        @DisplayName("should do nothing when invalidating a non-existent cache")
        void shouldDoNothingForNonExistentCache() {
            // Act & Assert - should not throw
            cacheManager.invalidate("non-existent");
        }
    }

    @Nested
    @DisplayName("invalidateAll")
    class InvalidateAll {

        @Test
        @DisplayName("should invalidate entries in all caches")
        void shouldInvalidateAllCaches() {
            // Arrange
            CacheManager.CacheConfig config = CacheManager.CacheConfig.builder()
                    .maximumSize(100)
                    .build();
            Cache<String, String> cache1 = cacheManager.getOrCreateCache("cache1", config);
            Cache<String, String> cache2 = cacheManager.getOrCreateCache("cache2", config);
            cache1.put("k", "v1");
            cache2.put("k", "v2");

            // Act
            cacheManager.invalidateAll();

            // Assert
            assertThat(cache1.getIfPresent("k")).isNull();
            assertThat(cache2.getIfPresent("k")).isNull();
        }
    }

    @Nested
    @DisplayName("removeCache")
    class RemoveCache {

        @Test
        @DisplayName("should remove cache and its configuration")
        void shouldRemoveCacheAndConfig() {
            // Arrange
            CacheManager.CacheConfig config = CacheManager.CacheConfig.builder()
                    .maximumSize(100)
                    .build();
            cacheManager.getOrCreateCache("removable", config);

            // Act
            cacheManager.removeCache("removable");

            // Assert
            assertThat(cacheManager.hasCache("removable")).isFalse();
            assertThat(cacheManager.getCacheConfig("removable")).isNull();
        }

        @Test
        @DisplayName("should do nothing when removing a non-existent cache")
        void shouldDoNothingForNonExistentCache() {
            // Act & Assert - should not throw
            cacheManager.removeCache("non-existent");
        }
    }

    @Nested
    @DisplayName("stats")
    class Stats {

        @Test
        @DisplayName("should return cache statistics when stats are enabled")
        void shouldReturnCacheStats() {
            // Arrange
            CacheManager.CacheConfig config = CacheManager.CacheConfig.builder()
                    .maximumSize(100)
                    .recordStats(true)
                    .build();
            Cache<String, String> cache = cacheManager.getOrCreateCache("stats-cache", config);
            cache.put("key", "value");
            cache.getIfPresent("key");       // hit
            cache.getIfPresent("missing");   // miss

            // Act
            CacheStats stats = cacheManager.getStats("stats-cache");

            // Assert
            assertThat(stats.hitCount()).isEqualTo(1);
            assertThat(stats.missCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("should throw IllegalArgumentException for non-existent cache")
        void shouldThrowForNonExistentCache() {
            assertThatThrownBy(() -> cacheManager.getStats("non-existent"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Cache not found");
        }

        @Test
        @DisplayName("should return total size across all caches")
        void shouldReturnTotalSize() {
            // Arrange
            CacheManager.CacheConfig config = CacheManager.CacheConfig.builder()
                    .maximumSize(100)
                    .build();
            Cache<String, String> cache1 = cacheManager.getOrCreateCache("size-cache1", config);
            Cache<String, String> cache2 = cacheManager.getOrCreateCache("size-cache2", config);
            cache1.put("a", "1");
            cache1.put("b", "2");
            cache2.put("c", "3");

            // Act
            long totalSize = cacheManager.getTotalSize();

            // Assert
            assertThat(totalSize).isEqualTo(3);
        }

        @Test
        @DisplayName("should return cache names")
        void shouldReturnCacheNames() {
            // Arrange
            CacheManager.CacheConfig config = CacheManager.CacheConfig.builder()
                    .maximumSize(10)
                    .build();
            cacheManager.getOrCreateCache("alpha", config);
            cacheManager.getOrCreateCache("beta", config);

            // Act & Assert
            assertThat(cacheManager.getCacheNames()).containsExactlyInAnyOrder("alpha", "beta");
        }
    }

    @Nested
    @DisplayName("Presets")
    class PresetsTest {

        @Test
        @DisplayName("small preset should have 100 max size and 5 min TTL")
        void smallPreset() {
            CacheManager.CacheConfig config = CacheManager.Presets.small();
            assertThat(config.maximumSize()).isEqualTo(100);
            assertThat(config.expireAfterWrite()).isEqualTo(Duration.ofMinutes(5));
            assertThat(config.recordStats()).isTrue();
        }

        @Test
        @DisplayName("medium preset should have 1000 max size and 15 min TTL")
        void mediumPreset() {
            CacheManager.CacheConfig config = CacheManager.Presets.medium();
            assertThat(config.maximumSize()).isEqualTo(1000);
            assertThat(config.expireAfterWrite()).isEqualTo(Duration.ofMinutes(15));
            assertThat(config.recordStats()).isTrue();
        }

        @Test
        @DisplayName("large preset should have 10000 max size and 1 hour TTL")
        void largePreset() {
            CacheManager.CacheConfig config = CacheManager.Presets.large();
            assertThat(config.maximumSize()).isEqualTo(10000);
            assertThat(config.expireAfterWrite()).isEqualTo(Duration.ofHours(1));
            assertThat(config.recordStats()).isTrue();
        }

        @Test
        @DisplayName("llmResponses preset should have 5000 max size and 24 hour TTL")
        void llmResponsesPreset() {
            CacheManager.CacheConfig config = CacheManager.Presets.llmResponses();
            assertThat(config.maximumSize()).isEqualTo(5000);
            assertThat(config.expireAfterWrite()).isEqualTo(Duration.ofHours(24));
            assertThat(config.recordStats()).isTrue();
        }

        @Test
        @DisplayName("embeddings preset should have 50000 max size and weak values")
        void embeddingsPreset() {
            CacheManager.CacheConfig config = CacheManager.Presets.embeddings();
            assertThat(config.maximumSize()).isEqualTo(50000);
            assertThat(config.expireAfterWrite()).isEqualTo(Duration.ofDays(7));
            assertThat(config.weakValues()).isTrue();
            assertThat(config.recordStats()).isTrue();
        }

        @Test
        @DisplayName("toolResults preset should have 1000 max size and 30 min TTL")
        void toolResultsPreset() {
            CacheManager.CacheConfig config = CacheManager.Presets.toolResults();
            assertThat(config.maximumSize()).isEqualTo(1000);
            assertThat(config.expireAfterWrite()).isEqualTo(Duration.ofMinutes(30));
            assertThat(config.recordStats()).isTrue();
        }

        @Test
        @DisplayName("presets should work with getOrCreateCache")
        void presetsShouldWorkWithGetOrCreateCache() {
            Cache<String, String> cache = cacheManager.getOrCreateCache("preset-test",
                    CacheManager.Presets.small());
            cache.put("key", "value");
            assertThat(cache.getIfPresent("key")).isEqualTo("value");
        }
    }

    @Nested
    @DisplayName("singleton")
    class Singleton {

        @Test
        @DisplayName("getInstance should return the same instance")
        void shouldReturnSameInstance() {
            CacheManager instance1 = CacheManager.getInstance();
            CacheManager instance2 = CacheManager.getInstance();
            assertThat(instance1).isSameAs(instance2);
        }

        @Test
        @DisplayName("reset should clear the singleton")
        void resetShouldClearSingleton() {
            CacheManager before = CacheManager.getInstance();
            CacheManager.reset();
            CacheManager after = CacheManager.getInstance();
            assertThat(after).isNotSameAs(before);
        }
    }
}
