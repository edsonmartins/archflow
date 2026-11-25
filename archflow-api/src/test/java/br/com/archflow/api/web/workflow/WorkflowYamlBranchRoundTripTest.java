package br.com.archflow.api.web.workflow;

import br.com.archflow.api.flow.DefaultFlowStepFactory;
import br.com.archflow.api.flow.DefaultWorkflowDeserializer;
import br.com.archflow.api.flow.WorkflowDeserializer;
import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.api.workflow.WorkflowYamlBridge;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.tenant.DefaultTenant;
import br.com.archflow.plugin.api.catalog.DefaultComponentCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RCHFLW-102: uma aresta que só declara {@code branch} ganhava uma {@code condition} derivada depois de
 * editar e salvar o YAML — o GET escrevia a condição que o transporte calcula para executar, e o PUT a
 * gravava. Como uma condição escrita vence o {@code branch}, renomear o ramo ou o nó no canvas deixava a
 * condição antiga valendo e o fluxo seguia o caminho errado, sem aviso. O teste passa pelas rotas de
 * verdade (GET/PUT {@code /api/workflows/{id}/yaml}) e confere o documento gravado e o que o motor lê dele.
 */
@DisplayName("YAML — round trip não materializa a condição derivada do branch")
class WorkflowYamlBranchRoundTripTest {

    private static final String WF = "wf-rota";

    private final ObjectMapper json = new ObjectMapper();
    private InMemoryWorkflowRuntimeStore store;
    private WorkflowDeserializer deserializer;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        store = new InMemoryWorkflowRuntimeStore();
        mvc = MockMvcBuilders.standaloneSetup(new SpringWorkflowYamlController(store, new WorkflowYamlBridge()))
                .build();
        deserializer = new DefaultWorkflowDeserializer(new DefaultFlowStepFactory(
                new DefaultComponentCatalog(), mock(DynamicWorkflowService.class), null, null, null));
    }

    @Test
    @DisplayName("aresta só com branch continua só com branch; a condição manual fica; o motor roteia igual")
    void branchSobreviveSemCondicao() throws Exception {
        store.putWorkflow(DefaultTenant.ID, WF, workflow());
        List<String> antes = rotas(deserializer.toFlow(store.getWorkflow(DefaultTenant.ID, WF)));

        String yaml = getYaml();
        assertThat(yaml).as("o YAML mostra o documento, não o cálculo de execução")
                .doesNotContain("${rota.branch} == 'AUTO'")
                .contains("branch: AUTO");

        putYaml(yaml.replace("label: Rota", "label: Roteador"));

        List<Map<String, Object>> arestas = arestasDe(store.getWorkflow(DefaultTenant.ID, WF), "rota");
        assertThat(arestas.get(0)).as("só branch, como o designer gravou")
                .containsEntry("branch", "AUTO").doesNotContainKey("condition");
        assertThat(arestas.get(1)).containsEntry("branch", "REVIEW").doesNotContainKey("condition");
        assertThat(arestas.get(2)).as("a condição escrita à mão continua")
                .containsEntry("condition", "${rota.score} > 3");
        assertThat(rotas(deserializer.toFlow(store.getWorkflow(DefaultTenant.ID, WF))))
                .as("o motor deriva a mesma condição do branch")
                .isEqualTo(antes)
                .contains("rota->responder [${rota.branch} == 'AUTO']");

        // Um segundo GET/PUT não acumula nada.
        putYaml(getYaml());
        assertThat(arestasDe(store.getWorkflow(DefaultTenant.ID, WF), "rota").get(0))
                .doesNotContainKey("condition");
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static Map<String, Object> workflow() {
        Map<String, Object> rota = new LinkedHashMap<>();
        rota.put("id", "rota");
        rota.put("type", "switch");
        rota.put("label", "Rota");
        rota.put("connections", List.of(
                Map.of("sourceId", "rota", "targetId", "responder", "branch", "AUTO"),
                Map.of("sourceId", "rota", "targetId", "revisar", "branch", "REVIEW"),
                Map.of("sourceId", "rota", "targetId", "escalar", "condition", "${rota.score} > 3")));
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", WF);
        doc.put("metadata", Map.of("name", "Rota", "version", "1.0.0"));
        doc.put("configuration", new LinkedHashMap<>());
        doc.put("steps", List.of(rota,
                Map.of("id", "responder", "type", "output", "connections", List.of()),
                Map.of("id", "revisar", "type", "output", "connections", List.of()),
                Map.of("id", "escalar", "type", "output", "connections", List.of())));
        return doc;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> arestasDe(Map<String, Object> doc, String stepId) {
        return ((List<Map<String, Object>>) doc.get("steps")).stream()
                .filter(s -> stepId.equals(s.get("id")))
                .findFirst().map(s -> (List<Map<String, Object>>) s.get("connections"))
                .orElseThrow();
    }

    /** {@code origem->destino [condição]} de cada aresta, como o motor a lê. */
    private static List<String> rotas(Flow flow) {
        return flow.getSteps().stream()
                .flatMap(s -> s.getConnections().stream()
                        .map(c -> s.getId() + "->" + c.getTargetId() + " [" + c.getCondition().orElse("") + "]"))
                .toList();
    }

    private String getYaml() throws Exception {
        String body = mvc.perform(get("/api/workflows/" + WF + "/yaml"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("yaml").asText();
    }

    private void putYaml(String yaml) throws Exception {
        mvc.perform(put("/api/workflows/" + WF + "/yaml")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("id", WF, "yaml", yaml))))
                .andExpect(status().isOk());
    }
}
