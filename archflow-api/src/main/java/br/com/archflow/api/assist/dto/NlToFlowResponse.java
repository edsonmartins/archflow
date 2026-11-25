package br.com.archflow.api.assist.dto;

import java.util.List;
import java.util.Map;

/**
 * Rascunho de workflow gerado pela IA a partir de linguagem natural (change #22),
 * restrito ao catálogo de plugins fornecido (ADR-0001).
 *
 * <p>Contrato fixo, compartilhado com o backend/frontend Mentors. Em caso de
 * falha de parsing da resposta do modelo, retorna {@code workflow} vazio,
 * {@code lacunas} com uma mensagem padrão e {@code observacoes} com o texto cru
 * (ainda HTTP 200).
 *
 * <p><b>Este não é o documento que o motor executa.</b> O motor lê {@code steps[]} com as arestas
 * <i>dentro</i> de cada passo ({@code steps[].connections}, ver CLAUDE.md, "the three readers");
 * aqui as ligações ficam num {@code links} no topo, por {@code step.name}. O formato fica como está
 * porque é o contrato do Mentors, o consumidor desta API — nada no archflow (designer, motor,
 * standalone) lê esta resposta (RCHFLW-107). Para executá-la, quem consome converte: cada
 * {@link Step} vira {@code {id: name, type: nodeType, componentId: component,
 * configuration: parameters (+ operation)}} — num nó de adapter (chat, embedding, memória, vector
 * store, RAG) o {@code component} vai em {@code configuration.provider} e o {@code componentId} é o
 * próprio tipo —, e cada {@link Link} vira {@code {sourceId: source, targetId: target}} dentro de
 * {@code connections} do passo {@code source}. Emitir os {@code links} no topo como estão daria um
 * fluxo que abre no designer e não anda quando executado.
 *
 * @param workflow    rascunho do workflow (steps + links); nunca {@code null}
 * @param lacunas     capacidades pedidas sem plugin correspondente no catálogo
 * @param observacoes observações livres do modelo; pode ser {@code null}
 */
public record NlToFlowResponse(
        Workflow workflow,
        List<String> lacunas,
        String observacoes
) {
    /**
     * Rascunho de workflow.
     *
     * @param steps etapas do workflow
     * @param links ligações entre etapas (por {@code step.name})
     */
    public record Workflow(
            List<Step> steps,
            List<Link> links
    ) {
    }

    /**
     * Uma etapa do workflow, derivada exclusivamente do catálogo.
     *
     * @param name             nome único da etapa (usado como source/target de links)
     * @param component         id do plugin/componente (do catálogo)
     * @param componentName     nome do componente (do catálogo)
     * @param componentVersion  versão do componente (do catálogo)
     * @param pluginKind        tipo do plugin (do catálogo)
     * @param operation         operação escolhida (do catálogo); pode ser {@code null}
     * @param nodeType          tipo de nó no editor; pode ser {@code null}
     * @param parameters        parâmetros preenchidos (apenas propriedades do catálogo)
     */
    public record Step(
            String name,
            String component,
            String componentName,
            String componentVersion,
            String pluginKind,
            String operation,
            String nodeType,
            Map<String, Object> parameters
    ) {
    }

    /**
     * Ligação direcionada entre dois steps, por {@code step.name}.
     *
     * @param source nome do step de origem
     * @param target nome do step de destino
     */
    public record Link(
            String source,
            String target
    ) {
    }
}
