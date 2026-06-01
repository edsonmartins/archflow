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

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DefaultComponentQueryRouter")
class ComponentQueryRouterTest {

    private DefaultComponentCatalog catalog;
    private ComponentQueryRouter router;

    @BeforeEach
    void setUp() {
        catalog = new DefaultComponentCatalog();
        router = new DefaultComponentQueryRouter(catalog);
    }

    private void register(String id, String name, String description, ComponentType type,
                          Set<String> capabilities, Set<String> tags, Set<String> keywords) {
        catalog.register(new AIComponent() {
            @Override public void initialize(Map<String, Object> config) {}
            @Override public ComponentMetadata getMetadata() {
                return new ComponentMetadata(id, name, description, type, "1.0.0",
                        capabilities, List.of(), Map.of(), tags, keywords);
            }
            @Override public Object execute(String operation, Object input, ExecutionContext context) { return null; }
            @Override public void shutdown() {}
        });
    }

    @Nested
    @DisplayName("route")
    class Route {

        @Test
        @DisplayName("picks the component whose keywords match the query")
        void picksByKeyword() {
            register("billing", "Billing Agent", "Handles invoices", ComponentType.AGENT,
                    Set.of("finance"), Set.of(), Set.of("invoice", "payment", "billing"));
            register("weather", "Weather Agent", "Forecasts", ComponentType.AGENT,
                    Set.of("forecast"), Set.of(), Set.of("weather", "rain", "temperature"));

            var best = router.route("I need help with my invoice payment");

            assertThat(best).isPresent();
            assertThat(best.get().componentId()).isEqualTo("billing");
            assertThat(best.get().score()).isGreaterThan(0.0);
        }

        @Test
        @DisplayName("filters by component type")
        void filtersByType() {
            register("agent-1", "Refund Agent", "Refunds", ComponentType.AGENT,
                    Set.of(), Set.of(), Set.of("refund"));
            register("tool-1", "Refund Tool", "Refund utility", ComponentType.TOOL,
                    Set.of(), Set.of(), Set.of("refund"));

            var best = router.route("process a refund", ComponentType.TOOL);

            assertThat(best).isPresent();
            assertThat(best.get().componentId()).isEqualTo("tool-1");
            assertThat(best.get().metadata().type()).isEqualTo(ComponentType.TOOL);
        }

        @Test
        @DisplayName("blank query returns empty")
        void blankQuery() {
            register("x", "X", "desc", ComponentType.AGENT, Set.of("a"), Set.of(), Set.of("k"));
            assertThat(router.route("   ")).isEmpty();
            assertThat(router.route("")).isEmpty();
        }

        @Test
        @DisplayName("no match returns empty")
        void noMatch() {
            register("billing", "Billing", "Invoices", ComponentType.AGENT,
                    Set.of("finance"), Set.of(), Set.of("invoice"));
            assertThat(router.route("photosynthesis chlorophyll")).isEmpty();
        }

        @Test
        @DisplayName("falls back to capabilities when no keywords are set")
        void capabilityFallback() {
            register("research", "Research Agent", "Generic", ComponentType.AGENT,
                    Set.of("research", "analysis"), Set.of(), Set.of());

            var best = router.route("I need some research done");

            assertThat(best).isPresent();
            assertThat(best.get().componentId()).isEqualTo("research");
        }
    }

    @Nested
    @DisplayName("rank")
    class Rank {

        @Test
        @DisplayName("orders by score, strongest signal first")
        void orders() {
            // Two keyword hits → higher ratio than one.
            register("strong", "A", "x", ComponentType.AGENT, Set.of(), Set.of(),
                    Set.of("refund", "payment"));
            register("weak", "B", "y", ComponentType.AGENT, Set.of(), Set.of(),
                    Set.of("refund", "shipping", "tracking", "label"));

            List<ComponentQueryRouter.ScoredComponent> ranked = router.rank("refund payment");

            assertThat(ranked).hasSize(2);
            assertThat(ranked.get(0).componentId()).isEqualTo("strong");
            assertThat(ranked.get(0).score()).isGreaterThan(ranked.get(1).score());
        }
    }
}
