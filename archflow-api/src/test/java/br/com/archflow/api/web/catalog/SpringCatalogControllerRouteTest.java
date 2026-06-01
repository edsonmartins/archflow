package br.com.archflow.api.web.catalog;

import br.com.archflow.api.catalog.dto.RoutedComponentDto;
import br.com.archflow.model.ai.AIComponent;
import br.com.archflow.model.ai.metadata.ComponentMetadata;
import br.com.archflow.model.ai.type.ComponentType;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.plugin.api.catalog.ComponentQueryRouter;
import br.com.archflow.plugin.api.catalog.DefaultComponentCatalog;
import br.com.archflow.plugin.api.catalog.DefaultComponentQueryRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SpringCatalogController.route")
class SpringCatalogControllerRouteTest {

    private SpringCatalogController controller;

    @BeforeEach
    void setUp() {
        DefaultComponentCatalog catalog = new DefaultComponentCatalog();
        catalog.register(agent("billing-agent", "Billing Agent", ComponentType.AGENT,
                Set.of("finance"), Set.of("invoice", "payment", "billing")));
        catalog.register(agent("weather-tool", "Weather Tool", ComponentType.TOOL,
                Set.of("forecast"), Set.of("weather", "rain")));
        ComponentQueryRouter router = new DefaultComponentQueryRouter(catalog);
        controller = new SpringCatalogController(null, router);
    }

    private AIComponent agent(String id, String name, ComponentType type,
                             Set<String> capabilities, Set<String> keywords) {
        return new AIComponent() {
            @Override public void initialize(Map<String, Object> config) {}
            @Override public ComponentMetadata getMetadata() {
                return new ComponentMetadata(id, name, "desc", type, "1.0.0",
                        capabilities, List.of(), Map.of(), Set.of(), keywords);
            }
            @Override public Object execute(String op, Object in, ExecutionContext c) { return null; }
            @Override public void shutdown() {}
        };
    }

    @Test
    @DisplayName("routes a query to the best-matching component with keywords surfaced")
    void routesByQuery() {
        List<RoutedComponentDto> results = controller.route("help with an invoice payment", null, 5);

        assertThat(results).isNotEmpty();
        RoutedComponentDto top = results.get(0);
        assertThat(top.componentId()).isEqualTo("billing-agent");
        assertThat(top.kind()).isEqualTo("agent");
        assertThat(top.score()).isGreaterThan(0.0);
        assertThat(top.keywords()).contains("invoice", "payment");
    }

    @Test
    @DisplayName("filters by type")
    void filtersByType() {
        List<RoutedComponentDto> results = controller.route("weather forecast", "tool", 5);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).componentId()).isEqualTo("weather-tool");
    }

    @Test
    @DisplayName("respects the limit")
    void respectsLimit() {
        // both match "agent"-less generic query weakly; limit caps the result count
        List<RoutedComponentDto> results = controller.route("invoice weather", null, 1);
        assertThat(results).hasSize(1);
    }
}
