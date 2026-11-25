package br.com.archflow.agent.orchestration;

import br.com.archflow.model.ai.metadata.ComponentMetadata;
import br.com.archflow.model.ai.type.ComponentType;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * O critério "pode rodar como sub-agente da orquestração" — declarado aqui e em nenhum outro lugar
 * (RCHFLW-108).
 *
 * <p>O {@link CatalogAgentWorker} executa a instância do catálogo, com o contexto recortado por
 * {@link SubAgentContext}. Um componente que só roda com a configuração de um nó ou com algo que esse
 * recorte não carrega falha em toda subtarefa que recebe — e o roteador o escolhia mesmo assim. No Labs,
 * 4 de 6 subtarefas foram para o {@code mcp-agent} e todas falharam com "mcp-agent não inicializado".</p>
 *
 * <p>Esses componentes ficam <b>fora do roteamento</b>: o worker nunca os escolhe, e a orquestração
 * registra uma vez, no início, quais ficaram fora e por quê ({@link OrchestrationListener#onExcludedFromRouting})
 * — nada é silencioso. A ordem entre os que podem rodar continua a do roteador; não há score mínimo novo.
 * Sobrando só excluídos para uma subtarefa, vale a falha explícita "no agent matched" que já existia.</p>
 *
 * <p>A lista é por id, e não por uma marca nos metadados do componente, porque o que impede a execução é
 * a configuração <em>desta</em> orquestração (o catálogo compartilhado e o {@link SubAgentContext}), não o
 * componente: quando o sub-agente ganhar fonte de configuração e host, a entrada sai daqui, sem mexer no
 * componente. O teste com o catálogo real ({@code OrquestracaoComAgentesReaisTest}, archflow-api) confere
 * o id contra a constante do componente.</p>
 */
public final class SubAgentEligibility {

    /**
     * Componentes que não rodam como sub-agente nesta configuração, com o motivo que vai ao trace e ao
     * stream. Não se inventa configuração para eles: excluir é a decisão (RCHFLW-108, opção a).
     */
    private static final Map<String, String> NOT_RUNNABLE = Map.of(
            "mcp-agent", "não roda como sub-agente: precisa da configuração de um nó (systemPrompt, servidor "
                    + "MCP, tools), que a instância do catálogo não tem, e do McpAgentHost, que o contexto de "
                    + "sub-agente não repassa");

    /** Um componente fora do roteamento da orquestração e o porquê. */
    public record Exclusion(String componentId, String reason) {

        public Exclusion {
            Objects.requireNonNull(componentId, "componentId");
            Objects.requireNonNull(reason, "reason");
        }
    }

    private SubAgentEligibility() {
    }

    /** Por que o componente não pode ser sub-agente, ou vazio quando pode. */
    public static Optional<String> exclusionReason(String componentId) {
        return componentId == null ? Optional.empty() : Optional.ofNullable(NOT_RUNNABLE.get(componentId));
    }

    /** Se o roteamento da orquestração pode escolher o componente. */
    public static boolean canRunAsSubAgent(String componentId) {
        return exclusionReason(componentId).isEmpty();
    }

    /**
     * Os agentes do {@code catalog} que ficam fora do roteamento, na ordem do catálogo. Só os do tipo
     * {@code AGENT}: são os únicos que o worker roteia, e um componente fora do escopo
     * ({@code allowedComponents}) já não aparece no catálogo recortado — não há o que relatar sobre ele.
     */
    public static List<Exclusion> excludedIn(ComponentCatalog catalog) {
        if (catalog == null) {
            return List.of();
        }
        List<ComponentMetadata> components = catalog.listComponents();
        if (components == null) {
            return List.of();
        }
        return components.stream()
                .filter(md -> md != null && md.type() == ComponentType.AGENT)
                .flatMap(md -> exclusionReason(md.id()).map(r -> new Exclusion(md.id(), r)).stream())
                .toList();
    }
}
