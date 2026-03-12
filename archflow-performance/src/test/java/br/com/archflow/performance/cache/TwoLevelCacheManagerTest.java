package br.com.archflow.performance.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TwoLevelCacheManager")
class TwoLevelCacheManagerTest {

    private Cache<String, String> l1Cache;
    private StubRedisClient stubRedisClient;
    private RedisCacheManager l2Cache;
    private TwoLevelCacheManager<String> twoLevelCache;

    @BeforeEach
    void setUp() {
        l1Cache = Caffeine.newBuilder()
                .maximumSize(100)
                .build();

        stubRedisClient = new StubRedisClient();
        l2Cache = new RedisCacheManager(stubRedisClient, "test:", 3600);
        twoLevelCache = new TwoLevelCacheManager<>(l1Cache, l2Cache);
    }

    @Test
    @DisplayName("should get value from L1 cache")
    void shouldGetFromL1() {
        // Arrange
        l1Cache.put("key1", "value1");

        // Act
        String result = twoLevelCache.get("key1");

        // Assert
        assertThat(result).isEqualTo("value1");
        assertThat(twoLevelCache.getL1Hits()).isEqualTo(1);
        assertThat(twoLevelCache.getL2Hits()).isEqualTo(0);
    }

    @Test
    @DisplayName("should fallback to L2 on L1 miss")
    void shouldFallbackToL2() {
        // Arrange - put directly in L2 only
        l2Cache.put("key1", "value-from-l2");

        // Act
        String result = twoLevelCache.get("key1");

        // Assert
        assertThat(result).isEqualTo("value-from-l2");
        assertThat(twoLevelCache.getL2Hits()).isEqualTo(1);
        assertThat(twoLevelCache.getL1Hits()).isEqualTo(0);
    }

    @Test
    @DisplayName("should promote value from L2 to L1 on L2 hit")
    void shouldPromoteFromL2ToL1() {
        // Arrange - put directly in L2 only
        l2Cache.put("key1", "value-promoted");

        // Act - first access goes to L2
        twoLevelCache.get("key1");

        // Assert - value should now be in L1
        assertThat(l1Cache.getIfPresent("key1")).isEqualTo("value-promoted");

        // Second access should hit L1
        String result = twoLevelCache.get("key1");
        assertThat(result).isEqualTo("value-promoted");
        assertThat(twoLevelCache.getL1Hits()).isEqualTo(1);
    }

    @Test
    @DisplayName("should put value to both L1 and L2")
    void shouldPutToBothLevels() {
        // Act
        twoLevelCache.put("key1", "value1");

        // Assert
        assertThat(l1Cache.getIfPresent("key1")).isEqualTo("value1");
        String l2Value = l2Cache.get("key1");
        assertThat(l2Value).isEqualTo("value1");
    }

    @Test
    @DisplayName("should evict from both L1 and L2")
    void shouldEvictFromBothLevels() {
        // Arrange
        twoLevelCache.put("key1", "value1");

        // Act
        twoLevelCache.evict("key1");

        // Assert
        assertThat(l1Cache.getIfPresent("key1")).isNull();
        assertThat(l2Cache.exists("key1")).isFalse();
    }

    @Test
    @DisplayName("should return null when both L1 and L2 miss")
    void shouldReturnNullWhenBothMiss() {
        // Act
        String result = twoLevelCache.get("non-existent");

        // Assert
        assertThat(result).isNull();
        assertThat(twoLevelCache.getTotalMisses()).isEqualTo(1);
    }

    @Test
    @DisplayName("should track combined statistics")
    void shouldTrackCombinedStats() {
        // Arrange
        l1Cache.put("l1-key", "l1-value");
        l2Cache.put("l2-key", "l2-value");

        // Act
        twoLevelCache.get("l1-key");   // L1 hit
        twoLevelCache.get("l2-key");   // L2 hit
        twoLevelCache.get("missing");  // total miss

        // Assert
        assertThat(twoLevelCache.getL1Hits()).isEqualTo(1);
        assertThat(twoLevelCache.getL2Hits()).isEqualTo(1);
        assertThat(twoLevelCache.getTotalMisses()).isEqualTo(1);
        assertThat(twoLevelCache.getTotalRequests()).isEqualTo(3);
        assertThat(twoLevelCache.getCombinedHitRate()).isCloseTo(2.0 / 3.0, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    @DisplayName("should handle L2 failure gracefully")
    void shouldHandleL2Failure() {
        // Arrange - use a failing Redis client
        RedisCacheManager.RedisClient failingClient = new RedisCacheManager.RedisClient() {
            @Override
            public byte[] get(byte[] key) {
                throw new RuntimeException("Redis connection failed");
            }

            @Override
            public void set(byte[] key, byte[] value, int ttlSeconds) {
                throw new RuntimeException("Redis connection failed");
            }

            @Override
            public void delete(byte[] key) {
                throw new RuntimeException("Redis connection failed");
            }

            @Override
            public boolean exists(byte[] key) {
                throw new RuntimeException("Redis connection failed");
            }
        };
        RedisCacheManager failingL2 = new RedisCacheManager(failingClient, "fail:", 3600);
        TwoLevelCacheManager<String> failingCache = new TwoLevelCacheManager<>(l1Cache, failingL2);

        // Act & Assert - should not throw, should return null on miss
        String result = failingCache.get("key1");
        assertThat(result).isNull();

        // Put should still work for L1 even if L2 fails
        failingCache.put("key2", "value2");
        assertThat(l1Cache.getIfPresent("key2")).isEqualTo("value2");
    }

    @Test
    @DisplayName("should work with L1 only when L2 has no data")
    void shouldWorkWithL1Only() {
        // Arrange
        l1Cache.put("key1", "l1-only");

        // Act
        String result = twoLevelCache.get("key1");

        // Assert
        assertThat(result).isEqualTo("l1-only");
        assertThat(twoLevelCache.getL1Hits()).isEqualTo(1);
        assertThat(twoLevelCache.getL2Hits()).isEqualTo(0);
    }

    @Test
    @DisplayName("should work with L2 only when L1 has no data")
    void shouldWorkWithL2Only() {
        // Arrange
        l2Cache.put("key1", "l2-only");

        // Act
        String result = twoLevelCache.get("key1");

        // Assert
        assertThat(result).isEqualTo("l2-only");
        assertThat(twoLevelCache.getL2Hits()).isEqualTo(1);
    }

    /**
     * In-memory stub implementation of RedisClient for testing.
     */
    private static class StubRedisClient implements RedisCacheManager.RedisClient {

        private final Map<String, byte[]> store = new ConcurrentHashMap<>();

        @Override
        public byte[] get(byte[] key) {
            return store.get(new String(key));
        }

        @Override
        public void set(byte[] key, byte[] value, int ttlSeconds) {
            store.put(new String(key), value);
        }

        @Override
        public void delete(byte[] key) {
            store.remove(new String(key));
        }

        @Override
        public boolean exists(byte[] key) {
            return store.containsKey(new String(key));
        }
    }
}
