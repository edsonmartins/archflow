package br.com.archflow.api.flow;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * O que identifica um nó de Base de conhecimento no documento (RCHFLW-55).
 *
 * <p>Separado do {@code KnowledgeNodeComponent} (archflow-api) em RCHFLW-37: a fábrica comum precisa
 * reconhecer o nó — para mandá-lo ao servidor, ou recusá-lo sem servidor — e a ponte dos adapters cita os
 * mesmos tipos na sua mensagem; a busca em si continua no servidor, que é quem tem o índice.</p>
 */
public final class KnowledgeNodes {

    /** Id para {@code configuration.allowedComponents}: um fluxo restrito precisa listá-lo. */
    public static final String COMPONENT_ID = "knowledge-base";

    /** Os tipos de nó que aceitam uma base (o painel mostra o seletor nos mesmos). */
    public static final Set<String> NODE_TYPES = Set.of("vector-search", "rag");

    /** A chave que o painel grava. */
    public static final String BASE_KEY = "knowledgeBaseId";

    /**
     * A chave que os templates do backend gravavam e que nenhum código lia. Continua lida para os fluxos
     * já instalados com ela — o documento salvo não muda.
     */
    public static final String LEGACY_BASE_KEY = "vectorStoreId";

    private KnowledgeNodes() {
    }

    /** A base que o nó escolheu ({@link #BASE_KEY}, senão {@link #LEGACY_BASE_KEY}), ou {@code null}. */
    public static String baseIdOf(Map<String, Object> config) {
        for (String key : List.of(BASE_KEY, LEGACY_BASE_KEY)) {
            if (config.get(key) instanceof String id && !id.isBlank()) {
                return id.trim();
            }
        }
        return null;
    }
}
