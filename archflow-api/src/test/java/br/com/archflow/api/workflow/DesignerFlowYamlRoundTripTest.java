package br.com.archflow.api.workflow;

import br.com.archflow.standalone.model.SerializableFlow;
import br.com.archflow.standalone.model.SerializableStep;
import br.com.archflow.model.flow.StepType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Um fluxo <b>como o designer o grava</b> atravessa a ponte YAML.
 *
 * <p>Os dois leitores do documento de workflow discordavam sobre o campo
 * {@code type}. O {@code DefaultFlowStepFactory}, que executa, trata-o como
 * string livre — o designer grava {@code "input"}, {@code "llm-chat"},
 * {@code "vector-search"}, e é assim que os templates em
 * {@code archflow-ui/public/templates} estão escritos. O {@link SerializableStep},
 * que transporta o documento para YAML e para a execução standalone, declarava-o
 * como o enum {@link StepType}, cujos valores são
 * {@code ASSISTANT/AGENT/TOOL/CHAIN/CUSTOM/ORCHESTRATE/APPROVAL}.
 *
 * <p>A consequência não era teórica: qualquer fluxo desenhado na tela fazia
 * {@code GET /api/workflows/{id}/yaml} estourar com
 * "Malformed JSON: Cannot deserialize value of type StepType from String
 * \"input\"", e não podia ser executado pelo archflow-standalone. Só os fluxos
 * escritos à mão com o nome do enum — como o
 * {@code archflow-standalone/src/test/resources/test-workflow.json}, que usa
 * {@code "type": "AGENT"} — passavam, e eram exatamente os que os testes
 * existentes exercitavam.
 */
@DisplayName("fluxo do designer atravessa a ponte YAML")
class DesignerFlowYamlRoundTripTest {

    /** Exatamente a forma dos templates de {@code archflow-ui/public/templates}. */
    private static final String DESIGNER_JSON = """
            {
              "id": "rag-doc-qa",
              "metadata": {"name": "Document Q&A", "version": "1.0.0"},
              "steps": [
                {"id": "s1", "type": "input", "componentId": "input",
                 "label": "Question", "position": {"x": 60, "y": 200},
                 "connections": [{"sourceId": "s1", "targetId": "s2", "isErrorPath": false}]},
                {"id": "s2", "type": "llm-chat", "componentId": "openai",
                 "label": "Answer", "config": {"model": "gpt-4o"}, "connections": []}
              ]
            }""";

    private final WorkflowYamlBridge bridge = new WorkflowYamlBridge();

    @Test
    @DisplayName("converter para YAML não estoura")
    void jsonToYamlDoesNotThrow() {
        assertThatCode(() -> bridge.jsonToYaml(DESIGNER_JSON))
                .as("a aba Code do designer ficava inutilizavel para todo fluxo feito na tela")
                .doesNotThrowAnyException();
    }

    /**
     * O tipo do designer precisa <b>voltar como veio</b>. Reescrevê-lo para
     * {@code TOOL} seria pior que o erro: o round-trip pela aba Code gravaria de
     * volta um documento em que {@code llm-chat} virou {@code TOOL}, e o
     * roteamento de adapter — que casa pelo tipo do nó — deixaria de reconhecer
     * o passo, silenciosamente.
     */
    @Test
    @DisplayName("o tipo do designer sobrevive ao round-trip, sem virar TOOL")
    void designerTypeSurvivesRoundTrip() {
        String yaml = bridge.jsonToYaml(DESIGNER_JSON);

        assertThat(yaml).contains("llm-chat").contains("input");

        String json = bridge.yamlToJson(yaml);
        assertThat(json)
                .as("um llm-chat reescrito como TOOL perde o roteamento de adapter")
                .contains("\"llm-chat\"");
    }

    @Test
    @DisplayName("os passos e as conexões sobrevivem")
    void stepsAndConnectionsSurvive() throws Exception {
        SerializableFlow flow = new br.com.archflow.standalone.FlowSerializer()
                .deserialize(DESIGNER_JSON);

        assertThat(flow.getSteps()).extracting(s -> s.getId()).containsExactly("s1", "s2");
        assertThat(flow.getSteps().get(0).getConnections()).singleElement()
                .satisfies(c -> assertThat(c.getTargetId()).isEqualTo("s2"));
    }

    /**
     * O tipo cru é preservado para o transporte, mas {@link SerializableStep#getType()}
     * — o contrato de {@code FlowStep} — precisa devolver um {@link StepType}
     * válido. {@code TOOL} é o mesmo que o motor atribui a todo passo que não
     * seja ORCHESTRATE nem APPROVAL.
     */
    @Test
    @DisplayName("getType() devolve TOOL para tipo do designer, como o motor faz")
    void unknownTypeBecomesToolOnTheInterface() throws Exception {
        SerializableFlow flow = new br.com.archflow.standalone.FlowSerializer()
                .deserialize(DESIGNER_JSON);

        assertThat(flow.getSteps().get(1).getType()).isEqualTo(StepType.TOOL);
    }

    @Test
    @DisplayName("o nome do enum continua sendo aceito — fluxos antigos não mudam")
    void enumNamesStillWork() throws Exception {
        String legado = """
                {"id":"f","steps":[{"id":"a","type":"AGENT","componentId":"c","connections":[]}]}""";

        SerializableFlow flow = new br.com.archflow.standalone.FlowSerializer().deserialize(legado);

        assertThat(flow.getSteps().get(0).getType()).isEqualTo(StepType.AGENT);
        assertThat(new br.com.archflow.standalone.FlowSerializer().serialize(flow))
                .contains("AGENT");
    }
}
