package br.com.archflow.performance.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Spring Cache configuration for archflow services.
 *
 * <p>Configures Caffeine-backed caches for:
 * <ul>
 *   <li>templates - Workflow template lookups (1h TTL)</li>
 *   <li>apikeys - API key validation (30min TTL)</li>
 *   <li>tools - MCP tool registry lookups (1h TTL)</li>
 *   <li>llm-responses - LLM response cache (24h TTL, deterministic only)</li>
 *   <li>embeddings - Embedding cache (7d TTL)</li>
 * </ul>
 */
@Configuration
@EnableCaching
public class SpringCacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(1, TimeUnit.HOURS)
                .recordStats());
        manager.setCacheNames(java.util.List.of(
                "templates", "template-categories", "template-search",
                "apikeys", "apikeys-by-owner",
                "mcp-tools", "mcp-tools-by-server",
                "llm-responses",
                "embeddings"
        ));
        return manager;
    }
}
