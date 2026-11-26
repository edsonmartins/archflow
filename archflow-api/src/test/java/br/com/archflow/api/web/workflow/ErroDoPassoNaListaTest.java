package br.com.archflow.api.web.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.archflow.agent.persistence.InMemoryFlowRepository;
import br.com.archflow.api.audit.AuditTrail;
import br.com.archflow.api.flow.FlowEngineFactory;
import br.com.archflow.api.flow.SimpleFlow;
import br.com.archflow.api.flow.SimpleStepResult;
import br.com.archflow.api.flow.StepRecordingListener;
import br.com.archflow.api.flow.WorkflowDeserializer;
import br.com.archflow.engine.lifecycle.FlowLifecycleListeners;
import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.FlowMetadata;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepConnection;
import br.com.archflow.model.flow.StepResult;
import br.com.archflow.model.flow.StepType;
import br.com.archflow.model.tenant.DefaultTenant;
import br.com.archflow.observability.audit.InMemoryAuditRepository;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InvalidRequestException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RCHFLW-130 pelo motor de verdade: um passo que falha não faz o motor lançar — ele devolve um resultado FAILED
 * —, e o {@code /execute} só gravava o erro de uma exceção. A execução aparecia "Falhou" com a coluna Erro em
 * branco (modelo inexistente, componente não encontrado, condição vazia). E o erro do provedor chegava ao
 * detalhe como o corpo HTTP cru, com o {@code user_id} da conta OpenRouter da plataforma.
 */
@DisplayName("Execução que falhou num passo: a lista mostra o erro do passo, legível e sem identificador da conta")
class ErroDoPassoNaListaTest {

    private static final String CORPO = "{\"error\":{\"message\":\"qa/modelo-que-nao-existe is not a valid model "
            + "ID\",\"code\":400},\"user_id\":\"user_314F4GOabcdefXYZ\"}";

    private InMemoryWorkflowRuntimeStore store;
    private StepRecordingListener recorder;

    @BeforeEach
    void setUp() {
        store = new InMemoryWorkflowRuntimeStore();
        recorder = new StepRecordingListener(store);
        FlowLifecycleListeners.register(recorder);
    }

    @AfterEach
    void tearDown() {
        FlowLifecycleListeners.unregister(recorder);
    }

    private static FlowStep passo(String id, Function<String, CompletableFuture<StepResult>> execucao) {
        return new FlowStep() {
            @Override public String getId() { return id; }
            @Override public StepType getType() { return StepType.TOOL; }
            @Override public List<StepConnection> getConnections() { return List.of(); }
            @Override public CompletableFuture<StepResult> execute(ExecutionContext c) { return execucao.apply(id); }
        };
    }

    /** Roda pelo {@code /execute} de verdade, com o motor de verdade, e devolve a linha da lista. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> rodarPelaLista(FlowStep passo) throws Exception {
        FlowRepository repo = new InMemoryFlowRepository();
        WorkflowDeserializer deserializer = mock(WorkflowDeserializer.class);
        when(deserializer.toFlow(any())).thenAnswer(inv -> new SimpleFlow(
                String.valueOf(((Map<String, Object>) inv.getArgument(0)).get("id")),
                new FlowMetadata("Falha", "", "1.0.0", null, null, List.of()), List.of(passo)));
        var crud = new SpringWorkflowCrudController(store, deserializer, FlowEngineFactory.create(repo), repo,
                new AuditTrail(InMemoryAuditRepository::new), null);
        String workflowId = String.valueOf(crud.create(new HashMap<>(Map.of("metadata", Map.of("name", "Falha"))))
                .getBody().get("id"));
        String executionId = String.valueOf(crud.execute(workflowId, Map.of()).getBody().get("executionId"));

        long limite = System.currentTimeMillis() + 15_000;
        while (!"FAILED".equals(store.getExecution(DefaultTenant.ID, executionId).get("status"))) {
            assertThat(System.currentTimeMillis()).as("a execução terminou FAILED").isLessThan(limite);
            Thread.sleep(20);
        }
        return store.executions(DefaultTenant.ID, null, null).stream()
                .filter(e -> executionId.equals(e.get("id"))).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("passo que devolve FAILED (condição vazia): a coluna Erro tem o motivo do passo")
    void passoQueDevolveFalha() throws Exception {
        var linha = rodarPelaLista(passo("decide", id -> CompletableFuture.completedFuture(
                SimpleStepResult.failed(id, "'conditionExpression' é obrigatória", 1))));

        assertThat(linha).containsEntry("status", "FAILED")
                .containsEntry("error", "'conditionExpression' é obrigatória");
    }

    @Test
    @DisplayName("erro do provedor: lista e passo mostram a frase traduzida, sem o JSON nem o user_id")
    @SuppressWarnings("unchecked")
    void erroDoProvedor() throws Exception {
        var linha = rodarPelaLista(passo("classificar", id -> CompletableFuture.failedFuture(
                new InvalidRequestException(new HttpException(400, CORPO)))));

        String esperado = "O provedor do modelo recusou a chamada (HTTP 400, requisição inválida): "
                + "qa/modelo-que-nao-existe is not a valid model ID";
        assertThat(linha).containsEntry("error", esperado);
        Map<String, Object> passo = ((List<Map<String, Object>>) linha.get("steps")).get(0);
        assertThat(passo).containsEntry("status", "FAILED").containsEntry("error", esperado);
        assertThat(String.valueOf(linha)).doesNotContain("user_314F4GO").doesNotContain("\"error\"");
    }
}
