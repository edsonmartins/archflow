package br.com.archflow.api.web.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.com.archflow.api.audit.AuditTrail;
import br.com.archflow.api.flow.WorkflowDeserializer;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.exceptions.FlowEngineException;
import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.tenant.DefaultTenant;
import br.com.archflow.observability.audit.InMemoryAuditRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O motor recusa um fluxo com nó sem execução antes do primeiro passo (RCHFLW-11), e a razão vem na
 * exceção de validação. Ela chega embrulhada duas vezes ({@code CompletionException} →
 * {@code FlowEngineException("Error executing flow: …")} → a validação). O registro da execução guardava
 * só o primeiro invólucro, então o detalhe mostrava "Error executing flow: exec-…" no lugar da razão.
 */
@DisplayName("Execução pela lista/API: o registro guarda a razão da falha, não o invólucro do motor")
class ErroDaExecucaoPelaListaTest {

    @Test
    @DisplayName("a mensagem gravada é a da causa raiz")
    void guardaACausaRaiz() {
        var store = new InMemoryWorkflowRuntimeStore();
        FlowEngine engine = mock(FlowEngine.class);
        WorkflowDeserializer deserializer = mock(WorkflowDeserializer.class);
        when(deserializer.toFlow(any())).thenReturn(mock(Flow.class));
        String razao = "o tipo de nó 'agent' ainda não tem execução no servidor — nó(s): \"Agente\" (ag)";
        when(engine.execute(any(), any())).thenReturn(CompletableFuture.failedFuture(new CompletionException(
                new FlowEngineException("Error executing flow: exec-x", new IllegalStateException(razao)))));
        var crud = new SpringWorkflowCrudController(store, deserializer, engine, mock(FlowRepository.class),
                new AuditTrail(InMemoryAuditRepository::new), null);
        String workflowId = String.valueOf(crud.create(new HashMap<>(Map.of("metadata", Map.of("name", "X"))))
                .getBody().get("id"));

        var body = crud.execute(workflowId, Map.of()).getBody();

        Map<String, Object> registro = store.getExecution(DefaultTenant.ID, String.valueOf(body.get("executionId")));
        assertThat(registro).containsEntry("status", "FAILED").containsEntry("error", razao);
    }
}
