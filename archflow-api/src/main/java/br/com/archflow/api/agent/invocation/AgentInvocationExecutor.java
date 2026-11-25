package br.com.archflow.api.agent.invocation;

import br.com.archflow.agent.queue.InvocationRequest;

/**
 * Executa uma invocação retirada da fila e devolve a saída do agente. Lançar é falhar: o consumidor
 * grava {@code FAILED} com a mensagem.
 */
@FunctionalInterface
public interface AgentInvocationExecutor {

    Object execute(InvocationRequest request) throws Exception;
}
