package br.com.archflow.engine.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checagem estática das expressões dos nós de controle — {@code conditionExpression} do {@code condition} e
 * {@code switchExpression} do {@code switch} — feita antes de o fluxo executar (RCHFLW-116).
 *
 * <p><b>Por que existe.</b> O avaliador é permissivo de propósito (fluxos legados): um operando sem
 * {@code ${}} é procurado como variável e, se não existe, vira o próprio texto; um caminho que não resolve
 * vira {@code null}. Com isso {@code ticket.urgente == true} compara a string {@code "ticket.urgente"} com
 * {@code true} e dá sempre {@code false} — o pedido urgente seguia o ramo errado sem erro nem aviso. A
 * condição é política do fluxo: um erro de escrita nela tem de parar o fluxo antes do primeiro passo, não
 * escolher um ramo em silêncio.</p>
 *
 * <p><b>A regra</b> (a mesma do editor, {@code archflow-ui/.../FlowCanvas/controlExpressions.ts}, e os dois
 * leem os mesmos casos em {@code controlExpressionCases.json}):</p>
 * <ul>
 *   <li>{@link Problem#MALFORMED}: a condição não segue a gramática do avaliador
 *       ({@link ConditionEvaluator#isWellFormed}).</li>
 *   <li>{@link Problem#BARE_OPERAND}: um operando da condição que não é {@code ${caminho}}, texto entre aspas,
 *       número nem {@code true}/{@code false}. É a forma do defeito ({@code ticket.urgente == true},
 *       {@code ${x.route} == ESCALATE}). Só vale para os nós: a condição escrita à mão numa <i>aresta</i> segue
 *       aceitando o identificador solto ({@code route == 'billing'}), que fluxos existentes usam.</li>
 *   <li>{@link Problem#UNKNOWN_STEP}: um {@code ${X.campo}} em que nenhum prefixo do caminho é o id de um
 *       passo do fluxo nem um campo declarado no nó Entrada ({@code ioFields}), e {@code X} não é raiz que o
 *       servidor grava ({@link #ENGINE_ROOTS}). O caso típico é o rótulo do nó no lugar do id, ou uma variável
 *       inventada.</li>
 *   <li>{@link Problem#TEXT_OUTPUT_FIELD}: um {@code ${X.campo}} em que {@code X} é um nó de chat — a saída
 *       dele é o texto da resposta ({@code ChatTurn}), e um campo de texto nunca resolve.</li>
 *   <li>{@link Problem#NO_REFERENCE}: um switch sem nenhum {@code ${…}} — o valor é sempre o mesmo texto.</li>
 * </ul>
 *
 * <p><b>O que fica de fora, de propósito (falso positivo).</b> Um {@code ${nome}} <i>sem ponto</i> que não
 * é passo é aceito: as chaves do JSON de entrada viram variáveis do contexto ({@code RunInputSeeding}) e só
 * são conhecidas na execução. Um campo <i>aninhado</i> de uma chave da entrada ({@code ${ticket.urgente}} com
 * a entrada {@code {"ticket": {"urgente": true}}}) também resolve na execução, mas estaticamente não se
 * distingue de uma variável inventada — o defeito deste ticket. O que torna a chave conhecida é o contrato
 * que o fluxo já tem: os campos esperados do nó Entrada ({@code ioFields}). Declarado ali, {@code ticket} vale
 * como raiz, e a forma funciona em qualquer entrada — o {@code ${<id da Entrada>.ticket.urgente}} não: quando
 * a entrada também traz a chave {@code input}, o nó Entrada guarda só o valor dela, e o fluxo sem nó Entrada
 * não tem id para prefixar. O que não resolve só na execução continua com o aviso "caminho não resolvido" do
 * {@code ControlStep} (RCHFLW-101).</p>
 */
public final class ControlExpressionRules {

    /** O motivo da recusa; o nome é o código que o editor também usa. */
    public enum Problem {
        MALFORMED,
        BARE_OPERAND,
        UNKNOWN_STEP,
        TEXT_OUTPUT_FIELD,
        NO_REFERENCE
    }

    /**
     * Uma recusa: o motivo e o trecho da expressão que a causou.
     *
     * @param problem  o motivo
     * @param fragment o operando, o caminho ou a expressão inteira, conforme o motivo
     */
    public record Finding(Problem problem, String fragment) {

        /** A mensagem para quem executou: diz o que está errado e como escrever certo. */
        public String message() {
            String raiz = fragment.split("\\.", 2)[0];
            return switch (problem) {
                case MALFORMED -> "a expressão «" + fragment + "» não segue a gramática do avaliador "
                        + "(operando operador operando, unidos por && ou ||)";
                case BARE_OPERAND -> "«" + fragment + "» não é um valor: escreva a referência como ${…} "
                        + "ou o texto entre aspas ('…') — sem ${}, o avaliador compara o próprio texto e o ramo "
                        + "nunca muda";
                case UNKNOWN_STEP -> "${" + fragment + "}: nenhum passo do fluxo tem o id '" + raiz
                        + "' — a saída de um passo fica sob o id do nó, não sob o rótulo; se '" + raiz
                        + "' é uma chave do JSON de entrada do run, declare-a em «Campos de entrada esperados» "
                        + "do nó Entrada";
                case TEXT_OUTPUT_FIELD -> "${" + fragment + "}: '" + raiz + "' é um LLM Chat, cuja saída é "
                        + "o texto da resposta, sem campos — compare ${" + raiz + "} (ex.: contains '…') ou "
                        + "classifique com um nó decision";
                case NO_REFERENCE -> "o switch não lê nenhum ${…}: o valor é sempre «" + fragment
                        + "» e o ramo nunca muda";
            };
        }
    }

    /**
     * O que o fluxo oferece às referências.
     *
     * @param stepIds           os ids dos passos (as anotações do canvas não são passos)
     * @param textOutputStepIds os passos cuja saída é texto puro (nós de chat)
     * @param inputFields       as chaves da entrada do run declaradas nos nós Entrada ({@code ioFields})
     */
    public record FlowRefs(Set<String> stepIds, Set<String> textOutputStepIds, Set<String> inputFields) {
        public FlowRefs {
            stepIds = stepIds == null ? Set.of() : Set.copyOf(stepIds);
            textOutputStepIds = textOutputStepIds == null ? Set.of() : Set.copyOf(textOutputStepIds);
            inputFields = inputFields == null ? Set.of() : Set.copyOf(inputFields);
        }

        /** Sem campos de entrada declarados (fluxo montado em código, sem nó Entrada). */
        public FlowRefs(Set<String> stepIds, Set<String> textOutputStepIds) {
            this(stepIds, textOutputStepIds, Set.of());
        }
    }

    /**
     * Raízes que o servidor grava fora dos ids dos passos: a entrada ({@code input}), a saída registrada pelo
     * executor ({@code step.<id>.output}), as chaves de execução ({@code llm.model}, {@code archflow.*},
     * {@code __archflow.*}, ver {@code ExecutionKeys}), o {@code approvalPayload} que a aprovação grava
     * ({@code ExecutionKeys.APPROVAL_PAYLOAD}) e, no invoke do VendaX ({@code AgentFlowRunner}), o
     * {@code payload} lido como objeto (VEN-397, {@code ${payload.pergunta}}) e as chaves de correlação
     * {@code vendax.*} ({@code CorrelacaoMcp.CTX_*}). Faltando uma delas, uma referência que resolve na
     * execução era recusada antes do 1º passo.
     */
    public static final Set<String> ENGINE_ROOTS = Set.of(
            "input", "step", "llm", "archflow", "__archflow", "approvalPayload", "payload", "vendax");

    /** Os nós de chat: a saída é a {@code String} da resposta (ver {@code AdapterNodeTypes}). */
    public static final Set<String> TEXT_OUTPUT_NODE_TYPES = Set.of("llm-chat", "llm-streaming", "chat");

    /** Um número como literal. Mais estreito que {@code Double.parseDouble} para o editor ler igual. */
    private static final Pattern NUMBER = Pattern.compile("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$");

    /** O placeholder dentro de um template, como o {@code StateTemplate} que resolve o switch. */
    private static final Pattern VAR = Pattern.compile("\\$\\{([^}]+)}");

    private ControlExpressionRules() {
    }

    /** As recusas de uma {@code conditionExpression}; em branco não é aqui (é campo obrigatório). */
    public static List<Finding> checkCondition(String expression, FlowRefs refs) {
        List<Finding> findings = new ArrayList<>();
        if (expression == null || expression.isBlank()) {
            return findings;
        }
        String expr = expression.trim();
        if (!new ConditionEvaluator().isWellFormed(expr)) {
            findings.add(new Finding(Problem.MALFORMED, expr));
            return findings;
        }
        checkConditionPart(expr, refs, findings);
        return findings;
    }

    /** As recusas de uma {@code switchExpression}: um template com ao menos um {@code ${…}}. */
    public static List<Finding> checkSwitch(String expression, FlowRefs refs) {
        List<Finding> findings = new ArrayList<>();
        if (expression == null || expression.isBlank()) {
            return findings;
        }
        Matcher m = VAR.matcher(expression);
        boolean any = false;
        while (m.find()) {
            any = true;
            checkPath(m.group(1).trim(), refs, findings);
        }
        if (!any) {
            findings.add(new Finding(Problem.NO_REFERENCE, expression.trim()));
        }
        return findings;
    }

    /** A mesma descida do {@code doEvaluate}: {@code ||}, depois {@code &&}, depois a comparação. */
    private static void checkConditionPart(String expr, FlowRefs refs, List<Finding> findings) {
        List<String> alternatives = ConditionEvaluator.splitTopLevel(expr, "||");
        if (alternatives.size() > 1) {
            alternatives.forEach(a -> checkConditionPart(a.trim(), refs, findings));
            return;
        }
        List<String> conjuncts = ConditionEvaluator.splitTopLevel(expr, "&&");
        if (conjuncts.size() > 1) {
            conjuncts.forEach(c -> checkConditionPart(c.trim(), refs, findings));
            return;
        }
        Matcher contains = ConditionEvaluator.CONTAINS.matcher(expr);
        if (contains.matches()) {
            checkOperand(contains.group(1), refs, findings);
            checkOperand(contains.group(2), refs, findings);
            return;
        }
        Matcher comparison = ConditionEvaluator.COMPARISON.matcher(expr);
        if (comparison.matches()) {
            checkOperand(comparison.group(1), refs, findings);
            checkOperand(comparison.group(3), refs, findings);
            return;
        }
        checkOperand(expr, refs, findings);
    }

    private static void checkOperand(String token, FlowRefs refs, List<Finding> findings) {
        String t = token.trim();
        Matcher placeholder = ConditionEvaluator.PLACEHOLDER.matcher(t);
        if (placeholder.matches()) {
            String path = placeholder.group(1).trim();
            // "${a}x${b}" também casa com o placeholder, mas o avaliador procuraria a chave "a}x${b".
            if (path.contains("{") || path.contains("}") || path.contains("$")) {
                findings.add(new Finding(Problem.BARE_OPERAND, t));
            } else {
                checkPath(path, refs, findings);
            }
            return;
        }
        boolean quoted = t.length() >= 2 && ((t.startsWith("'") && t.endsWith("'"))
                || (t.startsWith("\"") && t.endsWith("\"")));
        if (quoted || "true".equalsIgnoreCase(t) || "false".equalsIgnoreCase(t) || NUMBER.matcher(t).matches()) {
            return;
        }
        findings.add(new Finding(Problem.BARE_OPERAND, t));
    }

    /**
     * Um caminho sem ponto pode ser chave da entrada do run e é aceito. Com ponto, o prefixo mais longo que é
     * id de passo ou campo declarado da entrada decide (um id ou uma chave pode ter ponto: o avaliador procura
     * a chave inteira antes de descer).
     */
    private static void checkPath(String path, FlowRefs refs, List<Finding> findings) {
        if (path.isEmpty()) {
            findings.add(new Finding(Problem.BARE_OPERAND, "${}"));
            return;
        }
        if (!path.contains(".")) {
            return;
        }
        String prefix = path;
        while (true) {
            if (refs.stepIds().contains(prefix)) {
                if (!prefix.equals(path) && refs.textOutputStepIds().contains(prefix)) {
                    findings.add(new Finding(Problem.TEXT_OUTPUT_FIELD, path));
                }
                return;
            }
            if (refs.inputFields().contains(prefix)) {
                return;
            }
            int dot = prefix.lastIndexOf('.');
            if (dot <= 0) {
                break;
            }
            prefix = prefix.substring(0, dot);
        }
        if (!ENGINE_ROOTS.contains(path.substring(0, path.indexOf('.')))) {
            findings.add(new Finding(Problem.UNKNOWN_STEP, path));
        }
    }
}
