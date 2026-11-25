package br.com.archflow.agent.orchestration;

import br.com.archflow.orchestration.Result;
import br.com.archflow.orchestration.Worker;

import java.util.List;

/**
 * {@link Worker} da orquestração dinâmica que recebe a rodada junto com a subtarefa.
 *
 * <p>Existe porque o worker é quem sabe qual componente o roteador escolheu, e o evento de despacho
 * ({@link OrchestrationListener#onDispatched}) precisa dizer em que rodada isso aconteceu (RCHFLW-64).
 * O contrato do núcleo ({@code Worker.apply(item)}) não tem rodada, e mudá-lo mexeria em todo usuário
 * do orquestrador; um {@code ThreadLocal} não serve, porque o fan-out roda cada item noutra thread
 * virtual. O {@link DynamicSupervisor} chama {@link #apply(int, String)} quando o worker o implementa.</p>
 */
public interface SubtaskWorker extends Worker<String, Object> {

    /** Executa {@code subtask}, planejada na rodada {@code round}. */
    Result<Object> apply(int round, String subtask);

    /**
     * Os componentes que este worker não escolhe para nenhuma subtarefa, com o motivo. O supervisor os
     * relata uma vez, no início ({@link OrchestrationListener#onExcludedFromRouting}). Vazio por padrão:
     * um worker que não roteia não exclui nada.
     */
    default List<SubAgentEligibility.Exclusion> excludedFromRouting() {
        return List.of();
    }

    /** Fora do supervisor não há rodada: vale 0. */
    @Override
    default Result<Object> apply(String subtask) {
        return apply(0, subtask);
    }
}
