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
import java.util.function.Function;
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
 * <p><b>Referência sem valor falha o passo (RCHFLW-123).</b> Um {@code ${…}} que não resolve na execução
 * não escolhe ramo: o passo falha nomeando o nó, o campo e a referência. Antes ele seguia
 * {@code false}/{@code default}, e o portão "acima de 500 pede aprovação" ({@code ${entrada.valor} > 500})
 * com a entrada em texto livre ia para o ramo do "aprova sozinho" — o LLM confirmou um reembolso de
 * R$ 900 sem ninguém aprovar. Na condição vale a lógica de três valores do
 * {@link ConditionEvaluator#evaluateRequiringValues}: a referência que falta só derruba o passo quando o
 * resultado depende dela. {@code ${input}} sozinho vale {@code ""} sem dado (RCHFLW-101).</p>
 *
 * <p><b>Comparação de ordem sem número não decide.</b> {@code ${valor} > 500} com o valor «R$ 1.200» ou
 * «1.200,00» (o que um LLM em pt-BR devolve) caía na ordem do texto — «R» depois de «5», «1» antes — e escolhia
 * um ramo ao acaso. Agora o passo falha dizendo o valor: um portão de valor não pode decidir por ordem
 * alfabética.</p>
 *
 * <p><b>Switch sem caso nem default falha (RCHFLW-135).</b> O valor que não casa com nenhum caso, sem aresta
 * {@code default}, terminava COMPLETED sem chegar a saída nenhuma. Agora falha dizendo o valor e os casos. O
 * caso declarado e casado que não tem aresta continua concluindo: é o "termina aqui" deliberado, como a
 * condition com só a aresta do {@code true}.</p>
 *
 * <p><b>Compatibilidade.</b> {@code archflow.flow.lenient-conditions=true} ({@code lenient}) devolve o
 * comportamento antigo: a referência sem valor segue {@code false}/{@code default}, só com o
 * {@code warning} do caminho não resolvido na saída, e o switch sem saída conclui.</p>
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
    /** O rótulo do nó, para a falha dizer qual nó o usuário vê no canvas; {@code null} sem rótulo. */
    private final String label;
    /** {@code archflow.flow.lenient-conditions}: a referência sem valor segue o ramo padrão, como antes. */
    private final boolean lenient;
    /**
     * {@code archflow.flow.strict-conditions}, o mesmo do motor: com ele a aresta cuja condição não se avalia
     * não é seguida. O "sem saída" do switch lê as arestas com a mesma regra, para o veredito ser o do motor.
     */
    private final boolean strictEdges;

    public ControlStep(String id, Kind kind, List<StepConnection> connections,
                       Map<String, Object> config) {
        this(id, kind, connections, config, null, false, false);
    }

    /**
     * @param label   o rótulo do nó no documento, ou {@code null}
     * @param lenient {@code true} restaura o ramo padrão para a referência sem valor e o switch sem saída
     */
    public ControlStep(String id, Kind kind, List<StepConnection> connections,
                       Map<String, Object> config, String label, boolean lenient) {
        this(id, kind, connections, config, label, lenient, false);
    }

    /**
     * @param strictEdges o {@code archflow.flow.strict-conditions} com que o motor percorre as arestas
     */
    public ControlStep(String id, Kind kind, List<StepConnection> connections,
                       Map<String, Object> config, String label, boolean lenient, boolean strictEdges) {
        this.id = id;
        this.kind = kind;
        this.connections = connections == null ? List.of() : List.copyOf(connections);
        this.config = config == null ? Map.of() : Map.copyOf(config);
        this.label = label == null || label.isBlank() ? null : label.trim();
        this.lenient = lenient;
        this.strictEdges = strictEdges;
    }

    @Override public String getId() { return id; }
    @Override public StepType getType() { return StepType.CUSTOM; }
    @Override public List<StepConnection> getConnections() { return connections; }
    @Override public LLMConfigPatch getLLMPatch() { return LLMConfigPatch.empty(); }

    /** Condição ou switch — o {@link ExecutableNodesValidator} confere a expressão antes do 1º passo. */
    Kind kind() { return kind; }

    /** A chave do campo da expressão no nó, como o designer a grava. */
    String expressionField() { return kind == Kind.CONDITION ? "conditionExpression" : "switchExpression"; }

    /** A expressão do nó, ou {@code null} quando falta (aí quem recusa é o próprio passo, ao executar). */
    String expression() { return texto(config.get(expressionField())); }

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
                boolean resultado;
                if (lenient) {
                    resultado = avaliador.evaluate(expressao, context);
                } else {
                    ConditionEvaluator.Verdict veredito = avaliador.evaluateRequiringValues(expressao, context);
                    if (!veredito.unresolved().isEmpty()) {
                        return falha(start, semValor(veredito.unresolved()));
                    }
                    if (!veredito.decided()) {
                        return falha(start, semNumero(veredito.notNumbers()));
                    }
                    resultado = veredito.result();
                }
                saida.put("expression", expressao);
                saida.put("result", resultado);
                saida.put("branch", String.valueOf(resultado));
            } else {
                String expressao = texto(config.get("switchExpression"));
                if (expressao == null) {
                    return falha(start, "switch: 'switchExpression' é obrigatória");
                }
                Set<String> ausentes = new LinkedHashSet<>();
                Object valor = StateTemplate.resolve(expressao, lookup(context, ausentes));
                if (!ausentes.isEmpty()) {
                    return falha(start, semValor(List.copyOf(ausentes)));
                }
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
        // Depois do context.set: as arestas do ramo leem ${id.branch}, e é por elas que se sabe se há saída. Só o
        // ramo default: o caso casado sem aresta é um fim deliberado, fora da decisão da RCHFLW-135.
        if (!lenient && kind == Kind.SWITCH && !saida.containsKey("error") && "default".equals(saida.get("branch"))
                && semSaida(context)) {
            return falha(start, nomeDoNo() + ": o valor «" + saida.get("value") + "» não casa com nenhum caso ("
                    + (casos().isEmpty() ? "nenhum declarado" : String.join(", ", casos())) + ") e nenhuma aresta "
                    + "de saída seria seguida pelo ramo default — o fluxo terminaria sem chegar a uma saída. Ligue "
                    + "uma aresta default ou um caso para esse valor");
        }
        return CompletableFuture.completedFuture(SimpleStepResult.ok(id, saida, ms(start)));
    }

    /**
     * A busca do switch, anotando em {@code ausentes} o {@code ${caminho}} que não chega a chave nenhuma. No
     * modo leniente, a de sempre: o caminho que falta vira vazio. {@code ${input}} sem dado vale {@code ""}
     * (RCHFLW-101).
     */
    private Function<String, Object> lookup(ExecutionContext context, Set<String> ausentes) {
        Function<String, Object> base = StateTemplate.fromContext(context, null);
        if (lenient) {
            return base;
        }
        return caminho -> {
            // O StateTemplate só desce a partir do 1º segmento; o hasPath, abaixo, aceita qualquer prefixo como
            // chave ("step.n1.output"). Sem ler pelo mesmo caminho, o valor que existe sairia null sem falha — e
            // o switch iria ao default calado, o falhar aberto de novo.
            Object valor = base.apply(caminho);
            if (valor == null) {
                valor = ConditionEvaluator.resolvePath(caminho, context);
            }
            if (valor == null && "input".equals(caminho)) {
                return "";
            }
            // Valor nulo gravado (o decision que falhou: decision null) é valor, e vai ao default como sempre.
            if (valor == null && !ConditionEvaluator.hasPath(caminho, context)) {
                ausentes.add("${" + caminho + "}");
            }
            return valor;
        };
    }

    /**
     * Nenhuma aresta normal do switch seria seguida com o ramo que ele gravou. Sem arestas de saída não se
     * aplica: o nó é o fim do caminho, com caso ou sem caso.
     *
     * <p>Avalia com a mesma regra do motor ({@code archflow.flow.strict-conditions}, {@link #strictEdges}):
     * no padrão permissivo a condição que não se avalia conta como seguida, como o motor a segue; no estrito
     * ela conta como não seguida, porque o motor a bloqueia — e o switch que só tinha essa aresta terminaria o
     * fluxo calado. Com outra regra aqui o veredito divergiria do caminho que o motor de fato percorre.</p>
     */
    private boolean semSaida(ExecutionContext context) {
        List<StepConnection> normais = connections.stream().filter(c -> !c.isErrorPath()).toList();
        if (normais.isEmpty()) {
            return false;
        }
        ConditionEvaluator avaliador = new ConditionEvaluator(strictEdges);
        return normais.stream().noneMatch(c -> avaliador.evaluate(c.getCondition().orElse(null), context));
    }

    /** A falha da referência sem valor: o nó, o campo, as referências e o que fazer. */
    private String semValor(List<String> referencias) {
        return nomeDoNo() + ", campo " + expressionField() + ": "
                + (referencias.size() == 1 ? "a referência " : "as referências ") + String.join(", ", referencias)
                + (referencias.size() == 1 ? " não tem valor" : " não têm valor") + " nesta execução — o passo "
                + "falha em vez de escolher um ramo. Se o valor vem do texto do cliente, extraia-o antes com um nó "
                + "decision (uma pergunta choice por faixa) e ramifique pela resposta dele; se vem do JSON de "
                + "entrada, envie o campo";
    }

    /** A falha da comparação de ordem entre um número e um valor que não é número. */
    private String semNumero(List<String> valores) {
        return nomeDoNo() + ", campo " + expressionField() + ": a comparação de ordem (>, >=, <, <=) põe um número "
                + "contra " + String.join(", ", valores) + ", que não é número — o passo falha em vez de escolher um "
                + "ramo pela ordem do texto. Extraia o valor com um nó decision (uma pergunta choice por faixa) e "
                + "ramifique pela resposta dele: o número escrito por um LLM não é confiável para comparar (1.500 "
                + "seria lido como 1,5)";
    }

    /** {@code condition "Rótulo" (id)}, ou só o id quando o nó não tem rótulo. */
    private String nomeDoNo() {
        String tipo = kind == Kind.CONDITION ? "condition" : "switch";
        return label == null ? tipo + " " + id : tipo + " \"" + label + "\" (" + id + ")";
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
