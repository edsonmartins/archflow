package br.com.archflow.performance.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SpringCacheConfig")
class SpringCacheConfigTest {

    @Test
    @DisplayName("should create cache manager with all expected caches")
    void shouldCreateCacheManager() {
        SpringCacheConfig config = new SpringCacheConfig();
        var cacheManager = config.cacheManager();

        assertNotNull(cacheManager);
        assertNotNull(cacheManager.getCache("templates"));
        assertNotNull(cacheManager.getCache("template-categories"));
        assertNotNull(cacheManager.getCache("template-search"));
        assertNotNull(cacheManager.getCache("apikeys"));
        assertNotNull(cacheManager.getCache("apikeys-by-owner"));
        assertNotNull(cacheManager.getCache("mcp-tools"));
        assertNotNull(cacheManager.getCache("mcp-tools-by-server"));
        assertNotNull(cacheManager.getCache("llm-responses"));
        assertNotNull(cacheManager.getCache("embeddings"));
    }

    @Test
    @DisplayName("should return null for undefined cache names")
    void shouldReturnNullForUndefinedCache() {
        SpringCacheConfig config = new SpringCacheConfig();
        var cacheManager = config.cacheManager();

        // CaffeineCacheManager with explicit names returns null for unknown caches
        assertNull(cacheManager.getCache("nonexistent"));
    }
}
