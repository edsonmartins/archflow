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
 * Caching interceptor for LLM response operations.
 *
 * <p>Wraps LLM calls with a cache layer to avoid redundant API calls for
 * deterministic prompts. Only caches responses when temperature is 0, since
 * non-zero temperature introduces randomness making caching unreliable.
 *
 * <p>Cache keys are computed as SHA-256 hashes of the combination of model name,
 * prompt text, and temperature value.
 *
 * <p>Example usage:
 * <pre>{@code
 * CacheManager cacheManager = CacheManager.getInstance();
 * LlmResponseCacheInterceptor interceptor = new LlmResponseCacheInterceptor(cacheManager);
 *
 * String response = interceptor.getCachedResponse("gpt-4", "What is 2+2?", 0.0);
 * if (response == null) {
 *     response = llmClient.complete("gpt-4", "What is 2+2?", 0.0);
 *     interceptor.cacheResponse("gpt-4", "What is 2+2?", 0.0, response);
 * }
 * }</pre>
 */
public class LlmResponseCacheInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(LlmResponseCacheInterceptor.class);
    private static final String CACHE_NAME = "llm-response-cache";
    private static final Duration DEFAULT_TTL = Duration.ofHours(1);
    private static final long DEFAULT_MAX_SIZE = 5000;
    private static final double TEMPERATURE_THRESHOLD = 0.0001;

    private final Cache<String, String> cache;
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);
    private final AtomicLong skippedNonDeterministic = new AtomicLong(0);

    /**
     * Creates an LlmResponseCacheInterceptor with the given CacheManager and default settings.
     *
     * @param cacheManager the cache manager to use for creating the backing cache
     */
    public LlmResponseCacheInterceptor(CacheManager cacheManager) {
        this(cacheManager, DEFAULT_TTL, DEFAULT_MAX_SIZE);
    }

    /**
     * Creates an LlmResponseCacheInterceptor with custom TTL and max size.
     *
     * @param cacheManager the cache manager to use
     * @param ttl          the time-to-live for cached responses
     * @param maxSize      the maximum number of cached responses
     */
    public LlmResponseCacheInterceptor(CacheManager cacheManager, Duration ttl, long maxSize) {
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
     * Creates an LlmResponseCacheInterceptor with a pre-built cache (for testing).
     *
     * @param cache the backing cache
     */
    LlmResponseCacheInterceptor(Cache<String, String> cache) {
        this.cache = Objects.requireNonNull(cache, "cache must not be null");
    }

    /**
     * Gets a cached LLM response for the given parameters.
     *
     * <p>Returns null immediately if temperature > 0, as non-deterministic
     * responses should not be cached.
     *
     * @param model       the model identifier (e.g., "gpt-4", "claude-3-opus")
     * @param prompt      the prompt text
     * @param temperature the temperature parameter
     * @return the cached response, or null if not cached or non-deterministic
     */
    public String getCachedResponse(String model, String prompt, double temperature) {
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(prompt, "prompt must not be null");

        if (!isDeterministic(temperature)) {
            skippedNonDeterministic.incrementAndGet();
            logger.debug("Skipping cache lookup for non-deterministic call (temperature={})", temperature);
            return null;
        }

        String key = computeKey(model, prompt, temperature);
        String response = cache.getIfPresent(key);

        if (response != null) {
            cacheHits.incrementAndGet();
            logger.debug("LLM response cache hit for model={}, hash={}", model, key);
            return response;
        }

        cacheMisses.incrementAndGet();
        logger.debug("LLM response cache miss for model={}, hash={}", model, key);
        return null;
    }

    /**
     * Caches an LLM response for the given parameters.
     *
     * <p>Skips caching if temperature > 0, as non-deterministic responses
     * should not be cached.
     *
     * @param model       the model identifier
     * @param prompt      the prompt text
     * @param temperature the temperature parameter
     * @param response    the LLM response to cache
     */
    public void cacheResponse(String model, String prompt, double temperature, String response) {
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(prompt, "prompt must not be null");
        Objects.requireNonNull(response, "response must not be null");

        if (!isDeterministic(temperature)) {
            skippedNonDeterministic.incrementAndGet();
            logger.debug("Skipping caching for non-deterministic call (temperature={})", temperature);
            return;
        }

        String key = computeKey(model, prompt, temperature);
        cache.put(key, response);
        logger.debug("Cached LLM response for model={}, hash={}", model, key);
    }

    /**
     * Clears all cached LLM responses.
     */
    public void clearCache() {
        cache.invalidateAll();
        logger.info("LLM response cache cleared");
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
     * Returns the number of skipped non-deterministic calls.
     */
    public long getSkippedNonDeterministic() {
        return skippedNonDeterministic.get();
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
        skippedNonDeterministic.set(0);
    }

    /**
     * Checks if the temperature value indicates a deterministic call.
     */
    static boolean isDeterministic(double temperature) {
        return Math.abs(temperature) < TEMPERATURE_THRESHOLD;
    }

    /**
     * Computes a SHA-256 hash of the combination of model, prompt, and temperature.
     *
     * @param model       the model identifier
     * @param prompt      the prompt text
     * @param temperature the temperature value
     * @return the hex-encoded SHA-256 hash
     */
    static String computeKey(String model, String prompt, double temperature) {
        String combined = model + "|" + prompt + "|" + temperature;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(combined.getBytes(StandardCharsets.UTF_8));
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
