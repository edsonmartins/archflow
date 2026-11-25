package br.com.archflow.template;

import br.com.archflow.template.WorkflowTemplateDefinition.TemplateConnection;
import br.com.archflow.template.WorkflowTemplateDefinition.TemplateNode;
import br.com.archflow.template.WorkflowTemplateDefinition.TemplateStructure;
import br.com.archflow.template.knowledge.KnowledgeBaseTemplate;
import br.com.archflow.template.processing.DocumentProcessingTemplate;
import br.com.archflow.template.supervisor.AgentSupervisorTemplate;
import br.com.archflow.template.support.CustomerSupportTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O template SPI vira os {@code steps[]} do documento canônico (RCHFLW-18).
 *
 * <p>Antes, nada fazia essa conversão: a API só mandava {@code stepCount} e o clone de qualquer
 * template do backend nascia vazio. A execução de verdade do documento é conferida no
 * archflow-api ({@code TemplateConformanceTest}, pelo deserializador do motor); aqui fica a forma.
 */
@DisplayName("TemplateDocument: estrutura do template → steps[] canônicos")
class TemplateDocumentTest {

    static Stream<WorkflowTemplate> builtIns() {
        return Stream.of(new CustomerSupportTemplate(), new KnowledgeBaseTemplate(),
                new DocumentProcessingTemplate(), new AgentSupervisorTemplate());
    }

    private static Map<String, Object> stepById(List<Map<String, Object>> steps, String id) {
        return steps.stream().filter(s -> id.equals(s.get("id"))).findFirst()
                .orElseThrow(() -> new AssertionError("passo ausente: " + id));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> connections(Map<String, Object> step) {
        return (List<Map<String, Object>>) step.get("connections");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> config(Map<String, Object> step) {
        return (Map<String, Object>) step.get("configuration");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("builtIns")
    @DisplayName("um passo por nó, com o type cru e componentId")
    void oneStepPerNodeWithRawType(WorkflowTemplate template) {
        WorkflowTemplateDefinition def = template.getDefinition();
        List<Map<String, Object>> steps = TemplateDocument.steps(def);

        assertThat(steps).hasSize(TemplateMetadata.from(template).stepCount());
        assertThat(steps.getFirst().get("id"))
                .as("a entrada abre o documento").isEqualTo(def.getStructure().getEntryPoint());
        for (TemplateNode node : def.getStructure().getNodes().values()) {
            Map<String, Object> step = stepById(steps, node.getId());
            assertThat(step.get("type")).as("type cru, nunca enum").isEqualTo(node.getType());
            assertThat(step.get("componentId")).isEqualTo(node.getType());
            assertThat(step.get("label")).isEqualTo(node.getName());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("builtIns")
    @DisplayName("toda conexão do template está dentro do passo de origem")
    void everyConnectionLivesInItsSourceStep(WorkflowTemplate template) {
        TemplateStructure structure = template.getDefinition().getStructure();
        List<Map<String, Object>> steps = TemplateDocument.steps(template.getDefinition());

        int total = steps.stream().mapToInt(s -> connections(s).size()).sum();
        assertThat(total).isEqualTo(structure.getConnections().size());
        for (TemplateConnection c : structure.getConnections().values()) {
            assertThat(connections(stepById(steps, c.getSource())))
                    .as("conexão %s", c.getId())
                    .anySatisfy(conn -> {
                        assertThat(conn.get("sourceId")).isEqualTo(c.getSource());
                        assertThat(conn.get("targetId")).isEqualTo(c.getTarget());
                        assertThat(conn.get("isErrorPath")).isEqualTo(false);
                    });
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("builtIns")
    @DisplayName("nenhuma condição sai com {{...}} e nenhum parâmetro fica sem valor na config")
    void placeholdersAreConverted(WorkflowTemplate template) {
        Set<String> params = template.getDefinition().getDefaultParameters().keySet();
        List<Map<String, Object>> steps = TemplateDocument.steps(template.getDefinition());

        for (Map<String, Object> step : steps) {
            for (Map<String, Object> conn : connections(step)) {
                Object condition = conn.get("condition");
                if (condition != null) {
                    assertThat(condition.toString()).doesNotContain("{{");
                }
            }
            String cfg = String.valueOf(config(step));
            for (String p : params) {
                assertThat(cfg).as("passo %s", step.get("id")).doesNotContain("{{" + p + "}}");
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("builtIns")
    @DisplayName("posições distintas: o designer não abre os nós empilhados")
    void positionsAreDistinct(WorkflowTemplate template) {
        List<Map<String, Object>> steps = TemplateDocument.steps(template.getDefinition());
        Set<Object> positions = new HashSet<>();
        for (Map<String, Object> step : steps) {
            assertThat(positions.add(step.get("position"))).as("posição de %s", step.get("id")).isTrue();
        }
        assertThat(stepById(steps, template.getDefinition().getStructure().getEntryPoint()).get("position"))
                .extracting(p -> ((Map<?, ?>) p).get("x")).isEqualTo(TemplateDocument.ORIGIN_X);
    }

    @Test
    @DisplayName("variável de execução vira ${x}; parâmetro vira literal")
    void conditionsUseTheEngineGrammar() {
        List<Map<String, Object>> steps = TemplateDocument.steps(new CustomerSupportTemplate().getDefinition());

        assertThat(connections(stepById(steps, "intent-classifier")))
                .singleElement()
                .extracting(c -> c.get("condition")).isEqualTo("${intent} != 'general'");
        assertThat(connections(stepById(steps, "response-generator")))
                .extracting(c -> c.get("condition"))
                .containsExactly("true == true", "true == false");
        assertThat(connections(stepById(steps, "input")))
                .singleElement()
                .satisfies(c -> assertThat(c).doesNotContainKey("condition"));
    }

    @Test
    @DisplayName("parâmetro de quem instala vence o default; marcador sozinho mantém o tipo")
    void parametersOverrideDefaults() {
        WorkflowTemplateDefinition def = new CustomerSupportTemplate().getDefinition();

        List<Map<String, Object>> defaults = TemplateDocument.steps(def);
        assertThat(config(stepById(defaults, "kb-search")).get("vectorStoreId")).isEqualTo("");

        List<Map<String, Object>> installed = TemplateDocument.steps(def,
                Map.of("knowledgeBaseId", "support-docs", "enableSentiment", false));
        assertThat(config(stepById(installed, "kb-search")).get("vectorStoreId")).isEqualTo("support-docs");
        assertThat(connections(stepById(installed, "response-generator")))
                .extracting(c -> c.get("condition"))
                .containsExactly("false == true", "false == false");
        // Variável de prompt (não é parâmetro) fica para a execução.
        assertThat(config(stepById(installed, "intent-classifier")).get("promptTemplate").toString())
                .contains("{{message}}");

        List<Map<String, Object>> supervisor = TemplateDocument.steps(new AgentSupervisorTemplate().getDefinition());
        assertThat(config(stepById(supervisor, "supervisor")).get("agents"))
                .isEqualTo(List.of("researcher", "writer", "reviewer"));
        assertThat(connections(stepById(supervisor, "quality-checker")))
                .extracting(c -> c.get("condition"))
                .contains("${quality} >= 0.8");
    }

    @Test
    @DisplayName("o laço refiner → quality-checker não empurra o layout; o fim fica à direita")
    void backEdgesDoNotBreakTheLayout() {
        List<Map<String, Object>> steps = TemplateDocument.steps(new AgentSupervisorTemplate().getDefinition());
        int refiner = x(stepById(steps, "refiner"));
        int checker = x(stepById(steps, "quality-checker"));
        int output = x(stepById(steps, "output"));
        int logger = x(stepById(steps, "logger"));

        assertThat(refiner).isGreaterThan(checker);
        assertThat(output).isGreaterThan(logger);
        assertThat(connections(stepById(steps, "refiner")))
                .as("a aresta de volta continua no documento")
                .singleElement().extracting(c -> c.get("targetId")).isEqualTo("quality-checker");
    }

    private static int x(Map<String, Object> step) {
        return (Integer) ((Map<?, ?>) step.get("position")).get("x");
    }

    @Test
    @DisplayName("sem definição, sem estrutura ou com aresta pendurada: nada quebra")
    void edgeCases() {
        assertThat(TemplateDocument.steps(null)).isEmpty();
        WorkflowTemplateDefinition noStructure = new WorkflowTemplateDefinition(
                "x", "1", "x", "", "c", Set.of(), Map.of(), null);
        assertThat(TemplateDocument.steps(noStructure, null)).isEmpty();

        Map<String, TemplateNode> nodes = new LinkedHashMap<>();
        nodes.put("a", new TemplateNode("a", "input", null, null));
        nodes.put("b", new TemplateNode("b", "output", "B", Map.of("quote", "{{name}} diz {{x}}")));
        nodes.put("solto", new TemplateNode("solto", "tool", "Solto", Map.of()));
        Map<String, TemplateConnection> conns = new LinkedHashMap<>();
        conns.put("c1", new TemplateConnection("c1", "a", "b", "{{name}} == {{x}}", null));
        conns.put("c2", new TemplateConnection("c2", "a", "fantasma", null, null));
        conns.put("c3", new TemplateConnection("c3", "b", "a", "  ", null));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", "O'Neil");
        WorkflowTemplateDefinition def = new WorkflowTemplateDefinition("t", "1", "t", "", "c", Set.of(),
                params, new TemplateStructure("a", nodes, conns));

        List<Map<String, Object>> steps = TemplateDocument.steps(def, null);

        assertThat(steps).extracting(s -> s.get("id")).containsExactly("a", "solto", "b");
        assertThat(stepById(steps, "a").get("label")).as("sem nome, o id vira rótulo").isEqualTo("a");
        assertThat(connections(stepById(steps, "a")))
                .as("aresta para nó inexistente não entra")
                .singleElement()
                .extracting(c -> c.get("condition")).isEqualTo("\"O'Neil\" == ${x}");
        assertThat(connections(stepById(steps, "b"))).singleElement()
                .satisfies(c -> assertThat(c).doesNotContainKey("condition"));
        assertThat(config(stepById(steps, "b")).get("quote")).isEqualTo("O'Neil diz {{x}}");
    }

    @Test
    @DisplayName("literais: número, booleano, texto e nulo")
    void literals() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("n", 3);
        params.put("b", true);
        params.put("s", "en");
        params.put("nada", null);

        assertThat(TemplateDocument.convertCondition("{{n}} < {{ b }} && {{s}} == {{nada}}", params))
                .isEqualTo("3 < true && 'en' == ''");
        assertThat(TemplateDocument.convertCondition(null, params)).isNull();
        assertThat(TemplateDocument.resolveValue(List.of("{{n}}", 7), params)).isEqualTo(List.of(3, 7));
        assertThat(TemplateDocument.resolveValue(42, params)).isEqualTo(42);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("unsafeValues")
    @DisplayName("valor de quem instala que reescreveria a condição é recusado, nunca colado")
    void unsafeParameterValueIsRejected(String value) {
        // Sem a recusa, `a' || "1" == "1` fechava o literal e virava outra expressão; e um operador no
        // valor partia a comparação com o parâmetro à esquerda. Em modo permissivo as duas seguem a aresta.
        Map<String, Object> params = Map.of("p", value);

        assertThatThrownBy(() -> TemplateDocument.convertCondition("{{p}} == true", params))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'p'");
        assertThatThrownBy(() -> TemplateDocument.convertCondition("${x} == {{p}}", params))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<String> unsafeValues() {
        return Stream.of("a' || \"1\" == \"1", "true == true", "x || y", "a && b", "1 > 0", "x != y",
                "lista CONTAINS x");
    }

    @Test
    @DisplayName("o install com valor inseguro falha antes de gravar; o valor só no config passa")
    void unsafeValueOnlyMattersInConditions() {
        Map<String, TemplateNode> nodes = new LinkedHashMap<>();
        nodes.put("a", new TemplateNode("a", "input", "A", Map.of("prompt", "{{p}}")));
        nodes.put("b", new TemplateNode("b", "output", "B", Map.of()));
        Map<String, TemplateConnection> conns =
                Map.of("c1", new TemplateConnection("c1", "a", "b", "{{q}} == true", null));
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("p", "");
        defaults.put("q", true);
        WorkflowTemplateDefinition def = new WorkflowTemplateDefinition("t", "1", "t", "", "c", Set.of(),
                defaults, new TemplateStructure("a", nodes, conns));

        // Na config o valor é dado, não expressão: pode ter aspas e operadores.
        List<Map<String, Object>> steps = TemplateDocument.steps(def, Map.of("p", "a' || \"b\" == 1"));
        assertThat(config(stepById(steps, "a")).get("prompt")).isEqualTo("a' || \"b\" == 1");
        assertThatThrownBy(() -> TemplateDocument.steps(def, Map.of("q", "true || x")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(TemplateDocument.convertCondition("{{q}} == 'x'", Map.of("q", "O'Neil")))
                .isEqualTo("\"O'Neil\" == 'x'");
    }
}
