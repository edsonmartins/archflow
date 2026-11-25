package br.com.archflow.api.flow;

import br.com.archflow.agent.orchestration.OrchestrationListener;
import br.com.archflow.model.flow.ExecutionPath;
import br.com.archflow.model.flow.FlowState;
import br.com.archflow.model.flow.PathStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Materializes a dynamic workflow's rounds/subtasks as {@link ExecutionPath}s on
 * the flow's {@link FlowState} (design-0005 step 4), so the dynamic tree is
 * persisted and inspectable (e.g. via the execution status endpoint / canvas).
 * Wraps a {@code delegate} listener (typically the streaming one) so a single
 * listener both streams and materializes.
 *
 * <p>The supervisor drives rounds sequentially, so path mutation here is not
 * concurrent; collections are still thread-safe to be defensive. A exceção é
 * {@link #onDispatched}, que vem das threads do fan-out — ver lá.
 */
public final class MaterializingOrchestrationListener implements OrchestrationListener {

    private final FlowState state;
    private final OrchestrationListener delegate;
    private final Map<Integer, ExecutionPath> roundPaths = new ConcurrentHashMap<>();

    public MaterializingOrchestrationListener(FlowState state, OrchestrationListener delegate) {
        this.state = state;
        this.delegate = delegate == null ? OrchestrationListener.NOOP : delegate;
    }

    private List<ExecutionPath> paths() {
        if (state.getExecutionPaths() == null) {
            state.setExecutionPaths(new CopyOnWriteArrayList<>());
        }
        return state.getExecutionPaths();
    }

    @Override
    public void onPlanned(int round, List<?> items) {
        delegate.onPlanned(round, items);
        List<ExecutionPath> branches = new ArrayList<>();
        for (Object item : items) {
            branches.add(ExecutionPath.builder()
                    .pathId(String.valueOf(item))
                    .status(PathStatus.RUNNING)
                    .build());
        }
        ExecutionPath roundPath = ExecutionPath.builder()
                .pathId("round-" + round)
                .status(PathStatus.RUNNING)
                .parallelBranches(branches)
                .build();
        roundPaths.put(round, roundPath);
        paths().add(roundPath);
    }

    /**
     * O ramo da subtarefa guarda quem a atendeu ({@code componentId}, {@code score}, {@code success}) na
     * metadata, para o escopo de sub-agentes ({@code allowedComponents}) ser auditável pelo estado
     * persistido (RCHFLW-64). Sem agente casado, {@code componentId} fica {@code null} — registrado assim
     * mesmo: "ninguém atendeu" também é a resposta da auditoria.
     *
     * <p>Este evento vem das threads do fan-out, em paralelo; cada uma mexe só no ramo da sua subtarefa, e
     * a troca do mapa é sincronizada no ramo.</p>
     */
    @Override
    public void onDispatched(int round, Object subtask, String componentId, Double score, boolean success) {
        delegate.onDispatched(round, subtask, componentId, score, success);
        ExecutionPath branch = branchOf(round, subtask);
        if (branch == null) {
            return;
        }
        synchronized (branch) {
            Map<String, Object> metadata = metadataOf(branch);
            metadata.put("componentId", componentId);
            metadata.put("score", score);
            metadata.put("success", success);
            branch.setMetadata(metadata);
        }
    }

    /** O ramo da subtarefa fica FAILED, com o motivo na metadata (RCHFLW-61). */
    @Override
    public void onWorkerFailed(int round, Object subtask, String error) {
        delegate.onWorkerFailed(round, subtask, error);
        ExecutionPath branch = branchOf(round, subtask);
        if (branch == null || branch.getStatus() == PathStatus.FAILED) {
            return;
        }
        synchronized (branch) {
            branch.setStatus(PathStatus.FAILED);
            Map<String, Object> metadata = metadataOf(branch);
            metadata.put("error", error);
            branch.setMetadata(metadata);
        }
    }

    /**
     * O ramo de uma subtarefa que não saiu ganha {@code dispatched=false} e o motivo em
     * {@code notDispatched}. Sem isso o fechamento da rodada o marcava COMPLETED sem mais nada e o estado
     * durável dizia que um trabalho que nunca rodou tinha terminado. O status continua sendo o de rodada
     * fechada — não há um "não executado" em {@link PathStatus}, e inventar um mudaria um enum que outros
     * leitores do estado conhecem —; quem audita lê a metadata.
     */
    @Override
    public void onNotDispatched(int round, Object subtask, String reason) {
        delegate.onNotDispatched(round, subtask, reason);
        ExecutionPath branch = branchOf(round, subtask);
        if (branch == null) {
            return;
        }
        synchronized (branch) {
            Map<String, Object> metadata = metadataOf(branch);
            metadata.put("dispatched", false);
            metadata.put("notDispatched", reason);
            branch.setMetadata(metadata);
        }
    }

    @Override
    public void onUsageUnknown(int round, Object subtask, String source) {
        delegate.onUsageUnknown(round, subtask, source);
    }

    @Override
    public void onBudgetExhausted(int round, long spentTokens, Long maxTokens) {
        delegate.onBudgetExhausted(round, spentTokens, maxTokens);
        // Na rodada em que acabou: um passo que termina com [] por orçamento diz isso no estado, não só no
        // SSE. Um teto que já chega esgotado não tem rodada (round 0) — nada a marcar.
        ExecutionPath roundPath = roundPaths.get(round);
        if (roundPath == null) {
            return;
        }
        synchronized (roundPath) {
            Map<String, Object> metadata = metadataOf(roundPath);
            metadata.put("budgetExhausted", true);
            metadata.put("spentTokens", spentTokens);
            metadata.put("maxTokens", maxTokens);
            roundPath.setMetadata(metadata);
        }
    }

    private ExecutionPath branchOf(int round, Object subtask) {
        ExecutionPath roundPath = roundPaths.get(round);
        if (roundPath == null || roundPath.getParallelBranches() == null) {
            return null;
        }
        String pathId = String.valueOf(subtask);
        for (ExecutionPath branch : roundPath.getParallelBranches()) {
            if (pathId.equals(branch.getPathId())) {
                return branch;
            }
        }
        return null;
    }

    /** Cópia mutável: a metadata pode ter vindo imutável (ou de outra rodada de eventos). */
    private static Map<String, Object> metadataOf(ExecutionPath branch) {
        return branch.getMetadata() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(branch.getMetadata());
    }

    @Override
    public void onVerified(int round, Object finding, boolean confirmed) {
        delegate.onVerified(round, finding, confirmed);
    }

    @Override
    public void onRoundCompleted(int round, boolean producedNew, int confirmedSoFar) {
        delegate.onRoundCompleted(round, producedNew, confirmedSoFar);
        ExecutionPath roundPath = roundPaths.get(round);
        if (roundPath != null) {
            roundPath.setStatus(PathStatus.COMPLETED);
            if (roundPath.getParallelBranches() != null) {
                // A subtarefa que falhou continua FAILED: fechar a rodada não a transforma em sucesso.
                roundPath.getParallelBranches().stream()
                        .filter(b -> b.getStatus() != PathStatus.FAILED)
                        .forEach(b -> b.setStatus(PathStatus.COMPLETED));
            }
        }
    }

    @Override
    public void onConverged(int rounds, int confirmedTotal) {
        delegate.onConverged(rounds, confirmedTotal);
    }
}
