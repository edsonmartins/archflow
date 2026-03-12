package br.com.archflow.performance.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CachingTemplateRegistry")
class CachingTemplateRegistryTest {

    @Test
    @DisplayName("should return empty optional for unknown template")
    void shouldReturnEmptyForUnknown() {
        CachingTemplateRegistry registry = new CachingTemplateRegistry();
        assertTrue(registry.getTemplate("unknown").isEmpty());
    }

    @Test
    @DisplayName("should return empty collection initially")
    void shouldReturnEmptyCollectionInitially() {
        CachingTemplateRegistry registry = new CachingTemplateRegistry();
        assertTrue(registry.getAllTemplates().isEmpty());
    }

    @Test
    @DisplayName("should return empty list for category search")
    void shouldReturnEmptyListForCategorySearch() {
        CachingTemplateRegistry registry = new CachingTemplateRegistry();
        assertTrue(registry.getTemplatesByCategory("ai").isEmpty());
    }

    @Test
    @DisplayName("should return empty list for keyword search")
    void shouldReturnEmptyListForKeywordSearch() {
        CachingTemplateRegistry registry = new CachingTemplateRegistry();
        assertTrue(registry.search("customer").isEmpty());
    }

    @Test
    @DisplayName("should not throw on register event")
    void shouldNotThrowOnRegisterEvent() {
        CachingTemplateRegistry registry = new CachingTemplateRegistry();
        assertDoesNotThrow(() -> registry.onTemplateRegistered("test-template"));
    }

    @Test
    @DisplayName("should not throw on unregister event")
    void shouldNotThrowOnUnregisterEvent() {
        CachingTemplateRegistry registry = new CachingTemplateRegistry();
        assertDoesNotThrow(() -> registry.onTemplateUnregistered("test-template"));
    }
}
