package br.com.archflow.agent.orchestration;

import br.com.archflow.orchestration.BudgetLedger;
import br.com.archflow.orchestration.Goal;
import br.com.archflow.orchestration.Orchestrator;
import br.com.archflow.orchestration.Plan;
import br.com.archflow.orchestration.PlanSpec;
import br.com.archflow.orchestration.Planner;
import br.com.archflow.orchestration.Result;
import br.com.archflow.orchestration.RoundOutcome;
import br.com.archflow.orchestration.Round;
import br.com.archflow.orchestration.Usage;
import br.com.archflow.orchestration.Verdict;
import br.com.archflow.orchestration.Voter;
import br.com.archflow.orchestration.Worker;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * End-to-end supervisor that composes all four orchestration primitives into the
 * dynamic-workflow pattern from the blog: <em>decompose → fan-out → verify →
 * loop-until-dry</em> (ADR-0002). This is the runtime, model-driven counterpart
 * to the static {@code AgentSupervisorTemplate} (which builds an author-time
 * Workflow): here the subtasks, work and verification are decided at runtime.
 *
 * <p>Each round re-plans, dedups already-seen subtasks, fans the fresh ones out
 * to workers, adversarially verifies each finding and accumulates the confirmed
 * ones. The loop converges when a round produces nothing new (the planner stops
 * expanding) or the {@link BudgetLedger} is exhausted — the same ledger drives
 * both the per-item fan-out cap and the loop stop, so cost is bounded end-to-end.
 *
 * <p>O ledger recebe o uso do planejador ({@link MeteredPlanner}) e o que cada worker informa. Antes as
 * rodadas devolviam {@code Usage.ZERO} e o planejador não relatava nada: o ledger ficava em zero e o
 * orçamento nunca parava a execução (RCHFLW-62). Uso não informado vira aviso
 * ({@link OrchestrationListener#onUsageUnknown}), nunca um número inventado.</p>
 */
public final class DynamicSupervisor {

    private final Orchestrator orchestrator;

    public DynamicSupervisor(Orchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    public SupervisorResult run(Goal goal,
                                SupervisorConfig config,
                                Planner<String> planner,
                                Worker<String, Object> worker,
                                Voter<String> voter,
                                BudgetLedger budget) {
        return run(goal, config, planner, worker, voter, budget, OrchestrationListener.NOOP);
    }

    /** As {@link #run}, reporting progress to {@code listener} (design-0004 step 3). */
    public SupervisorResult run(Goal goal,
                                SupervisorConfig config,
                                Planner<String> planner,
                                Worker<String, Object> worker,
                                Voter<String> voter,
                                BudgetLedger budget,
                                OrchestrationListener listener) {

        OrchestrationListener obs = listener == null ? OrchestrationListener.NOOP : listener;
        // Um ledger só, criado aqui quando não vem nenhum: o planejador é debitado nele direto (abaixo), e
        // o fan-out e o loop precisam enxergar esse débito.
        BudgetLedger ledger = budget == null ? BudgetLedger.unlimited() : budget;
        AtomicBoolean exhaustionAnnounced = new AtomicBoolean();
        Set<String> seen = ConcurrentHashMap.newKeySet();
        AtomicInteger roundsRun = new AtomicInteger();
        AtomicInteger dispatched = new AtomicInteger();
        AtomicInteger notDispatched = new AtomicInteger();
        List<SupervisorResult.SubtaskFailure> failures = new CopyOnWriteArrayList<>();
        PlanSpec spec = new PlanSpec(config.decomposePrompt(), config.maxSubtasks());

        Round<List<Object>> round = (confirmed, r) -> {
            roundsRun.set(r);

            // O uso do planejador é debitado AQUI, antes do fan-out, e não devolvido no RoundOutcome: o
            // loop só o debitaria depois da rodada, e o fan-out desta rodada despacharia com um orçamento
            // que o planejamento já gastou (RCHFLW-62).
            AtomicReference<Usage> plannerUsage = new AtomicReference<>();
            Planner<String> metered = (g, s) -> {
                if (planner instanceof MeteredPlanner m) {
                    MeteredPlanner.Decomposition d = m.decomposeMetered(g, s);
                    plannerUsage.set(d.usage());
                    return d.items();
                }
                return planner.decompose(g, s);
            };
            Plan<String> plan = orchestrator.plan(goal, spec, metered);
            if (plannerUsage.get() != null) {
                ledger.charge(plannerUsage.get());
            } else {
                obs.onUsageUnknown(r, null, OrchestrationListener.PLANNER);
            }
            List<String> fresh = new ArrayList<>();
            for (String item : plan.items()) {
                if (seen.add(item)) {
                    fresh.add(item);
                }
            }
            if (fresh.isEmpty()) {
                announceIfExhausted(ledger, r, obs, exhaustionAnnounced);
                obs.onRoundCompleted(r, false, confirmed.size());
                return new RoundOutcome<>(confirmed, false, 0.0, Usage.ZERO);
            }
            obs.onPlanned(r, fresh);
            if (announceIfExhausted(ledger, r, obs, exhaustionAnnounced)) {
                // O planejamento acabou com o orçamento: nada é despachado. Não são falhas — nada foi
                // tentado —, e o loop não começa outra rodada porque o ledger já não tem saldo.
                notDispatched.addAndGet(fresh.size());
                fresh.forEach(item -> obs.onNotDispatched(r, item, OrchestrationListener.BUDGET_EXHAUSTED));
                obs.onRoundCompleted(r, false, confirmed.size());
                return new RoundOutcome<>(confirmed, false, 0.0, Usage.ZERO);
            }

            // O orquestrador recusa um item sem chamar o worker quando o orçamento já acabou
            // ("budget exhausted"). Esse item não foi despachado nem falhou: contá-lo como falha faria
            // um orçamento gasto pelo planner virar "todas as subtarefas falharam". O que separa os
            // dois casos é o item ter chegado ao worker — anotado aqui, sem depender da mensagem.
            Set<String> reached = ConcurrentHashMap.newKeySet();
            Worker<String, Object> tracked = item -> {
                reached.add(item);
                // O worker que sabe a rodada a põe no evento de despacho (RCHFLW-64).
                return worker instanceof SubtaskWorker sw ? sw.apply(r, item) : worker.apply(item);
            };
            List<Result<Object>> results = orchestrator.fanOut(fresh, tracked, ledger);
            dispatched.addAndGet(reached.size());
            int before = confirmed.size();
            // fanOut devolve um resultado por item, na ordem dos itens: é o que liga a falha à subtarefa.
            for (int i = 0; i < results.size(); i++) {
                Result<Object> res = results.get(i);
                String item = i < fresh.size() ? fresh.get(i) : null;
                if (!res.ok() && item != null && !reached.contains(item)) {
                    notDispatched.incrementAndGet();
                    // O motivo é o que o orquestrador deu ("budget exhausted" no DefaultOrchestrator);
                    // não se presume orçamento quando ele não disse.
                    obs.onNotDispatched(r, item, res.error() == null || res.error().isBlank()
                            ? "não despachada, sem motivo informado" : res.error());
                    continue;
                }
                if (!res.ok()) {
                    // A falha era descartada com `continue`, sem evento: a rodada convergia com zero
                    // confirmados e ninguém sabia por quê (RCHFLW-61). Agora vai ao listener e ao
                    // resultado, com o motivo que o worker deu.
                    String error = res.error() == null || res.error().isBlank()
                            ? "falhou sem mensagem" : res.error();
                    failures.add(new SupervisorResult.SubtaskFailure(r, item, error));
                    obs.onWorkerFailed(r, item, error);
                    continue;
                }
                Verdict verdict = orchestrator.verify(String.valueOf(res.value()), voter, config.verifyPolicy());
                obs.onVerified(r, res.value(), verdict.confirmed());
                if (verdict.confirmed()) {
                    confirmed.add(res.value());
                }
            }
            boolean producedNew = confirmed.size() > before;
            announceIfExhausted(ledger, r, obs, exhaustionAnnounced);
            obs.onRoundCompleted(r, producedNew, confirmed.size());
            return new RoundOutcome<>(confirmed, producedNew, 0.0, Usage.ZERO);
        };

        List<Object> confirmed = orchestrator.loopUntil(
                new ArrayList<>(), round, config.convergePolicy(), ledger);
        // Um orçamento que já chega esgotado (teto 0) para o loop antes da 1ª rodada: avisa aqui.
        announceIfExhausted(ledger, roundsRun.get(), obs, exhaustionAnnounced);
        obs.onConverged(roundsRun.get(), confirmed.size());
        return new SupervisorResult(confirmed, roundsRun.get(), dispatched.get(), notDispatched.get(), failures,
                ledger.spentTokens(), exhaustionAnnounced.get());
    }

    /**
     * Avisa, uma vez por execução, que o orçamento acabou. Devolve se ele está esgotado. Só um orçamento
     * com teto se esgota — o ilimitado nunca chega aqui como esgotado.
     */
    private static boolean announceIfExhausted(BudgetLedger ledger, int round, OrchestrationListener obs,
                                               AtomicBoolean announced) {
        if (ledger.hasRemaining()) {
            return false;
        }
        if (announced.compareAndSet(false, true)) {
            obs.onBudgetExhausted(round, ledger.spentTokens(), ledger.budget().maxTokens());
        }
        return true;
    }
}
