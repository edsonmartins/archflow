package br.com.archflow.api.web.workflow;

import br.com.archflow.agent.persistence.InMemoryFlowRepository;
import br.com.archflow.api.approval.dto.ApprovalResponse;
import br.com.archflow.api.approval.dto.ApprovalSubmitRequest;
import br.com.archflow.api.approval.impl.ApprovalQueueService;
import br.com.archflow.api.flow.DefaultFlowStepFactory;
import br.com.archflow.api.flow.DefaultWorkflowDeserializer;
import br.com.archflow.api.flow.FlowEngineFactory;
import br.com.archflow.api.flow.InMemoryStateManager;
import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.model.tenant.DefaultTenant;
import br.com.archflow.plugin.api.catalog.DefaultComponentCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RCHFLW-122: excluir um fluxo apagava junto as execuções ({@code DELETE FROM workflow_executions}). A execução
 * sumia de Execuções, e a aprovada pela fila depois da exclusão dava "HTTP 404" no detalhe — "quem autorizou isto"
 * deixava de ter resposta.
 *
 * <p>No motor de verdade (deserializer, fábrica, portão de aprovação, fila): enquanto a execução espera a aprovação a
 * exclusão é recusada (409); decidida a aprovação, o fluxo sai da lista e do GET (404), e a execução continua na
 * lista e no detalhe, com o nome do fluxo e quem aprovou.</p>
 */
@DisplayName("excluir fluxo mantém o histórico; 409 com execução pausada ou em andamento (RCHFLW-122)")
class ExcluirFluxoMantemHistoricoTest {

    private static final String T = DefaultTenant.ID;

    private InMemoryWorkflowRuntimeStore store;
    private SpringWorkflowCrudController crud;
    private SpringExecutionController execucoes;
    private ApprovalQueueService fila;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void monta() {
        store = new InMemoryWorkflowRuntimeStore();
        InMemoryStateManager estado = new InMemoryStateManager();
        InMemoryFlowRepository repositorio = new InMemoryFlowRepository();
        FlowEngine motor = FlowEngineFactory.create(repositorio, null, null, estado);
        ObjectProvider<FlowEngine> provedor = mock(ObjectProvider.class);
        when(provedor.getObject()).thenReturn(motor);
        when(provedor.getIfAvailable()).thenReturn(motor);
        DefaultFlowStepFactory fabrica = new DefaultFlowStepFactory(new DefaultComponentCatalog(),
                mock(DynamicWorkflowService.class), null, estado, provedor);
        crud = new SpringWorkflowCrudController(store, new DefaultWorkflowDeserializer(fabrica), motor, repositorio,
                null, null);
        execucoes = new SpringExecutionController(store, motor, estado, null);
        fila = new ApprovalQueueService(estado, motor, Duration.ZERO, Clock.systemUTC(), null, store);
    }

    private static Map<String, Object> no(String id, String type, String label, Map<String, Object> config,
                                          List<Map<String, Object>> arestas) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("type", type);
        node.put("componentId", type);
        node.put("label", label);
        node.put("configuration", config);
        node.put("connections", arestas);
        return node;
    }

    /** Receber pedido → Aprovação do gerente → Responder. */
    private String criaFluxoComAprovacao() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("metadata", Map.of("name", "Reembolso com aprovação", "version", "1.0.0"));
        body.put("steps", List.of(
                no("receber", "input", "Receber pedido", Map.of(), List.of(Map.of("targetId", "gerente"))),
                no("gerente", "approval", "Aprovação do gerente", Map.of("approvalTitle", "Reembolso"),
                        List.of(Map.of("targetId", "responder"))),
                no("responder", "output", "Responder", Map.of(), List.of())));
        return String.valueOf(crud.create(body).getBody().get("id"));
    }

    private static void espera(BooleanSupplier condicao, String oQue) throws InterruptedException {
        long limite = System.currentTimeMillis() + 15_000;
        while (!condicao.getAsBoolean()) {
            if (System.currentTimeMillis() > limite) {
                throw new AssertionError("não aconteceu em 15 s: " + oQue);
            }
            Thread.sleep(20);
        }
    }

    private String statusDe(String execucao) {
        Map<String, Object> registro = store.getExecution(T, execucao);
        return registro == null ? null : String.valueOf(registro.get("status"));
    }

    @Test
    @DisplayName("pausada na aprovação: 409; decidida, o fluxo some e a execução fica, com o nome e o decisor")
    void excluirDepoisDaAprovacaoMantemOHistorico() throws Exception {
        String fluxo = criaFluxoComAprovacao();
        String execucao = String.valueOf(crud.execute(fluxo, Map.of("input", "reembolso de 900 reais"))
                .getBody().get("executionId"));
        espera(() -> "PAUSED".equals(statusDe(execucao)), "a execução parar na aprovação");

        var recusa = crud.delete(fluxo);

        assertThat(recusa.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(recusa.getBody())
                .containsEntry("code", "WORKFLOW_HAS_ACTIVE_EXECUTIONS")
                .containsEntry("executions", List.of(execucao));
        assertThat(String.valueOf(recusa.getBody().get("error")))
                .contains("\"Reembolso com aprovação\"")
                .contains("aguardando aprovação");
        assertThat(crud.get(fluxo).getStatusCode()).as("nada foi excluído").isEqualTo(HttpStatus.OK);

        ApprovalResponse pendente = fila.listPending(T).stream()
                .filter(p -> execucao.equals(p.flowId())).findFirst().orElseThrow();
        fila.submitDecision(pendente.requestId(), new ApprovalSubmitRequest(T, "APPROVED", null, "gerente-ana"));
        espera(() -> "COMPLETED".equals(statusDe(execucao)), "a execução concluir depois da aprovação");

        assertThat(crud.delete(fluxo).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // O fluxo some de verdade: lista e GET.
        assertThat(crud.get(fluxo).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(crud.list()).noneMatch(w -> fluxo.equals(w.get("id")));
        // O histórico fica: lista (geral e filtrada pelo fluxo) e detalhe, com o nome e quem aprovou.
        assertThat(execucoes.list(null, null)).anyMatch(e -> execucao.equals(e.get("id")));
        assertThat(execucoes.list(fluxo, null)).singleElement()
                .satisfies(e -> assertThat(e).containsEntry("workflowName", "Reembolso com aprovação"));
        var detalhe = execucoes.get(execucao);
        assertThat(detalhe.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detalhe.getBody())
                .containsEntry("workflowName", "Reembolso com aprovação")
                .containsEntry("status", "COMPLETED");
        assertThat(String.valueOf(detalhe.getBody().get("approvals"))).contains("gerente-ana");
    }

    @Test
    @DisplayName("execução em andamento também bloqueia; terminada, não")
    void emAndamentoBloqueia() {
        String fluxo = criaFluxoComAprovacao();
        String rodando = String.valueOf(store.createExecution(T, fluxo, "Reembolso com aprovação").get("id"));

        var recusa = crud.delete(fluxo);
        assertThat(recusa.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(recusa.getBody()).containsEntry("executions", List.of(rodando));

        store.completeExecution(rodando, "CANCELLED", null);
        assertThat(crud.delete(fluxo).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(execucoes.get(rodando).getBody()).containsEntry("workflowName", "Reembolso com aprovação");
    }
}
