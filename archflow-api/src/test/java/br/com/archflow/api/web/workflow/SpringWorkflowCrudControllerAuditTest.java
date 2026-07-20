package br.com.archflow.api.web.workflow;

import br.com.archflow.api.audit.AuditTrail;
import br.com.archflow.api.flow.WorkflowDeserializer;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.observability.audit.AuditAction;
import br.com.archflow.observability.audit.AuditEvent;
import br.com.archflow.observability.audit.AuditRepository;
import br.com.archflow.observability.audit.InMemoryAuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Produção de eventos de auditoria pelo CRUD/execute de workflows (fase 5.6).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SpringWorkflowCrudController — auditoria")
class SpringWorkflowCrudControllerAuditTest {

    @Mock
    private WorkflowDeserializer deserializer;
    @Mock
    private FlowEngine flowEngine;
    @Mock
    private FlowRepository flowRepository;

    private InMemoryAuditRepository auditRepo;
    private SpringWorkflowCrudController controller;

    @BeforeEach
    void setUp() {
        auditRepo = new InMemoryAuditRepository();
        controller = new SpringWorkflowCrudController(
                new InMemoryWorkflowRuntimeStore(),
                deserializer,
                flowEngine,
                flowRepository,
                new AuditTrail(() -> auditRepo));
        lenient().when(deserializer.toFlow(any())).thenReturn(mock(Flow.class));
        lenient().when(flowEngine.execute(any(), any())).thenReturn(new CompletableFuture<>());
    }

    private List<AuditEvent> events() {
        return auditRepo.query(AuditRepository.AuditQuery.builder().limit(20));
    }

    @Test
    @DisplayName("create/update/delete gravam CREATE/UPDATE/DELETE em 'workflow'")
    void crudIsAudited() {
        var created = controller.create(new java.util.HashMap<>(
                Map.of("metadata", Map.of("name", "Flow A"))));
        String id = String.valueOf(created.getBody().get("id"));

        controller.update(id, new java.util.HashMap<>(Map.of("metadata", Map.of("name", "Flow B"))));
        controller.delete(id);

        var events = events();
        assertThat(events).hasSize(3);
        assertThat(events).extracting(AuditEvent::getAction)
                .containsExactlyInAnyOrder(AuditAction.CREATE, AuditAction.UPDATE, AuditAction.DELETE);
        assertThat(events).allMatch(e -> "workflow".equals(e.getResourceType()));
        assertThat(events).allMatch(e -> id.equals(e.getResourceId()));
    }

    @Test
    @DisplayName("execute grava WORKFLOW_EXECUTE com o executionId no contexto")
    void executeIsAudited() {
        var created = controller.create(new java.util.HashMap<>(
                Map.of("metadata", Map.of("name", "Flow A"))));
        String id = String.valueOf(created.getBody().get("id"));

        var response = controller.execute(id, Map.of());
        String executionId = String.valueOf(response.getBody().get("executionId"));

        assertThat(events())
                .filteredOn(e -> e.getAction() == AuditAction.WORKFLOW_EXECUTE)
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.getResourceId()).isEqualTo(id);
                    assertThat(e.getContext()).containsEntry("executionId", executionId);
                });
    }

    @Test
    @DisplayName("workflow inexistente não gera evento")
    void missingWorkflowProducesNoEvent() {
        controller.delete("wf-missing");
        controller.execute("wf-missing", Map.of());

        assertThat(events()).isEmpty();
    }
}
