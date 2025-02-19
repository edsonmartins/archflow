package br.com.archflow.model.engine;

import br.com.archflow.model.flow.FlowState;
import dev.langchain4j.memory.ChatMemory;

import java.util.Optional;

/**
 * Contexto mantido durante a execução de um fluxo.
 * Mantém estado, variáveis e memória entre os passos.
 *
 * @since 1.0.0
 */
public interface ExecutionContext {
    /**
     * Obtém um valor do contexto.
     */
    Optional<Object> get(String key);

    /**
     * Define um valor no contexto.
     */
    void set(String key, Object value);

    /**
     * Retorna a memória de chat do LangChain4j.
     */
    ChatMemory getChatMemory();

    /**
     * Obtém métricas da execução atual.
     */
    ExecutionMetrics getMetrics();

    /**
     * Obtém o estado atual do fluxo.
     */
    FlowState getState();

    /**
     * Atualiza o estado do fluxo.
     */
    void setState(FlowState state);
}