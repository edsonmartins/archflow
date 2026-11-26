package br.com.archflow.api.flow;

import br.com.archflow.api.flow.adapter.LangChainAdapterComponent;
import br.com.archflow.engine.exceptions.FlowValidationException;
import br.com.archflow.engine.exceptions.ValidationError;
import br.com.archflow.engine.execution.ControlExpressionRules;
import br.com.archflow.engine.validation.FlowValidator;
import br.com.archflow.engine.validation.ValidationContext;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepConnection;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A validação que o motor faz antes do primeiro passo, mais uma regra: nenhum nó de tipo sem execução
 * ({@link UnsupportedNodeStep}, RCHFLW-11).
 *
 * <p>Sem ela, o fluxo rodava os passos anteriores — com efeito, chamadas pagas ao modelo — e só
 * falhava ao chegar no nó, com {@code component not found}. Aqui a execução termina antes de começar,
 * com uma mensagem por tipo que nomeia os nós: o usuário sabe o que tirar do fluxo.</p>
 *
 * <p>Embrulha o validador do motor em vez de mudá-lo: o archflow-core não conhece os passos do
 * archflow-api, e a regra é deste runtime. Os erros dos dois se somam numa só falha.</p>
 *
 * <p>Fora do servidor (runner standalone, SDK embarcado) vale a mesma regra para o nó que precisa do
 * servidor ({@link ServerOnlyNodeStep}, RCHFLW-37): um erro por nó, com o motivo. No servidor esse passo
 * não existe — a fábrica recebe os {@link ServerNodes} — e nada muda.</p>
 *
 * <p>E a expressão de cada {@code condition}/{@code switch} ({@link ControlExpressionRules}, RCHFLW-116):
 * fora do formato do avaliador, ela escolhia um ramo em silêncio — o pedido urgente seguia para o LLM e
 * nunca chegava ao humano. Um erro por nó, nomeando o nó e o campo. É a mesma regra que impede o Executar
 * do editor; quem roda pela API, pelo YAML ou pelo standalone passa por aqui. Diferença deliberada: a
 * referência a um passo que o fluxo não tem ({@code UNKNOWN_STEP}) só é recusada no editor — aqui vira
 * aviso no log, porque ela resolve se a entrada do run trouxer a chave, e fluxos criados por API contam
 * com isso.</p>
 *
 * <p>A "Mensagem do usuário" de um LLM Chat ({@code userMessage}, RCHFLW-120) passa pela mesma regra
 * ({@link ControlExpressionRules#checkTemplate}): um <code>${</code> que não é referência ou um campo da saída de texto
 * de outro LLM Chat nunca funcionam e são recusados; {@code UNKNOWN_STEP}, só aviso, pelo mesmo motivo.</p>
 *
 * <p>E os nós soltos de um fluxo com arestas ({@code NODE_DISCONNECTED}, RCHFLW-135): rodariam à parte, logo no
 * início, antes de qualquer portão.</p>
 */
public final class ExecutableNodesValidator implements FlowValidator {

    static final String CODE = "NODE_TYPE_NOT_EXECUTABLE";
    static final String SERVER_CODE = "NODE_REQUIRES_SERVER";
    static final String EXPRESSION_CODE = "CONTROL_EXPRESSION_INVALID";
    static final String DISCONNECTED_CODE = "NODE_DISCONNECTED";
    static final String BRANCH_CODE = "BRANCH_NOT_DECLARED";

    /** As rotas do {@code decision}, na ordem do seletor da aresta no designer. */
    private static final List<String> DECISION_ROUTES = List.of("AUTO", "REVIEW", "ESCALATE");

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ExecutableNodesValidator.class);

    private final FlowValidator delegate;

    public ExecutableNodesValidator(FlowValidator delegate) {
        this.delegate = delegate;
    }

    @Override
    public void validate(Flow flow) throws FlowValidationException {
        List<ValidationError> errors = new ArrayList<>();
        try {
            delegate.validate(flow);
        } catch (FlowValidationException e) {
            errors.addAll(e.getErrors());
        }
        errors.addAll(unsupported(flow));
        errors.addAll(serverOnly(flow));
        errors.addAll(controlExpressions(flow));
        errors.addAll(disconnected(flow));
        // Aresta em ramo não declarado (RCHFLW-147): só aviso no log, como o UNKNOWN_STEP. O editor recusa o
        // Executar e avisa no canvas; aqui, recusar faria falhar todo run de fluxos já publicados e chamados
        // pela API/VendaX que hoje seguem pelos outros ramos — mudança de contrato não decidida.
        undeclaredBranches(flow).forEach(e -> log.warn("Fluxo {}: {} ({})", flow.getId(), e.message(), BRANCH_CODE));
        if (!errors.isEmpty()) {
            throw new FlowValidationException(errors);
        }
    }

    @Override
    public void validateStep(FlowStep step, ValidationContext context) throws FlowValidationException {
        delegate.validateStep(step, context);
    }

    /** Um erro por tipo, na ordem em que o tipo aparece, com os nós que o usam. */
    private static List<ValidationError> unsupported(Flow flow) {
        Map<String, List<String>> nodesByKind = new LinkedHashMap<>();
        for (FlowStep step : flow.getSteps()) {
            if (step instanceof UnsupportedNodeStep unsupported) {
                nodesByKind.computeIfAbsent(unsupported.kind(), k -> new ArrayList<>()).add(unsupported.describe());
            }
        }
        List<ValidationError> errors = new ArrayList<>();
        nodesByKind.forEach((kind, nodes) -> errors.add(new ValidationError(
                "step.type",
                NodesWithoutExecution.message(kind) + " — nó(s): " + String.join(", ", nodes),
                CODE,
                Map.of("type", kind, "nodes", List.copyOf(nodes)))));
        return errors;
    }

    /**
     * Um erro por nó de controle com expressão recusada, na ordem dos passos. Os ids vêm dos passos (uma
     * anotação não é passo); o tipo, o rótulo e os campos esperados de cada nó, do documento — o passo do LLM
     * Chat não guarda o tipo do nó, e é ele que diz que a saída é texto; o da Entrada não guarda os
     * {@code ioFields}, que tornam conhecida uma chave da entrada do run. Sem documento (fluxo montado em
     * código), só os ids.
     */
    private static List<ValidationError> controlExpressions(Flow flow) {
        List<ControlStep> controles = flow.getSteps().stream()
                .filter(ControlStep.class::isInstance).map(ControlStep.class::cast).toList();
        // Os LLM Chat com "Mensagem do usuário": o template está no documento, e o passo do chat é um ComponentStep
        // que não guarda o tipo do nó. Fluxo sem documento (montado em código) não tem o campo.
        Map<String, String> mensagens = userMessages(flow);
        if (controles.isEmpty() && mensagens.isEmpty()) {
            return List.of();
        }
        Set<String> ids = new HashSet<>();
        flow.getSteps().forEach(s -> ids.add(s.getId()));
        Map<String, String> rotulos = new LinkedHashMap<>();
        Set<String> texto = new HashSet<>();
        Set<String> camposDeEntrada = new HashSet<>();
        for (Map<?, ?> no : nodesOf(flow)) {
            String id = no.get("id") == null ? null : no.get("id").toString();
            if (id == null || !ids.contains(id)) {
                continue;
            }
            if (isTextOutput(no.get("componentId")) || isTextOutput(no.get("type"))) {
                texto.add(id);
            }
            if (isKind(no.get("componentId"), "input") || isKind(no.get("type"), "input")) {
                camposDeEntrada.addAll(declaredFields(no));
            }
            if (no.get("label") instanceof String label && !label.isBlank()) {
                rotulos.put(id, label.trim());
            }
        }
        ControlExpressionRules.FlowRefs refs = new ControlExpressionRules.FlowRefs(ids, texto, camposDeEntrada);
        List<ValidationError> errors = new ArrayList<>();
        for (ControlStep step : controles) {
            String expressao = step.expression();
            List<ControlExpressionRules.Finding> todos = step.kind() == ControlStep.Kind.CONDITION
                    ? ControlExpressionRules.checkCondition(expressao, refs)
                    : ControlExpressionRules.checkSwitch(expressao, refs);
            recusar(flow, step.getId(), rotulos, step.expressionField(), expressao, todos, errors);
        }
        mensagens.forEach((id, template) -> {
            if (ids.contains(id)) {
                recusar(flow, id, rotulos, LangChainAdapterComponent.USER_MESSAGE_KEY, template,
                        ControlExpressionRules.checkTemplate(template, refs), errors);
            }
        });
        return errors;
    }

    /** Um erro para o campo do nó com as recusas que nunca funcionam; o {@code UNKNOWN_STEP} vai ao log. */
    private static void recusar(Flow flow, String id, Map<String, String> rotulos, String campo, String expressao,
                                List<ControlExpressionRules.Finding> todos, List<ValidationError> errors) {
        String no = rotulos.containsKey(id) ? "\"" + rotulos.get(id) + "\" (" + id + ")" : id;
        // UNKNOWN_STEP aqui é só aviso: um ${X.y} cujo X não é passo, raiz do motor nem campo declarado na
        // Entrada ainda resolve no run se o JSON de entrada trouxer a chave X — e fluxos criados por API
        // (VendaX Core, YAML, standalone) fazem isso sem declarar. Recusar no servidor quebraria fluxos que
        // funcionam hoje; o editor continua impedindo (lá o designer declara o campo). Os outros problemas
        // nunca funcionam — comparam o próprio texto ou leem campo de saída de texto — e são recusados.
        todos.stream().filter(f -> f.problem() == ControlExpressionRules.Problem.UNKNOWN_STEP)
                .forEach(f -> log.warn("Fluxo {}: o nó {}, campo {}: {} — se não vier na entrada do run, a "
                        + "referência não resolve", flow.getId(), no, campo, f.message()));
        List<ControlExpressionRules.Finding> achados = todos.stream()
                .filter(f -> f.problem() != ControlExpressionRules.Problem.UNKNOWN_STEP).toList();
        if (achados.isEmpty()) {
            return;
        }
        String motivos = String.join("; ", achados.stream().map(ControlExpressionRules.Finding::message).toList());
        errors.add(new ValidationError("step.configuration." + campo,
                "o nó " + no + ", campo " + campo + ": " + motivos,
                EXPRESSION_CODE,
                Map.of("node", id, "field", campo, "expression", expressao,
                        "problems", achados.stream().map(f -> f.problem().name()).toList())));
    }

    /**
     * O {@code userMessage} de cada nó de chat do documento, por id. É o tipo do nó que diz "chat", como na fábrica
     * ({@code AdapterNodeTypes}).
     *
     * <p>Lê na mesma ordem da fábrica ({@code PortableFlowStepFactory.config}): {@code config} primeiro e
     * {@code configuration} por cima, que vence. Se o validador lesse em outra ordem, um documento com as duas
     * chaves (legado ou editado à mão) seria julgado por um template que não é o que roda — recusado por um valor
     * que o motor ignora, ou aceito sem checar o que o motor envia. Um valor em branco por cima também vale como
     * "sem campo", como no motor (comportamento de hoje).
     */
    private static Map<String, String> userMessages(Flow flow) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<?, ?> no : nodesOf(flow)) {
            if (no.get("id") == null || !(isTextOutput(no.get("type")) || isTextOutput(no.get("componentId")))) {
                continue;
            }
            Object valor = null;
            for (String chave : List.of("config", "configuration")) {
                if (no.get(chave) instanceof Map<?, ?> config
                        && config.containsKey(LangChainAdapterComponent.USER_MESSAGE_KEY)) {
                    valor = config.get(LangChainAdapterComponent.USER_MESSAGE_KEY);
                }
            }
            if (valor instanceof String t && !t.isBlank()) {
                out.put(no.get("id").toString(), t);
            }
        }
        return out;
    }

    private static boolean isKind(Object kind, String expected) {
        return kind instanceof String s && expected.equals(s.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Os {@code ioFields} do nó, em {@code configuration} ou no sinônimo {@code config} — os dois que o motor e o
     * YAML leem (ver {@code workflowSerde.ts}).
     */
    private static List<String> declaredFields(Map<?, ?> no) {
        List<String> campos = new ArrayList<>();
        for (String chave : List.of("configuration", "config")) {
            if (no.get(chave) instanceof Map<?, ?> config && config.get("ioFields") instanceof List<?> lista) {
                lista.forEach(c -> {
                    if (c instanceof String campo && !campo.isBlank()) {
                        campos.add(campo.trim());
                    }
                });
            }
        }
        return campos;
    }

    private static boolean isTextOutput(Object kind) {
        return kind instanceof String s
                && ControlExpressionRules.TEXT_OUTPUT_NODE_TYPES.contains(s.trim().toLowerCase(Locale.ROOT));
    }

    private static List<Map<?, ?>> nodesOf(Flow flow) {
        if (flow instanceof SimpleFlow simple && simple.getSourceDocument() != null
                && simple.getSourceDocument().get("steps") instanceof List<?> steps) {
            List<Map<?, ?>> nodes = new ArrayList<>();
            steps.forEach(m -> {
                if (m instanceof Map<?, ?> node) {
                    nodes.add(node);
                }
            });
            return nodes;
        }
        return List.of();
    }

    /**
     * Um erro com os nós soltos de um fluxo que tem arestas (RCHFLW-135).
     *
     * <p>Com alguma aresta o motor percorre o grafo a partir dos passos sem aresta de entrada, e um nó sem
     * aresta nenhuma é um deles: roda à parte, com a entrada da execução, logo no começo. No QA, ao religar a
     * aprovação para outra Saída, a Saída antiga ficou solta e rodou antes do portão; um LLM ou uma
     * ferramenta solta faria a chamada (paga, com efeito) sem passar por ele. O editor já avisa "Nós sem
     * conexão"; quem roda pela API, pelo YAML ou pelo standalone passa por aqui.</p>
     *
     * <p>Sem aresta nenhuma o fluxo é linear (a ordem do documento) e nada muda — inclusive o fluxo de um nó
     * só. A aresta conta como o motor a conta: no passo de origem e para um passo que existe.</p>
     */
    private static List<ValidationError> disconnected(Flow flow) {
        List<FlowStep> steps = flow.getSteps();
        if (steps.size() < 2 || steps.stream().allMatch(s -> s.getConnections() == null
                || s.getConnections().isEmpty())) {
            return List.of();
        }
        Set<String> ids = new HashSet<>();
        steps.forEach(s -> ids.add(s.getId()));
        Set<String> ligados = new HashSet<>();
        for (FlowStep step : steps) {
            for (StepConnection c : step.getConnections() == null ? List.<StepConnection>of()
                    : step.getConnections()) {
                String origem = c.getSourceId();
                if (c.getTargetId() == null || !ids.contains(c.getTargetId())
                        || (origem != null && !origem.isBlank() && !origem.equals(step.getId()))) {
                    continue;
                }
                ligados.add(step.getId());
                ligados.add(c.getTargetId());
            }
        }
        Map<String, String> rotulos = new LinkedHashMap<>();
        for (Map<?, ?> no : nodesOf(flow)) {
            if (no.get("id") != null && no.get("label") instanceof String label && !label.isBlank()) {
                rotulos.put(no.get("id").toString(), label.trim());
            }
        }
        List<String> soltos = new ArrayList<>();
        List<String> nomes = new ArrayList<>();
        for (FlowStep step : steps) {
            if (!ligados.contains(step.getId())) {
                soltos.add(step.getId());
                nomes.add(rotulos.containsKey(step.getId())
                        ? "\"" + rotulos.get(step.getId()) + "\" (" + step.getId() + ")" : step.getId());
            }
        }
        if (soltos.isEmpty()) {
            return List.of();
        }
        return List.of(new ValidationError("step.connections",
                "nó(s) sem nenhuma aresta: " + String.join(", ", nomes) + " — o fluxo tem arestas, e um nó solto "
                        + "rodaria à parte, logo no início e com a entrada da execução (antes de um portão de "
                        + "aprovação, por exemplo). Ligue-o(s) ao fluxo ou remova-o(s)",
                DISCONNECTED_CODE,
                Map.of("nodes", List.copyOf(soltos))));
    }

    /**
     * Um erro por aresta que declara um ramo que o nó de origem não tem (RCHFLW-147).
     *
     * <p>Renomear um caso do switch ("arrependimento" → "arrependido"), à mão, pelo copiloto ou pelo Gerar com IA,
     * deixava a aresta no nome antigo. O switch só grava um caso declarado ou {@code default}, então a condição
     * derivada do ramo ({@code ${sw.branch} == 'arrependimento'}) nunca vale: a requisição que caía no caso novo
     * terminava COMPLETED sem passar por nada, porque o caso casado sem aresta é o fim deliberado do
     * {@link ControlStep} — o passo não tem como distinguir. Só a leitura do documento antes do primeiro passo vê
     * a aresta morta. Vale também para o {@code condition} (só {@code true}/{@code false}) e para o
     * {@code decision} (as três rotas, em maiúsculas, como o motor as grava).</p>
     *
     * <p>Só conta o ramo que vale, como na {@code BranchCondition}: uma {@code condition} escrita à mão vence o
     * ramo, e a aresta de erro não tem ramo. Sem documento (fluxo montado em código) não há ramo para ler.</p>
     */
    static List<ValidationError> undeclaredBranches(Flow flow) {
        Set<String> ids = new HashSet<>();
        flow.getSteps().forEach(s -> ids.add(s.getId()));
        List<ValidationError> errors = new ArrayList<>();
        for (Map<?, ?> no : nodesOf(flow)) {
            String id = no.get("id") == null ? null : no.get("id").toString();
            if (id == null || !ids.contains(id) || !(no.get("connections") instanceof List<?> conexoes)) {
                continue;
            }
            List<String> ramos = branchesOf(no);
            if (ramos.isEmpty()) {
                continue;
            }
            String nome = no.get("label") instanceof String label && !label.isBlank()
                    ? "\"" + label.trim() + "\" (" + id + ")" : id;
            for (Object c : conexoes) {
                if (!(c instanceof Map<?, ?> aresta) || Boolean.TRUE.equals(aresta.get("isErrorPath"))
                        || (aresta.get("condition") instanceof String cond && !cond.isBlank())
                        || !(aresta.get("branch") instanceof String bruto) || bruto.isBlank()) {
                    continue;
                }
                // A mesma normalização da BranchCondition: é este texto que a condição derivada compara. O
                // designer (undeclaredBranch) faz a mesma conta, para o aviso do editor e esta recusa concordarem.
                String ramo = bruto.trim();
                if (ramos.contains(ramo.replace("'", ""))) {
                    continue;
                }
                // Um caso com apóstrofo ("d'água") é declarado, mas a condição derivada da aresta compara sem ele
                // ('dágua') e nunca vale — dizer só "não declara" mandaria o usuário procurar o caso que está lá.
                String apostrofo = ramo.indexOf('\'') >= 0
                        ? " (o ramo é comparado sem apóstrofos, como «" + ramo.replace("'", "") + "»)" : "";
                Object destino = aresta.get("targetId");
                errors.add(new ValidationError("step.connections.branch",
                        "a aresta de " + nome + " para " + destino + " está no ramo «" + ramo + "»" + apostrofo
                                + ", que o nó não declara (ramos: " + String.join(", ", ramos) + ") — nenhum valor "
                                + "chega a ela, e a execução terminaria sem passar por ali. Escolha um dos ramos ou "
                                + "declare o caso",
                        BRANCH_CODE,
                        Map.of("node", id, "target", String.valueOf(destino), "branch", ramo,
                                "branches", List.copyOf(ramos))));
            }
        }
        return errors;
    }

    /** Os ramos que o nó oferece, pelo tipo do documento (componentId, senão type); vazio quando não ramifica. */
    private static List<String> branchesOf(Map<?, ?> no) {
        Object bruto = no.get("componentId") != null ? no.get("componentId") : no.get("type");
        String tipo = bruto == null ? "" : bruto.toString().trim().toLowerCase(Locale.ROOT);
        return switch (tipo) {
            case "condition" -> List.of("true", "false");
            case "decision" -> DECISION_ROUTES;
            case "switch" -> {
                // config primeiro e configuration por cima, como a fábrica (PortableFlowStepFactory.config).
                Object casos = null;
                for (String chave : List.of("config", "configuration")) {
                    if (no.get(chave) instanceof Map<?, ?> config && config.containsKey("switchCases")) {
                        casos = config.get("switchCases");
                    }
                }
                List<String> ramos = new ArrayList<>(new java.util.LinkedHashSet<>(ControlStep.casosDe(casos)));
                if (!ramos.contains("default")) {
                    ramos.add("default");
                }
                yield ramos;
            }
            default -> List.of();
        };
    }

    /** Um erro por nó que precisa do servidor, na ordem dos passos. */
    private static List<ValidationError> serverOnly(Flow flow) {
        List<ValidationError> errors = new ArrayList<>();
        for (FlowStep step : flow.getSteps()) {
            if (step instanceof ServerOnlyNodeStep node) {
                errors.add(new ValidationError("step.type", node.message(), SERVER_CODE,
                        Map.of("node", node.getId(), "capability", node.capability().name())));
            }
        }
        return errors;
    }
}
