package br.com.archflow.api.workflow.dto;

import java.util.List;

/**
 * Public metadata of an LLM provider exposed to the workflow editor so
 * the frontend can populate its provider/model dropdowns dynamically
 * instead of hardcoding options.
 *
 * <p>This is a projection of {@link br.com.archflow.langchain4j.provider.LLMProvider}
 * — it intentionally drops internals like base URLs and keeps only what
 * the editor needs (id, label, capability flags, model catalog).
 *
 * <p>{@code models} é o catálogo da plataforma (ativo) recortado pelos modelos permitidos do tenant
 * da requisição (RCHFLW-79), não a lista fixa do enum.
 *
 * @param allowCustomModel {@code true} quando o tenant não tem restrição de modelos: o seletor aceita
 *                         um id digitado além da lista. Com restrição, só a lista — a execução recusa
 *                         o resto ({@code TenantModelPolicy})
 * @param platformDefault  {@code true} no provider do {@code archflow.llm.provider}: é o que um nó sem
 *                         provider herda (sem padrão no fluxo), e o seletor de modelo usa a lista dele
 * @param defaultModel     o {@code archflow.llm.model}, só no provider da plataforma ({@code null} nos
 *                         outros): é o modelo que um nó sem modelo herda quando o fluxo não diz um. O
 *                         painel precisa dele para avisar, num tenant restrito, que o herdado vai ser
 *                         recusado na execução — sem isso o nó parece válido e falha com [LLM-MODELO]
 */
public record ProviderDto(
        String id,
        String displayName,
        boolean requiresApiKey,
        boolean supportsStreaming,
        String group,
        List<ModelDto> models,
        boolean allowCustomModel,
        boolean platformDefault,
        String defaultModel
) {

    /** Sem catálogo nem tenant (testes, hosts mínimos): lista do enum, id livre permitido. */
    public ProviderDto(String id, String displayName, boolean requiresApiKey, boolean supportsStreaming,
                       String group, List<ModelDto> models) {
        this(id, displayName, requiresApiKey, supportsStreaming, group, models, true, false, null);
    }

    /**
     * @param id             model id (e.g. {@code gpt-4o})
     * @param name           human-readable label
     * @param contextWindow  maximum context tokens; {@code 0} quando o catálogo traz um id que o
     *                       código não conhece (o painel não inventa um número)
     * @param maxTemperature upper bound the frontend uses to clamp the
     *                       temperature slider
     */
    public record ModelDto(
            String id,
            String name,
            int contextWindow,
            double maxTemperature
    ) {}
}
