package br.com.archflow.api.flow;

import br.com.archflow.decision.StateTemplate;
import br.com.archflow.engine.execution.ConditionEvaluator;
import br.com.archflow.model.config.LLMConfigPatch;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepConnection;
import br.com.archflow.model.flow.StepResult;
import br.com.archflow.model.flow.StepType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 *
 * <p>Um {@code ${raiz.campo}} cuja <b>raiz</b> não existe no contexto não muda o ramo (segue
 * {@code false}/{@code default}, como sempre foi — fluxos em produção não mudam de desfecho), mas
 * deixa de ser silencioso: a saída ganha {@code warning} com o caminho não resolvido. O caso típico é
 * escrever o <i>rótulo</i> do nó ({@code ${triagem.route}}) quando o motor grava a saída pelo
 * <i>id</i> ({@code node-1712345678901}); sem o aviso o fluxo termina COMPLETED sem ter feito nada.</p>
 */
public final class ControlStep implements FlowStep {

    public enum Kind { CONDITION, SWITCH }

    private static final Logger log = LoggerFactory.getLogger(ControlStep.class);

    /** Mesmo formato de placeholder do {@link StateTemplate} e das condições das arestas. */
    private static final Pattern VAR = Pattern.compile("\\$\\{([^}]+)}");

    /** Um aviso por passo: o mesmo nó rodando em laço não deve inundar o log. */
    private final AtomicBoolean avisou = new AtomicBoolean();

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
        avisarCaminhosNaoResolvidos(context, saida);
        context.set(id, saida);
        return CompletableFuture.completedFuture(SimpleStepResult.ok(id, saida, ms(start)));
    }

    /**
     * Só diagnostica: não toca em {@code branch} nem em {@code error}. Uma raiz ausente quase sempre é
     * referência ao rótulo em vez do id do nó — e a expressão "funciona", caindo no ramo padrão.
     */
    private void avisarCaminhosNaoResolvidos(ExecutionContext context, Map<String, Object> saida) {
        String expressao = texto(config.get(kind == Kind.CONDITION ? "conditionExpression" : "switchExpression"));
        if (expressao == null || context == null) {
            return;
        }
        Set<String> ausentes = new LinkedHashSet<>();
        Matcher m = VAR.matcher(expressao);
        while (m.find()) {
            String caminho = m.group(1).trim();
            if (!caminho.isEmpty() && !raizExiste(caminho, context)) {
                ausentes.add(caminho);
            }
        }
        if (ausentes.isEmpty()) {
            return;
        }
        List<String> motivos = new ArrayList<>();
        for (String caminho : ausentes) {
            String raiz = caminho.split("\\.", 2)[0];
            motivos.add("caminho não resolvido: " + caminho + " (nenhum passo ou variável com o id '"
                    + raiz + "')");
        }
        String aviso = String.join("; ", motivos)
                + " — a saída de um passo fica sob o id do nó, não sob o rótulo";
        saida.put("warning", aviso);
        if (avisou.compareAndSet(false, true)) {
            log.warn("{} '{}': {}; ramo '{}'", kind == Kind.CONDITION ? "condition" : "switch", id, aviso,
                    saida.get("branch"));
        }
    }

    /**
     * A raiz existe se o caminho inteiro, ou algum prefixo dele, é uma chave do contexto — as duas
     * resoluções ({@link StateTemplate} e {@link ConditionEvaluator}) aceitam {@code a.b} guardado
     * como chave única. Raiz presente com campo ausente não é aviso: pode ser só um valor opcional.
     */
    private static boolean raizExiste(String caminho, ExecutionContext context) {
        if (context.get(caminho).isPresent()) {
            return true;
        }
        int idx = caminho.length();
        while ((idx = caminho.lastIndexOf('.', idx - 1)) > 0) {
            if (context.get(caminho.substring(0, idx)).isPresent()) {
                return true;
            }
        }
        return false;
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
