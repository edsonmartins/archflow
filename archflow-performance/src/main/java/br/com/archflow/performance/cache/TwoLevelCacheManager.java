package br.com.archflow.performance.cache;

import com.github.benmanes.caffeine.cache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Composite two-level cache combining an in-memory L1 (Caffeine) cache with a
 * distributed L2 (Redis) cache.
 *
 * <p>Lookup strategy:
 * <ol>
 *   <li>Check L1 (Caffeine) - fast, in-process</li>
 *   <li>On L1 miss, check L2 (Redis) - slower, distributed</li>
 *   <li>On L2 hit, promote value back to L1 for future fast access</li>
 * </ol>
 *
 * <p>Write strategy:
 * <ul>
 *   <li>Writes go to both L1 and L2 simultaneously</li>
 *   <li>Evictions remove from both L1 and L2</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * CacheManager l1Manager = CacheManager.getInstance();
 * Cache<String, Object> l1Cache = l1Manager.getOrCreateCache("my-cache",
 *     CacheManager.Presets.medium());
 *
 * RedisCacheManager l2Manager = new RedisCacheManager(redisClient);
 * TwoLevelCacheManager twoLevel = new TwoLevelCacheManager(l1Cache, l2Manager);
 *
 * twoLevel.put("key", value);
 * Object result = twoLevel.get("key");
 * }</pre>
 *
 * @param <V> the value type stored in the cache
 */
public class TwoLevelCacheManager<V> {

    private static final Logger logger = LoggerFactory.getLogger(TwoLevelCacheManager.class);

    private final Cache<String, V> l1Cache;
    private final RedisCacheManager l2Cache;
    private final AtomicLong l1Hits = new AtomicLong(0);
    private final AtomicLong l2Hits = new AtomicLong(0);
    private final AtomicLong totalMisses = new AtomicLong(0);

    /**
     * Creates a TwoLevelCacheManager with the given L1 and L2 caches.
     *
     * @param l1Cache the Caffeine L1 cache (in-memory)
     * @param l2Cache the Redis L2 cache (distributed)
     */
    public TwoLevelCacheManager(Cache<String, V> l1Cache, RedisCacheManager l2Cache) {
        this.l1Cache = Objects.requireNonNull(l1Cache, "l1Cache must not be null");
        this.l2Cache = Objects.requireNonNull(l2Cache, "l2Cache must not be null");
    }

    /**
     * Gets a value from the two-level cache.
     *
     * <p>Checks L1 first. On L1 miss, checks L2. On L2 hit, promotes the value
     * back to L1 for subsequent fast access.
     *
     * @param key the cache key
     * @return the cached value, or null if not found in either level
     */
    @SuppressWarnings("unchecked")
    public V get(String key) {
        Objects.requireNonNull(key, "key must not be null");

        // Check L1 first
        V value = l1Cache.getIfPresent(key);
        if (value != null) {
            l1Hits.incrementAndGet();
            return value;
        }

        // L1 miss - check L2
        try {
            V l2Value = l2Cache.get(key);
            if (l2Value != null) {
                l2Hits.incrementAndGet();
                // Promote to L1
                l1Cache.put(key, l2Value);
                return l2Value;
            }
        } catch (Exception e) {
            logger.warn("L2 cache lookup failed for key: {}, falling back to miss", key, e);
        }

        totalMisses.incrementAndGet();
        return null;
    }

    /**
     * Puts a value into both L1 and L2 caches.
     *
     * @param key   the cache key
     * @param value the value to cache
     */
    public void put(String key, V value) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(value, "value must not be null");

        l1Cache.put(key, value);
        try {
            l2Cache.put(key, value);
        } catch (Exception e) {
            logger.warn("Failed to write to L2 cache for key: {}", key, e);
        }
    }

    /**
     * Puts a value into both L1 and L2 caches with a specific TTL for L2.
     *
     * @param key        the cache key
     * @param value      the value to cache
     * @param ttlSeconds TTL in seconds for the L2 cache entry
     */
    public void put(String key, V value, int ttlSeconds) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(value, "value must not be null");

        l1Cache.put(key, value);
        try {
            l2Cache.put(key, value, ttlSeconds);
        } catch (Exception e) {
            logger.warn("Failed to write to L2 cache for key: {}", key, e);
        }
    }

    /**
     * Evicts a value from both L1 and L2 caches.
     *
     * @param key the cache key to evict
     */
    public void evict(String key) {
        Objects.requireNonNull(key, "key must not be null");

        l1Cache.invalidate(key);
        try {
            l2Cache.evict(key);
        } catch (Exception e) {
            logger.warn("Failed to evict from L2 cache for key: {}", key, e);
        }
    }

    /**
     * Returns the number of L1 cache hits.
     */
    public long getL1Hits() {
        return l1Hits.get();
    }

    /**
     * Returns the number of L2 cache hits.
     */
    public long getL2Hits() {
        return l2Hits.get();
    }

    /**
     * Returns the total number of cache misses (both levels missed).
     */
    public long getTotalMisses() {
        return totalMisses.get();
    }

    /**
     * Returns the total number of requests across both levels.
     */
    public long getTotalRequests() {
        return l1Hits.get() + l2Hits.get() + totalMisses.get();
    }

    /**
     * Returns the combined hit rate (L1 hits + L2 hits) / total requests.
     */
    public double getCombinedHitRate() {
        long total = getTotalRequests();
        if (total == 0) {
            return 0.0;
        }
        return (double) (l1Hits.get() + l2Hits.get()) / total;
    }

    /**
     * Returns the L1 hit rate as a proportion of total requests.
     */
    public double getL1HitRate() {
        long total = getTotalRequests();
        if (total == 0) {
            return 0.0;
        }
        return (double) l1Hits.get() / total;
    }

    /**
     * Returns the L2 hit rate as a proportion of total requests.
     */
    public double getL2HitRate() {
        long total = getTotalRequests();
        if (total == 0) {
            return 0.0;
        }
        return (double) l2Hits.get() / total;
    }

    /**
     * Resets all statistics counters.
     */
    public void resetStats() {
        l1Hits.set(0);
        l2Hits.set(0);
        totalMisses.set(0);
    }

    /**
     * Returns the underlying L1 cache.
     */
    public Cache<String, V> getL1Cache() {
        return l1Cache;
    }

    /**
     * Returns the underlying L2 cache manager.
     */
    public RedisCacheManager getL2Cache() {
        return l2Cache;
    }
}
