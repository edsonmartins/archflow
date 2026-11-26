package br.com.archflow.engine.execution;

import br.com.archflow.model.engine.ExecutionContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Avalia as condições de transição das conexões de um fluxo.
 *
 * <p>Formato emitido pelo designer: {@code ${caminho.da.variavel} <op> literal},
 * onde {@code op} ∈ {@code ==, !=, >=, <=, >, <, contains}. Também aceita um
 * operando único avaliado por veracidade ({@code ${flag}}) e os literais
 * {@code true}/{@code false}.
 *
 * <p>Variáveis são resolvidas no {@link ExecutionContext}: primeiro pela chave
 * completa (o executor grava saídas como {@code step.<id>.output}), depois por
 * travessia de mapas aninhados ({@code agent.confidence} → {@code get("agent")}
 * seguido de {@code Map.get("confidence")}).
 *
 * <p>Suporta composição booleana com {@code &&} e {@code ||}, com a precedência
 * usual ({@code &&} liga mais forte) e curto-circuito. A divisão ignora
 * ocorrências dentro de aspas, então {@code ${msg} == 'a && b'} continua sendo
 * uma expressão só. <b>Não</b> há parênteses: a gramática é deliberadamente
 * pequena, e uma condição que precise deles provavelmente devia ser um step.
 *
 * <p>Condição em branco segue a transição. Condição não avaliável segue com
 * warning no modo default — comportamento permissivo herdado de fluxos legados,
 * e uma armadilha para quem expressa política como condição: uma expressão com
 * erro de digitação libera o caminho em vez de barrá-lo. Ligue o modo estrito
 * ({@code archflow.flow.strict-conditions=true}) para que o não avaliável
 * BLOQUEIE a transição.
 */
public final class ConditionEvaluator {

    private static final Logger logger = Logger.getLogger(ConditionEvaluator.class.getName());

    // Pacote, e não privados: o ControlExpressionRules lê a expressão com a MESMA gramática, para a
    // checagem estática não aceitar nem recusar diferente do que o avaliador faz ao executar.
    static final Pattern COMPARISON = Pattern.compile(
            "^(.+?)\\s*(==|!=|>=|<=|>|<)\\s*(.+)$");
    static final Pattern CONTAINS = Pattern.compile(
            "^(.+?)\\s+contains\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{(.+)}$");

    /**
     * Quando ligado, uma condição não avaliável <b>bloqueia</b> a transição em
     * vez de segui-la.
     *
     * <p>O default permissivo preserva fluxos legados, mas é uma armadilha para
     * qualquer política expressa como condição: uma expressão com erro de
     * digitação libera o caminho em vez de barrá-lo. Quem usa condição como
     * controle liga o modo estrito.
     */
    private final boolean strict;

    public ConditionEvaluator() {
        this(false);
    }

    public ConditionEvaluator(boolean strict) {
        this.strict = strict;
    }

    /**
     * Checagem estática de boa-formação, para validação de fluxos (sem
     * contexto). Uma condição é malformada quando contém um operador mas
     * não casa com a gramática {@code operando op operando} — ex.:
     * {@code "${a} >"}. Operando único e condição em branco são válidos.
     */
    public boolean isWellFormed(String condition) {
        if (condition == null || condition.isBlank()) {
            return true;
        }
        String expr = condition.trim();
        // Expressão composta: cada operando precisa ser bem-formado por si.
        try {
            List<String> parts = splitTopLevel(expr, "||");
            if (parts.size() == 1) {
                parts = splitTopLevel(expr, "&&");
            }
            if (parts.size() > 1) {
                return parts.stream().allMatch(part -> isWellFormed(part.trim()));
            }
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        Matcher contains = CONTAINS.matcher(expr);
        if (contains.matches()) {
            return !contains.group(1).isBlank() && !contains.group(2).isBlank();
        }
        Matcher m = COMPARISON.matcher(expr);
        if (m.matches()) {
            return !m.group(1).isBlank() && !m.group(3).isBlank();
        }
        // Tem cara de comparação mas não parseou → malformada
        return !expr.matches(".*(==|!=|>=|<=|>|<).*")
                && !expr.toLowerCase().matches(".*\\bcontains\\b.*");
    }

    /**
     * @return {@code true} se a transição deve ser seguida
     */
    public boolean evaluate(String condition, ExecutionContext context) {
        if (condition == null || condition.isBlank()) {
            return true;
        }
        try {
            return doEvaluate(condition.trim(), context);
        } catch (Exception e) {
            if (strict) {
                logger.warning("Condição não avaliável, transição BLOQUEADA (modo estrito): \""
                        + condition + "\" (" + e.getMessage() + ")");
                return false;
            }
            logger.warning("Condição não avaliável, seguindo a transição: \""
                    + condition + "\" (" + e.getMessage() + ")");
            return true;
        }
    }

    /**
     * O desfecho de uma condição avaliada sem aceitar referência sem valor ({@link #evaluateRequiringValues}).
     *
     * @param result     o valor da condição — só tem sentido quando {@link #decided()}
     * @param unresolved as referências {@code ${…}} sem valor de que o resultado dependeu; vazia quando a
     *                   condição se decidiu
     * @param notNumbers os operandos ({@code ${x} = «R$ 1.200»}) que uma comparação de ordem pôs contra um número
     *                   sem serem número, de que o resultado dependeu
     */
    public record Verdict(boolean result, List<String> unresolved, List<String> notNumbers) {
        public Verdict {
            unresolved = unresolved == null ? List.of() : List.copyOf(unresolved);
            notNumbers = notNumbers == null ? List.of() : List.copyOf(notNumbers);
        }

        public Verdict(boolean result, List<String> unresolved) {
            this(result, unresolved, List.of());
        }

        /** {@code true} quando o resultado não depende de referência sem valor nem de ordem entre número e texto. */
        public boolean decided() {
            return unresolved.isEmpty() && notNumbers.isEmpty();
        }
    }

    /**
     * Avalia a condição de um nó {@code condition} sem deixar uma referência sem valor escolher o ramo
     * (RCHFLW-123).
     *
     * <p>No {@link #evaluate} um {@code ${caminho}} que não resolve vira {@code null}, e {@code null > 500} dá
     * {@code false}: o portão "acima de 500 pede aprovação" com a entrada em texto livre ia para o ramo do
     * "aprova sozinho", sem aviso. Aqui uma referência sem valor deixa o operando <i>desconhecido</i>, e a
     * composição segue a lógica de três valores: {@code desconhecido || verdadeiro} é verdadeiro e
     * {@code desconhecido && falso} é falso — o resultado não depende do que falta. Só quando depende o
     * {@link Verdict} sai indeciso, com as referências, e quem chama falha o passo. Sem isso o exemplo que o
     * assistente ensina ({@code ${triagem.answers.urgente.result} == true || ${triagem.route} == 'ESCALATE'},
     * em que a decisão que falhou não tem respostas mas tem a rota) deixaria de levar a falha ao humano.</p>
     *
     * <p>"Sem valor" é o caminho que não chega a chave nenhuma ({@link #hasPath}). Uma chave gravada com
     * {@code null} é valor e compara como no {@link #evaluate} ({@code ==}, {@code !=}, {@code contains}) — menos
     * numa comparação de ordem ({@code > 500} contra nulo), que não tem resposta e também deixa o resultado
     * indeciso. {@code ${input}} sozinho nunca fica sem valor: é o canal do motor, e sem dado ele vale
     * {@code ""} — a regra da entrada vazia (RCHFLW-101, {@code RunInputSeeding}). {@code ${input.campo}} segue a
     * regra geral. Operando sem {@code ${}} não é referência (o avaliador o lê como texto). Uma expressão
     * malformada continua lançando {@link IllegalArgumentException}, como no {@link #evaluate}.</p>
     *
     * <p>Uma comparação de ordem entre um número e um valor que não é número também fica indecisa
     * ({@link Verdict#notNumbers()}). No {@link #evaluate} ela cai na ordem do texto: {@code ${v} > 500} com
     * «1.200,00» dá {@code false} ("1" antes de "5") e com «R$ 120» dá {@code true} — o valor que um LLM em pt-BR
     * devolve escolheria o ramo do portão por ordem alfabética. Dois textos (datas ISO) seguem ordenando como
     * texto.</p>
     */
    public Verdict evaluateRequiringValues(String condition, ExecutionContext context) {
        if (condition == null || condition.isBlank()) {
            return new Verdict(true, List.of());
        }
        Set<String> unresolved = new LinkedHashSet<>();
        Set<String> notNumbers = new LinkedHashSet<>();
        Boolean result = evaluateKnown(condition.trim(), context, unresolved, notNumbers);
        return result == null
                ? new Verdict(false, List.copyOf(unresolved), List.copyOf(notNumbers))
                : new Verdict(result, List.of());
    }

    /** Como {@link #doEvaluate}, com {@code null} para "depende de um operando que não decide". */
    private Boolean evaluateKnown(String expr, ExecutionContext context, Set<String> unresolved,
                                  Set<String> notNumbers) {
        List<String> alternatives = splitTopLevel(expr, "||");
        if (alternatives.size() > 1) {
            boolean unknown = false;
            for (String alternative : alternatives) {
                Boolean value = evaluateKnown(alternative.trim(), context, unresolved, notNumbers);
                if (Boolean.TRUE.equals(value)) {
                    return true;
                }
                unknown |= value == null;
            }
            return unknown ? null : false;
        }
        List<String> conjuncts = splitTopLevel(expr, "&&");
        if (conjuncts.size() > 1) {
            boolean unknown = false;
            for (String conjunct : conjuncts) {
                Boolean value = evaluateKnown(conjunct.trim(), context, unresolved, notNumbers);
                if (Boolean.FALSE.equals(value)) {
                    return false;
                }
                unknown |= value == null;
            }
            return unknown ? null : true;
        }

        Set<String> missing = new LinkedHashSet<>();
        Boolean result;
        Matcher contains = CONTAINS.matcher(expr);
        Matcher m = COMPARISON.matcher(expr);
        if (contains.matches()) {
            Object left = resolveRequired(contains.group(1), context, missing);
            Object right = resolveRequired(contains.group(2), context, missing);
            result = missing.isEmpty() ? containsOf(left, right) : null;
        } else if (m.matches()) {
            Object left = resolveRequired(m.group(1), context, missing);
            Object right = resolveRequired(m.group(3), context, missing);
            String op = m.group(2);
            // Ordenar contra um valor nulo (o decision que falhou: decision null) não tem resposta; no evaluate
            // isso lança e vira "falso" — o ramo do "não", que pode ser justamente o do "aprova sozinho".
            if (missing.isEmpty() && !op.startsWith("=") && !op.startsWith("!") && (left == null || right == null)) {
                if (left == null) {
                    missing.add(m.group(1).trim());
                }
                if (right == null) {
                    missing.add(m.group(3).trim());
                }
            }
            Set<String> mixed = new LinkedHashSet<>();
            if (missing.isEmpty() && !op.startsWith("=") && !op.startsWith("!")) {
                boolean leftNumber = asNumber(left) != null;
                boolean rightNumber = asNumber(right) != null;
                if (leftNumber != rightNumber) {
                    String token = (leftNumber ? m.group(3) : m.group(1)).trim();
                    mixed.add(token + " = «" + (leftNumber ? right : left) + "»");
                }
            }
            notNumbers.addAll(mixed);
            result = missing.isEmpty() && mixed.isEmpty() ? compare(left, op, right) : null;
        } else {
            Object value = resolveRequired(expr, context, missing);
            result = missing.isEmpty() ? truthy(value) : null;
        }
        unresolved.addAll(missing);
        return result;
    }

    /** O operando, anotando em {@code missing} o {@code ${caminho}} que não tem valor. */
    private Object resolveRequired(String token, ExecutionContext context, Set<String> missing) {
        Matcher placeholder = PLACEHOLDER.matcher(token.trim());
        if (!placeholder.matches()) {
            return resolve(token, context);
        }
        String path = placeholder.group(1).trim();
        Object value = lookup(path, context);
        if (value == null && "input".equals(path)) {
            return "";
        }
        if (value == null && !hasPath(path, context)) {
            missing.add("${" + path + "}");
        }
        return value;
    }

    /**
     * {@code true} quando o caminho chega a uma chave que existe, mesmo com valor {@code null}: a chave inteira
     * no contexto, ou um prefixo dela seguido de mapas que têm cada campo do resto.
     *
     * <p>É o que separa "sem valor" de "valor nulo" (RCHFLW-123). O decision que falhou grava
     * {@code decision: null} de propósito — é a resposta "não sei", e o switch {@code ${D.decision}} a leva ao
     * ramo {@code default} (o humano), como o assistente ensina. Já {@code ${entrada.valor}} com a entrada em
     * texto livre não chega a chave nenhuma: é essa a referência que não pode escolher um ramo.</p>
     */
    public static boolean hasPath(String path, ExecutionContext context) {
        if (path == null || context == null) {
            return false;
        }
        if (context.get(path).isPresent()) {
            return true;
        }
        int idx = path.length();
        while ((idx = path.lastIndexOf('.', idx - 1)) > 0) {
            Optional<Object> root = context.get(path.substring(0, idx));
            if (root.isPresent() && reaches(root.get(), path.substring(idx + 1))) {
                return true;
            }
        }
        return false;
    }

    private static boolean reaches(Object root, String remainder) {
        Object current = root;
        String[] parts = remainder.split("\\.");
        for (int i = 0; i < parts.length; i++) {
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(parts[i])) {
                return false;
            }
            current = map.get(parts[i]);
            if (current == null && i < parts.length - 1) {
                return false;
            }
        }
        return true;
    }

    private boolean doEvaluate(String expr, ExecutionContext context) {
        // Composição booleana antes de tudo: `||` liga menos que `&&`, então a
        // divisão por `||` acontece primeiro (o nível de cima da árvore).
        List<String> alternatives = splitTopLevel(expr, "||");
        if (alternatives.size() > 1) {
            for (String alternative : alternatives) {
                if (doEvaluate(alternative.trim(), context)) {
                    return true;   // curto-circuito
                }
            }
            return false;
        }
        List<String> conjuncts = splitTopLevel(expr, "&&");
        if (conjuncts.size() > 1) {
            for (String conjunct : conjuncts) {
                if (!doEvaluate(conjunct.trim(), context)) {
                    return false;  // curto-circuito
                }
            }
            return true;
        }

        Matcher contains = CONTAINS.matcher(expr);
        if (contains.matches()) {
            Object left = resolve(contains.group(1), context);
            Object right = resolve(contains.group(2), context);
            return containsOf(left, right);
        }

        Matcher m = COMPARISON.matcher(expr);
        if (m.matches()) {
            Object left = resolve(m.group(1), context);
            Object right = resolve(m.group(3), context);
            return compare(left, m.group(2), right);
        }

        return truthy(resolve(expr, context));
    }

    /**
     * Divide por um operador booleano, ignorando ocorrências dentro de aspas.
     *
     * <p>Sem isso, uma condição como {@code ${msg} == 'a && b'} se partiria no
     * meio do literal e viraria duas expressões sem sentido.
     */
    static List<String> splitTopLevel(String expr, String operator) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        char quote = 0;
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                continue;
            }
            if (expr.startsWith(operator, i)) {
                parts.add(expr.substring(start, i));
                i += operator.length() - 1;
                start = i + 1;
            }
        }
        parts.add(expr.substring(start));
        // Um operador solto no fim ("a &&") deixaria um pedaço vazio: é
        // expressão malformada, e tratá-la como parte vazia a faria passar
        // silenciosamente.
        if (parts.size() > 1 && parts.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("Expressão malformada em torno de " + operator);
        }
        return parts;
    }

    private Object resolve(String token, ExecutionContext context) {
        String t = token.trim();

        Matcher placeholder = PLACEHOLDER.matcher(t);
        if (placeholder.matches()) {
            return lookup(placeholder.group(1).trim(), context);
        }
        if ((t.startsWith("'") && t.endsWith("'") && t.length() >= 2)
                || (t.startsWith("\"") && t.endsWith("\"") && t.length() >= 2)) {
            return t.substring(1, t.length() - 1);
        }
        if ("true".equalsIgnoreCase(t) || "false".equalsIgnoreCase(t)) {
            return Boolean.parseBoolean(t);
        }
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException ignored) {
            // Não é literal: tenta como variável sem ${}; senão, string crua.
            Object fromContext = lookup(t, context);
            return fromContext != null ? fromContext : t;
        }
    }

    private Object lookup(String path, ExecutionContext context) {
        return resolvePath(path, context);
    }

    /**
     * Resolve o caminho de um {@code ${caminho}} no contexto, como as condições o resolvem: a chave inteira
     * primeiro (o executor grava {@code step.<id>.output}; um id pode ter ponto), depois o prefixo mais longo
     * que é chave, descendo pelos mapas aninhados.
     *
     * <p>Público para a "Mensagem do usuário" do LLM Chat ({@link MessageTemplate}, RCHFLW-120) ler um
     * {@code ${x.y}} exatamente como uma condição o lê — um segundo resolvedor faria o mesmo texto querer dizer
     * coisas diferentes em dois campos do mesmo fluxo.</p>
     *
     * @return o valor, ou {@code null} quando nenhum prefixo resolve
     */
    public static Object resolvePath(String path, ExecutionContext context) {
        if (path == null || context == null) {
            return null;
        }
        Optional<Object> direct = context.get(path);
        if (direct.isPresent()) {
            return direct.get();
        }
        // Travessia de mapas aninhados: a.b.c → get("a") / get("a.b") + descida
        int idx = path.length();
        while ((idx = path.lastIndexOf('.', idx - 1)) > 0) {
            Optional<Object> root = context.get(path.substring(0, idx));
            if (root.isPresent()) {
                Object value = descend(root.get(), path.substring(idx + 1));
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    private static Object descend(Object root, String remainder) {
        Object current = root;
        for (String part : remainder.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(part);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private boolean compare(Object left, String op, Object right) {
        return switch (op) {
            case "==" -> looseEquals(left, right);
            case "!=" -> !looseEquals(left, right);
            case ">", ">=", "<", "<=" -> ordered(left, op, right);
            default -> throw new IllegalArgumentException("Operador desconhecido: " + op);
        };
    }

    private boolean looseEquals(Object left, Object right) {
        if (left == null || right == null) {
            return left == right;
        }
        Double ln = asNumber(left);
        Double rn = asNumber(right);
        if (ln != null && rn != null) {
            return ln.compareTo(rn) == 0;
        }
        return String.valueOf(left).equals(String.valueOf(right));
    }

    private boolean ordered(Object left, String op, Object right) {
        Double ln = asNumber(left);
        Double rn = asNumber(right);
        int cmp;
        if (ln != null && rn != null) {
            cmp = ln.compareTo(rn);
        } else if (left != null && right != null) {
            cmp = String.valueOf(left).compareTo(String.valueOf(right));
        } else {
            throw new IllegalArgumentException("Comparação ordenada com operando nulo");
        }
        return switch (op) {
            case ">" -> cmp > 0;
            case ">=" -> cmp >= 0;
            case "<" -> cmp < 0;
            case "<=" -> cmp <= 0;
            default -> throw new IllegalArgumentException("Operador desconhecido: " + op);
        };
    }

    private boolean containsOf(Object left, Object right) {
        if (left == null || right == null) {
            return false;
        }
        if (left instanceof Collection<?> collection) {
            return collection.stream().anyMatch(item -> looseEquals(item, right));
        }
        return String.valueOf(left).contains(String.valueOf(right));
    }

    private Double asNumber(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private boolean truthy(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0;
        }
        if (value instanceof String s) {
            return !s.isBlank() && !"false".equalsIgnoreCase(s.trim());
        }
        return true;
    }
}
