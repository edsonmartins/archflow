package br.com.archflow.api.agui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.spy;

import br.com.archflow.agent.persistence.InMemoryFlowRepository;
import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.agent.streaming.RunningFlowsRegistry;
import br.com.archflow.api.flow.FlowEngineFactory;
import br.com.archflow.api.flow.InMemoryStateManager;
import br.com.archflow.api.flow.SimpleFlow;
import br.com.archflow.api.flow.SimpleStepResult;
import br.com.archflow.api.web.workflow.InMemoryWorkflowRuntimeStore;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.FlowMetadata;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepConnection;
import br.com.archflow.model.flow.StepResult;
import br.com.archflow.model.flow.StepType;
import br.com.archflow.model.tenant.DefaultTenant;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * RCHFLW-194, item 6: o Runner AG-UI terminava com {@code RUN_FINISHED {"result":{"status":"COMPLETED"}}} e
 * nenhuma resposta, embora a execução tivesse a saída do LLM. Nenhum nó do caminho de workflow emite
 * {@code TEXT_MESSAGE_*}: a saída do fluxo vai no {@code result} do {@code RUN_FINISHED}. Motor e mapper de
 * verdade; só a conexão é trocada por uma que guarda o que foi enviado.
 */
@DisplayName("AG-UI: o RUN_FINISHED traz a saída do fluxo")
class AgUiRunFinishedComSaidaTest {

    private static final String WORKFLOW_ID = "wf-47764b9a";
    private static final String RESPOSTA = "Produtos digitais podem ser reembolsados em até 7 dias.";
    private static final ObjectMapper JSON = new ObjectMapper();

    private AgUiController controller;
    private PararNoAgUiCancelaAExecucaoTest.Conexao conexao;

    @BeforeEach
    void monta() {
        var flowRepository = new InMemoryFlowRepository();
        var stateManager = new InMemoryStateManager();
        var hub = new EventStreamRegistry();
        FlowEngine engine = spy(FlowEngineFactory.create(flowRepository, hub, new RunningFlowsRegistry(),
                stateManager));
        var store = new InMemoryWorkflowRuntimeStore();
        store.putWorkflow(DefaultTenant.ID, WORKFLOW_ID,
                Map.of("id", WORKFLOW_ID, "metadata", Map.of("name", "RAG"), "steps", List.of()));
        conexao = new PararNoAgUiCancelaAExecucaoTest.Conexao();
        controller = new AgUiController(store,
                json -> new SimpleFlow(String.valueOf(json.get("id")),
                        new FlowMetadata("RAG", "", "1.0.0", null, null, List.of()), List.of(responder())),
                engine, hub, new DefaultAgUiEventMapper(), stateManager, flowRepository, new ObjectMapper()) {
            @Override
            SseEmitter novoEmissor() {
                return conexao;
            }
        };
    }

    @AfterEach
    void fecha() {
        controller.close();
    }

    @Test
    @DisplayName("COMPLETED: o result leva o status e a saída do fluxo")
    void saidaNoRunFinished() throws Exception {
        controller.run(WORKFLOW_ID, null);

        JsonNode fim = fimDoRun();
        assertThat(fim.path("type").asText()).isEqualTo("RUN_FINISHED");
        assertThat(fim.path("result").path("status").asText()).isEqualTo("COMPLETED");
        assertThat(fim.path("result").path("output").asText()).isEqualTo(RESPOSTA);
    }

    private JsonNode fimDoRun() throws Exception {
        long prazo = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < prazo) {
            for (String enviado : conexao.enviados) {
                JsonNode evento = ler(enviado);
                String tipo = evento.path("type").asText();
                if (tipo.equals("RUN_FINISHED") || tipo.equals("RUN_ERROR")) {
                    return evento;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("o run não terminou: " + conexao.enviados);
    }

    private static JsonNode ler(String evento) {
        String corpo = evento.strip();
        try {
            return JSON.readTree(corpo.startsWith("data:") ? corpo.substring("data:".length()) : corpo);
        } catch (Exception e) {
            throw new IllegalStateException(evento, e);
        }
    }

    private static FlowStep responder() {
        return new FlowStep() {
            @Override public String getId() { return "llm"; }
            @Override public StepType getType() { return StepType.TOOL; }
            @Override public List<StepConnection> getConnections() { return List.of(); }
            @Override public CompletableFuture<StepResult> execute(ExecutionContext c) {
                return CompletableFuture.completedFuture(SimpleStepResult.ok("llm", RESPOSTA, 1));
            }
        };
    }
}
