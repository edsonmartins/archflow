package br.com.archflow.agent.orchestration;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@link OrchestrationListener} that accumulates a flat, serializable timeline of
 * a dynamic workflow run — suitable for returning in an API response or feeding a
 * live stream (design-0004 step 3).
 */
public final class OrchestrationTrace implements OrchestrationListener {

    /** Tipo da linha de {@link #onUsageUnknown} — contado na resposta do Labs (RCHFLW-62). */
    public static final String USAGE_UNKNOWN = "usage-unknown";

    /** Tipo da linha de {@link #onNotDispatched}. */
    public static final String NOT_DISPATCHED = "not-dispatched";

    /**
     * Uma linha do trace.
     *
     * @param componentId o componente que atendeu a subtarefa da linha ({@code dispatched}, {@code failed},
     *                    {@code usage-unknown}), ou {@code null} quando a linha não é de uma subtarefa ou
     *                    nenhum agente casou. É a coluna "Agente" do Labs (RCHFLW-64)
     */
    public record Entry(String type, int round, String detail, Boolean confirmed, String componentId) {

        public Entry(String type, int round, String detail, Boolean confirmed) {
            this(type, round, detail, confirmed, null);
        }
    }

    private final List<Entry> entries = new CopyOnWriteArrayList<>();

    /**
     * Quem atendeu cada subtarefa, para a linha "failed" dizer o agente sem depender do texto do erro. O
     * {@code onDispatched} chega antes do {@code onWorkerFailed} da mesma subtarefa: o worker o emite
     * dentro do fan-out, e o supervisor só relata a falha depois que o fan-out volta.
     */
    private final Map<String, String> agentBySubtask = new ConcurrentHashMap<>();

    @Override
    public void onPlanned(int round, List<?> items) {
        entries.add(new Entry("planned", round, items.size() + " subtask(s): " + items, null));
    }

    @Override
    public void onDispatched(int round, Object subtask, String componentId, Double score, boolean success) {
        if (componentId != null) {
            agentBySubtask.put(String.valueOf(subtask), componentId);
        }
        String detail = componentId == null
                ? subtask + " (no agent matched)"
                : subtask + " (score=" + String.format(Locale.ROOT, "%.2f", score == null ? 0.0 : score)
                        + ", success=" + success + ")";
        entries.add(new Entry("dispatched", round, detail, null, componentId));
    }

    @Override
    public void onWorkerFailed(int round, Object subtask, String error) {
        entries.add(new Entry("failed", round, subtask + " — " + error, null,
                agentBySubtask.get(String.valueOf(subtask))));
    }

    @Override
    public void onUsageUnknown(int round, Object subtask, String source) {
        boolean planner = subtask == null;
        String detail = planner
                ? source + ": token usage not reported — not counted against the budget"
                : subtask + ": token usage not reported by " + source + " — not counted against the budget";
        entries.add(new Entry(USAGE_UNKNOWN, round, detail, null, planner ? null : source));
    }

    @Override
    public void onBudgetExhausted(int round, long spentTokens, Long maxTokens) {
        entries.add(new Entry("budget-exhausted", round, "spent=" + spentTokens + " tokens"
                + (maxTokens == null ? "" : ", limit=" + maxTokens)
                + " — no new subtask or round dispatched", null));
    }

    /**
     * Uma linha por subtarefa planejada que não saiu: sem ela o trace listava N planejadas e nada mais
     * sobre elas, e a tela concluía que o planejador não tinha gerado nada (RCHFLW-62).
     */
    @Override
    public void onNotDispatched(int round, Object subtask, String reason) {
        entries.add(new Entry(NOT_DISPATCHED, round, subtask + " — " + reason, null));
    }

    @Override
    public void onVerified(int round, Object finding, boolean confirmed) {
        entries.add(new Entry("verified", round, String.valueOf(finding), confirmed));
    }

    @Override
    public void onRoundCompleted(int round, boolean producedNew, int confirmedSoFar) {
        entries.add(new Entry("round", round,
                "producedNew=" + producedNew + ", confirmed=" + confirmedSoFar, null));
    }

    @Override
    public void onConverged(int rounds, int confirmedTotal) {
        entries.add(new Entry("converged", rounds, "confirmed=" + confirmedTotal, null));
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    /** Quantas linhas avisam uso de tokens não informado — o total gasto é um piso quando há alguma. */
    public static int usageUnknownCount(List<Entry> entries) {
        return entries == null ? 0
                : (int) entries.stream().filter(e -> USAGE_UNKNOWN.equals(e.type())).count();
    }
}
