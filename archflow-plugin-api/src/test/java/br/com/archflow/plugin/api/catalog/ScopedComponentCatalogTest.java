package br.com.archflow.plugin.api.catalog;

import br.com.archflow.model.ai.AIComponent;
import br.com.archflow.model.ai.metadata.ComponentMetadata;
import br.com.archflow.model.ai.type.ComponentType;
import br.com.archflow.model.engine.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ScopedComponentCatalog")
class ScopedComponentCatalogTest {

    private static final String READER = "log-reader";
    private static final String WRITER = "service-restarter";

    private ComponentCatalog full;

    private static AIComponent component(String id) {
        return new AIComponent() {
            @Override public void initialize(Map<String, Object> config) { }
            @Override public ComponentMetadata getMetadata() {
                return new ComponentMetadata(id, id, "componente " + id, ComponentType.TOOL,
                        "1.0.0", Set.of(), List.of(), Map.of(), Set.of());
            }
            @Override public Object execute(String op, Object in, ExecutionContext ctx) { return null; }
            @Override public void shutdown() { }
        };
    }

    @BeforeEach
    void setUp() {
        full = new DefaultComponentCatalog();
        full.register(component(READER));
        full.register(component(WRITER));
    }

    @Test
    @DisplayName("não resolve componente fora do escopo")
    void deniedComponentIsNotResolvable() {
        ComponentCatalog scoped = new ScopedComponentCatalog(
                full, ComponentAccessPolicy.allowOnly(Set.of(READER)));

        assertThat(scoped.getComponent(READER)).isPresent();
        assertThat(scoped.getComponent(WRITER))
                .as("o invariante: o fluxo de leitura nao alcanca a tool de escrita")
                .isEmpty();
    }

    @Test
    @DisplayName("componente fora do escopo também não aparece em listagem nem busca")
    void deniedComponentIsInvisible() {
        ComponentCatalog scoped = new ScopedComponentCatalog(
                full, ComponentAccessPolicy.allowOnly(Set.of(READER)));

        assertThat(scoped.listComponents()).extracting(ComponentMetadata::id).containsExactly(READER);
        assertThat(scoped.searchComponents(
                new ComponentSearchCriteria(null, Set.of(), null)))
                .extracting(ComponentMetadata::id)
                .as("se o roteador enxergasse, rotearia para algo que nao resolve depois")
                .containsExactly(READER);
        assertThat(scoped.getMetadata(WRITER)).isEmpty();
    }

    @Test
    @DisplayName("allowlist vazia bloqueia tudo")
    void emptyAllowlistBlocksEverything() {
        ComponentCatalog scoped = new ScopedComponentCatalog(
                full, ComponentAccessPolicy.allowOnly(List.of()));

        assertThat(scoped.getComponent(READER)).isEmpty();
        assertThat(scoped.listComponents()).isEmpty();
    }

    @Test
    @DisplayName("of() devolve o catálogo original quando a política é irrestrita")
    void unrestrictedPolicyDoesNotWrap() {
        assertThat(ScopedComponentCatalog.of(full, ComponentAccessPolicy.allowAll())).isSameAs(full);
    }

    @Test
    @DisplayName("escrever pela vista restrita falha alto em vez de enganar")
    void writesAreRejected() {
        ComponentCatalog scoped = new ScopedComponentCatalog(full, ComponentAccessPolicy.allowAll());

        assertThatThrownBy(() -> scoped.register(component("x")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> scoped.unregister(READER))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("comparação de id é case-insensitive")
    void allowlistIsCaseInsensitive() {
        ComponentCatalog scoped = new ScopedComponentCatalog(
                full, ComponentAccessPolicy.allowOnly(Set.of("LOG-READER")));

        assertThat(scoped.getComponent(READER)).isPresent();
    }
}
