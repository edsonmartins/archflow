package br.com.archflow.core.engine;

import br.com.archflow.core.ExecutionContext;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowResult;

import java.util.concurrent.CompletableFuture;

/**
 * Engine principal do archflow, responsável pela execução de fluxos.
 * Integra-se com o Apache Camel para orquestração e com o LangChain4j
 * para processamento de IA.
 *
 * @since 1.0.0
 */
public interface FlowEngine {
    /**
     * Executa um fluxo de forma assíncrona.
     *
     * @param flow fluxo a ser executado
     * @param context contexto inicial de execução
     * @return future com o resultado da execução
     * @throws br.com.archflow.core.exceptions.FlowEngineException se houver erro na execução
     */
    CompletableFuture<FlowResult> execute(Flow flow, ExecutionContext context);

    /**
     * Pausa a execução de um fluxo em andamento.
     * O estado atual é preservado para posterior continuação.
     *
     * @param flowId identificador do fluxo
     * @throws br.com.archflow.core.exceptions.FlowNotFoundException se o fluxo não for encontrado
     */
    void pause(String flowId);

    /**
     * Retoma a execução de um fluxo previamente pausado.
     *
     * @param flowId identificador do fluxo
     * @param context contexto atualizado para continuação
     * @throws br.com.archflow.core.exceptions.FlowNotFoundException se o fluxo não for encontrado
     */
    void resume(String flowId, ExecutionContext context);

    /**
     * Cancela a execução de um fluxo em andamento.
     *
     * @param flowId identificador do fluxo
     * @throws br.com.archflow.core.exceptions.FlowNotFoundException se o fluxo não for encontrado
     */
    void cancel(String flowId);
}