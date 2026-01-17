package br.com.archflow.performance.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * High-performance caching layer using Caffeine.
 *
 * <p>Features:
 * <ul>
 *   <li>In-memory caching with automatic eviction</li>
 *   <li>TTL and size-based eviction</li>
 *   <li>Cache statistics for monitoring</li>
 *   <li>Multi-cache support with namespacing</li>
 *   <li>Micrometer metrics integration</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * CacheManager cacheManager = CacheManager.getInstance();
 *
 * // Get or create a cache
 * Cache<String, Object> myCache = cacheManager.getOrCreateCache("my-cache",
 *     CacheConfig.builder()
 *         .maximumSize(1000)
 *         .expireAfterWrite(Duration.ofMinutes(10))
 *         .build()
 * );
 *
 * // Use the cache
 * myCache.put("key", value);
 * Object value = myCache.getIfPresent("key");
 * }</pre>
 */
public class CacheManager {

    private static volatile CacheManager instance;

    private final Map<String, Cache<?, ?>> caches;
    private final Map<String, CacheConfig> cacheConfigs;
    private final MeterRegistry meterRegistry;

    private CacheManager(MeterRegistry meterRegistry) {
        this.caches = new ConcurrentHashMap<>();
        this.cacheConfigs = new ConcurrentHashMap<>();
        this.meterRegistry = meterRegistry;
    }

    /**
     * Gets the singleton instance without metrics.
     */
    public static CacheManager getInstance() {
        return getInstance(null);
    }

    /**
     * Gets the singleton instance with metrics.
     */
    public static synchronized CacheManager getInstance(MeterRegistry meterRegistry) {
        if (instance == null) {
            instance = new CacheManager(meterRegistry);
        }
        return instance;
    }

    /**
     * Resets the singleton (mainly for testing).
     */
    public static synchronized void reset() {
        if (instance != null) {
            instance.invalidateAll();
        }
        instance = null;
    }

    /**
     * Gets or creates a cache with the given name and configuration.
     */
    @SuppressWarnings("unchecked")
    public <K, V> Cache<K, V> getOrCreateCache(String name, CacheConfig config) {
        return (Cache<K, V>) caches.computeIfAbsent(name, key -> {
            cacheConfigs.put(name, config);
            Caffeine<Object, Object> builder = Caffeine.newBuilder();

            if (config.maximumSize() > 0) {
                builder.maximumSize(config.maximumSize());
            }

            if (config.expireAfterWrite() != null) {
                builder.expireAfterWrite(config.expireAfterWrite());
            }

            if (config.expireAfterAccess() != null) {
                builder.expireAfterAccess(config.expireAfterAccess());
            }

            if (config.refreshAfterWrite() != null) {
                builder.refreshAfterWrite(config.refreshAfterWrite());
            }

            if (config.weakKeys()) {
                builder.weakKeys();
            }

            if (config.weakValues()) {
                builder.weakValues();
            }

            if (config.recordStats()) {
                builder.recordStats();
            }

            Cache<K, V> cache = builder.build();

            // Register metrics if registry is available
            if (meterRegistry != null && config.recordStats()) {
                String cacheName = name.replace("-", ".");
                io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics.monitor(
                        meterRegistry,
                        cache,
                        cacheName
                );
            }

            return cache;
        });
    }

    /**
     * Gets an existing cache by name.
     */
    @SuppressWarnings("unchecked")
    public <K, V> Cache<K, V> getCache(String name) {
        Cache<K, V> cache = (Cache<K, V>) caches.get(name);
        if (cache == null) {
            throw new IllegalArgumentException("Cache not found: " + name);
        }
        return cache;
    }

    /**
     * Checks if a cache exists.
     */
    public boolean hasCache(String name) {
        return caches.containsKey(name);
    }

    /**
     * Gets all cache names.
     */
    public Set<String> getCacheNames() {
        return Set.copyOf(caches.keySet());
    }

    /**
     * Gets the configuration for a cache.
     */
    public CacheConfig getCacheConfig(String name) {
        return cacheConfigs.get(name);
    }

    /**
     * Gets statistics for a cache (if stats are enabled).
     */
    public CacheStats getStats(String name) {
        Cache<?, ?> cache = caches.get(name);
        if (cache == null) {
            throw new IllegalArgumentException("Cache not found: " + name);
        }
        return cache.stats();
    }

    /**
     * Invalidates all entries in a cache.
     */
    public void invalidate(String name) {
        Cache<?, ?> cache = caches.get(name);
        if (cache != null) {
            cache.invalidateAll();
        }
    }

    /**
     * Invalidates all entries in all caches.
     */
    public void invalidateAll() {
        caches.values().forEach(Cache::invalidateAll);
    }

    /**
     * Removes a cache entirely.
     */
    public void removeCache(String name) {
        Cache<?, ?> cache = caches.remove(name);
        cacheConfigs.remove(name);
        if (cache != null) {
            cache.invalidateAll();
        }
    }

    /**
     * Gets the total number of entries across all caches.
     */
    public long getTotalSize() {
        return caches.values().stream()
                .mapToLong(Cache::estimatedSize)
                .sum();
    }

    /**
     * Computes and caches a value if absent.
     */
    @SuppressWarnings("unchecked")
    public <K, V> V getOrCompute(String cacheName, K key, Function<K, V> computeFunction) {
        Cache<K, V> cache = (Cache<K, V>) caches.get(cacheName);
        if (cache == null) {
            return computeFunction.apply(key);
        }
        return cache.get(key, k -> computeFunction.apply(k));
    }

    /**
     * Configuration for a cache.
     */
    public record CacheConfig(
            long maximumSize,
            Duration expireAfterWrite,
            Duration expireAfterAccess,
            Duration refreshAfterWrite,
            boolean weakKeys,
            boolean weakValues,
            boolean recordStats
    ) {
        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private long maximumSize = 0;
            private Duration expireAfterWrite;
            private Duration expireAfterAccess;
            private Duration refreshAfterWrite;
            private boolean weakKeys = false;
            private boolean weakValues = false;
            private boolean recordStats = false;

            public Builder maximumSize(long maximumSize) {
                this.maximumSize = maximumSize;
                return this;
            }

            public Builder expireAfterWrite(Duration duration) {
                this.expireAfterWrite = duration;
                return this;
            }

            public Builder expireAfterAccess(Duration duration) {
                this.expireAfterAccess = duration;
                return this;
            }

            public Builder refreshAfterWrite(Duration duration) {
                this.refreshAfterWrite = duration;
                return this;
            }

            public Builder weakKeys(boolean weakKeys) {
                this.weakKeys = weakKeys;
                return this;
            }

            public Builder weakValues(boolean weakValues) {
                this.weakValues = weakValues;
                return this;
            }

            public Builder recordStats(boolean recordStats) {
                this.recordStats = recordStats;
                return this;
            }

            public CacheConfig build() {
                return new CacheConfig(
                        maximumSize,
                        expireAfterWrite,
                        expireAfterAccess,
                        refreshAfterWrite,
                        weakKeys,
                        weakValues,
                        recordStats
                );
            }
        }
    }

    /**
     * Pre-configured cache configurations.
     */
    public static class Presets {
        /**
         * Small cache for frequently accessed data.
         */
        public static CacheConfig small() {
            return CacheConfig.builder()
                    .maximumSize(100)
                    .expireAfterWrite(Duration.ofMinutes(5))
                    .recordStats(true)
                    .build();
        }

        /**
         * Medium cache for general use.
         */
        public static CacheConfig medium() {
            return CacheConfig.builder()
                    .maximumSize(1000)
                    .expireAfterWrite(Duration.ofMinutes(15))
                    .recordStats(true)
                    .build();
        }

        /**
         * Large cache for high-volume data.
         */
        public static CacheConfig large() {
            return CacheConfig.builder()
                    .maximumSize(10000)
                    .expireAfterWrite(Duration.ofHours(1))
                    .recordStats(true)
                    .build();
        }

        /**
         * Cache for LLM responses (longer TTL).
         */
        public static CacheConfig llmResponses() {
            return CacheConfig.builder()
                    .maximumSize(5000)
                    .expireAfterWrite(Duration.ofHours(24))
                    .recordStats(true)
                    .build();
        }

        /**
         * Cache for embeddings (vector data).
         */
        public static CacheConfig embeddings() {
            return CacheConfig.builder()
                    .maximumSize(50000)
                    .expireAfterWrite(Duration.ofDays(7))
                    .weakValues(true)
                    .recordStats(true)
                    .build();
        }

        /**
         * Cache for tool execution results.
         */
        public static CacheConfig toolResults() {
            return CacheConfig.builder()
                    .maximumSize(1000)
                    .expireAfterWrite(Duration.ofMinutes(30))
                    .recordStats(true)
                    .build();
        }
    }
}
