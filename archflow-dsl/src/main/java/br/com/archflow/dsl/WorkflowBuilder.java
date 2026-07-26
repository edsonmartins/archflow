package br.com.archflow.dsl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Monta o documento canônico. Obtido por {@link Workflows#define(String)}.
 *
 * <p>Mutável e não thread-safe: a expectativa é que um builder seja construído,
 * usado e descartado numa expressão só. As {@link NodeSpec} que ele consome,
 * essas sim, são imutáveis e reusáveis.
 *
 * @since 1.1.0
 */
public final class WorkflowBuilder {

    private final String id;
    private String name;
    private String description;
    private String version = "1.0.0";
    private String author;
    private String category;
    private List<String> allowedComponents;

    /** Ordem de inserção preservada: é a ordem em que os passos aparecem no JSON. */
    private final Map<String, NodeSpec> steps = new LinkedHashMap<>();
    private final List<Edge> edges = new ArrayList<>();

    WorkflowBuilder(String id) {
        this.id = id;
    }

    // ------------------------------------------------------------- metadata

    public WorkflowBuilder named(String name) {
        this.name = name;
        return this;
    }

    public WorkflowBuilder describedAs(String description) {
        this.description = description;
        return this;
    }

    /** Default {@code 1.0.0}, o mesmo que o desserializador assume quando ausente. */
    public WorkflowBuilder version(String version) {
        this.version = version;
        return this;
    }

    public WorkflowBuilder author(String author) {
        this.author = author;
        return this;
    }

    public WorkflowBuilder category(String category) {
        this.category = category;
        return this;
    }

    /**
     * Restringe o fluxo a estes componentes ({@code configuration.allowedComponents}).
     *
     * <p>Não é decoração: um passo que resolva um componente fora da lista falha
     * com "component not found", e a restrição vale também para os sub-agentes
     * de um nó de orquestração — senão seria contornável por delegação.
     *
     * <p><b>Não declarar significa sem restrição</b>, que é o default histórico.
     * Um deployment com {@code archflow.components.require-allowlist=true}
     * inverte isso: lá, a ausência nega tudo.
     *
     * <p>Declarada, ela é conferida em {@link #build()} contra os componentes
     * que os passos de fato usam.
     */
    public WorkflowBuilder allowing(String... componentIds) {
        this.allowedComponents = componentIds == null ? List.of() : List.of(componentIds);
        return this;
    }

    // ---------------------------------------------------------------- grafo

    /**
     * Acrescenta um passo.
     *
     * @param stepId identificador único dentro do fluxo; é por ele que as
     *               arestas se referem ao passo
     */
    public WorkflowBuilder step(String stepId, NodeSpec spec) {
        Objects.requireNonNull(spec, "spec do passo " + stepId);
        if (stepId == null || stepId.isBlank()) {
            throw new DslValidationException("um passo foi declarado sem id");
        }
        if (steps.putIfAbsent(stepId, spec) != null) {
            // Falha aqui, e não em build(): o segundo step() com o mesmo id
            // sobrescreveria o primeiro em silêncio, e o fluxo resultante teria
            // um passo a menos do que o código aparenta ter.
            throw new DslValidationException("passo duplicado: " + stepId);
        }
        return this;
    }

    /** Aresta normal: quando {@code from} termina bem, segue para {@code to}. */
    public WorkflowBuilder edge(String from, String to) {
        return edge(from, to, null, false);
    }

    /**
     * Aresta condicional. A condição é avaliada pelo motor sobre o resultado do
     * passo de origem; a DSL a transporta como texto, sem interpretar.
     */
    public WorkflowBuilder edge(String from, String to, String condition) {
        return edge(from, to, condition, false);
    }

    /** Caminho de erro: percorrido quando {@code from} falha. */
    public WorkflowBuilder onError(String from, String to) {
        return edge(from, to, null, true);
    }

    private WorkflowBuilder edge(String from, String to, String condition, boolean errorPath) {
        edges.add(new Edge(from, to, condition, errorPath));
        return this;
    }

    // ---------------------------------------------------------------- build

    /**
     * Valida e produz o documento.
     *
     * <p>O que é conferido aqui é o que dá para conferir sem servidor e sem
     * catálogo — erros que, sem isso, só apareceriam no meio de uma execução:
     *
     * <ul>
     *   <li>id do fluxo em branco;</li>
     *   <li>fluxo sem nenhum passo;</li>
     *   <li>aresta apontando para um passo que não existe (o motor
     *       simplesmente não caminharia para lá, sem erro nenhum);</li>
     *   <li>aresta partindo de um passo que não existe (ela seria gravada em
     *       lugar nenhum e sumiria);</li>
     *   <li>componente usado por um passo mas fora do {@code allowedComponents}
     *       declarado — em runtime isso vira "component not found", longe da
     *       causa.</li>
     * </ul>
     *
     * <p>O que <b>não</b> é conferido: se o componente existe de fato, se o
     * provider do adapter está no classpath, e se as chaves de {@code config}
     * fazem sentido para aquele componente. Nada disso é conhecível fora do
     * runtime que vai executar o fluxo, e fingir o contrário daria uma
     * aprovação falsa.
     *
     * @throws DslValidationException com todos os problemas encontrados, não só
     *                                o primeiro
     */
    public WorkflowDocument build() {
        List<String> problems = new ArrayList<>();

        if (id == null || id.isBlank()) {
            problems.add("o fluxo precisa de um id");
        }
        if (steps.isEmpty()) {
            problems.add("o fluxo não tem nenhum passo");
        }

        for (Edge edge : edges) {
            if (!steps.containsKey(edge.from())) {
                problems.add("aresta parte de um passo inexistente: " + edge.from()
                        + " -> " + edge.to());
            }
            if (!steps.containsKey(edge.to())) {
                problems.add("aresta aponta para um passo inexistente: " + edge.from()
                        + " -> " + edge.to());
            }
        }

        if (allowedComponents != null) {
            Set<String> allowed = new LinkedHashSet<>(allowedComponents);
            steps.forEach((stepId, spec) -> {
                String component = spec.componentId();
                if (component != null && !allowed.contains(component)) {
                    problems.add("o passo " + stepId + " usa o componente '" + component
                            + "', que não está em allowedComponents " + allowedComponents);
                }
            });
        }

        if (!problems.isEmpty()) {
            throw new DslValidationException(id, problems);
        }

        return new WorkflowDocument(render());
    }

    /**
     * Monta o mapa exatamente na forma que {@code DefaultWorkflowDeserializer} e
     * {@code DefaultFlowStepFactory} leem.
     *
     * <p>Duas escolhas que parecem detalhe e não são:
     *
     * <ul>
     *   <li>as arestas são gravadas <b>dentro de cada passo</b>
     *       ({@code steps[].connections}), que é de onde o motor as lê. Os
     *       templates da UI trazem um {@code connections} no topo do documento
     *       — aquele é outro formato, e emitir aquele aqui produziria um fluxo
     *       que carrega no designer e não caminha ao executar;</li>
     *   <li>campos nulos são omitidos em vez de virarem {@code null}, para que
     *       o YAML de um fluxo escrito em código não venha poluído de chaves
     *       vazias.</li>
     * </ul>
     */
    private Map<String, Object> render() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        putIfPresent(metadata, "name", name != null ? name : id);
        putIfPresent(metadata, "description", description);
        putIfPresent(metadata, "version", version);
        putIfPresent(metadata, "author", author);
        putIfPresent(metadata, "category", category);

        List<Map<String, Object>> renderedSteps = new ArrayList<>();
        steps.forEach((stepId, spec) -> {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", stepId);
            putIfPresent(node, "type", spec.type());
            putIfPresent(node, "componentId", spec.componentId());
            putIfPresent(node, "label", spec.label());
            putIfPresent(node, "operation", spec.operation());
            if (spec.position() != null) {
                node.put("position", new LinkedHashMap<>(spec.position()));
            }
            if (!spec.config().isEmpty()) {
                node.put("config", new LinkedHashMap<>(spec.config()));
            }
            node.put("connections", connectionsOf(stepId));
            renderedSteps.add(node);
        });

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", id);
        document.put("metadata", metadata);
        if (allowedComponents != null) {
            document.put("configuration",
                    new LinkedHashMap<>(Map.of("allowedComponents", List.copyOf(allowedComponents))));
        }
        document.put("steps", renderedSteps);
        return document;
    }

    private List<Map<String, Object>> connectionsOf(String stepId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Edge edge : edges) {
            if (!edge.from().equals(stepId)) {
                continue;
            }
            Map<String, Object> connection = new LinkedHashMap<>();
            connection.put("sourceId", edge.from());
            connection.put("targetId", edge.to());
            putIfPresent(connection, "condition", edge.condition());
            connection.put("isErrorPath", edge.errorPath());
            result.add(connection);
        }
        return result;
    }

    private static void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private record Edge(String from, String to, String condition, boolean errorPath) {
    }
}
