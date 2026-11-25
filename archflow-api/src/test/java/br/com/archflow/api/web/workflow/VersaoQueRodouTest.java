package br.com.archflow.api.web.workflow;

import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.api.agui.AgUiController;
import br.com.archflow.api.agui.DefaultAgUiEventMapper;
import br.com.archflow.api.agui.RunAgentInput;
import br.com.archflow.api.audit.AuditTrail;
import br.com.archflow.api.flow.WorkflowDeserializer;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.core.StateManager;
import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.tenant.DefaultTenant;
import br.com.archflow.observability.audit.InMemoryAuditRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RCHFLW-89: o mesmo fluxo rodava o rascunho pelo editor (AG-UI) e a versão em produção pela lista/API
 * ({@code /execute}), e nenhuma tela dizia qual. O registro da execução passa a gravar o que rodou no
 * momento da criação — a tela lê o registro, não adivinha pela lista de versões.
 */
@DisplayName("Execução grava a versão que rodou (rascunho ou vN publicada)")
class VersaoQueRodouTest {

    private InMemoryWorkflowRuntimeStore store;
    private FlowEngine engine;
    private WorkflowDeserializer deserializer;
    private SpringWorkflowCrudController crud;
    private String workflowId;

    @BeforeEach
    void setUp() {
        store = new InMemoryWorkflowRuntimeStore();
        engine = mock(FlowEngine.class);
        deserializer = mock(WorkflowDeserializer.class);
        when(deserializer.toFlow(any())).thenReturn(mock(Flow.class));
        // Nunca completa: o teste olha o registro criado, não o desfecho.
        when(engine.execute(any(), any())).thenReturn(new CompletableFuture<>());
        crud = new SpringWorkflowCrudController(store, deserializer, engine, mock(FlowRepository.class),
                new AuditTrail(InMemoryAuditRepository::new), null);
        workflowId = String.valueOf(crud.create(new HashMap<>(Map.of("metadata", Map.of("name", "Triagem"))))
                .getBody().get("id"));
    }

    private Map<String, Object> registro(Object executionId) {
        return store.getExecution(DefaultTenant.ID, String.valueOf(executionId));
    }

    @Test
    @DisplayName("/execute com versão em produção grava o número dela e devolve-o na resposta")
    void execucaoPelaListaGravaAVersaoPublicada() {
        crud.publish(workflowId, Map.of("comment", "primeira"));
        crud.publish(workflowId, Map.of("comment", "segunda"));

        var body = crud.execute(workflowId, Map.of()).getBody();

        assertThat(body).containsEntry("workflowVersionNumber", 2).containsEntry("workflowDraft", false);
        assertThat(registro(body.get("executionId")))
                .containsEntry("workflowDraft", false)
                .containsEntry("workflowVersionNumber", 2)
                .containsEntry("workflowVersionId", body.get("workflowVersionId"));
    }

    @Test
    @DisplayName("/execute sem versão em produção roda o rascunho e grava isso")
    void execucaoSemImplantacaoGravaORascunho() {
        var body = crud.execute(workflowId, Map.of()).getBody();

        assertThat(body).containsEntry("workflowDraft", true);
        assertThat(registro(body.get("executionId")))
                .containsEntry("workflowDraft", true)
                .doesNotContainKey("workflowVersionNumber");
    }

    @Test
    @DisplayName("Executar do editor (AG-UI) grava o rascunho mesmo com versão em produção")
    void execucaoDoEditorGravaORascunho() {
        crud.publish(workflowId, Map.of("comment", "primeira"));
        AgUiController agUi = new AgUiController(store, deserializer, engine, new EventStreamRegistry(),
                new DefaultAgUiEventMapper(), mock(StateManager.class), mock(FlowRepository.class),
                new ObjectMapper());

        agUi.run(workflowId, new RunAgentInput(List.of(), Map.of(), List.of(), List.of(), null, null));

        assertThat(store.executions(DefaultTenant.ID, workflowId, null)).singleElement()
                .satisfies(execucao -> assertThat(execucao)
                        .containsEntry("workflowDraft", true)
                        .doesNotContainKey("workflowVersionNumber"));
    }
}
