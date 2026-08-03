package br.com.archflow.dsl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Caminho de volta: um workflow salvo (JSON ou YAML) vira código da DSL.
 *
 * <pre>{@code
 * GeneratedCode code = WorkflowCodeGenerator.fromJson(json)
 *         .inPackage("com.acme.flows")
 *         .generate();
 *
 * if (!code.isLossless()) {
 *     code.unrepresented().forEach(System.err::println);  // não jogue o JSON fora ainda
 * }
 * }</pre>
 *
 * <p>Serve para migrar o que já existe — fluxos desenhados no designer ou
 * escritos à mão — sem redigitá-los.
 *
 * <h2>O que ele não promete</h2>
 * Que o código gerado seja bonito, e que ele cubra todo documento possível. O
 * que ele promete é <b>dizer</b> quando não cobriu: ver
 * {@link GeneratedCode#unrepresented()}. Um gerador silencioso seria pior que
 * nenhum, porque o usuário só descobriria a perda depois de apagar o original.
 *
 * @since 1.1.0
 */
public final class WorkflowCodeGenerator {

    /**
     * Chaves do documento que o gerador entende. Uma chave fora desta lista é
     * reportada como não representada — a lista é a definição operacional de
     * "o que a DSL sabe escrever".
     */
    private static final Set<String> KNOWN_DOCUMENT_KEYS =
            Set.of("id", "metadata", "configuration", "steps");
    private static final Set<String> KNOWN_STEP_KEYS =
            Set.of("id", "type", "componentId", "label", "operation", "position", "config",
                    "configuration", "connections");
    private static final Set<String> KNOWN_METADATA_KEYS =
            Set.of("name", "description", "version", "author", "category");
    private static final Set<String> KNOWN_CONNECTION_KEYS =
            Set.of("id", "sourceId", "targetId", "condition", "isErrorPath", "errorPath", "type");

    /** Tipos de nó com atalho em {@link Nodes} — espelham {@code AdapterNodeTypes}. */
    private static final Map<String, String> ADAPTER_SHORTCUTS = Map.of(
            "llm-chat", "llmChat",
            "llm-streaming", "llmStreaming",
            "embedding", "embedding",
            "vector-search", "vectorSearch",
            "vector-store", "vectorStore",
            "memory", "memory",
            "rag", "rag");

    private final Map<String, Object> document;
    private String packageName;
    private String className;

    private WorkflowCodeGenerator(Map<String, Object> document) {
        this.document = document;
    }

    /** A partir do documento já em memória. */
    public static WorkflowCodeGenerator from(Map<String, Object> document) {
        return new WorkflowCodeGenerator(document);
    }

    /** A partir do JSON. */
    public static WorkflowCodeGenerator fromJson(String json) {
        return new WorkflowCodeGenerator(read(new ObjectMapper(), json, "JSON"));
    }

    /** A partir do YAML — o mesmo texto de {@code GET /api/workflows/{id}/yaml}. */
    public static WorkflowCodeGenerator fromYaml(String yaml) {
        return new WorkflowCodeGenerator(read(new ObjectMapper(new YAMLFactory()), yaml, "YAML"));
    }

    /** A partir de um arquivo; o formato vem da extensão. */
    public static WorkflowCodeGenerator fromFile(Path file) {
        String content;
        try {
            content = Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("falha ao ler " + file, e);
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".yaml") || name.endsWith(".yml") ? fromYaml(content) : fromJson(content);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(ObjectMapper mapper, String content, String format) {
        try {
            return mapper.readValue(content, Map.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("conteúdo não é um documento de workflow em " + format, e);
        }
    }

    /** Pacote da classe gerada. Sem isto, a classe sai no pacote default. */
    public WorkflowCodeGenerator inPackage(String packageName) {
        this.packageName = packageName;
        return this;
    }

    /** Nome da classe. Default: derivado do id do fluxo. */
    public WorkflowCodeGenerator className(String className) {
        this.className = className;
        return this;
    }

    /** Gera o código. */
    public GeneratedCode generate() {
        List<String> unrepresented = new ArrayList<>();
        String id = str(document.get("id"), "");
        String type = className != null ? className : classNameFor(id);

        Map<String, Object> metadata = asMap(document.get("metadata"));
        List<Object> steps = asList(document.get("steps"));

        reportUnknownKeys(document, KNOWN_DOCUMENT_KEYS, "documento", unrepresented);
        reportUnknownKeys(metadata, KNOWN_METADATA_KEYS, "metadata", unrepresented);

        StringBuilder out = new StringBuilder();
        if (packageName != null && !packageName.isBlank()) {
            out.append("package ").append(packageName).append(";\n\n");
        }
        out.append("import br.com.archflow.dsl.Nodes;\n")
                .append("import br.com.archflow.dsl.WorkflowDocument;\n")
                .append("import br.com.archflow.dsl.Workflows;\n")
                .append("import br.com.archflow.model.config.LLMConfigPatch;\n\n")
                .append("/** Gerado a partir do workflow \"").append(escape(id)).append("\". */\n")
                .append("public final class ").append(type).append(" {\n\n")
                .append("    private ").append(type).append("() {\n    }\n\n")
                .append("    public static WorkflowDocument build() {\n")
                .append("        return Workflows.define(\"").append(escape(id)).append("\")\n");

        appendMetadata(out, metadata);
        appendAllowedComponents(out, unrepresented);
        appendLLMConfig(out);
        appendSteps(out, steps, unrepresented);
        appendEdges(out, steps, unrepresented);

        out.append("                .build();\n")
                .append("    }\n")
                .append("}\n");

        return new GeneratedCode(out.toString(), packageName, type, unrepresented);
    }

    private void appendMetadata(StringBuilder out, Map<String, Object> metadata) {
        appendIfPresent(out, "named", metadata.get("name"));
        appendIfPresent(out, "describedAs", metadata.get("description"));
        appendIfPresent(out, "version", metadata.get("version"));
        appendIfPresent(out, "author", metadata.get("author"));
        appendIfPresent(out, "category", metadata.get("category"));
    }

    private void appendAllowedComponents(StringBuilder out, List<String> unrepresented) {
        Map<String, Object> configuration = asMap(document.get("configuration"));
        Object allowed = configuration.get("allowedComponents");
        if (allowed instanceof List<?> ids && !ids.isEmpty()) {
            out.append("                .allowing(");
            for (int i = 0; i < ids.size(); i++) {
                if (i > 0) {
                    out.append(", ");
                }
                out.append('"').append(escape(String.valueOf(ids.get(i)))).append('"');
            }
            out.append(")\n");
        }
        configuration.keySet().stream()
                .filter(key -> !"allowedComponents".equals(key) && !"llmConfig".equals(key))
                .forEach(key -> unrepresented.add(
                        "configuration." + key + " — a DSL só modela allowedComponents"));
    }

    private void appendLLMConfig(StringBuilder out) {
        Map<String, Object> llm = asMap(asMap(document.get("configuration")).get("llmConfig"));
        if (!llm.isEmpty()) {
            out.append("                .llm(LLMConfigPatch.fromMap(")
                    .append(literal(llm)).append("))\n");
        }
    }

    private void appendSteps(StringBuilder out, List<Object> steps, List<String> unrepresented) {
        for (Object item : steps) {
            Map<String, Object> step = asMap(item);
            String stepId = str(step.get("id"), "");
            reportUnknownKeys(step, KNOWN_STEP_KEYS, "passo " + stepId, unrepresented);

            out.append("                .step(\"").append(escape(stepId)).append("\", ")
                    .append(nodeExpression(step))
                    .append(")\n");
        }
    }

    /**
     * A expressão de {@link Nodes} que reconstrói o nó.
     *
     * <p>Prefere o atalho tipado quando o tipo é um dos servidos por adapter, e
     * cai para {@code component(...)} no resto — a mesma assimetria da DSL, e
     * pela mesma razão: fora dos tipos de adapter não existe conjunto fechado.
     */
    private String nodeExpression(Map<String, Object> step) {
        String type = str(step.get("type"), "");
        String componentId = str(step.get("componentId"), type);
        Map<String, Object> config = configOf(step);

        StringBuilder expr = new StringBuilder("Nodes.");
        String shortcut = ADAPTER_SHORTCUTS.get(type.toLowerCase(Locale.ROOT));
        if ("APPROVAL".equalsIgnoreCase(type)) {
            expr.append("approval()");
        } else if ("ORCHESTRATE".equalsIgnoreCase(type)) {
            expr.append("orchestrate()");
        } else if (shortcut != null) {
            expr.append(shortcut).append("(\"").append(escape(componentId)).append("\")");
        } else {
            expr.append("component(\"").append(escape(type)).append("\", \"")
                    .append(escape(componentId)).append("\")");
        }

        config.forEach((key, value) -> {
            // O provider já é o argumento do atalho de adapter; repeti-lo em
            // .with() geraria código correto mas redundante.
            if (shortcut != null && "provider".equals(key) && componentId.equals(String.valueOf(value))) {
                return;
            }
            expr.append("\n                        .with(\"").append(escape(key)).append("\", ")
                    .append(literal(value)).append(')');
        });

        String label = str(step.get("label"), null);
        if (label != null) {
            expr.append("\n                        .labeled(\"").append(escape(label)).append("\")");
        }
        String operation = str(step.get("operation"), null);
        if (operation != null) {
            expr.append("\n                        .operation(\"").append(escape(operation)).append("\")");
        }
        Map<String, Object> position = asMap(step.get("position"));
        if (position.get("x") != null && position.get("y") != null) {
            expr.append("\n                        .at(").append(number(position.get("x")))
                    .append(", ").append(number(position.get("y"))).append(')');
        }
        return expr.toString();
    }

    private void appendEdges(StringBuilder out, List<Object> steps, List<String> unrepresented) {
        for (Object item : steps) {
            Map<String, Object> step = asMap(item);
            String stepId = str(step.get("id"), "");
            for (Object c : asList(step.get("connections"))) {
                Map<String, Object> connection = asMap(c);
                reportUnknownKeys(connection, KNOWN_CONNECTION_KEYS,
                        "conexão de " + stepId, unrepresented);

                String source = str(connection.get("sourceId"), stepId);
                String target = str(connection.get("targetId"), "");
                String condition = str(connection.get("condition"), null);
                boolean errorPath = bool(connection.get("isErrorPath"))
                        || bool(connection.get("errorPath"))
                        || "error".equals(connection.get("type"));

                if (errorPath) {
                    if (condition != null) {
                        // A DSL não tem "aresta de erro condicional": onError não
                        // aceita condição. Emitir edge(...) perderia o caminho de
                        // erro; emitir onError(...) perderia a condição.
                        unrepresented.add("conexão " + source + " -> " + target
                                + " é caminho de erro E tem condição; a DSL não combina os dois"
                                + " (a condição foi omitida do código gerado)");
                    }
                    out.append("                .onError(\"").append(escape(source)).append("\", \"")
                            .append(escape(target)).append("\")\n");
                } else if (condition != null) {
                    out.append("                .edge(\"").append(escape(source)).append("\", \"")
                            .append(escape(target)).append("\", \"")
                            .append(escape(condition)).append("\")\n");
                } else {
                    out.append("                .edge(\"").append(escape(source)).append("\", \"")
                            .append(escape(target)).append("\")\n");
                }
            }
        }
    }

    // ------------------------------------------------------------- utilidades

    private static void reportUnknownKeys(Map<String, Object> source, Set<String> known,
                                          String where, List<String> unrepresented) {
        Set<String> unknown = new LinkedHashSet<>(source.keySet());
        unknown.removeAll(known);
        unknown.forEach(key -> unrepresented.add(where + "." + key + " — a DSL não modela este campo"));
    }

    private static void appendIfPresent(StringBuilder out, String method, Object value) {
        String text = str(value, null);
        if (text != null) {
            out.append("                .").append(method).append("(\"")
                    .append(escape(text)).append("\")\n");
        }
    }

    /** {@code config} e {@code configuration} são a mesma coisa para o motor. */
    private static Map<String, Object> configOf(Map<String, Object> step) {
        Map<String, Object> merged = new java.util.LinkedHashMap<>(asMap(step.get("config")));
        merged.putAll(asMap(step.get("configuration")));
        return merged;
    }

    /**
     * O literal Java de um valor de configuração.
     *
     * <p>Mapas e listas viram {@code Map.of(...)} / {@code List.of(...)}; o
     * resto vira o literal do tipo. Um valor que não caiba nisso é emitido como
     * texto — o gerador prefere código compilável e explícito a um cast criativo.
     */
    private static String literal(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            return "\"" + escape(s) + "\"";
        }
        if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
            return String.valueOf(value);
        }
        if (value instanceof Double || value instanceof Float) {
            return String.valueOf(value);
        }
        if (value instanceof List<?> items) {
            StringBuilder sb = new StringBuilder("java.util.List.of(");
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(literal(items.get(i)));
            }
            return sb.append(')').toString();
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("java.util.Map.of(");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append('"').append(escape(String.valueOf(entry.getKey()))).append("\", ")
                        .append(literal(entry.getValue()));
            }
            return sb.append(')').toString();
        }
        return "\"" + escape(String.valueOf(value)) + "\"";
    }

    private static String number(Object value) {
        return value instanceof Number n ? String.valueOf(n.doubleValue()) : "0.0";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /** {@code meu-fluxo-v2} → {@code MeuFluxoV2Flow}. */
    static String classNameFor(String id) {
        StringBuilder sb = new StringBuilder();
        boolean upper = true;
        for (char c : id.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                sb.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            } else {
                upper = true;
            }
        }
        if (sb.isEmpty() || Character.isDigit(sb.charAt(0))) {
            sb.insert(0, "Workflow");
        }
        return sb.append("Flow").toString();
    }

    private static String str(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = value.toString();
        return text.isBlank() ? fallback : text;
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean b ? b
                : value instanceof String s && Boolean.parseBoolean(s);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return value instanceof List<?> l ? (List<Object>) l : List.of();
    }
}
