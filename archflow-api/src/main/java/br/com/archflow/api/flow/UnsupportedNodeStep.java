package br.com.archflow.api.flow;

import br.com.archflow.model.config.LLMConfigPatch;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepConnection;
import br.com.archflow.model.flow.StepResult;
import br.com.archflow.model.flow.StepType;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Um nó de um tipo que o motor ainda não executa ({@link NodesWithoutExecution}).
 *
 * <p>Existe para o fluxo falhar <b>no início</b>, dizendo qual tipo falta, em vez de rodar até o nó e
 * cair num {@code component not found}: o {@link ExecutableNodesValidator} encontra este passo na
 * validação que o motor faz antes do primeiro passo. O {@link #execute} falha com a mesma mensagem
 * para quem executar o passo por fora do motor.</p>
 *
 * <p>O documento não muda: o nó continua salvo como estava, e o fluxo volta a rodar quando o tipo
 * ganhar execução.</p>
 */
public final class UnsupportedNodeStep implements FlowStep {

    private final String id;
    private final String kind;
    private final String label;
    private final List<StepConnection> connections;

    /**
     * @param kind  o tipo do nó como o usuário o reconhece (o {@code componentId})
     * @param label o nome do nó no designer, ou {@code null}
     */
    public UnsupportedNodeStep(String id, String kind, String label, List<StepConnection> connections) {
        this.id = id;
        this.kind = kind;
        this.label = label == null || label.isBlank() ? null : label.trim();
        this.connections = connections == null ? List.of() : List.copyOf(connections);
    }

    @Override public String getId() { return id; }
    @Override public StepType getType() { return StepType.TOOL; }
    @Override public List<StepConnection> getConnections() { return connections; }
    @Override public LLMConfigPatch getLLMPatch() { return LLMConfigPatch.empty(); }

    public String kind() {
        return kind;
    }

    /** Como o nó aparece na mensagem: o nome do designer com o id, ou só o id. */
    public String describe() {
        return label == null ? id : "\"" + label + "\" (" + id + ")";
    }

    @Override
    public CompletableFuture<StepResult> execute(ExecutionContext context) {
        return CompletableFuture.completedFuture(
                SimpleStepResult.failed(id, NodesWithoutExecution.message(kind), 0));
    }
}
