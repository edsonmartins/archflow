package br.com.archflow.agent.orchestration;

import java.util.List;

/**
 * Observes a {@link DynamicSupervisor} run so callers can trace, stream or
 * persist its progress (ADR-0002 D7 / design-0004 step 3) without coupling the
 * supervisor to any UI/event-bus. All methods default to no-op.
 */
public interface OrchestrationListener {

    /** A planning round produced these fresh (not-yet-seen) subtasks. */
    default void onPlanned(int round, List<?> items) {}

    /**
     * Uma subtarefa falhou antes de chegar à verificação (nenhum agente casou, o agente recusou a
     * tarefa, lançou exceção, o orçamento acabou...). Antes a falha era descartada em silêncio e a
     * orquestração terminava com zero confirmados sem dizer por quê (RCHFLW-61).
     */
    default void onWorkerFailed(int round, Object subtask, String error) {}

    /**
     * O roteador escolheu (ou não) um componente para a subtarefa e ela foi executada. Emitido em todo
     * caminho do worker, inclusive "nenhum agente casou" ({@code componentId} e {@code score}
     * {@code null}). Antes o componente escolhido era descartado no sucesso e o escopo de sub-agentes
     * ({@code allowedComponents}) não era auditável pelo trace, pelo SSE nem pelo estado (RCHFLW-64).
     *
     * <p>Chamado pelas threads do fan-out, em paralelo: quem implementa precisa ser thread-safe.</p>
     *
     * @param score   a confiança do roteador em [0, 1], ou {@code null} quando nenhum agente casou
     * @param success se o componente devolveu um resultado utilizável
     */
    default void onDispatched(int round, Object subtask, String componentId, Double score, boolean success) {}

    /**
     * Uma chamada que gasta tokens não informou quanto gastou — o planejador ({@code subtask} nulo,
     * {@code source} = {@link #PLANNER}) ou o componente {@code source} ao executar {@code subtask}.
     * Esse gasto não entra no orçamento: não se inventa um número, avisa-se (RCHFLW-62).
     */
    default void onUsageUnknown(int round, Object subtask, String source) {}

    /**
     * O orçamento de tokens acabou nesta rodada: nenhuma subtarefa nova é despachada e nenhuma rodada
     * nova começa. Emitido uma vez por execução. Antes a parada era um {@code break} silencioso
     * (RCHFLW-62).
     *
     * @param maxTokens o teto configurado, ou {@code null} se o teto atingido foi o de custo
     */
    default void onBudgetExhausted(int round, long spentTokens, Long maxTokens) {}

    /**
     * Uma subtarefa planejada não foi entregue ao worker — hoje só por orçamento esgotado, no planejamento
     * ou no meio do fan-out. Não é falha (nada foi tentado) nem sucesso: sem este evento o ramo dela
     * fechava COMPLETED no estado durável e a tela dizia "o planejador não gerou nenhuma subtarefa"
     * quando ele tinha gerado (RCHFLW-62).
     *
     * @param reason o motivo dado pelo orquestrador ({@link #BUDGET_EXHAUSTED})
     */
    default void onNotDispatched(int round, Object subtask, String reason) {}

    /** A finding was verified (confirmed or refuted). */
    default void onVerified(int round, Object finding, boolean confirmed) {}

    /** A round finished: whether it produced anything new and the running confirmed count. */
    default void onRoundCompleted(int round, boolean producedNew, int confirmedSoFar) {}

    /** The loop converged (no new work, quality met, or budget exhausted). */
    default void onConverged(int rounds, int confirmedTotal) {}

    /** {@code source} de {@link #onUsageUnknown} quando quem não informou o uso foi o planejador. */
    String PLANNER = "planner";

    /**
     * Motivo de {@link #onNotDispatched} quando o orçamento acabou — o mesmo texto com que o
     * {@code DefaultOrchestrator} recusa o item, para as duas origens dizerem a mesma coisa.
     */
    String BUDGET_EXHAUSTED = "budget exhausted";

    /** No-op listener. */
    OrchestrationListener NOOP = new OrchestrationListener() {};
}
