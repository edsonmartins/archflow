package br.com.archflow.engine.core;

import br.com.archflow.model.engine.ExecutionContext;

/**
 * Restaura a memória de chat ao retomar um fluxo suspenso.
 *
 * <p>Implementações carregam o histórico de conversação (ex: do Redis)
 * e injetam no {@link ExecutionContext} antes de acionar o agente.
 *
 * <p>Sem este componente, fluxos retomados após PAUSED ou AWAITING_APPROVAL
 * perdem o histórico de conversa.
 */
@FunctionalInterface
public interface MemoryRestorer {

    /**
     * Restaura a memória do contexto para um fluxo retomado.
     *
     * @param context O contexto de execução a ser restaurado
     */
    void restore(ExecutionContext context);
}
