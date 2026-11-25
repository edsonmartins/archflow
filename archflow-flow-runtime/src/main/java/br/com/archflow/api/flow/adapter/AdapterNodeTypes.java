package br.com.archflow.api.flow.adapter;

import br.com.archflow.langchain4j.core.spi.LangChainRegistry;

import java.util.Locale;
import java.util.Map;

/**
 * Traduz o tipo de nó do designer para o tipo de adapter da SPI, e decide se um
 * nó é de fato servido por um adapter.
 *
 * <p>A decisão é conservadora de propósito: só se considera um nó "de adapter"
 * quando o {@link LangChainRegistry} <b>realmente tem</b> aquele provider para
 * aquele tipo. Qualquer outra coisa cai no caminho antigo do catálogo, então
 * nenhum workflow existente muda de comportamento.
 */
public final class AdapterNodeTypes {

    /**
     * Tipo de nó do designer (ver {@code NODE_TYPE_TO_CATEGORY} no
     * archflow-ui) → tipo de adapter do {@link LangChainRegistry}.
     */
    private static final Map<String, String> ADAPTER_TYPE_BY_NODE_TYPE = Map.of(
            "llm-chat", "chat",
            "llm-streaming", "chat",
            "chat", "chat",
            "embedding", "embedding",
            "memory", "memory",
            "vector-store", "vectorstore",
            "vector-search", "vectorstore",
            "vectorstore", "vectorstore",
            "rag", "chain");

    private AdapterNodeTypes() {
    }

    /** Tipo de adapter para um tipo de nó, ou {@code null} se não for de adapter. */
    public static String adapterTypeFor(String nodeType) {
        if (nodeType == null || nodeType.isBlank()) {
            return null;
        }
        return ADAPTER_TYPE_BY_NODE_TYPE.get(nodeType.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * {@code true} quando o registry tem este provider para este tipo — ou seja,
     * quando o nó pode de fato ser executado por um adapter.
     */
    public static boolean isAdapterNode(String nodeType, String providerId) {
        String adapterType = adapterTypeFor(nodeType);
        if (adapterType == null || providerId == null || providerId.isBlank()) {
            return false;
        }
        return LangChainRegistry.getProvidersOfType(adapterType).contains(providerId.trim());
    }
}
