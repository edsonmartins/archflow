package br.com.archflow.agent.queue;

import java.util.List;
import java.util.Optional;

/**
 * Fila de invocação de agentes com controle de recursão.
 *
 * <p>Permite invocações agente-para-agente sem bloquear o chamador.
 * Implementa controle de profundidade máxima de recursão para
 * impedir loops infinitos.
 */
public interface AgentInvocationQueue {

    /**
     * Submete uma requisição de invocação à fila.
     *
     * @param request A requisição de invocação
     * @throws MaxRecursionDepthException se a profundidade máxima for excedida
     */
    void submit(InvocationRequest request);

    /**
     * Obtém a próxima requisição pendente (FIFO).
     *
     * @return A próxima requisição ou empty se a fila estiver vazia
     */
    Optional<InvocationRequest> poll();

    /**
     * Retorna o tamanho atual da fila.
     */
    int size();

    /**
     * Retorna a profundidade máxima de recursão configurada.
     */
    int getMaxRecursionDepth();

    /**
     * Lista todas as requisições pendentes (sem removê-las).
     */
    List<InvocationRequest> pending();

    /**
     * Exceção lançada quando a profundidade máxima de recursão é excedida.
     */
    class MaxRecursionDepthException extends RuntimeException {
        private final int currentDepth;
        private final int maxDepth;

        public MaxRecursionDepthException(int currentDepth, int maxDepth) {
            super(String.format(
                    "Maximum recursion depth exceeded: current=%d, max=%d", currentDepth, maxDepth));
            this.currentDepth = currentDepth;
            this.maxDepth = maxDepth;
        }

        public int getCurrentDepth() { return currentDepth; }
        public int getMaxDepth() { return maxDepth; }
    }
}
