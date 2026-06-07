package br.com.archflow.api.assist;

import br.com.archflow.api.assist.dto.ExplainErrorRequest;
import br.com.archflow.api.assist.dto.ExplainErrorResponse;
import br.com.archflow.api.assist.dto.NlToFlowRequest;
import br.com.archflow.api.assist.dto.NlToFlowResponse;
import br.com.archflow.api.assist.dto.SuggestMappingRequest;
import br.com.archflow.api.assist.dto.SuggestMappingResponse;

/**
 * Serviço de assistência por IA (família {@code /archflow/assist/*}).
 *
 * <p>Concentra a montagem de prompt, a chamada síncrona ao LLM padrão
 * (via {@code LLMConfigResolver} + {@code LLMProviderHub}) e o parsing da
 * resposta. Pensado para ser reutilizado pelas próximas operações de assist
 * (#22/#23).
 *
 * @since 1.0.0
 */
public interface AssistService {

    /**
     * Diagnostica um erro de execução de integração de forma síncrona,
     * retornando explicação e sugestão de correção em linguagem natural.
     *
     * @param request contexto estruturado do erro
     * @return diagnóstico + sugestão
     * @throws AssistUnavailableException se o modelo falhar ou der timeout
     */
    ExplainErrorResponse explainError(ExplainErrorRequest request);

    /**
     * Sugere mapeamento campo-a-campo entre dois schemas (origem→destino) de
     * forma síncrona, casando por similaridade semântica e de tipo (change #23).
     *
     * @param request schemas de origem/destino + idioma
     * @return sugestões de mapeamento (vazio se a resposta não for parseável)
     * @throws AssistUnavailableException se o modelo falhar ou der timeout
     */
    SuggestMappingResponse suggestMapping(SuggestMappingRequest request);

    /**
     * Gera um rascunho de workflow a partir de uma descrição em linguagem
     * natural, usando EXCLUSIVAMENTE o catálogo de plugins fornecido
     * (ADR-0001, change #22).
     *
     * @param request descrição + catálogo (manifest) + idioma
     * @return rascunho do workflow + lacunas + observações
     * @throws AssistUnavailableException se o modelo falhar ou der timeout
     */
    NlToFlowResponse nlToFlow(NlToFlowRequest request);
}
