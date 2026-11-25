package br.com.archflow.api.web.workflow;

import br.com.archflow.api.audit.AuditTrail;
import br.com.archflow.api.flow.WorkflowDeserializer;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.observability.audit.InMemoryAuditRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("lista de fluxos: a contagem de passos não inclui anotações do canvas")
class ContagemDePassosTest {

    @Test
    @DisplayName("2 passos + nota + quadro = 2 passos")
    void anotacoesNaoContam() {
        var controller = new SpringWorkflowCrudController(new InMemoryWorkflowRuntimeStore(),
                mock(WorkflowDeserializer.class), mock(FlowEngine.class), mock(FlowRepository.class),
                new AuditTrail(InMemoryAuditRepository::new), null);
        var criado = controller.create(new HashMap<>(Map.of(
                "metadata", Map.of("name", "Com notas"),
                "steps", List.of(
                        Map.of("id", "a", "componentId", "condition", "type", "CONDITION"),
                        Map.of("id", "nota", "componentId", "sticky-note", "type", "STICKY-NOTE"),
                        Map.of("id", "b", "componentId", "switch", "type", "SWITCH"),
                        Map.of("id", "quadro", "type", "GROUP-FRAME")))));

        Object id = criado.getBody().get("id");

        // O store em memória já vem com um fluxo de demonstração; olha só o criado aqui.
        assertThat(controller.list()).filteredOn(resumo -> id.equals(resumo.get("id"))).singleElement()
                .satisfies(resumo -> assertThat(resumo.get("stepCount")).isEqualTo(2));
    }
}
