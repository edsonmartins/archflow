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
