package br.com.archflow.api.assist;

import br.com.archflow.api.catalog.CatalogController;
import br.com.archflow.api.catalog.dto.CatalogItemDto;
import br.com.archflow.api.flow.NodeSupport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * O que este servidor sabe executar, para os prompts da assistência (RCHFLW-107).
 *
 * <p><b>Por que existe.</b> O {@code nl-to-flow} só usava o catálogo que vinha no corpo: sem ele, o
 * modelo recebia "catálogo vazio" e devolvia um fluxo sem passos, embora o servidor conheça o próprio
 * catálogo. E o {@code explain-error} não sabia nada do archflow: para "component not found:
 * transform" falava de "plataforma iPaaS" e sugeria {@code data-transform}, {@code mapper} e {@code jq},
 * que não existem aqui. As duas respostas precisam da mesma coisa — a lista do que roda.</p>
 *
 * <p><b>Nenhuma regra própria.</b> Os itens saem do {@link CatalogController} (a mesma fonte da paleta
 * do designer) e cada um passa pelo {@link NodeSupport}, que constrói o nó com o factory do motor: só
 * entra o que o motor executaria. Quando o motor ganhar ou perder um tipo, as respostas mudam junto.</p>
 *
 * <p>A única lista escrita aqui é {@link #DESIGNER_NODE_TYPES}, os tipos da paleta do designer — o
 * servidor não tem outra fonte para eles. Ela espelha {@code NODE_TYPE_TO_CATEGORY} do archflow-ui, e o
 * {@code ServerFlowCatalogMirrorTest} falha nas duas direções se as duas divergirem.</p>
 */
public class ServerFlowCatalog {

    /**
     * Tipos de nó da paleta do designer, sem as anotações do canvas (que o motor ignora por desenho).
     * Espelho de {@code NODE_TYPE_TO_CATEGORY} em {@code archflow-ui/src/components/FlowCanvas/constants.ts}.
     */
    public static final List<String> DESIGNER_NODE_TYPES = List.of(
            "agent", "assistant", "llm-chat", "llm-streaming", "orchestrate",
            "condition", "parallel", "loop", "switch",
            "transform", "map", "filter", "reduce", "merge",
            "tool", "function", "custom",
            "vector-search", "vector-store", "embedding", "rag", "memory",
            "prompt-template", "prompt-chunk", "input", "output",
            "skills", "approval", "decision", "subflow", "mcp-tool",
            "linktor-send", "linktor-escalate");

    /**
     * O tipo de nó do designer para cada categoria do catálogo: é o {@code type} que o nó grava no
     * documento, e o que decide por onde o motor o executa (catálogo ou adapter).
     */
    private static final Map<String, String> NODE_TYPE_BY_KIND = Map.of(
            "agent", "agent",
            "assistant", "assistant",
            "tool", "tool",
            "provider", "llm-chat",
            "embedding", "embedding",
            "memory", "memory",
            "vectorstore", "vector-store",
            "chain", "rag");

    /**
     * Um componente que um nó pode usar e que o motor executa.
     *
     * @param nodeType  o {@code type} do nó no documento
     * @param component o id do componente ({@code componentId} de um nó do catálogo, {@code provider}
     *                  de um nó de adapter)
     * @param adapter   {@code true} quando o componente é um provider de adapter: vai em
     *                  {@code configuration.provider}, e o {@code componentId} do nó é o próprio tipo
     */
    public record ExecutableComponent(String nodeType, String component, String name, String kind,
                                      String description, boolean adapter,
                                      List<CatalogItemDto.OperationDto> operations,
                                      List<CatalogItemDto.ConfigKeyDto> config) {
    }

    private final CatalogController catalog;
    private final NodeSupport nodeSupport;

    public ServerFlowCatalog(CatalogController catalog, NodeSupport nodeSupport) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.nodeSupport = Objects.requireNonNull(nodeSupport, "nodeSupport");
    }

    /** Os componentes do catálogo que o motor executa, na ordem do catálogo. */
    public List<ExecutableComponent> executableComponents() {
        List<ExecutableComponent> out = new ArrayList<>();
        for (CatalogItemDto item : catalog.listAll()) {
            String nodeType = item == null || item.kind() == null ? null
                    : NODE_TYPE_BY_KIND.get(item.kind().toLowerCase(Locale.ROOT));
            if (nodeType == null || item.id() == null || item.id().isBlank()) {
                continue;
            }
            boolean adapter = !"agent".equals(item.kind()) && !"assistant".equals(item.kind())
                    && !"tool".equals(item.kind());
            Map<String, Object> node = adapter
                    ? probe(nodeType, nodeType, Map.of("provider", item.id()))
                    : probe(nodeType, item.id(), Map.of());
            if (!executes(node)) {
                continue;
            }
            out.add(new ExecutableComponent(nodeType, item.id(), item.displayName(), item.kind(),
                    item.description(), adapter,
                    item.operations() == null ? List.of() : item.operations(),
                    item.configSchema() == null ? List.of() : item.configSchema()));
        }
        return out;
    }

    /**
     * Tipos da paleta que executam como vêm dela — controle, aprovação, decisão, agente MCP, nós de
     * adapter com provider padrão... —, sem precisar de um componente do catálogo.
     */
    public List<String> executableNodeTypes() {
        return DESIGNER_NODE_TYPES.stream().filter(t -> executes(probe(t, t, Map.of()))).toList();
    }

    /**
     * Tipos da paleta que, como vêm dela, não têm execução neste servidor: o passo falha com
     * "component not found: &lt;tipo&gt;". Os de categoria (agent, assistant, tool) só executam quando o
     * nó aponta para um componente do catálogo.
     */
    public List<String> nonExecutableNodeTypes() {
        return DESIGNER_NODE_TYPES.stream().filter(t -> !executes(probe(t, t, Map.of()))).toList();
    }

    private boolean executes(Map<String, Object> node) {
        return nodeSupport.unsupportedNodeTypes(List.of(node)).isEmpty();
    }

    /** Um nó como o designer o grava, só para perguntar ao factory do motor se ele roda. */
    private static Map<String, Object> probe(String type, String componentId, Map<String, Object> configuration) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "assist-probe");
        node.put("type", type);
        node.put("componentId", componentId);
        node.put("configuration", configuration);
        return node;
    }
}
