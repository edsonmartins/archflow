package br.com.archflow.agent.execution;

import br.com.archflow.model.engine.ExecutionContext;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
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
 * <p>Condição em branco segue a transição. Condição não avaliável também segue,
 * com warning — preserva o comportamento permissivo de fluxos legados; a
 * validação estática (DefaultFlowValidator) é o lugar de rejeitar expressões
 * malformadas.
 */
public final class ConditionEvaluator {

    private static final Logger logger = Logger.getLogger(ConditionEvaluator.class.getName());

    private static final Pattern COMPARISON = Pattern.compile(
            "^(.+?)\\s*(==|!=|>=|<=|>|<)\\s*(.+)$");
    private static final Pattern CONTAINS = Pattern.compile(
            "^(.+?)\\s+contains\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{(.+)}$");

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
            logger.warning("Condição não avaliável, seguindo a transição: \""
                    + condition + "\" (" + e.getMessage() + ")");
            return true;
        }
    }

    private boolean doEvaluate(String expr, ExecutionContext context) {
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

    private Object descend(Object root, String remainder) {
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
