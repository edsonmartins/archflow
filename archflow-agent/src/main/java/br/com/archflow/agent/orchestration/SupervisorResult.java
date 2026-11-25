package br.com.archflow.agent.orchestration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Outcome of a {@link DynamicSupervisor} run: the confirmed findings and how many rounds ran.
 *
 * <p>Também diz quantas subtarefas foram despachadas e quais falharam, com o motivo (RCHFLW-61). Só com
 * {@code confirmed} vazio não dá para distinguir "os verificadores rejeitaram tudo" de "nada chegou à
 * verificação" — e a tela afirmava a primeira quando era a segunda.</p>
 *
 * @param dispatched    subtarefas entregues ao worker, somadas em todas as rodadas
 * @param notDispatched subtarefas planejadas que o orquestrador não entregou ao worker porque o orçamento
 *                      já tinha acabado — não são falhas: nada foi tentado
 * @param failures      as que falharam antes da verificação, na ordem em que voltaram
 * @param tokensSpent   tokens debitados no orçamento (planejador + workers). É o que foi INFORMADO: uso
 *                      não informado não entra, e o trace avisa cada caso (RCHFLW-62)
 * @param budgetExhausted o orçamento acabou e a execução parou de despachar por isso
 */
public record SupervisorResult(List<Object> confirmed, int rounds, int dispatched, int notDispatched,
                               List<SubtaskFailure> failures, long tokensSpent, boolean budgetExhausted) {

    /** Uma subtarefa que não chegou à verificação e por quê. */
    public record SubtaskFailure(int round, String subtask, String error) {
    }

    public SupervisorResult {
        confirmed = List.copyOf(confirmed);
        failures = failures == null ? List.of() : List.copyOf(failures);
    }

    /** Sem contagem de despacho nem falhas — para quem só tem o resultado confirmado. */
    public SupervisorResult(List<Object> confirmed, int rounds) {
        this(confirmed, rounds, 0, 0, List.of());
    }

    /** Sem subtarefas deixadas de fora pelo orçamento. */
    public SupervisorResult(List<Object> confirmed, int rounds, int dispatched, List<SubtaskFailure> failures) {
        this(confirmed, rounds, dispatched, 0, failures);
    }

    /** Sem gasto medido nem orçamento esgotado. */
    public SupervisorResult(List<Object> confirmed, int rounds, int dispatched, int notDispatched,
                            List<SubtaskFailure> failures) {
        this(confirmed, rounds, dispatched, notDispatched, failures, 0L, false);
    }

    public int confirmedCount() {
        return confirmed.size();
    }

    public int failedCount() {
        return failures.size();
    }

    /**
     * Houve subtarefa despachada e nenhuma chegou à verificação. Uma orquestração assim não produziu
     * nada por falha, não por rejeição — quem a executa não pode reportá-la como sucesso vazio.
     */
    public boolean allSubtasksFailed() {
        return dispatched > 0 && failures.size() >= dispatched;
    }

    /**
     * Os motivos distintos, com quantas subtarefas falharam por cada um ({@code "2× motivo; 1× outro"}),
     * na ordem em que apareceram. Oito subtarefas recusadas pelo mesmo motivo viram uma linha, não oito.
     */
    public String failureSummary() {
        Map<String, Long> byError = failures.stream().collect(Collectors.groupingBy(
                SubtaskFailure::error, LinkedHashMap::new, Collectors.counting()));
        return byError.entrySet().stream()
                .map(e -> e.getValue() + "× " + e.getKey())
                .collect(Collectors.joining("; "));
    }
}
