package br.com.archflow.langchain4j.core.spi;

import dev.langchain4j.model.chat.ChatModel;

/**
 * Contrato opcional para adapters de chat que expõem o {@link ChatModel}
 * subjacente do LangChain4j.
 *
 * <p>Os adapters de chat do archflow implementam {@link LangChainAdapter} e
 * encapsulam o modelo internamente — eles próprios <em>não</em> são um
 * {@code ChatModel}. Componentes de composição (ex.: RAG chain, agents,
 * chains conversacionais) precisam do modelo cru para construir estruturas
 * do LangChain4j como {@code ConversationalChain}. Esta interface expõe o
 * modelo já configurado sem quebrar o encapsulamento do ciclo de vida do
 * adapter (configure/execute/shutdown).
 *
 * <p>Exemplo de uso na composição (RAG):
 * <pre>{@code
 * LangChainAdapter adapter = LangChainRegistry.createAdapter("openai", "chat", props);
 * ChatModel model = (adapter instanceof ChatModelProvider p)
 *         ? p.getChatModel()
 *         : (ChatModel) adapter; // retrocompatibilidade
 * }</pre>
 *
 * @see LangChainAdapter
 */
public interface ChatModelProvider {

    /**
     * Retorna o {@link ChatModel} subjacente, já configurado.
     *
     * @return o modelo de chat configurado, nunca {@code null}
     * @throws IllegalStateException se o adapter ainda não foi configurado
     *                               (ou já sofreu {@code shutdown()})
     */
    ChatModel getChatModel();
}
