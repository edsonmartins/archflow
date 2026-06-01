package br.com.archflow.model.engine;

/**
 * Chaves bem-conhecidas do {@link ExecutionContext} para passar config resolvida
 * de LLM para os adapters em runtime.
 *
 * <p>Contrato entre quem RESOLVE (runner/produto, via {@code LLMConfigResolver})
 * e quem CONSOME (adapters de chat). O engine permanece provider-agnostic: ele não
 * resolve modelo — só transporta o resultado por estas chaves.
 *
 * @since 1.0.0
 */
public final class ExecutionKeys {

    private ExecutionKeys() {
    }

    /**
     * Config de LLM já resolvida para o passo atual — valor do tipo
     * {@code br.com.archflow.model.config.ResolvedLLMConfig}. Quando presente,
     * adapters devem usá-la (model/temperature/maxTokens/…) em vez do default
     * estático configurado.
     */
    public static final String LLM_RESOLVED_CONFIG = "archflow.llm.resolved";

    /**
     * Override legado apenas do nome do modelo (String). Mantido para
     * compatibilidade; prefira {@link #LLM_RESOLVED_CONFIG}.
     */
    public static final String LLM_MODEL = "llm.model";
}
