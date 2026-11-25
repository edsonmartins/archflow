package br.com.archflow.api.web.workflow;

import br.com.archflow.api.flow.DefaultFlowStepFactory;
import br.com.archflow.api.flow.DefaultWorkflowDeserializer;
import br.com.archflow.api.flow.WorkflowDeserializer;
import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.api.workflow.WorkflowYamlBridge;
import br.com.archflow.model.ai.AIComponent;
import br.com.archflow.model.ai.metadata.ComponentMetadata;
import br.com.archflow.model.ai.type.ComponentType;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.enums.StepStatus;
import br.com.archflow.model.flow.StepResult;
import br.com.archflow.model.tenant.DefaultTenant;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import br.com.archflow.plugin.api.catalog.DefaultComponentCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RCHFLW-63: abrir a aba Código e Salvar YAML apagava {@code configuration.allowedComponents} — o POJO do YAML
 * não tinha o campo e o PUT trocava a {@code configuration} inteira pela versão reduzida. O fluxo restrito
 * voltava a resolver qualquer componente do catálogo, sem aviso. O teste passa pelas rotas de verdade
 * (GET/PUT {@code /api/workflows/{id}/yaml}) e termina executando o documento gravado pelo deserializador real,
 * porque o que importa é o fluxo continuar restrito, não só o campo continuar no JSON.
 */
@DisplayName("YAML — round trip preserva configuration.allowedComponents")
class WorkflowYamlConfigurationRoundTripTest {

    private static final String WF = "wf-restrito";
    private static final String READER = "log-reader";
    private static final String WRITER = "service-restarter";

    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger writerRuns = new AtomicInteger();
    private InMemoryWorkflowRuntimeStore store;
    private WorkflowDeserializer deserializer;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        store = new InMemoryWorkflowRuntimeStore();
        mvc = MockMvcBuilders.standaloneSetup(new SpringWorkflowYamlController(store, new WorkflowYamlBridge()))
                .build();
        ComponentCatalog catalog = new DefaultComponentCatalog();
        catalog.register(component(READER, null));
        catalog.register(component(WRITER, writerRuns));
        deserializer = new DefaultWorkflowDeserializer(new DefaultFlowStepFactory(
                catalog, mock(DynamicWorkflowService.class), null, null, null));
    }

    @Test
    @DisplayName("Salvar YAML mantém a lista e as chaves que o YAML não representa; o fluxo segue restrito")
    void saveYamlKeepsTheScope() throws Exception {
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("timeout", 30000);
        configuration.put("allowedComponents", List.of(READER));
        configuration.put("chaveQueOYamlNaoModela", Map.of("a", 1));
        store.putWorkflow(DefaultTenant.ID, WF, workflow(WRITER, configuration));

        String yaml = getYaml();
        assertThat(yaml).as("o YAML mostra a lista para ela poder ser editada")
                .contains("allowedComponents").contains(READER);

        putYaml(yaml.replace("description: Antes", "description: Depois"));

        Map<String, Object> saved = store.getWorkflow(DefaultTenant.ID, WF);
        assertThat(((Map<?, ?>) saved.get("metadata")).get("description")).isEqualTo("Depois");
        Map<?, ?> savedConfig = (Map<?, ?>) saved.get("configuration");
        assertThat(savedConfig.get("allowedComponents")).isEqualTo(List.of(READER));
        assertThat(savedConfig.get("chaveQueOYamlNaoModela")).isEqualTo(Map.of("a", 1));

        StepResult result = runFirstStep(saved);
        assertThat(writerRuns).as("componente fora da lista não pode rodar depois do Salvar YAML").hasValue(0);
        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).asString().contains("component not found");
    }

    @Test
    @DisplayName("lista escrita à mão no YAML sobrevive ao Salvar YAML e passa a restringir a execução")
    void handWrittenListSurvives() throws Exception {
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("timeout", 30000);
        store.putWorkflow(DefaultTenant.ID, WF, workflow(WRITER, configuration));

        String yaml = getYaml();
        assertThat(yaml).doesNotContain("allowedComponents");
        String edited = yaml.replace("configuration:\n", "configuration:\n  allowedComponents:\n  - " + READER + "\n");
        assertThat(edited).isNotEqualTo(yaml);

        putYaml(edited);

        Map<String, Object> saved = store.getWorkflow(DefaultTenant.ID, WF);
        assertThat(((Map<?, ?>) saved.get("configuration")).get("allowedComponents")).isEqualTo(List.of(READER));
        assertThat(getYaml()).contains("allowedComponents");
        assertThat(runFirstStep(saved).getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(writerRuns).hasValue(0);
    }

    @Test
    @DisplayName("lista vazia (nega tudo) não vira ausente (irrestrito) no round trip")
    void emptyListStaysEmpty() throws Exception {
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("allowedComponents", List.of());
        store.putWorkflow(DefaultTenant.ID, WF, workflow(WRITER, configuration));

        putYaml(getYaml());

        Map<?, ?> savedConfig = (Map<?, ?>) store.getWorkflow(DefaultTenant.ID, WF).get("configuration");
        assertThat(savedConfig.get("allowedComponents")).isEqualTo(List.of());
    }

    @Test
    @DisplayName("a lista aparece no YAML, então apagá-la ali é uma edição do usuário e vale")
    void removingTheListInYamlIsAnEdit() throws Exception {
        Map<String, Object> configuration = new LinkedHashMap<>();
        configuration.put("allowedComponents", List.of(READER));
        store.putWorkflow(DefaultTenant.ID, WF, workflow(WRITER, configuration));

        String yaml = getYaml();
        String edited = yaml.replace("  allowedComponents:\n    - " + READER + "\n", "");
        assertThat(edited).doesNotContain("allowedComponents");

        putYaml(edited);

        Map<?, ?> savedConfig = (Map<?, ?>) store.getWorkflow(DefaultTenant.ID, WF).get("configuration");
        assertThat(savedConfig.containsKey("allowedComponents")).isFalse();
    }

    // ── helpers ──────────────────────────────────────────────────────────

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

    private static Map<String, Object> workflow(String componentId, Map<String, Object> configuration) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", WF);
        doc.put("metadata", Map.of("name", "Leitura", "description", "Antes", "version", "1.0.0"));
        doc.put("configuration", configuration);
        doc.put("steps", List.of(Map.of("id", "s1", "type", "TOOL", "componentId", componentId,
                "connections", List.of())));
        return doc;
    }

    private StepResult runFirstStep(Map<String, Object> doc) {
        ExecutionContext ctx = new DefaultExecutionContext("acme", "u", "run-1",
                MessageWindowChatMemory.builder().maxMessages(5).build());
        return deserializer.toFlow(doc).getSteps().get(0).execute(ctx).join();
    }

    private AIComponent component(String id, AtomicInteger counter) {
        return new AIComponent() {
            @Override public void initialize(Map<String, Object> config) { }
            @Override public ComponentMetadata getMetadata() {
                return new ComponentMetadata(id, id, "componente " + id, ComponentType.TOOL,
                        "1.0.0", Set.of(), List.of(), Map.of(), Set.of());
            }
            @Override public Object execute(String op, Object in, ExecutionContext ctx) {
                if (counter != null) {
                    counter.incrementAndGet();
                }
                return "saída de " + id;
            }
            @Override public void shutdown() { }
        };
    }
}
