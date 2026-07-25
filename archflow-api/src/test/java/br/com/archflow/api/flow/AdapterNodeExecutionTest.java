package br.com.archflow.api.flow;

import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.enums.StepStatus;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.StepResult;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import br.com.archflow.plugin.api.catalog.DefaultComponentCatalog;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Prova a afirmação que o designer sempre fez e o runtime nunca sustentou: um nó
 * apontando para um provider LangChain4j <b>executa</b>.
 *
 * <p>Antes, o catálogo listava os providers e o {@code ComponentStep} resolvia
 * {@code componentId} contra o {@code ComponentCatalog} — onde adapters nunca
 * estiveram, porque adapter não é {@code AIComponent}. O nó falhava com
 * "component not found".
 *
 * <p>Usa o provider de teste registrado por
 * {@code LangChainAdapterBridgeTest.TestAdapterFactory} via SPI.
 */
@DisplayName("Nó de adapter no workflow JSON")
class AdapterNodeExecutionTest {

    private static final String PROVIDER = "provider-de-teste";

    private ComponentCatalog catalog;
    private WorkflowDeserializer deserializer;

    @BeforeEach
    void setUp() {
        catalog = new DefaultComponentCatalog();
        deserializer = new DefaultWorkflowDeserializer(new DefaultFlowStepFactory(
                catalog, mock(DynamicWorkflowService.class), null, null, null));
    }

    private static ExecutionContext context() {
        return new DefaultExecutionContext("acme", "u", "run-1",
                MessageWindowChatMemory.builder().maxMessages(5).build());
    }

    private Map<String, Object> workflow(String nodeType, String componentId,
                                         Map<String, Object> config, List<String> allowed) {
        Map<String, Object> node = new HashMap<>();
        node.put("id", "s1");
        node.put("type", nodeType);
        node.put("componentId", componentId);
        node.put("config", config);
        Map<String, Object> json = new HashMap<>();
        json.put("id", "wf");
        json.put("metadata", Map.of("name", "Adapter"));
        json.put("steps", List.of(node));
        if (allowed != null) {
            json.put("configuration", Map.of("allowedComponents", allowed));
        }
        return json;
    }

    private StepResult runFirstStep(Map<String, Object> json) {
        Flow flow = deserializer.toFlow(json);
        return flow.getSteps().get(0).execute(context()).join();
    }

    @Test
    @DisplayName("nó llm-chat com provider registrado executa pelo adapter")
    void adapterNodeExecutes() {
        StepResult result = runFirstStep(workflow(
                "llm-chat", PROVIDER, Map.of("operation", "generate", "model", "m1"), null));

        assertThat(result.getStatus())
                .as("o designer oferecia isto e o runtime respondia 'component not found'")
                .isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).contains("eco:generate:null");
    }

    @Test
    @DisplayName("o input do step anterior chega ao adapter")
    void inputFlowsIntoTheAdapter() {
        Flow flow = deserializer.toFlow(workflow(
                "llm-chat", PROVIDER, Map.of("operation", "generate"), null));
        ExecutionContext ctx = context();
        ctx.set("input", "resuma isto");

        StepResult result = flow.getSteps().get(0).execute(ctx).join();

        assertThat(result.getOutput()).contains("eco:generate:resuma isto");
    }

    @Test
    @DisplayName("provider desconhecido continua caindo no catálogo — zero regressão")
    void unknownProviderStillUsesTheCatalog() {
        StepResult result = runFirstStep(workflow(
                "llm-chat", "provider-que-nao-existe", Map.of(), null));

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).asString().contains("component not found");
    }

    @Test
    @DisplayName("o escopo do fluxo também vale para nós de adapter")
    void componentScopeAppliesToAdapters() {
        StepResult result = runFirstStep(workflow(
                "llm-chat", PROVIDER, Map.of("operation", "generate"), List.of("outra-coisa")));

        assertThat(result.getStatus())
                .as("um fluxo restrito nao pode alcancar um adapter fora da lista")
                .isEqualTo(StepStatus.FAILED);
    }

    @Test
    @DisplayName("adapter dentro da allowlist executa normalmente")
    void allowedAdapterExecutes() {
        StepResult result = runFirstStep(workflow(
                "llm-chat", PROVIDER, Map.of("operation", "generate"), List.of(PROVIDER)));

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
    }
}
