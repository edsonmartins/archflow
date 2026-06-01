package br.com.archflow.plugin.api.catalog;

import br.com.archflow.model.ai.metadata.ComponentMetadata;
import br.com.archflow.model.ai.type.ComponentType;

import java.util.List;
import java.util.Optional;

/**
 * Seleciona o componente de IA mais adequado para uma query em linguagem natural,
 * pontuando os componentes do {@link ComponentCatalog} por keywords, capabilities,
 * tags e texto (nome/descrição).
 *
 * <p>Preenche a lacuna de roteamento descriptor-driven que apps como gestor-rq e
 * integrall-commerce-api resolviam ad hoc (o {@code canHandle(query)} do
 * AbstractGestorRqAgent) — agora centralizado sobre o primitivo de agente já
 * existente ({@link br.com.archflow.model.ai.AIComponent}).
 *
 * @since 1.0.0
 */
public interface ComponentQueryRouter {

    /** Melhor componente para a query (qualquer tipo), se houver score {@literal >} 0. */
    Optional<ScoredComponent> route(String query);

    /** Melhor componente da query filtrando por tipo (ex.: só AGENT). */
    Optional<ScoredComponent> route(String query, ComponentType type);

    /** Todos os componentes com score {@literal >} 0, do maior para o menor. */
    List<ScoredComponent> rank(String query);

    /** Ranking filtrado por tipo. */
    List<ScoredComponent> rank(String query, ComponentType type);

    /**
     * Resultado pontuado do roteamento.
     *
     * @param componentId id para resolver o componente vivo via
     *                    {@link ComponentCatalog#getComponent(String)}
     * @param metadata    metadados do componente
     * @param score       confiança em [0.0, 1.0]
     */
    record ScoredComponent(String componentId, ComponentMetadata metadata, double score) {}
}
