package br.com.archflow.api.assist;

import br.com.archflow.api.assist.dto.ExplainErrorRequest;
import br.com.archflow.api.assist.dto.ExplainErrorResponse;

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
}
