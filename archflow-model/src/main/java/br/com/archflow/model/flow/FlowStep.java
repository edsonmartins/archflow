package br.com.archflow.model.flow;


import br.com.archflow.model.engine.ExecutionContext;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Representa um passo individual dentro de um fluxo.
 * Cada passo pode ser uma Chain, Agent ou Tool do LangChain4j.
 *
 * @since 1.0.0
 */
public interface FlowStep {
    /**
     * Retorna o identificador único do passo.
     *
     * @return identificador do passo
     */
    String getId();

    /**
     * Retorna o tipo do passo (chain, agent, tool).
     *
     * @return tipo do passo
     */
    StepType getType();

    /**
     * Retorna as conexões deste passo com outros passos no fluxo.
     * Inclui tanto conexões de sucesso quanto de erro.
     *
     * @return lista de conexões do passo
     */
    List<StepConnection> getConnections();

    /**
     * Executa o passo usando o contexto fornecido.
     * A execução é assíncrona e pode envolver chamadas a LLMs.
     *
     * @param context contexto de execução
     * @return future com o resultado da execução
     */
    CompletableFuture<StepResult> execute(ExecutionContext context);
}