package br.com.archflow.model.engine;

import java.util.Optional;

/**
 * Quem conta o consumo de uma chamada a modelo feita por um nó de fluxo (RCHFLW-75).
 *
 * <p>Existe para os adapters de chat ({@code llm-chat}): eles vivem nos módulos
 * {@code archflow-langchain4j-*}, que não enxergam o contador de uso da API — só o
 * {@link ExecutionContext}. Quem hospeda a execução põe o ouvinte no contexto sob {@link #CONTEXT_KEY}
 * ({@code transient}: é infraestrutura, não estado, e não vai para o checkpoint); o adapter relata cada
 * chamada. Sem ouvinte, o adapter não relata nada e a chamada é a mesma de antes.
 */
public interface ModelUsageListener {

    /** Onde o ouvinte viaja no contexto. */
    String CONTEXT_KEY = ExecutionKeys.TRANSIENT_PREFIX + "modelUsage";

    /**
     * Uma chamada a modelo.
     *
     * @param provider     o provedor ({@code openai}, {@code openrouter}…), ou {@code null}
     * @param model        {@code provider/modelo} pedido — a chave com que o operador declara preço —,
     *                     ou {@code null} quando não se sabe
     * @param inputTokens  tokens de entrada informados pelo provedor, ou {@code null}
     * @param outputTokens tokens de saída informados pelo provedor, ou {@code null}
     */
    void onModelCall(String provider, String model, Long inputTokens, Long outputTokens);

    /** O ouvinte desta execução, se quem a hospeda pôs um. */
    static Optional<ModelUsageListener> from(ExecutionContext context) {
        if (context == null) {
            return Optional.empty();
        }
        return context.get(CONTEXT_KEY)
                .filter(ModelUsageListener.class::isInstance)
                .map(ModelUsageListener.class::cast);
    }
}
