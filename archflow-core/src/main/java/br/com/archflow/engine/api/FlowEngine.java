package br.com.archflow.engine.api;

import br.com.archflow.engine.exceptions.FlowEngineException;
import br.com.archflow.engine.exceptions.FlowNotFoundException;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowResult;
import br.com.archflow.model.flow.FlowStatus;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Engine principal do archflow, responsável pela execução de fluxos.
 * Integra-se com componentes de IA para processamento.
 *
 * @since 1.0.0
 */
public interface FlowEngine {
    /**
     * Inicia a execução de um fluxo
     *
     * @param flowId identificador do fluxo
     * @param input variáveis iniciais do fluxo
     * @return resultado da execução
     * @throws FlowNotFoundException se o fluxo não for encontrado
     * @throws FlowEngineException se houver erro na execução
     */
    CompletableFuture<FlowResult> startFlow(String flowId, Map<String, Object> input);

    /**
     * Executa um fluxo de forma assíncrona.
     *
     * @param flow fluxo a ser executado
     * @param context contexto inicial de execução
     * @return future com o resultado da execução
     * @throws FlowEngineException se houver erro na execução
     */
    CompletableFuture<FlowResult> execute(Flow flow, ExecutionContext context);

    /**
     * Retoma a execução de um fluxo pausado
     *
     * @param flowId identificador do fluxo
     * @param context contexto atualizado para continuação
     * @return resultado da execução
     * @throws FlowNotFoundException se o fluxo não for encontrado
     */
    CompletableFuture<FlowResult> resumeFlow(String flowId, ExecutionContext context);

    /**
     * Obtém o status atual do fluxo
     *
     * @param flowId identificador do fluxo
     * @return status atual do fluxo
     * @throws FlowNotFoundException se o fluxo não for encontrado
     */
    FlowStatus getFlowStatus(String flowId);

    /**
     * Pausa a execução de um fluxo em andamento.
     *
     * @param flowId identificador do fluxo
     * @throws FlowNotFoundException se o fluxo não for encontrado
     */
    void pause(String flowId);

    /**
     * Cancela a execução de um fluxo em andamento.
     *
     * @param flowId identificador do fluxo
     * @throws FlowNotFoundException se o fluxo não for encontrado
     */
    void cancel(String flowId);

    /**
     * Retorna o conjunto de IDs dos fluxos ativos
     */
    Set<String> getActiveFlows();

    /**
     * Suspende um fluxo aguardando aprovação humana (AWAITING_APPROVAL).
     *
     * @param flowId  ID do fluxo a suspender
     * @param stepId  ID do step que solicita aprovação
     * @param proposal Payload da proposta a ser aprovada
     * @return ID da requisição de aprovação
     */
    default String requestApproval(String flowId, String stepId, Object proposal) {
        throw new UnsupportedOperationException("Human-in-the-loop not supported by this engine");
    }

    /**
     * Submete uma decisão humana para retomar um fluxo suspenso.
     *
     * @param flowId    ID do fluxo suspenso
     * @param requestId ID da requisição de aprovação
     * @param approved  Se a proposta foi aprovada
     * @param editedPayload Payload editado (quando aplicável)
     * @return Future com o resultado da execução retomada
     */
    default CompletableFuture<FlowResult> submitApproval(String flowId, String requestId,
                                                          boolean approved, Object editedPayload) {
        throw new UnsupportedOperationException("Human-in-the-loop not supported by this engine");
    }
}