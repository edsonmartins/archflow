package br.com.archflow.api.agent.mcp;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Confere os argumentos de uma chamada de tool contra o {@code inputSchema} que
 * o MCP server declarou.
 *
 * <p>O laço já rejeitava argumentos que nem eram JSON. Faltava o caso mais
 * comum com modelos de tool-calling fraco: JSON <b>bem formado</b> e errado —
 * campo obrigatório ausente, número mandado como string, valor fora do enum.
 * Isso chegava à tool e virava erro do lado do server (ou, pior, execução com
 * semântica diferente da pretendida). Detectar aqui devolve ao modelo uma
 * mensagem específica, que ele consegue corrigir no turno seguinte.
 *
 * <p>Cobre deliberadamente o mesmo subset de JSON Schema que
 * {@link McpToolSpecifications} sabe converter — {@code type}, {@code required},
 * {@code enum}, {@code properties} aninhadas e {@code items} de array. Manter os
 * dois no mesmo subset evita o pior dos mundos: recusar uma chamada por causa de
 * uma construção que nem chegamos a descrever ao modelo. Propriedades não
 * declaradas são aceitas (o default de JSON Schema é {@code additionalProperties}
 * livre, e recusá-las quebraria servers legítimos).
 */
final class ToolArgumentValidator {

    private ToolArgumentValidator() {
    }

    /**
     * @return lista de violações legíveis; vazia quando os argumentos servem
     */
    static List<String> validate(Map<String, Object> schema, Map<String, Object> arguments) {
        List<String> violations = new ArrayList<>();
        validateObject("", schema, arguments == null ? Map.of() : arguments, violations);
        return violations;
    }

    @SuppressWarnings("unchecked")
    private static void validateObject(String path, Map<String, Object> schema,
                                       Map<String, Object> value, List<String> violations) {
        if (schema == null || schema.isEmpty()) {
            return;
        }

        Object required = schema.get("required");
        if (required instanceof Collection<?> names) {
            for (Object name : names) {
                String field = String.valueOf(name);
                if (!value.containsKey(field) || value.get(field) == null) {
                    violations.add("campo obrigatório ausente: " + path + field);
                }
            }
        }

        Object properties = schema.get("properties");
        if (!(properties instanceof Map<?, ?> props)) {
            return;
        }
        for (Map.Entry<?, ?> entry : props.entrySet()) {
            String field = String.valueOf(entry.getKey());
            if (!(entry.getValue() instanceof Map<?, ?> propSchema)) {
                continue;
            }
            Object actual = value.get(field);
            if (actual == null) {
                continue; // ausência já tratada por `required`
            }
            validateValue(path + field, (Map<String, Object>) propSchema, actual, violations);
        }
    }

    @SuppressWarnings("unchecked")
    private static void validateValue(String path, Map<String, Object> schema,
                                      Object value, List<String> violations) {
        Object enumValues = schema.get("enum");
        if (enumValues instanceof Collection<?> allowed && !allowed.isEmpty()) {
            boolean matches = allowed.stream()
                    .anyMatch(a -> String.valueOf(a).equals(String.valueOf(value)));
            if (!matches) {
                violations.add(path + " deve ser um de " + allowed + " (recebido: " + value + ")");
            }
            return;
        }

        String type = schema.get("type") == null ? null : String.valueOf(schema.get("type"));
        if (type == null) {
            return;
        }
        switch (type) {
            case "string" -> requireType(path, value instanceof String, "string", value, violations);
            case "boolean" -> requireType(path, value instanceof Boolean, "boolean", value, violations);
            case "integer" -> requireType(path,
                    value instanceof Integer || value instanceof Long
                            || (value instanceof Number n && n.doubleValue() == Math.floor(n.doubleValue())),
                    "integer", value, violations);
            case "number" -> requireType(path, value instanceof Number, "number", value, violations);
            case "array" -> {
                if (!(value instanceof Collection<?> items)) {
                    requireType(path, false, "array", value, violations);
                    return;
                }
                if (schema.get("items") instanceof Map<?, ?> itemSchema) {
                    int i = 0;
                    for (Object item : items) {
                        if (item != null) {
                            validateValue(path + "[" + i + "]",
                                    (Map<String, Object>) itemSchema, item, violations);
                        }
                        i++;
                    }
                }
            }
            case "object" -> {
                if (!(value instanceof Map<?, ?> nested)) {
                    requireType(path, false, "object", value, violations);
                    return;
                }
                validateObject(path + ".", schema, (Map<String, Object>) nested, violations);
            }
            default -> { /* tipo que não descrevemos ao modelo: não é justo recusar */ }
        }
    }

    private static void requireType(String path, boolean ok, String expected,
                                    Object value, List<String> violations) {
        if (!ok) {
            violations.add(path + " deve ser " + expected + " (recebido: "
                    + value.getClass().getSimpleName() + " " + value + ")");
        }
    }
}
