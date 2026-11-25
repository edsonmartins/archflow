package br.com.archflow.agent.queue;

import java.time.Duration;
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
     * Reivindica a próxima requisição para um consumidor, que a confirma com {@link #complete} depois de
     * registrar o resultado.
     *
     * <p>Por que não basta o {@link #poll()}: ele remove a requisição no ato. Se o processo cair entre
     * retirar e terminar de executar, a invocação some — o "responde sucesso e nada acontece" que a
     * fila deveria evitar. Numa fila durável, o claim marca a linha com um lease; ela só sai da fila no
     * {@code complete}, e um lease vencido (consumidor morto) a devolve a quem reivindicar depois.</p>
     *
     * <p>O padrão delega ao {@code poll()}: uma fila em memória morre com o processo de qualquer jeito, e
     * com um único consumidor retirar e reivindicar são a mesma coisa.</p>
     *
     * @param consumerId quem reivindica (o lease é dele)
     * @param lease      por quanto tempo a requisição fica reservada sem renovação
     */
    default Optional<InvocationRequest> claim(String consumerId, Duration lease) {
        return poll();
    }

    /**
     * Estende o lease de uma requisição em execução. Sem isso, uma execução mais longa que o lease seria
     * reivindicada por outra réplica e rodaria duas vezes.
     *
     * @return {@code false} se o lease já não é deste consumidor (venceu e outro o pegou)
     */
    default boolean renew(String requestId, String consumerId, Duration lease) {
        return true;
    }

    /**
     * Confirma que a requisição terminou (resultado registrado) e a tira da fila de vez. No padrão não
     * há o que fazer: o {@code poll()} já a removeu.
     */
    default void complete(String requestId, String consumerId) {
    }

    /**
     * Devolve à fila, sem confirmar, uma requisição que o consumidor reivindicou e não vai terminar — o
     * desligamento interrompeu a execução. Sem isso, numa fila durável, ela ficaria escondida até o lease
     * vencer (minutos) a cada deploy. No padrão não há o que devolver: o {@code poll()} já a removeu, e
     * uma fila em memória morre com o processo.
     */
    default void release(String requestId, String consumerId) {
    }

    /**
     * A requisição ainda na fila (pendente ou reivindicada e não confirmada), para quem consulta o
     * andamento de uma invocação pelo {@code requestId}.
     */
    default Optional<InvocationRequest> find(String requestId) {
        if (requestId == null) {
            return Optional.empty();
        }
        return pending().stream().filter(r -> requestId.equals(r.requestId())).findFirst();
    }

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
