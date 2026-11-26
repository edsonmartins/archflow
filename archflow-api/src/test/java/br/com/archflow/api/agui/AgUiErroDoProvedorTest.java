package br.com.archflow.api.agui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import br.com.archflow.agent.persistence.InMemoryFlowRepository;
import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.agent.streaming.RunningFlowsRegistry;
import br.com.archflow.api.flow.FlowEngineFactory;
import br.com.archflow.api.flow.InMemoryStateManager;
import br.com.archflow.api.flow.SimpleFlow;
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
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InvalidRequestException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * RCHFLW-130 pelo caminho do Executar do editor: o painel lê o stream AG-UI, não o registro da execução. O
 * registro já saía legível, mas o {@code STEP_FINISHED.error} (e o {@code RUN_ERROR.message}) levava o corpo
 * HTTP do OpenRouter como veio — JSON cru com o {@code user_id} da conta da plataforma — para a tela de um
 * usuário do tenant. Motor e mapper de verdade; só a conexão é trocada por uma que guarda o que foi enviado.
 */
@DisplayName("AG-UI: o erro do provedor de LLM chega ao editor legível, sem o JSON cru nem o user_id")
class AgUiErroDoProvedorTest {

    private static final String WORKFLOW_ID = "wf-modelo-inexistente";
    private static final String CORPO = "{\"error\":{\"message\":\"qa/modelo-que-nao-existe is not a valid model "
            + "ID\",\"code\":400},\"user_id\":\"user_314F4GOabcdefXYZ\"}";
    private static final String LEGIVEL = "O provedor do modelo recusou a chamada (HTTP 400, requisição inválida): "
            + "qa/modelo-que-nao-existe is not a valid model ID";
    private static final ObjectMapper JSON = new ObjectMapper();

    private FlowEngine engine;
    private AgUiController controller;
    private PararNoAgUiCancelaAExecucaoTest.Conexao conexao;

    @BeforeEach
    void monta() {
        var flowRepository = new InMemoryFlowRepository();
        var stateManager = new InMemoryStateManager();
        // O mesmo hub no motor e no controller: é por ele que os eventos dos passos chegam à resposta.
        var hub = new EventStreamRegistry();
        engine = spy(FlowEngineFactory.create(flowRepository, hub, new RunningFlowsRegistry(), stateManager));
        var store = new InMemoryWorkflowRuntimeStore();
        store.putWorkflow(DefaultTenant.ID, WORKFLOW_ID,
                Map.of("id", WORKFLOW_ID, "metadata", Map.of("name", "Modelo"), "steps", List.of()));
        conexao = new PararNoAgUiCancelaAExecucaoTest.Conexao();
        controller = new AgUiController(store,
                json -> new SimpleFlow(String.valueOf(json.get("id")),
                        new FlowMetadata("Modelo", "", "1.0.0", null, null, List.of()), List.of(llmQueFalha())),
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
    @DisplayName("STEP_FINISHED do passo que falhou leva a frase traduzida")
    void stepFinished() throws Exception {
        controller.run(WORKFLOW_ID, null);
        List<JsonNode> eventos = eventosAteOFim();

        JsonNode fim = eventos.stream()
                .filter(e -> "STEP_FINISHED".equals(e.path("type").asText()))
                .filter(e -> "STEP_FAILED".equals(e.path("status").asText()))
                .findFirst().orElseThrow(() -> new AssertionError("sem STEP_FINISHED falho: " + conexao.enviados));
        assertThat(fim.path("error").asText()).isEqualTo(LEGIVEL);
        assertThat(String.join("\n", conexao.enviados)).doesNotContain("user_314F4GO").doesNotContain("\\\"error\\\"");
    }

    @Test
    @DisplayName("RUN_ERROR leva a frase traduzida")
    void runError() throws Exception {
        doReturn(CompletableFuture.failedFuture(new CompletionException(
                new InvalidRequestException(new HttpException(400, CORPO)))))
                .when(engine).execute(any(), any());

        controller.run(WORKFLOW_ID, null);
        List<JsonNode> eventos = eventosAteOFim();

        JsonNode erro = eventos.get(eventos.size() - 1);
        assertThat(erro.path("type").asText()).isEqualTo("RUN_ERROR");
        assertThat(erro.path("message").asText()).isEqualTo(LEGIVEL);
        assertThat(String.join("\n", conexao.enviados)).doesNotContain("user_314F4GO");
    }

    private List<JsonNode> eventosAteOFim() throws Exception {
        long prazo = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < prazo) {
            List<JsonNode> eventos = conexao.enviados.stream().map(AgUiErroDoProvedorTest::ler).toList();
            if (eventos.stream().map(e -> e.path("type").asText())
                    .anyMatch(t -> t.equals("RUN_FINISHED") || t.equals("RUN_ERROR"))) {
                return eventos;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("o run não terminou: " + conexao.enviados);
    }

    /** O que a conexão guardou é a linha SSE inteira ({@code data:{…}}); o evento é o JSON depois do prefixo. */
    private static JsonNode ler(String evento) {
        String corpo = evento.strip();
        try {
            return JSON.readTree(corpo.startsWith("data:") ? corpo.substring("data:".length()) : corpo);
        } catch (Exception e) {
            throw new IllegalStateException(evento, e);
        }
    }

    private static FlowStep llmQueFalha() {
        return new FlowStep() {
            @Override public String getId() { return "classificar"; }
            @Override public StepType getType() { return StepType.TOOL; }
            @Override public List<StepConnection> getConnections() { return List.of(); }
            @Override public CompletableFuture<StepResult> execute(ExecutionContext c) {
                return CompletableFuture.failedFuture(new InvalidRequestException(new HttpException(400, CORPO)));
            }
        };
    }
}
