package br.com.archflow.api.web.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import br.com.archflow.api.audit.AuditTrail;
import br.com.archflow.api.flow.WorkflowDeserializer;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.observability.audit.InMemoryAuditRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O "Salvar YAML" grava o metadata com as chaves que o YAML mostra, inclusive vazias
 * ({@code description: null}, {@code author: null}). O resumo da lista era montado com {@code Map.of}, que
 * não aceita nulo, e o {@code getOrDefault} devolve o nulo quando a chave existe — um único fluxo salvo
 * pelo YAML derrubava {@code GET /api/workflows} (500) e a página Fluxos ficava vazia para o tenant
 * inteiro ("Internal Server Error" + "Nenhum fluxo ainda").
 */
@DisplayName("lista de fluxos: campo nulo no documento não derruba a lista")
class ListaComCampoNuloTest {

    @Test
    @DisplayName("metadata com description/author nulos (como o Salvar YAML grava) aparece na lista")
    void campoNuloNaoDerrubaALista() {
        var controller = new SpringWorkflowCrudController(new InMemoryWorkflowRuntimeStore(),
                mock(WorkflowDeserializer.class), mock(FlowEngine.class), mock(FlowRepository.class),
                new AuditTrail(InMemoryAuditRepository::new), null);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("name", "Salvo pelo YAML");
        metadata.put("description", null);
        metadata.put("version", "1.0.0");
        metadata.put("author", null);
        Map<String, Object> doc = new HashMap<>();
        doc.put("metadata", metadata);
        doc.put("steps", List.of(Map.of("id", "a", "type", "condition")));
        doc.put("status", null);
        Object id = controller.create(doc).getBody().get("id");

        assertThat(controller.list()).filteredOn(resumo -> id.equals(resumo.get("id"))).singleElement()
                .satisfies(resumo -> {
                    assertThat(resumo.get("name")).isEqualTo("Salvo pelo YAML");
                    assertThat(resumo.get("description")).isEqualTo("");
                    assertThat(resumo.get("status")).isEqualTo("draft");
                    assertThat(resumo.get("stepCount")).isEqualTo(1);
                });
    }
}
