package br.com.archflow.api.flow;

import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.engine.ExecutionKeys;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepConnection;
import br.com.archflow.model.flow.StepResult;
import br.com.archflow.model.flow.StepType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Step {@code APPROVAL}: o produtor que faltava para o gate human-in-the-loop.
 *
 * <p>O {@link FlowEngine} já implementava um gate durável — {@code requestApproval}
 * marca {@code AWAITING_APPROVAL}, persiste o estado e suspende a travessia;
 * {@code submitApproval} valida o id e retoma a partir do estado persistido,
 * sobrevivendo a restart. Só que <b>nada chamava {@code requestApproval}</b>:
 * nenhum tipo de step, nenhum endpoint. O gate era inalcançável.
 *
 * <p>Este step fecha esse circuito. Ele monta a proposta a partir do config do nó
 * ({@code proposal}) ou, na ausência, da saída do step anterior (a variável
 * {@code input}), e chama {@code requestApproval}. A suspensão é cooperativa: o
 * step conclui normalmente e a travessia para na iteração seguinte, ao ver o
 * pedido de pausa — por isso o step é marcado COMPLETED e não reexecuta na
 * retomada.
 *
 * <p>Após a decisão, os steps seguintes leem
 * {@link ExecutionKeys#APPROVAL_DECISION} (para ramificar com
 * {@code ${__archflow.approvalDecision} == APPROVED}) e
 * {@link ExecutionKeys#APPROVAL_PAYLOAD} (a proposta editada pelo humano, se houve).
 *
 * <p>O engine chega por {@link Supplier} porque o grafo de beans vai
 * {@code FlowEngine → FlowRepository → WorkflowDeserializer → FlowStepFactory}:
 * uma injeção direta fecharia o ciclo.
 */
public final class HumanApprovalStep implements FlowStep {

    private static final Logger log = LoggerFactory.getLogger(HumanApprovalStep.class);

    private final String id;
    private final List<StepConnection> connections;
    private final Map<String, Object> config;
    private final Supplier<FlowEngine> engineSupplier;

    public HumanApprovalStep(String id, List<StepConnection> connections,
                             Map<String, Object> config, Supplier<FlowEngine> engineSupplier) {
        this.id = id;
        this.connections = connections == null ? List.of() : List.copyOf(connections);
        this.config = config == null ? Map.of() : config;
        this.engineSupplier = engineSupplier;
    }

    @Override public String getId() { return id; }
    @Override public StepType getType() { return StepType.APPROVAL; }
    @Override public List<StepConnection> getConnections() { return connections; }

    @Override
    public CompletableFuture<StepResult> execute(ExecutionContext context) {
        long start = System.nanoTime();

        // Retomada: submitApproval já gravou a decisão e o engine reexecuta a
        // travessia. Este step não pode pedir aprovação de novo — seria um laço
        // infinito de gates. Ele apenas repassa a decisão adiante.
        String decision = context.get(ExecutionKeys.APPROVAL_DECISION)
                .map(String::valueOf).orElse(null);
        String pendingRequest = context.get(ExecutionKeys.APPROVAL_REQUEST_ID)
                .map(String::valueOf).orElse("");
        if (decision != null && pendingRequest.isBlank()) {
            log.info("Step {} retomado com decisão {}", id, decision);
            return CompletableFuture.completedFuture(
                    SimpleStepResult.ok(id, Map.of("decision", decision), elapsedMs(start)));
        }

        FlowEngine engine = engineSupplier != null ? engineSupplier.get() : null;
        if (engine == null) {
            return CompletableFuture.completedFuture(SimpleStepResult.failed(
                    id, "APPROVAL step requires a FlowEngine", elapsedMs(start)));
        }

        Object proposal = config.containsKey("proposal")
                ? config.get("proposal")
                : context.get(ComponentStep.INPUT_KEY).orElse(null);

        Object description = config.get("description");
        if (description != null) {
            context.set(ExecutionKeys.APPROVAL_DESCRIPTION, String.valueOf(description));
        }

        String flowId = context.getState() != null ? context.getState().getFlowId() : null;
        if (flowId == null || flowId.isBlank()) {
            return CompletableFuture.completedFuture(SimpleStepResult.failed(
                    id, "APPROVAL step requires a flow state with an id", elapsedMs(start)));
        }

        try {
            String requestId = engine.requestApproval(flowId, id, proposal);
            log.info("Fluxo {} suspenso para aprovação humana no step {} (requestId={})",
                    flowId, id, requestId);
            return CompletableFuture.completedFuture(
                    SimpleStepResult.ok(id, Map.of("requestId", requestId), elapsedMs(start)));
        } catch (RuntimeException e) {
            log.error("Falha ao solicitar aprovação no step {} do fluxo {}", id, flowId, e);
            return CompletableFuture.completedFuture(SimpleStepResult.failed(
                    id, "Failed to request approval: " + e.getMessage(), elapsedMs(start)));
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
