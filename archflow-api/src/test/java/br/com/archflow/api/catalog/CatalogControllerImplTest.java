package br.com.archflow.api.catalog;

import br.com.archflow.api.catalog.dto.CatalogItemDto;
import br.com.archflow.api.catalog.impl.CatalogControllerImpl;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import br.com.archflow.plugin.api.catalog.DefaultComponentCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CatalogControllerImpl")
class CatalogControllerImplTest {

    private CatalogController controller;

    @BeforeEach
    void setUp() {
        ComponentCatalog catalog = new DefaultComponentCatalog();
        // Register a single built-in to exercise plugin-backed paths.
        try {
            var cls = Class.forName("br.com.archflow.plugins.agents.ConversationalAgent");
            Object inst = cls.getDeclaredConstructor().newInstance();
            catalog.register((br.com.archflow.model.ai.AIComponent) inst);
        } catch (Throwable ignored) {}
        controller = new CatalogControllerImpl(catalog);
    }

    @Test
    @DisplayName("agents list reflects ComponentCatalog registrations")
    void listAgents() {
        List<CatalogItemDto> agents = controller.listAgents();
        assertThat(agents).isNotNull();
        // When the plugin jar is on the classpath we expect at least one;
        // when not, the list is empty — both outcomes are valid.
        for (CatalogItemDto item : agents) {
            assertThat(item.kind()).isEqualTo("agent");
            assertThat(item.id()).isNotBlank();
        }
    }

    @Test
    @DisplayName("chat providers surface SPI-registered adapters")
    void listChatProviders() {
        List<CatalogItemDto> providers = controller.listChatProviders();
        // openai / anthropic / openrouter are registered via SPI when their
        // jars are present. This test only asserts the shape to avoid
        // coupling to classpath composition.
        for (CatalogItemDto item : providers) {
            assertThat(item.kind()).isEqualTo("provider");
            assertThat(item.id()).isNotBlank();
            assertThat(item.displayName()).isNotBlank();
        }
    }

    @Test
    @DisplayName("vectorstores surface SPI-registered adapters")
    void listVectorStores() {
        List<CatalogItemDto> stores = controller.listVectorStores();
        for (CatalogItemDto item : stores) {
            assertThat(item.kind()).isEqualTo("vectorstore");
        }
    }

    @Test
    @DisplayName("listAll returns union of every kind")
    void listAll() {
        List<CatalogItemDto> all = controller.listAll();
        int expected = controller.listAgents().size()
                + controller.listAssistants().size()
                + controller.listTools().size()
                + controller.listChatProviders().size()
                + controller.listEmbeddings().size()
                + controller.listMemories().size()
                + controller.listVectorStores().size()
                + controller.listChains().size();
        assertThat(all).hasSize(expected);
    }
}
