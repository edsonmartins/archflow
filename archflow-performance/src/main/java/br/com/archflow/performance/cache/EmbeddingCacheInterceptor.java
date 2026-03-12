package br.com.archflow.performance.cache;

import com.github.benmanes.caffeine.cache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Caching interceptor for embedding operations.
 *
 * <p>Wraps embedding calls with a cache layer to avoid redundant computation.
 * Embeddings are deterministic (same input always produces the same output),
 * making them ideal candidates for caching.
 *
 * <p>Cache keys are computed as SHA-256 hashes of the input text, ensuring
 * consistent and compact key generation regardless of text length.
 *
 * <p>Example usage:
 * <pre>{@code
 * CacheManager cacheManager = CacheManager.getInstance();
 * EmbeddingCacheInterceptor interceptor = new EmbeddingCacheInterceptor(cacheManager);
 *
 * float[] embedding = interceptor.getCachedEmbedding("Hello, world!");
 * if (embedding == null) {
 *     embedding = embeddingModel.embed("Hello, world!");
 *     interceptor.cacheEmbedding("Hello, world!", embedding);
 * }
 * }</pre>
 */
public class EmbeddingCacheInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(EmbeddingCacheInterceptor.class);
    private static final String CACHE_NAME = "embedding-cache";
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);
    private static final long DEFAULT_MAX_SIZE = 50000;

    private final Cache<String, float[]> cache;
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);

    /**
     * Creates an EmbeddingCacheInterceptor with the given CacheManager and default settings.
     *
     * @param cacheManager the cache manager to use for creating the backing cache
     */
    public EmbeddingCacheInterceptor(CacheManager cacheManager) {
        this(cacheManager, DEFAULT_TTL, DEFAULT_MAX_SIZE);
    }

    /**
     * Creates an EmbeddingCacheInterceptor with custom TTL and max size.
     *
     * @param cacheManager the cache manager to use
     * @param ttl          the time-to-live for cached embeddings
     * @param maxSize      the maximum number of cached embeddings
     */
    public EmbeddingCacheInterceptor(CacheManager cacheManager, Duration ttl, long maxSize) {
        Objects.requireNonNull(cacheManager, "cacheManager must not be null");
        Objects.requireNonNull(ttl, "ttl must not be null");

        CacheManager.CacheConfig config = CacheManager.CacheConfig.builder()
                .maximumSize(maxSize)
                .expireAfterWrite(ttl)
                .recordStats(true)
                .build();

        this.cache = cacheManager.getOrCreateCache(CACHE_NAME, config);
    }

    /**
     * Creates an EmbeddingCacheInterceptor with a pre-built cache (for testing).
     *
     * @param cache the backing cache
     */
    EmbeddingCacheInterceptor(Cache<String, float[]> cache) {
        this.cache = Objects.requireNonNull(cache, "cache must not be null");
    }

    /**
     * Gets a cached embedding for the given text.
     *
     * @param text the input text
     * @return the cached embedding, or null if not cached
     */
    public float[] getCachedEmbedding(String text) {
        Objects.requireNonNull(text, "text must not be null");

        String key = computeKey(text);
        float[] embedding = cache.getIfPresent(key);

        if (embedding != null) {
            cacheHits.incrementAndGet();
            logger.debug("Embedding cache hit for text hash: {}", key);
            return embedding;
        }

        cacheMisses.incrementAndGet();
        logger.debug("Embedding cache miss for text hash: {}", key);
        return null;
    }

    /**
     * Caches an embedding for the given text.
     *
     * @param text      the input text
     * @param embedding the embedding to cache
     */
    public void cacheEmbedding(String text, float[] embedding) {
        Objects.requireNonNull(text, "text must not be null");
        Objects.requireNonNull(embedding, "embedding must not be null");

        String key = computeKey(text);
        cache.put(key, embedding);
        logger.debug("Cached embedding for text hash: {} (dimensions: {})", key, embedding.length);
    }

    /**
     * Clears all cached embeddings.
     */
    public void clearCache() {
        cache.invalidateAll();
        logger.info("Embedding cache cleared");
    }

    /**
     * Returns the number of cache hits.
     */
    public long getCacheHits() {
        return cacheHits.get();
    }

    /**
     * Returns the number of cache misses.
     */
    public long getCacheMisses() {
        return cacheMisses.get();
    }

    /**
     * Returns the estimated number of entries in the cache.
     */
    public long getCacheSize() {
        return cache.estimatedSize();
    }

    /**
     * Returns the cache hit rate as a value between 0.0 and 1.0.
     */
    public double getHitRate() {
        long total = cacheHits.get() + cacheMisses.get();
        if (total == 0) {
            return 0.0;
        }
        return (double) cacheHits.get() / total;
    }

    /**
     * Resets all statistics counters.
     */
    public void resetStats() {
        cacheHits.set(0);
        cacheMisses.set(0);
    }

    /**
     * Computes a SHA-256 hash of the input text for use as a cache key.
     *
     * @param text the input text
     * @return the hex-encoded SHA-256 hash
     */
    static String computeKey(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
