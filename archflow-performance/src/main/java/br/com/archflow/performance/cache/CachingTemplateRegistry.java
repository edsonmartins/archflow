package br.com.archflow.performance.cache;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caching decorator for workflow template registry operations.
 *
 * <p>Wraps template lookups with Spring Cache abstraction,
 * using Caffeine for L1 caching with automatic invalidation
 * on register/unregister operations.
 */
@Service
public class CachingTemplateRegistry {

    private final Map<String, Object> templates;

    public CachingTemplateRegistry() {
        this.templates = new ConcurrentHashMap<>();
    }

    @Cacheable(value = "templates", key = "#id")
    public Optional<Object> getTemplate(String id) {
        return Optional.ofNullable(templates.get(id));
    }

    @Cacheable(value = "templates", key = "'all'")
    public Collection<Object> getAllTemplates() {
        return Collections.unmodifiableCollection(templates.values());
    }

    @Cacheable(value = "template-categories", key = "#category")
    public List<Object> getTemplatesByCategory(String category) {
        // Delegate to actual registry
        return List.of();
    }

    @Cacheable(value = "template-search", key = "#keyword")
    public List<Object> search(String keyword) {
        // Delegate to actual registry
        return List.of();
    }

    @Caching(evict = {
            @CacheEvict(value = "templates", allEntries = true),
            @CacheEvict(value = "template-categories", allEntries = true),
            @CacheEvict(value = "template-search", allEntries = true)
    })
    public void onTemplateRegistered(String templateId) {
        // Cache eviction trigger
    }

    @Caching(evict = {
            @CacheEvict(value = "templates", allEntries = true),
            @CacheEvict(value = "template-categories", allEntries = true),
            @CacheEvict(value = "template-search", allEntries = true)
    })
    public void onTemplateUnregistered(String templateId) {
        // Cache eviction trigger
    }
}
