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
 * Os nós Entrada ({@code input}) e Saída ({@code output}) do designer: repassam a entrada que
 * receberam (RCHFLW-11).
 *
 * <p>Antes eles caíam no catálogo e o fluxo mais simples que o designer sugere — Entrada → Saída —
 * falhava com {@code component not found: input}. Não há o que computar: a Entrada é onde o dado do
 * run entra, a Saída é onde ele sai. Por isso o passo lê a variável {@code input} — a do run ou a que
 * o motor puser ali para o passo — e a devolve como a própria saída, sob o id do nó e de novo em
 * {@code input}, como o {@link ComponentStep} faz: o passo seguinte e as expressões
 * ({@code ${id-do-no}}) veem o mesmo valor.</p>
 *
 * <p>Os campos do painel (campos esperados, JSON Schema) documentam o contrato e não são validados
 * aqui: validar seria inventar uma regra que ninguém definiu. Sem entrada, a saída é vazia — o passo
 * não fabrica um valor.</p>
 */
public final class PassThroughStep implements FlowStep {

    private final String id;
    private final List<StepConnection> connections;

    public PassThroughStep(String id, List<StepConnection> connections) {
        this.id = id;
        this.connections = connections == null ? List.of() : List.copyOf(connections);
    }

    @Override public String getId() { return id; }
    @Override public StepType getType() { return StepType.CUSTOM; }
    @Override public List<StepConnection> getConnections() { return connections; }
    @Override public LLMConfigPatch getLLMPatch() { return LLMConfigPatch.empty(); }

    @Override
    public CompletableFuture<StepResult> execute(ExecutionContext context) {
        long start = System.nanoTime();
        Object input = context.get(ComponentStep.INPUT_KEY).orElse(null);
        // O contexto não guarda null (mapa concorrente): sem entrada, nada é escrito.
        if (input != null) {
            context.set(id, input);
            context.set(ComponentStep.INPUT_KEY, input);
        }
        return CompletableFuture.completedFuture(
                SimpleStepResult.ok(id, input, (System.nanoTime() - start) / 1_000_000));
    }
}
