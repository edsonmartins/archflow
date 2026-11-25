package br.com.archflow.template;

import br.com.archflow.template.WorkflowTemplateDefinition.TemplateConnection;
import br.com.archflow.template.WorkflowTemplateDefinition.TemplateNode;
import br.com.archflow.template.WorkflowTemplateDefinition.TemplateStructure;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converte a estrutura de um template SPI ({@link TemplateStructure}) nos {@code steps[]} do
 * documento canônico de workflow — o mesmo que o motor ({@code DefaultFlowStepFactory}) executa,
 * o designer abre e o {@code SerializableStep} do archflow-standalone transporta.
 *
 * <p><b>Por que existe.</b> Os templates SPI nasceram como "metadados + Workflow" e o conversor
 * que o javadoc deles prometia nunca foi escrito: {@code GET /api/templates} só mandava
 * {@code stepCount} e {@code /install} gravava {@code steps: []}. Clonar qualquer template do
 * backend criava um fluxo vazio (RCHFLW-18). Este é o único lugar que faz a conversão — a API de
 * templates e o install passam por aqui, então a galeria e o install não podem divergir.
 *
 * <p>Regras do documento que importam aqui (ver CLAUDE.md, "três leitores"):
 * <ul>
 *   <li>As arestas vão <b>dentro de cada passo</b> ({@code steps[].connections}), que é onde o
 *       motor lê. Um {@code connections} no topo abre no designer e não anda na execução.</li>
 *   <li>O {@code type} cru é preservado ({@code "vector-search"}, não um enum): o roteamento de
 *       adapter casa pelo tipo do nó, e reescrevê-lo mudaria qual executor o motor escolhe.</li>
 * </ul>
 *
 * <p>Os templates escrevem marcadores {@code {{x}}}. Na configuração dos nós, {@code {{p}}} de um
 * parâmetro do template é substituído pelo valor (o mesmo merge de defaults que o
 * {@code createInstance} faz); qualquer outro {@code {{x}}} fica como está, porque é variável de
 * prompt resolvida em tempo de execução. Nas condições das arestas o motor só entende
 * {@code ${x}} ({@code ConditionEvaluator}): parâmetro vira literal e o resto vira {@code ${x}}.
 * Deixar um parâmetro como {@code ${hybridSearch}} não funcionaria — ele não existe no contexto
 * da execução, e os dois ramos ({@code == true} / {@code == false}) seriam falsos.
 *
 * <p>Sem Spring e sem motor: depende só do modelo dos templates, como o resto deste módulo.
 */
public final class TemplateDocument {

    /** Distância horizontal entre camadas do layout. */
    static final int LAYER_WIDTH = 260;

    /** Distância vertical entre nós da mesma camada. */
    static final int ROW_HEIGHT = 140;

    static final int ORIGIN_X = 80;

    static final int ORIGIN_Y = 80;

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.\\-]+)\\s*}}");

    /** Operadores do {@code ConditionEvaluator}; dentro de um literal colado eles partiriam a expressão. */
    private static final Pattern UNSAFE_IN_LITERAL =
            Pattern.compile("==|!=|<|>|&&|\\|\\||(?i)\\bcontains\\b");

    private static final Pattern WHOLE_PLACEHOLDER = Pattern.compile("^\\{\\{\\s*([A-Za-z0-9_.\\-]+)\\s*}}$");

    /**
     * Ordem natural dos ids de conexão ({@code c2} antes de {@code c10}). O {@link TemplateStructure}
     * guarda os mapas com {@code Map.copyOf}, que não preserva a ordem de declaração — sem uma ordem
     * explícita o documento sairia diferente a cada JVM.
     */
    private static final Comparator<String> NATURAL = Comparator
            .comparing((String s) -> s.replaceAll("\\d+", "").toLowerCase())
            .thenComparingLong(TemplateDocument::numericPart)
            .thenComparing(Comparator.naturalOrder());

    private TemplateDocument() {
    }

    /** Os passos com os parâmetros default do template — o que a galeria mostra e o clone grava. */
    public static List<Map<String, Object>> steps(WorkflowTemplateDefinition definition) {
        return steps(definition, Map.of());
    }

    /**
     * Os passos do documento canônico, com os parâmetros informados sobrepostos aos defaults do
     * template.
     *
     * @param definition a definição do template; {@code null} ou sem estrutura dá lista vazia
     * @param parameters parâmetros de quem instala (pode ser {@code null})
     */
    public static List<Map<String, Object>> steps(WorkflowTemplateDefinition definition,
                                                  Map<String, Object> parameters) {
        if (definition == null || definition.getStructure() == null) {
            return List.of();
        }
        Map<String, Object> resolved = new HashMap<>(definition.getDefaultParameters());
        if (parameters != null) {
            parameters.forEach((k, v) -> {
                if (k != null) {
                    resolved.put(k, v);
                }
            });
        }
        return steps(definition.getStructure(), resolved);
    }

    static List<Map<String, Object>> steps(TemplateStructure structure, Map<String, Object> parameters) {
        Map<String, TemplateNode> nodes = structure.getNodes();
        Map<String, List<TemplateConnection>> outgoing = outgoing(structure);
        Layout layout = Layout.of(structure.getEntryPoint(), nodes, outgoing);

        List<Map<String, Object>> steps = new ArrayList<>();
        for (String nodeId : layout.order()) {
            TemplateNode node = nodes.get(nodeId);
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("id", node.getId());
            step.put("type", node.getType());
            // componentId = type: é o que o designer grava para nós de catálogo e o que o motor
            // usa (kindOf) para decidir o executor.
            step.put("componentId", node.getType());
            step.put("label", node.getName() != null ? node.getName() : node.getId());
            step.put("position", layout.position(nodeId));
            step.put("configuration", resolveConfig(node.getConfiguration(), parameters));
            List<Map<String, Object>> connections = new ArrayList<>();
            for (TemplateConnection c : outgoing.getOrDefault(nodeId, List.of())) {
                Map<String, Object> conn = new LinkedHashMap<>();
                conn.put("sourceId", c.getSource());
                conn.put("targetId", c.getTarget());
                conn.put("isErrorPath", false);
                String condition = convertCondition(c.getCondition(), parameters);
                if (condition != null) {
                    conn.put("condition", condition);
                }
                connections.add(conn);
            }
            step.put("connections", connections);
            steps.add(step);
        }
        return steps;
    }

    /**
     * Reescreve uma condição de template na gramática do motor.
     *
     * @return a condição convertida, ou {@code null} quando em branco (aresta incondicional)
     */
    static String convertCondition(String condition, Map<String, Object> parameters) {
        if (condition == null || condition.isBlank()) {
            return null;
        }
        Matcher m = PLACEHOLDER.matcher(condition.trim());
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String replacement = parameters.containsKey(name)
                    ? literal(name, parameters.get(name))
                    : "${" + name + "}";
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Literal da gramática do {@code ConditionEvaluator}: número/booleano cru, o resto entre aspas.
     *
     * <p>O valor vem de quem instala ({@code POST /install}) e é colado dentro da expressão. O
     * avaliador não tem escape e o recorte da comparação ({@code COMPARISON}) não respeita aspas:
     * um valor com os dois tipos de aspa fecharia o literal, e um com operador ({@code ==},
     * {@code ||}...) partiria a comparação quando o parâmetro está à esquerda
     * ({@code {{hybridSearch}} == true}). Em modo permissivo uma condição assim reescrita ou
     * inavaliável <b>segue a aresta</b> — então o valor é recusado (400), nunca colado.
     *
     * @throws IllegalArgumentException quando o valor não cabe num literal seguro
     */
    private static String literal(String name, Object value) {
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        String text = value == null ? "" : value.toString();
        if ((text.contains("'") && text.contains("\"")) || UNSAFE_IN_LITERAL.matcher(text).find()) {
            throw new IllegalArgumentException("O parâmetro '" + name + "' é usado numa condição do template"
                    + " e não pode conter aspas simples e duplas juntas nem operadores"
                    + " (==, !=, <, >, &&, ||, contains)");
        }
        // Um valor com aspa simples vai entre aspas duplas (o avaliador aceita as duas).
        return text.contains("'") ? "\"" + text + "\"" : "'" + text + "'";
    }

    static Object resolveValue(Object value, Map<String, Object> parameters) {
        if (value instanceof String s) {
            Matcher whole = WHOLE_PLACEHOLDER.matcher(s.trim());
            if (whole.matches() && parameters.containsKey(whole.group(1))) {
                // Marcador sozinho mantém o tipo do parâmetro (topK continua número, agents lista).
                return parameters.get(whole.group(1));
            }
            Matcher m = PLACEHOLDER.matcher(s);
            StringBuilder out = new StringBuilder();
            while (m.find()) {
                String name = m.group(1);
                String replacement = parameters.containsKey(name)
                        ? String.valueOf(parameters.get(name))
                        : m.group();
                m.appendReplacement(out, Matcher.quoteReplacement(replacement));
            }
            m.appendTail(out);
            return out.toString();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(Comparator.comparing(String::valueOf)))
                    .forEach(e -> copy.put(String.valueOf(e.getKey()), resolveValue(e.getValue(), parameters)));
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(resolveValue(item, parameters));
            }
            return copy;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resolveConfig(Map<String, Object> config, Map<String, Object> parameters) {
        return config == null ? new LinkedHashMap<>() : (Map<String, Object>) resolveValue(config, parameters);
    }

    private static Map<String, List<TemplateConnection>> outgoing(TemplateStructure structure) {
        Map<String, List<TemplateConnection>> outgoing = new HashMap<>();
        structure.getConnections().values().stream()
                .filter(c -> c.getSource() != null && c.getTarget() != null)
                // Aresta para um nó que não existe viraria um alvo pendurado no motor.
                .filter(c -> structure.getNodes().containsKey(c.getSource())
                        && structure.getNodes().containsKey(c.getTarget()))
                .sorted(Comparator.comparing(TemplateConnection::getId, NATURAL))
                .forEach(c -> outgoing.computeIfAbsent(c.getSource(), k -> new ArrayList<>()).add(c));
        return outgoing;
    }

    private static long numericPart(String s) {
        String digits = s.replaceAll("\\D+", "");
        if (digits.isEmpty()) {
            return -1;
        }
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException tooLong) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * Layout em camadas da esquerda para a direita, para o designer não abrir os nós empilhados.
     *
     * <p>Camada = caminho mais longo a partir da entrada, ignorando as arestas de volta (o laço
     * refiner → quality-checker do supervisor): com caminho mais curto o nó final ficaria na
     * mesma coluna de um nó que aponta para ele.
     */
    private record Layout(List<String> order, Map<String, Map<String, Object>> positions) {

        Map<String, Object> position(String nodeId) {
            return positions.get(nodeId);
        }

        static Layout of(String entryPoint, Map<String, TemplateNode> nodes,
                         Map<String, List<TemplateConnection>> outgoing) {
            List<String> roots = new ArrayList<>();
            if (entryPoint != null && nodes.containsKey(entryPoint)) {
                roots.add(entryPoint);
            }
            nodes.keySet().stream().sorted(NATURAL).filter(id -> !roots.contains(id)).forEach(roots::add);

            // DFS: marca as arestas de volta e devolve a ordem de descoberta.
            Set<String> visited = new HashSet<>();
            Set<String> onStack = new HashSet<>();
            Set<TemplateConnection> backEdges = new HashSet<>();
            List<String> discovery = new ArrayList<>();
            for (String root : roots) {
                dfs(root, nodes, outgoing, visited, onStack, backEdges, discovery);
            }

            // Caminho mais longo no DAG que sobra (Kahn na ordem de descoberta, para ser estável).
            Map<String, Integer> indegree = new HashMap<>();
            for (String id : discovery) {
                indegree.putIfAbsent(id, 0);
                for (TemplateConnection c : outgoing.getOrDefault(id, List.of())) {
                    if (!backEdges.contains(c) && nodes.containsKey(c.getTarget())) {
                        indegree.merge(c.getTarget(), 1, Integer::sum);
                    }
                }
            }
            Map<String, Integer> layer = new HashMap<>();
            Deque<String> ready = new ArrayDeque<>();
            for (String id : discovery) {
                if (indegree.get(id) == 0) {
                    ready.add(id);
                    layer.put(id, 0);
                }
            }
            List<String> topo = new ArrayList<>();
            while (!ready.isEmpty()) {
                String id = ready.poll();
                topo.add(id);
                for (TemplateConnection c : outgoing.getOrDefault(id, List.of())) {
                    String target = c.getTarget();
                    if (backEdges.contains(c) || !nodes.containsKey(target)) {
                        continue;
                    }
                    layer.merge(target, layer.get(id) + 1, Math::max);
                    if (indegree.merge(target, -1, Integer::sum) == 0) {
                        ready.add(target);
                    }
                }
            }

            Map<Integer, List<String>> byLayer = new TreeMap<>();
            for (String id : topo) {
                byLayer.computeIfAbsent(layer.get(id), k -> new ArrayList<>()).add(id);
            }
            int tallest = byLayer.values().stream().mapToInt(List::size).max().orElse(1);
            List<String> order = new ArrayList<>();
            Map<String, Map<String, Object>> positions = new HashMap<>();
            byLayer.forEach((l, ids) -> {
                // Centraliza a coluna em relação à mais alta, para os ramos ficarem simétricos.
                int offset = (tallest - ids.size()) * ROW_HEIGHT / 2;
                for (int i = 0; i < ids.size(); i++) {
                    Map<String, Object> pos = new LinkedHashMap<>();
                    pos.put("x", ORIGIN_X + l * LAYER_WIDTH);
                    pos.put("y", ORIGIN_Y + offset + i * ROW_HEIGHT);
                    positions.put(ids.get(i), pos);
                    order.add(ids.get(i));
                }
            });
            return new Layout(order, positions);
        }

        private static void dfs(String id, Map<String, TemplateNode> nodes,
                                Map<String, List<TemplateConnection>> outgoing, Set<String> visited,
                                Set<String> onStack, Set<TemplateConnection> backEdges, List<String> discovery) {
            if (!visited.add(id)) {
                return;
            }
            onStack.add(id);
            discovery.add(id);
            for (TemplateConnection c : outgoing.getOrDefault(id, List.of())) {
                String target = c.getTarget();
                if (!nodes.containsKey(target)) {
                    continue;
                }
                if (onStack.contains(target)) {
                    backEdges.add(c);
                } else {
                    dfs(target, nodes, outgoing, visited, onStack, backEdges, discovery);
                }
            }
            onStack.remove(id);
        }
    }
}
