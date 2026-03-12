package br.com.archflow.performance.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EmbeddingCacheInterceptor")
class EmbeddingCacheInterceptorTest {

    private Cache<String, float[]> cache;
    private EmbeddingCacheInterceptor interceptor;

    @BeforeEach
    void setUp() {
        cache = Caffeine.newBuilder()
                .maximumSize(1000)
                .build();
        interceptor = new EmbeddingCacheInterceptor(cache);
    }

    @Test
    @DisplayName("should cache an embedding for given text")
    void shouldCacheEmbedding() {
        // Arrange
        float[] embedding = {0.1f, 0.2f, 0.3f};

        // Act
        interceptor.cacheEmbedding("Hello, world!", embedding);

        // Assert
        assertThat(interceptor.getCacheSize()).isEqualTo(1);
    }

    @Test
    @DisplayName("should return cached embedding on subsequent lookup")
    void shouldReturnCachedEmbedding() {
        // Arrange
        float[] embedding = {0.1f, 0.2f, 0.3f};
        interceptor.cacheEmbedding("Hello, world!", embedding);

        // Act
        float[] result = interceptor.getCachedEmbedding("Hello, world!");

        // Assert
        assertThat(result).isEqualTo(embedding);
    }

    @Test
    @DisplayName("should return null on cache miss")
    void shouldReturnNullOnMiss() {
        // Act
        float[] result = interceptor.getCachedEmbedding("unknown text");

        // Assert
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("should clear all cached embeddings")
    void shouldClearCache() {
        // Arrange
        interceptor.cacheEmbedding("text1", new float[]{0.1f});
        interceptor.cacheEmbedding("text2", new float[]{0.2f});

        // Act
        interceptor.clearCache();

        // Assert
        assertThat(interceptor.getCachedEmbedding("text1")).isNull();
        assertThat(interceptor.getCachedEmbedding("text2")).isNull();
    }

    @Test
    @DisplayName("should track cache hit and miss statistics")
    void shouldTrackStats() {
        // Arrange
        interceptor.cacheEmbedding("text1", new float[]{0.1f});

        // Act
        interceptor.getCachedEmbedding("text1");  // hit
        interceptor.getCachedEmbedding("text2");  // miss
        interceptor.getCachedEmbedding("text1");  // hit

        // Assert
        assertThat(interceptor.getCacheHits()).isEqualTo(2);
        assertThat(interceptor.getCacheMisses()).isEqualTo(1);
        assertThat(interceptor.getHitRate()).isCloseTo(2.0 / 3.0, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    @DisplayName("should produce deterministic hash for same input")
    void shouldUseDeterministicHash() {
        // Act
        String hash1 = EmbeddingCacheInterceptor.computeKey("Hello, world!");
        String hash2 = EmbeddingCacheInterceptor.computeKey("Hello, world!");
        String hash3 = EmbeddingCacheInterceptor.computeKey("Different text");

        // Assert
        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).isNotEqualTo(hash3);
        assertThat(hash1).hasSize(64); // SHA-256 = 32 bytes = 64 hex chars
    }
}
