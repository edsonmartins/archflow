package br.com.archflow.api.flow;

import br.com.archflow.decision.StateTemplate;
import br.com.archflow.engine.execution.ConditionEvaluator;
import br.com.archflow.model.config.LLMConfigPatch;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepConnection;
import br.com.archflow.model.flow.StepResult;
import br.com.archflow.model.flow.StepType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Os nós de controle do designer — {@code condition} e {@code switch} — como passos de verdade.
 *
 * <p>Até aqui eles existiam só na tela: sem tratamento na factory, caíam no catálogo como componentes
 * inexistentes e o passo falhava. A ramificação do motor continua sendo a condição da <b>aresta</b>;
 * este passo só calcula o ramo e o deixa no contexto, onde a aresta o lê:</p>
 * <ul>
 *   <li>{@code condition}: avalia {@code conditionExpression} (a mesma sintaxe das arestas) e grava
 *       {@code branch = "true" | "false"};</li>
 *   <li>{@code switch}: resolve {@code switchExpression} e, se o valor é um dos {@code switchCases}
 *       (um por linha), grava {@code branch = <o caso>}; senão {@code "default"}.</li>
 * </ul>
 *
 * <p>A aresta que sai do nó declara o seu {@code branch}, e a factory a converte na condição
 * ({@code ${id.branch} == 'x'}) — ver {@link DefaultFlowStepFactory}. A entrada ({@code input}) segue
 * intacta: um nó de controle decide o caminho, não transforma o dado.</p>
 *
 * <p>Uma expressão que não se avalia é <b>falsa</b> (modo estrito), e o ramo vira {@code false} com o
 * motivo no {@code error} da saída: seguir pelo "verdadeiro" porque a expressão quebrou seria agir
 * com confiança sem base.</p>
 */
public final class ControlStep implements FlowStep {

    public enum Kind { CONDITION, SWITCH }

    private final String id;
    private final Kind kind;
    private final List<StepConnection> connections;
    private final Map<String, Object> config;

    public ControlStep(String id, Kind kind, List<StepConnection> connections,
                       Map<String, Object> config) {
        this.id = id;
        this.kind = kind;
        this.connections = connections == null ? List.of() : List.copyOf(connections);
        this.config = config == null ? Map.of() : Map.copyOf(config);
    }

    @Override public String getId() { return id; }
    @Override public StepType getType() { return StepType.CUSTOM; }
    @Override public List<StepConnection> getConnections() { return connections; }
    @Override public LLMConfigPatch getLLMPatch() { return LLMConfigPatch.empty(); }

    @Override
    public CompletableFuture<StepResult> execute(ExecutionContext context) {
        long start = System.nanoTime();
        Map<String, Object> saida = new LinkedHashMap<>();
        try {
            if (kind == Kind.CONDITION) {
                String expressao = texto(config.get("conditionExpression"));
                if (expressao == null) {
                    return falha(start, "condition: 'conditionExpression' é obrigatória");
                }
                ConditionEvaluator avaliador = new ConditionEvaluator(true);
                if (!avaliador.isWellFormed(expressao)) {
                    // Malformada não é "verdadeira": o avaliador a trataria como texto não vazio.
                    throw new IllegalArgumentException("expressão malformada: " + expressao);
                }
                boolean resultado = avaliador.evaluate(expressao, context);
                saida.put("expression", expressao);
                saida.put("result", resultado);
                saida.put("branch", String.valueOf(resultado));
            } else {
                String expressao = texto(config.get("switchExpression"));
                if (expressao == null) {
                    return falha(start, "switch: 'switchExpression' é obrigatória");
                }
                Object valor = StateTemplate.resolve(expressao, StateTemplate.fromContext(context, null));
                String texto = valor == null ? "" : valor.toString().trim();
                String escolhido = "default";
                for (String caso : casos()) {
                    if (caso.equalsIgnoreCase(texto)) {
                        escolhido = caso;
                        break;
                    }
                }
                saida.put("expression", expressao);
                saida.put("value", valor);
                saida.put("branch", escolhido);
            }
        } catch (RuntimeException e) {
            saida.put("branch", kind == Kind.CONDITION ? "false" : "default");
            saida.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
        }
        context.set(id, saida);
        return CompletableFuture.completedFuture(SimpleStepResult.ok(id, saida, ms(start)));
    }

    private List<String> casos() {
        Object bruto = config.get("switchCases");
        List<String> casos = new ArrayList<>();
        if (bruto instanceof String s) {
            for (String linha : s.split("\\R")) {
                if (!linha.isBlank()) {
                    casos.add(linha.trim());
                }
            }
        } else if (bruto instanceof List<?> l) {
            l.forEach(o -> casos.add(String.valueOf(o).trim()));
        }
        return casos;
    }

    private CompletableFuture<StepResult> falha(long start, String motivo) {
        return CompletableFuture.completedFuture(SimpleStepResult.failed(id, motivo, ms(start)));
    }

    private static String texto(Object v) {
        return v instanceof String s && !s.isBlank() ? s : null;
    }

    private static long ms(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }
}
