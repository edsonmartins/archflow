package br.com.archflow.standalone.model;

import br.com.archflow.model.flow.StepConnection;
import br.com.archflow.standalone.YamlFlowSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Uma aresta com {@code branch} (o que a DSL emite em {@code route(...)} e o designer grava) chega ao
 * motor standalone com a <b>condição</b> que o ramo quer dizer — pela mesma regra da factory do
 * archflow-api ({@code BranchCondition}).
 *
 * <p>Antes, o transporte ignorava a chave ({@code FAIL_ON_UNKNOWN_PROPERTIES=false}) e o fluxo
 * carregava com arestas incondicionais: seguia todos os ramos, sem erro nenhum.</p>
 */
@DisplayName("branch na aresta, no transporte standalone")
class SerializableConnectionBranchTest {

    private static final String YAML = """
            id: triagem
            metadata: { name: triagem, version: 1.0.0 }
            steps:
              - id: intencao
                type: decision
                componentId: decision
                connections:
                  - { sourceId: intencao, targetId: fecha,  branch: AUTO }
                  - { sourceId: intencao, targetId: humano, branch: REVIEW }
                  - { sourceId: intencao, targetId: humano, branch: ESCALATE, condition: "${x} == 1" }
              - id: checa
                type: condition
                connections:
                  - { sourceId: checa, targetId: sim, branch: "true" }
              - id: fecha
                type: mcp-agent
                connections:
                  - { sourceId: fecha, targetId: fim, branch: QUALQUER }
            """;

    private static List<StepConnection> arestas(SerializableFlow flow, String id) {
        return flow.getSteps().stream().filter(s -> id.equals(s.getId())).findFirst()
                .orElseThrow().getConnections();
    }

    @Test
    @DisplayName("o ramo vira a condição do tipo do nó de origem; uma condição escrita vence")
    void resolve() throws Exception {
        SerializableFlow flow = new YamlFlowSerializer().deserialize(YAML);

        assertThat(arestas(flow, "intencao")).extracting(c -> c.getCondition().orElse(null))
                .containsExactly("${intencao.route} == 'AUTO'", "${intencao.route} == 'REVIEW'",
                        "${x} == 1");
        assertThat(arestas(flow, "checa")).extracting(c -> c.getCondition().orElse(null))
                .containsExactly("${checa.branch} == 'true'");
    }

    @Test
    @DisplayName("nó sem ramos: o branch fica transportado, mas não vira condição")
    void noSemRamos() throws Exception {
        SerializableFlow flow = new YamlFlowSerializer().deserialize(YAML);
        StepConnection c = arestas(flow, "fecha").get(0);
        assertThat(c.getCondition()).isEmpty();
        assertThat(((SerializableConnection) c).getBranch()).isEqualTo("QUALQUER");
    }

    @Test
    @DisplayName("ida e volta: o branch sobrevive e a condição resolvida também sai")
    void idaEVolta() throws Exception {
        YamlFlowSerializer yaml = new YamlFlowSerializer();
        String out = yaml.serialize(yaml.deserialize(YAML));
        assertThat(out).contains("branch: AUTO").contains("${intencao.route} == 'AUTO'");
        assertThat(out).doesNotContain("branch: null");
    }
}
