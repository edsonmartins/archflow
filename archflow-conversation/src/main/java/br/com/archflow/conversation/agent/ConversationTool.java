package br.com.archflow.conversation.agent;

import java.util.Map;

/**
 * Ferramenta chamável por um agente conversacional durante o loop de tool-calling.
 *
 * <p>Intencionalmente leve e funcional — distinta de {@code model.ai.Tool} (que é
 * um componente de catálogo com metadados ricos). Um produto pode adaptar um
 * {@code model.ai.Tool} para esta interface se quiser reusá-lo no loop.
 *
 * @since 1.0.0
 */
@FunctionalInterface
public interface ConversationTool {

    /**
     * Executa a ferramenta com os parâmetros extraídos da chamada do LLM e
     * devolve um texto que será realimentado no transcript para o próximo turno.
     *
     * @param params parâmetros parseados (pode ser vazio)
     * @return resultado em texto
     */
    String execute(Map<String, Object> params);
}
