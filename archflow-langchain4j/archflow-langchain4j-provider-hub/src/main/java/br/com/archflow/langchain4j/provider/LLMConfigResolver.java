package br.com.archflow.langchain4j.provider;

import br.com.archflow.model.config.ResolvedLLMConfig;
import dev.langchain4j.model.chat.ChatModel;

/**
 * Resolve a configuração efetiva de LLM percorrendo a cadeia de herança
 * (plataforma → tenant → flow → agente → step) e produz o {@link ChatModel}
 * correspondente.
 *
 * @since 1.0.0
 */
public interface LLMConfigResolver {

    /**
     * Resolve a config efetiva aplicando os patches da requisição sobre o default
     * da plataforma. Operação pura — não cria modelo nem resolve chave.
     */
    ResolvedLLMConfig resolve(LLMResolutionRequest request);

    /**
     * Resolve a config, resolve a chave por tenant e entrega o {@link ChatModel}
     * pronto (delegando ao {@link LLMProviderHub}).
     *
     * @throws IllegalStateException se a config resolvida não tiver provider
     */
    ChatModel resolveModel(LLMResolutionRequest request);
}
