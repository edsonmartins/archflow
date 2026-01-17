package br.com.archflow.performance;

import br.com.archflow.performance.cache.CacheManager;
import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for CacheManager.
 */
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

    @Test
    void testCreateAndGetCache() {
        Cache<String, String> cache = cacheManager.getOrCreateCache(
                "test-cache",
                CacheManager.CacheConfig.builder()
                        .maximumSize(100)
                        .expireAfterWrite(Duration.ofMinutes(10))
                        .build()
        );

        assertThat(cache).isNotNull();
        assertThat(cacheManager.hasCache("test-cache")).isTrue();
    }

    @Test
    void testCachePutAndGet() {
        Cache<String, String> cache = cacheManager.getOrCreateCache(
                "test-cache",
                CacheManager.Presets.small()
        );

        cache.put("key1", "value1");
        cache.put("key2", "value2");

        assertThat(cache.getIfPresent("key1")).isEqualTo("value1");
        assertThat(cache.getIfPresent("key2")).isEqualTo("value2");
        assertThat(cache.getIfPresent("key3")).isNull();
    }

    @Test
    void testCacheInvalidate() {
        Cache<String, String> cache = cacheManager.getOrCreateCache(
                "test-cache",
                CacheManager.Presets.small()
        );

        cache.put("key1", "value1");
        cacheManager.invalidate("test-cache");

        assertThat(cache.getIfPresent("key1")).isNull();
    }

    @Test
    void testCacheStats() {
        Cache<String, String> cache = cacheManager.getOrCreateCache(
                "test-cache",
                CacheManager.CacheConfig.builder()
                        .maximumSize(100)
                        .recordStats(true)
                        .build()
        );

        cache.put("key1", "value1");
        cache.getIfPresent("key1");
        cache.getIfPresent("key2"); // miss

        var stats = cacheManager.getStats("test-cache");

        assertThat(stats).isNotNull();
        assertThat(stats.hitCount()).isEqualTo(1);
        assertThat(stats.missCount()).isEqualTo(1);
    }

    @Test
    void testGetOrCompute() {
        Cache<String, String> cache = cacheManager.getOrCreateCache(
                "test-cache",
                CacheManager.Presets.small()
        );

        String value1 = cacheManager.getOrCompute("test-cache", "key1", k -> "computed-" + k);
        String value2 = cacheManager.getOrCompute("test-cache", "key1", k -> "computed-again-" + k);

        assertThat(value1).isEqualTo("computed-key1");
        assertThat(value2).isEqualTo("computed-key1"); // Should use cached value
    }

    @Test
    void testRemoveCache() {
        cacheManager.getOrCreateCache("test-cache", CacheManager.Presets.small());
        assertThat(cacheManager.hasCache("test-cache")).isTrue();

        cacheManager.removeCache("test-cache");
        assertThat(cacheManager.hasCache("test-cache")).isFalse();
    }

    @Test
    void testGetTotalSize() {
        cacheManager.getOrCreateCache("cache1", CacheManager.Presets.small());
        cacheManager.getOrCreateCache("cache2", CacheManager.Presets.small());

        Cache<String, String> cache1 = cacheManager.getCache("cache1");
        cache1.put("key1", "value1");
        cache1.put("key2", "value2");

        Cache<String, String> cache2 = cacheManager.getCache("cache2");
        cache2.put("key3", "value3");

        assertThat(cacheManager.getTotalSize()).isEqualTo(3);
    }

    @Test
    void testPresets() {
        Cache<String, String> small = cacheManager.getOrCreateCache("small", CacheManager.Presets.small());
        Cache<String, String> medium = cacheManager.getOrCreateCache("medium", CacheManager.Presets.medium());
        Cache<String, String> large = cacheManager.getOrCreateCache("large", CacheManager.Presets.large());
        Cache<String, String> llm = cacheManager.getOrCreateCache("llm", CacheManager.Presets.llmResponses());
        Cache<String, String> tool = cacheManager.getOrCreateCache("tool", CacheManager.Presets.toolResults());

        assertThat(small).isNotNull();
        assertThat(medium).isNotNull();
        assertThat(large).isNotNull();
        assertThat(llm).isNotNull();
        assertThat(tool).isNotNull();
    }

    @Test
    void testInvalidateAll() {
        cacheManager.getOrCreateCache("cache1", CacheManager.Presets.small());
        cacheManager.getOrCreateCache("cache2", CacheManager.Presets.small());

        Cache<String, String> cache1 = cacheManager.getCache("cache1");
        Cache<String, String> cache2 = cacheManager.getCache("cache2");

        cache1.put("key1", "value1");
        cache2.put("key2", "value2");

        cacheManager.invalidateAll();

        assertThat(cache1.getIfPresent("key1")).isNull();
        assertThat(cache2.getIfPresent("key2")).isNull();
    }

    @Test
    void testCacheConfig() {
        CacheManager.CacheConfig config = CacheManager.CacheConfig.builder()
                .maximumSize(500)
                .expireAfterWrite(Duration.ofMinutes(30))
                .expireAfterAccess(Duration.ofMinutes(10))
                .recordStats(true)
                .build();

        assertThat(config.maximumSize()).isEqualTo(500);
        assertThat(config.expireAfterWrite()).isEqualTo(Duration.ofMinutes(30));
        assertThat(config.expireAfterAccess()).isEqualTo(Duration.ofMinutes(10));
        assertThat(config.recordStats()).isTrue();
    }
}
