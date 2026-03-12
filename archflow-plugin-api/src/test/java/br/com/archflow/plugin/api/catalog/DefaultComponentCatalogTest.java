package br.com.archflow.plugin.api.catalog;

import br.com.archflow.model.ai.AIComponent;
import br.com.archflow.model.ai.metadata.ComponentMetadata;
import br.com.archflow.model.ai.type.ComponentType;
import br.com.archflow.model.engine.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

@DisplayName("DefaultComponentCatalog")
class DefaultComponentCatalogTest {

    private DefaultComponentCatalog catalog;

    @BeforeEach
    void setUp() {
        catalog = new DefaultComponentCatalog();
    }

    private AIComponent createComponent(String id, String name, ComponentType type, String version) {
        return new AIComponent() {
            @Override
            public void initialize(Map<String, Object> config) {}

            @Override
            public ComponentMetadata getMetadata() {
                return new ComponentMetadata(
                        id, name, name + " description", type, version,
                        Set.of("chat"), List.of(), Map.of(), Set.of("test")
                );
            }

            @Override
            public Object execute(String operation, Object input, ExecutionContext context) {
                return null;
            }

            @Override
            public void shutdown() {}
        };
    }

    @Nested
    @DisplayName("register/unregister")
    class RegisterTest {

        @Test
        @DisplayName("should register component")
        void shouldRegister() {
            var component = createComponent("comp-1", "Test", ComponentType.ASSISTANT, "1.0.0");

            catalog.register(component);

            assertThat(catalog.getComponent("comp-1")).isPresent();
            assertThat(catalog.getMetadata("comp-1")).isPresent();
            assertThat(catalog.listComponents()).hasSize(1);
        }

        @Test
        @DisplayName("should unregister component")
        void shouldUnregister() {
            var component = createComponent("comp-1", "Test", ComponentType.ASSISTANT, "1.0.0");
            catalog.register(component);

            catalog.unregister("comp-1");

            assertThat(catalog.getComponent("comp-1")).isEmpty();
            assertThat(catalog.getMetadata("comp-1")).isEmpty();
            assertThat(catalog.listComponents()).isEmpty();
        }

        @Test
        @DisplayName("should register version through catalog")
        void shouldRegisterVersion() {
            var component = createComponent("comp-1", "Test", ComponentType.TOOL, "1.0.0");
            catalog.register(component);

            var versions = catalog.getVersionManager().getVersions("comp-1");
            assertThat(versions).contains("1.0.0");
        }
    }

    @Nested
    @DisplayName("getComponent / getMetadata")
    class GetTest {

        @Test
        @DisplayName("should return empty for missing component")
        void shouldReturnEmptyForMissing() {
            assertThat(catalog.getComponent("nonexistent")).isEmpty();
            assertThat(catalog.getMetadata("nonexistent")).isEmpty();
        }

        @Test
        @DisplayName("should return correct metadata")
        void shouldReturnMetadata() {
            catalog.register(createComponent("comp-1", "Test Comp", ComponentType.AGENT, "2.0.0"));

            var meta = catalog.getMetadata("comp-1");
            assertThat(meta).isPresent();
            assertThat(meta.get().name()).isEqualTo("Test Comp");
            assertThat(meta.get().type()).isEqualTo(ComponentType.AGENT);
        }
    }

    @Nested
    @DisplayName("searchComponents")
    class SearchTest {

        @BeforeEach
        void setUpComponents() {
            catalog.register(createComponent("assistant-1", "Chat Assistant", ComponentType.ASSISTANT, "1.0.0"));
            catalog.register(createComponent("agent-1", "Research Agent", ComponentType.AGENT, "1.0.0"));
            catalog.register(createComponent("tool-1", "Calculator Tool", ComponentType.TOOL, "1.0.0"));
        }

        @Test
        @DisplayName("should search by type")
        void shouldSearchByType() {
            var criteria = ComponentSearchCriteria.builder()
                    .type(ComponentType.ASSISTANT)
                    .build();

            var results = catalog.searchComponents(criteria);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).name()).isEqualTo("Chat Assistant");
        }

        @Test
        @DisplayName("should search by text")
        void shouldSearchByText() {
            var criteria = ComponentSearchCriteria.builder()
                    .textSearch("Research")
                    .build();

            var results = catalog.searchComponents(criteria);

            assertThat(results).hasSize(1);
            assertThat(results.get(0).name()).isEqualTo("Research Agent");
        }

        @Test
        @DisplayName("should search by text case-insensitive")
        void shouldSearchCaseInsensitive() {
            var criteria = ComponentSearchCriteria.builder()
                    .textSearch("calculator")
                    .build();

            var results = catalog.searchComponents(criteria);

            assertThat(results).hasSize(1);
        }

        @Test
        @DisplayName("should search by capabilities")
        void shouldSearchByCapabilities() {
            var criteria = ComponentSearchCriteria.builder()
                    .capabilities(Set.of("chat"))
                    .build();

            var results = catalog.searchComponents(criteria);

            assertThat(results).hasSize(3); // All have "chat"
        }

        @Test
        @DisplayName("should return all when no criteria")
        void shouldReturnAllWhenNoCriteria() {
            var criteria = ComponentSearchCriteria.builder().build();

            var results = catalog.searchComponents(criteria);

            assertThat(results).hasSize(3);
        }

        @Test
        @DisplayName("should combine type and text filters")
        void shouldCombineFilters() {
            var criteria = ComponentSearchCriteria.builder()
                    .type(ComponentType.AGENT)
                    .textSearch("Research")
                    .build();

            var results = catalog.searchComponents(criteria);

            assertThat(results).hasSize(1);
        }

        @Test
        @DisplayName("should return empty when no match")
        void shouldReturnEmptyWhenNoMatch() {
            var criteria = ComponentSearchCriteria.builder()
                    .textSearch("nonexistent")
                    .build();

            var results = catalog.searchComponents(criteria);

            assertThat(results).isEmpty();
        }
    }
}
