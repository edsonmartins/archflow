package br.com.archflow.api.orchestration;

import br.com.archflow.agent.orchestration.OrchestrationTrace;
import br.com.archflow.agent.orchestration.SupervisorResult;

import java.util.List;

/**
 * Result of a dynamic workflow run, including a progress trace (design-0004 step 3).
 *
 * <p>{@code subtasks}, {@code failedCount} e {@code failures} existem para a tela dizer quantas subtarefas
 * falharam e por quê (RCHFLW-61). Só com {@code confirmedCount = 0} ela afirmava "os verificadores
 * rejeitaram todos os resultados" quando nada tinha chegado à verificação.</p>
 *
 * @param subtasks    subtarefas despachadas, somadas em todas as rodadas
 * @param failedCount quantas falharam antes da verificação
 * @param failures    cada falha, com a rodada, a subtarefa e o motivo
 * @param tokensSpent tokens debitados no orçamento — o que o planejador e os agentes informaram
 *                    (RCHFLW-62). Antes o campo "Orçamento (tokens)" não tinha efeito e a resposta não dizia
 *                    nada sobre gasto
 * @param usageUnknown quantas chamadas não informaram uso: com alguma, {@code tokensSpent} é um piso, não o
 *                    total — a tela diz isso em vez de mostrar um número que parece completo
 * @param budgetExhausted o orçamento acabou e a execução parou de despachar subtarefas e rodadas
 * @param notDispatched subtarefas planejadas que não saíram porque o orçamento acabou. Sem isto, um
 *                    planejador que gastava o orçamento todo deixava {@code subtasks = 0} e a tela dizia
 *                    "o planejador não gerou nenhuma subtarefa" — ele tinha gerado
 */
public record DynamicWorkflowResponse(
        List<Object> confirmed,
        int confirmedCount,
        int rounds,
        List<OrchestrationTrace.Entry> trace,
        int subtasks,
        int failedCount,
        List<SupervisorResult.SubtaskFailure> failures,
        long tokensSpent,
        int usageUnknown,
        boolean budgetExhausted,
        int notDispatched) {

    static DynamicWorkflowResponse of(SupervisorResult result, List<OrchestrationTrace.Entry> trace) {
        return new DynamicWorkflowResponse(result.confirmed(), result.confirmedCount(), result.rounds(), trace,
                result.dispatched(), result.failedCount(), result.failures(), result.tokensSpent(),
                OrchestrationTrace.usageUnknownCount(trace), result.budgetExhausted(),
                result.notDispatched());
    }
}
