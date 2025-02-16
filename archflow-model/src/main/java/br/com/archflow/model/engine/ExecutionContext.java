package br.com.archflow.core;

import br.com.archflow.model.engine.ExecutionMetrics;
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
     *
     * @param key chave do valor
     * @return valor opcional
     */
    Optional<Object> get(String key);

    /**
     * Define um valor no contexto.
     *
     * @param key chave do valor
     * @param value valor a ser armazenado
     */
    void set(String key, Object value);

    /**
     * Retorna a memória de chat do LangChain4j.
     * Útil para manter contexto entre interações com LLMs.
     *
     * @return memória do chat
     */
    ChatMemory getChatMemory();

    /**
     * Obtém métricas da execução atual.
     * Inclui tempo de execução, tokens consumidos, etc.
     *
     * @return métricas da execução
     */
    ExecutionMetrics getMetrics();
}