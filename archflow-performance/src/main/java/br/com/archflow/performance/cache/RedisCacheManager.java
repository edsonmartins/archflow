package br.com.archflow.performance.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis-backed L2 cache manager.
 *
 * <p>Provides a distributed caching layer using a pluggable {@link RedisClient} interface,
 * allowing any Redis client implementation (Jedis, Lettuce, etc.) to be used without
 * introducing a direct dependency.
 *
 * <p>Features:
 * <ul>
 *   <li>TTL support per cache entry</li>
 *   <li>Thread-safe operations</li>
 *   <li>Stats tracking (hits, misses, evictions)</li>
 *   <li>Java serialization for complex types</li>
 *   <li>Key prefix namespacing</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * RedisClient client = new JedisRedisClient(jedisPool);
 * RedisCacheManager cacheManager = new RedisCacheManager(client, "archflow:", 3600);
 * cacheManager.put("my-key", myObject);
 * Object value = cacheManager.get("my-key");
 * }</pre>
 */
public class RedisCacheManager {

    private static final Logger logger = LoggerFactory.getLogger(RedisCacheManager.class);

    private final RedisClient redisClient;
    private final String keyPrefix;
    private final int defaultTtlSeconds;
    private final AtomicLong hits = new AtomicLong(0);
    private final AtomicLong misses = new AtomicLong(0);
    private final AtomicLong evictions = new AtomicLong(0);
    private final AtomicLong putCount = new AtomicLong(0);

    /**
     * Generic Redis client interface for cache operations.
     *
     * <p>Implement this interface to integrate with any Redis client library.
     */
    public interface RedisClient {

        /**
         * Gets a value by key.
         *
         * @param key the cache key
         * @return the cached value as bytes, or null if not found
         */
        byte[] get(byte[] key);

        /**
         * Sets a value with TTL.
         *
         * @param key        the cache key
         * @param value      the value to cache
         * @param ttlSeconds time-to-live in seconds
         */
        void set(byte[] key, byte[] value, int ttlSeconds);

        /**
         * Deletes a key.
         *
         * @param key the cache key to delete
         */
        void delete(byte[] key);

        /**
         * Checks if a key exists.
         *
         * @param key the cache key to check
         * @return true if the key exists
         */
        boolean exists(byte[] key);
    }

    /**
     * Creates a RedisCacheManager with the specified client, prefix, and default TTL.
     *
     * @param redisClient       the Redis client implementation
     * @param keyPrefix         prefix for all cache keys (e.g., "archflow:")
     * @param defaultTtlSeconds default TTL in seconds for cache entries
     */
    public RedisCacheManager(RedisClient redisClient, String keyPrefix, int defaultTtlSeconds) {
        this.redisClient = Objects.requireNonNull(redisClient, "redisClient must not be null");
        this.keyPrefix = Objects.requireNonNull(keyPrefix, "keyPrefix must not be null");
        this.defaultTtlSeconds = defaultTtlSeconds;
    }

    /**
     * Creates a RedisCacheManager with default prefix and TTL of 1 hour.
     *
     * @param redisClient the Redis client implementation
     */
    public RedisCacheManager(RedisClient redisClient) {
        this(redisClient, "archflow:cache:", 3600);
    }

    /**
     * Gets a cached value by key.
     *
     * @param key the cache key
     * @param <V> the value type
     * @return the cached value, or null if not found or on deserialization error
     */
    @SuppressWarnings("unchecked")
    public <V> V get(String key) {
        Objects.requireNonNull(key, "key must not be null");
        try {
            byte[] data = redisClient.get(toKeyBytes(key));
            if (data == null) {
                misses.incrementAndGet();
                return null;
            }
            hits.incrementAndGet();
            return (V) deserialize(data);
        } catch (Exception e) {
            logger.warn("Failed to get value from Redis cache for key: {}", key, e);
            misses.incrementAndGet();
            return null;
        }
    }

    /**
     * Puts a value into the cache with the default TTL.
     *
     * @param key   the cache key
     * @param value the value to cache
     */
    public void put(String key, Object value) {
        put(key, value, defaultTtlSeconds);
    }

    /**
     * Puts a value into the cache with a specific TTL.
     *
     * @param key        the cache key
     * @param value      the value to cache
     * @param ttlSeconds TTL in seconds
     */
    public void put(String key, Object value, int ttlSeconds) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(value, "value must not be null");
        try {
            byte[] data = serialize(value);
            redisClient.set(toKeyBytes(key), data, ttlSeconds);
            putCount.incrementAndGet();
        } catch (Exception e) {
            logger.warn("Failed to put value into Redis cache for key: {}", key, e);
        }
    }

    /**
     * Evicts a value from the cache.
     *
     * @param key the cache key to evict
     */
    public void evict(String key) {
        Objects.requireNonNull(key, "key must not be null");
        try {
            redisClient.delete(toKeyBytes(key));
            evictions.incrementAndGet();
        } catch (Exception e) {
            logger.warn("Failed to evict value from Redis cache for key: {}", key, e);
        }
    }

    /**
     * Checks if a key exists in the cache.
     *
     * @param key the cache key
     * @return true if the key exists
     */
    public boolean exists(String key) {
        Objects.requireNonNull(key, "key must not be null");
        try {
            return redisClient.exists(toKeyBytes(key));
        } catch (Exception e) {
            logger.warn("Failed to check existence in Redis cache for key: {}", key, e);
            return false;
        }
    }

    /**
     * Returns the number of cache hits.
     */
    public long getHits() {
        return hits.get();
    }

    /**
     * Returns the number of cache misses.
     */
    public long getMisses() {
        return misses.get();
    }

    /**
     * Returns the number of evictions.
     */
    public long getEvictions() {
        return evictions.get();
    }

    /**
     * Returns the number of puts.
     */
    public long getPutCount() {
        return putCount.get();
    }

    /**
     * Returns the hit rate as a value between 0.0 and 1.0.
     */
    public double getHitRate() {
        long totalRequests = hits.get() + misses.get();
        if (totalRequests == 0) {
            return 0.0;
        }
        return (double) hits.get() / totalRequests;
    }

    /**
     * Resets all statistics counters.
     */
    public void resetStats() {
        hits.set(0);
        misses.set(0);
        evictions.set(0);
        putCount.set(0);
    }

    /**
     * Returns the key prefix used for namespacing.
     */
    public String getKeyPrefix() {
        return keyPrefix;
    }

    /**
     * Returns the default TTL in seconds.
     */
    public int getDefaultTtlSeconds() {
        return defaultTtlSeconds;
    }

    private byte[] toKeyBytes(String key) {
        return (keyPrefix + key).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Serializes an object to a byte array using Java serialization.
     */
    static byte[] serialize(Object value) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(value);
            oos.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to serialize value: " + value.getClass().getName(), e);
        }
    }

    /**
     * Deserializes a byte array back to an object.
     */
    static Object deserialize(byte[] data) {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(data);
             ObjectInputStream ois = new ObjectInputStream(bis)) {
            return ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalArgumentException("Failed to deserialize value", e);
        }
    }
}
