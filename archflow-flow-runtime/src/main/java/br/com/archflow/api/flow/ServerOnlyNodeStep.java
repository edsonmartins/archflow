package br.com.archflow.api.flow;

import br.com.archflow.model.config.LLMConfigPatch;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepConnection;
import br.com.archflow.model.flow.StepResult;
import br.com.archflow.model.flow.StepType;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Um nó que precisa do servidor archflow, materializado onde não há servidor (RCHFLW-37): no runner
 * standalone e no SDK embarcado, quando a fábrica não recebeu um {@link ServerNodes}.
 *
 * <p>Como o {@link UnsupportedNodeStep}, existe para o fluxo falhar <b>no início</b>: o
 * {@link ExecutableNodesValidator} encontra este passo antes do primeiro passo e recusa o fluxo com
 * "o nó X precisa do servidor archflow (motivo)". Rodar os passos anteriores — chamadas pagas ao
 * modelo — para só então parar no nó seria o pior dos dois mundos. O {@link #execute} falha com a mesma
 * mensagem para quem executar o passo por fora do motor.</p>
 */
public final class ServerOnlyNodeStep implements FlowStep {

    private final String id;
    private final String label;
    private final ServerCapability capability;
    private final String detail;
    private final List<StepConnection> connections;

    /**
     * @param label o nome do nó no designer, ou {@code null}
     */
    public ServerOnlyNodeStep(String id, String label, ServerCapability capability,
                              List<StepConnection> connections) {
        this(id, label, capability, null, connections);
    }

    /**
     * @param detail o que, neste nó, falta fora do servidor (o provedor, o backend), ou {@code null} — vai na
     *               mensagem depois do motivo da {@link ServerCapability}
     */
    public ServerOnlyNodeStep(String id, String label, ServerCapability capability, String detail,
                              List<StepConnection> connections) {
        this.id = id;
        this.label = label == null || label.isBlank() ? null : label.trim();
        this.capability = Objects.requireNonNull(capability, "capability");
        this.detail = detail == null || detail.isBlank() ? null : detail.trim();
        this.connections = connections == null ? List.of() : List.copyOf(connections);
    }

    @Override public String getId() { return id; }
    @Override public StepType getType() { return StepType.TOOL; }
    @Override public List<StepConnection> getConnections() { return connections; }
    @Override public LLMConfigPatch getLLMPatch() { return LLMConfigPatch.empty(); }

    public ServerCapability capability() {
        return capability;
    }

    /** Como o nó aparece na mensagem: o nome do designer com o id, ou só o id. */
    public String describe() {
        return label == null ? id : "\"" + label + "\" (" + id + ")";
    }

    /** A mensagem da recusa — a mesma no passo e na validação do início da execução. */
    public String message() {
        return "o nó " + describe() + " precisa do servidor archflow (" + capability.reason()
                + (detail == null ? "" : " — " + detail) + ")";
    }

    @Override
    public CompletableFuture<StepResult> execute(ExecutionContext context) {
        return CompletableFuture.completedFuture(SimpleStepResult.failed(id, message(), 0));
    }
}
