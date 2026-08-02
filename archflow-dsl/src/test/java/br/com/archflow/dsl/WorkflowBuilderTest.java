package br.com.archflow.dsl;

import br.com.archflow.model.config.LLMConfigPatch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("DSL de workflow")
class WorkflowBuilderTest {

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> stepsOf(WorkflowDocument doc) {
        return (List<Map<String, Object>>) doc.toMap().get("steps");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> connectionsOf(Map<String, Object> step) {
        return (List<Map<String, Object>>) step.get("connections");
    }

    /**
     * {@code Map<?, ?>} faz o {@code containsEntry} do AssertJ recusar uma chave
     * String (o curinga captura); um cast tipado resolve sem espalhar
     * {@code @SuppressWarnings} por cada asserção.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object value) {
        return (Map<String, Object>) value;
    }

    @Nested
    @DisplayName("forma do documento")
    class Shape {

        /**
         * A asserção que justifica o módulo inteiro: as arestas vão
         * <b>dentro</b> de cada passo. O motor lê {@code steps[].connections}
         * ({@code DefaultFlowStepFactory}); os templates da UI trazem um
         * {@code connections} no topo do documento. Emitir a forma da UI daria
         * um fluxo que abre no designer e não caminha ao executar — falha muda,
         * e longe da causa.
         */
        @Test
        @DisplayName("as arestas ficam dentro do passo de origem, não no topo")
        void connectionsAreNestedInTheSourceStep() {
            WorkflowDocument doc = Workflows.define("f")
                    .step("a", Nodes.component("input"))
                    .step("b", Nodes.component("output"))
                    .edge("a", "b")
                    .build();

            assertThat(doc.toMap())
                    .as("um connections no topo seria a forma dos templates da UI, não a do motor")
                    .doesNotContainKey("connections");

            List<Map<String, Object>> steps = stepsOf(doc);
            assertThat(connectionsOf(steps.get(0)))
                    .singleElement()
                    .satisfies(c -> {
                        assertThat(c).containsEntry("sourceId", "a");
                        assertThat(c).containsEntry("targetId", "b");
                        assertThat(c).containsEntry("isErrorPath", false);
                    });
            assertThat(connectionsOf(steps.get(1)))
                    .as("o passo de destino não é dono da aresta")
                    .isEmpty();
        }

        @Test
        @DisplayName("preserva a ordem de declaração dos passos")
        void preservesStepOrder() {
            WorkflowDocument doc = Workflows.define("f")
                    .step("primeiro", Nodes.component("x"))
                    .step("segundo", Nodes.component("y"))
                    .step("terceiro", Nodes.component("z"))
                    .build();

            assertThat(stepsOf(doc)).extracting(s -> s.get("id"))
                    .containsExactly("primeiro", "segundo", "terceiro");
        }

        @Test
        @DisplayName("campos ausentes são omitidos, não viram null")
        void omitsAbsentFields() {
            Map<String, Object> step = stepsOf(
                    Workflows.define("f").step("a", Nodes.component("input")).build()).get(0);

            assertThat(step)
                    .as("um YAML cheio de 'operation: null' e 'label: null' é ilegível")
                    .doesNotContainKeys("operation", "label")
                    .doesNotContainKey("config");
        }

        @Test
        @DisplayName("metadata.name cai para o id quando não informado")
        void nameFallsBackToId() {
            WorkflowDocument doc = Workflows.define("meu-fluxo").step("a", Nodes.component("x")).build();

            assertThat(mapOf(doc.toMap().get("metadata"))).containsEntry("name", "meu-fluxo");
        }

        @Test
        @DisplayName("sem allowing(), o documento não traz configuration")
        void noConfigurationWhenUnrestricted() {
            WorkflowDocument doc = Workflows.define("f").step("a", Nodes.component("x")).build();

            // Ausente significa irrestrito. Emitir uma lista vazia inverteria o
            // sentido: num deployment com require-allowlist=true, lista vazia
            // nega tudo.
            assertThat(doc.toMap()).doesNotContainKey("configuration");
        }

        @Test
        @DisplayName("onError marca a aresta como caminho de erro")
        void errorEdge() {
            WorkflowDocument doc = Workflows.define("f")
                    .step("a", Nodes.component("x"))
                    .step("fallback", Nodes.component("y"))
                    .onError("a", "fallback")
                    .build();

            assertThat(connectionsOf(stepsOf(doc).get(0))).singleElement()
                    .satisfies(c -> assertThat(c).containsEntry("isErrorPath", true));
        }

        @Test
        @DisplayName("aresta condicional transporta a condição como texto")
        void conditionalEdge() {
            WorkflowDocument doc = Workflows.define("f")
                    .step("router", Nodes.component("semantic-router"))
                    .step("billing", Nodes.component("agent"))
                    .edge("router", "billing", "route == 'billing'")
                    .build();

            assertThat(connectionsOf(stepsOf(doc).get(0))).singleElement()
                    .satisfies(c -> assertThat(c).containsEntry("condition", "route == 'billing'"));
        }
    }

    @Nested
    @DisplayName("nós de adapter")
    class AdapterNodes {

        /**
         * O motor resolve o provider por {@code config.provider}, caindo para o
         * {@code componentId} quando ausente. Gravar os dois em desacordo daria
         * duas fontes de verdade; a DSL grava os dois iguais.
         */
        @Test
        @DisplayName("provider vai em config.provider e em componentId, coerentes")
        void providerIsWrittenConsistently() {
            Map<String, Object> step = stepsOf(Workflows.define("f")
                    .step("chat", Nodes.llmChat("openai").with("model", "gpt-4o"))
                    .build()).get(0);

            assertThat(step).containsEntry("type", "llm-chat");
            assertThat(step).containsEntry("componentId", "openai");
            assertThat(mapOf(step.get("config")))
                    .containsEntry("provider", "openai")
                    .containsEntry("model", "gpt-4o");
        }

        @Test
        @DisplayName("os tipos de nó são os que o motor reconhece como de adapter")
        void nodeTypesMatchEngine() {
            assertThat(List.of(
                    stepsOf(Workflows.define("f").step("a", Nodes.embedding("openai")).build()).get(0).get("type"),
                    stepsOf(Workflows.define("f").step("a", Nodes.vectorSearch("pgvector")).build()).get(0).get("type"),
                    stepsOf(Workflows.define("f").step("a", Nodes.memory("redis")).build()).get(0).get("type"),
                    stepsOf(Workflows.define("f").step("a", Nodes.rag("default")).build()).get(0).get("type")))
                    .containsExactly("embedding", "vector-search", "memory", "rag");
        }

        @Test
        @DisplayName("APPROVAL e ORCHESTRATE saem com o nome do StepType")
        void engineStepTypes() {
            assertThat(stepsOf(Workflows.define("f").step("gate", Nodes.approval()).build()).get(0))
                    .containsEntry("type", "APPROVAL");
            assertThat(stepsOf(Workflows.define("f").step("orq", Nodes.orchestrate()).build()).get(0))
                    .containsEntry("type", "ORCHESTRATE");
        }
    }

    @Nested
    @DisplayName("configuração LLM e MCP")
    class LlmAndMcp {
        @Test
        void writesFlowPatchAndMcpStepPatchSeparately() {
            WorkflowDocument doc = Workflows.define("mcp")
                    .llm(LLMConfigPatch.builder()
                            .provider("openrouter").model("flow-model").maxTokens(1024).build())
                    .step("agent", Nodes.mcpAgent()
                            .with("systemPrompt", "Ajude o usuário")
                            .with("model", "step-model"))
                    .build();

            Map<String, Object> flowConfig = mapOf(doc.toMap().get("configuration"));
            assertThat(mapOf(flowConfig.get("llmConfig")))
                    .containsEntry("provider", "openrouter")
                    .containsEntry("model", "flow-model");
            Map<String, Object> step = stepsOf(doc).get(0);
            assertThat(step).containsEntry("type", "agent")
                    .containsEntry("componentId", "mcp-agent")
                    .containsEntry("operation", "execute");
            assertThat(mapOf(step.get("config"))).containsEntry("model", "step-model");
        }
    }

    @Nested
    @DisplayName("NodeSpec é imutável")
    class SpecImmutability {

        /**
         * Uma NodeSpec guardada em constante e usada em dois fluxos não pode
         * carregar config de um para o outro — seria um vazamento silencioso, e
         * do tipo que só aparece no segundo fluxo.
         */
        @Test
        @DisplayName("with() não altera a instância original")
        void withDoesNotMutate() {
            NodeSpec base = Nodes.llmChat("openai");
            NodeSpec quente = base.with("temperature", 0.9);

            Map<String, Object> comBase = stepsOf(
                    Workflows.define("f").step("a", base).build()).get(0);
            Map<String, Object> comQuente = stepsOf(
                    Workflows.define("f").step("a", quente).build()).get(0);

            assertThat(mapOf(comBase.get("config"))).doesNotContainKey("temperature");
            assertThat(mapOf(comQuente.get("config"))).containsEntry("temperature", 0.9);
        }
    }

    @Nested
    @DisplayName("validação")
    class Validation {

        @Test
        @DisplayName("aresta para passo inexistente")
        void danglingTarget() {
            assertThatThrownBy(() -> Workflows.define("f")
                    .step("a", Nodes.component("x"))
                    .edge("a", "fantasma")
                    .build())
                    .isInstanceOf(DslValidationException.class)
                    // Sem esta checagem o motor apenas não caminharia para lá,
                    // sem erro nenhum: o fluxo "funciona" e faz menos do que devia.
                    .hasMessageContaining("aponta para um passo inexistente")
                    .hasMessageContaining("fantasma");
        }

        @Test
        @DisplayName("aresta partindo de passo inexistente")
        void danglingSource() {
            assertThatThrownBy(() -> Workflows.define("f")
                    .step("a", Nodes.component("x"))
                    .edge("fantasma", "a")
                    .build())
                    .isInstanceOf(DslValidationException.class)
                    .hasMessageContaining("parte de um passo inexistente");
        }

        @Test
        @DisplayName("passo duplicado falha na hora, não no build")
        void duplicateStep() {
            // Falhar só no build() já teria perdido a informação: o segundo
            // step() sobrescreveria o primeiro em silêncio.
            assertThatThrownBy(() -> Workflows.define("f")
                    .step("a", Nodes.component("x"))
                    .step("a", Nodes.component("y")))
                    .isInstanceOf(DslValidationException.class)
                    .hasMessageContaining("passo duplicado: a");
        }

        @Test
        @DisplayName("fluxo sem passos")
        void emptyFlow() {
            assertThatThrownBy(() -> Workflows.define("f").build())
                    .isInstanceOf(DslValidationException.class)
                    .hasMessageContaining("não tem nenhum passo");
        }

        @Test
        @DisplayName("id em branco")
        void blankId() {
            assertThatThrownBy(() -> Workflows.define("  ").step("a", Nodes.component("x")).build())
                    .isInstanceOf(DslValidationException.class)
                    .hasMessageContaining("precisa de um id");
        }

        /**
         * Em runtime isto vira "component not found" no meio da execução, longe
         * da causa. Aqui é uma linha de erro apontando o passo.
         */
        @Test
        @DisplayName("componente usado fora do allowedComponents declarado")
        void componentOutsideAllowlist() {
            assertThatThrownBy(() -> Workflows.define("f")
                    .allowing("openai")
                    .step("a", Nodes.llmChat("openai"))
                    .step("b", Nodes.vectorSearch("pgvector"))
                    .build())
                    .isInstanceOf(DslValidationException.class)
                    .hasMessageContaining("o passo b usa o componente 'pgvector'");
        }

        @Test
        @DisplayName("sem allowing(), nenhum componente é recusado")
        void unrestrictedAllowsAnything() {
            assertThatCode(() -> Workflows.define("f")
                    .step("a", Nodes.component("qualquer-coisa"))
                    .build())
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("reporta todos os problemas de uma vez, não só o primeiro")
        void reportsEveryProblem() {
            assertThatThrownBy(() -> Workflows.define("f")
                    .allowing("openai")
                    .step("a", Nodes.vectorSearch("pgvector"))
                    .edge("a", "fantasma")
                    .edge("outro-fantasma", "a")
                    .build())
                    .isInstanceOf(DslValidationException.class)
                    .satisfies(e -> assertThat(((DslValidationException) e).getProblems())
                            .as("três compilações para achar três erros é o que se quer evitar")
                            .hasSize(3));
        }
    }

    @Nested
    @DisplayName("serialização")
    class Serialization {

        @Test
        @DisplayName("o JSON relê como o mesmo mapa")
        void jsonRoundTrip() throws Exception {
            WorkflowDocument doc = Workflows.define("f")
                    .named("Fluxo")
                    .step("a", Nodes.llmChat("openai").with("model", "gpt-4o"))
                    .step("b", Nodes.component("output"))
                    .edge("a", "b")
                    .build();

            Map<?, ?> reread = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(doc.toJson(), Map.class);

            assertThat(reread).isEqualTo(doc.toMap());
        }

        @Test
        @DisplayName("o YAML relê como o mesmo mapa")
        void yamlRoundTrip() throws Exception {
            WorkflowDocument doc = Workflows.define("f")
                    .step("a", Nodes.component("input"))
                    .step("b", Nodes.component("output"))
                    .edge("a", "b")
                    .build();

            Map<?, ?> reread = new com.fasterxml.jackson.databind.ObjectMapper(
                    new com.fasterxml.jackson.dataformat.yaml.YAMLFactory())
                    .readValue(doc.toYaml(), Map.class);

            assertThat(reread).isEqualTo(doc.toMap());
        }

        @Test
        @DisplayName("writeTo escolhe o formato pela extensão")
        void writeToPicksFormatByExtension(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
            WorkflowDocument doc = Workflows.define("f").step("a", Nodes.component("x")).build();

            String yaml = java.nio.file.Files.readString(doc.writeTo(dir.resolve("f.yaml")));
            String json = java.nio.file.Files.readString(doc.writeTo(dir.resolve("f.json")));

            assertThat(yaml).doesNotStartWith("{").contains("id: f");
            assertThat(json).startsWith("{");
        }
    }
}
