package br.com.archflow.api.approval;

import br.com.archflow.api.agent.mcp.McpAgentRunner;
import br.com.archflow.api.agent.mcp.McpAgentState;
import br.com.archflow.api.agent.mcp.RetomadaDoLaco;
import br.com.archflow.api.approval.dto.ApprovalResponse;
import br.com.archflow.api.approval.dto.ApprovalSubmitRequest;
import br.com.archflow.api.approval.impl.ApprovalQueueService;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.core.StateManager;
import br.com.archflow.model.flow.FlowState;
import br.com.archflow.model.flow.FlowStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Uma fila, dois gates humanos.
 *
 * <p>O nó {@code APPROVAL} do grafo e o gate dentro do laço de agente são mecanismos diferentes —
 * um suspende a travessia do fluxo, o outro o laço de tool-calling. Para quem decide, a diferença
 * não existe: é sempre "isto vai acontecer, pode?". Duas filas exigiriam que o operador soubesse de
 * qual mecanismo veio cada pedido para saber onde respondê-lo.</p>
 */
@DisplayName("fila de aprovações com o gate do laço de agente")
class FilaComGateDoLacoTest {

    private static final String TENANT = "acme";

    private final StateManager stateManager = mock(StateManager.class);
    private final FlowEngine flowEngine = mock(FlowEngine.class);
    private final RetomadaDoLaco retomada = mock(RetomadaDoLaco.class);

    private ApprovalQueueService fila() {
        return new ApprovalQueueService(stateManager, flowEngine, Duration.ZERO,
                Clock.systemUTC(), retomada);
    }

    private static RetomadaDoLaco.Pendencia pendenciaDoLaco() {
        return new RetomadaDoLaco.Pendencia("req-laco", TENANT, "run-1", "reiniciar_servico",
                Map.of("servico", "traefik"), Instant.parse("2026-09-18T10:00:00Z"));
    }

    /** Um fluxo parado num nó APPROVAL, na forma que o motor grava. */
    private static FlowState fluxoAguardando() {
        FlowState state = FlowState.builder()
                .tenantId(TENANT)
                .flowId("flow-1")
                .status(FlowStatus.AWAITING_APPROVAL)
                .variables(Map.of(
                        br.com.archflow.model.engine.ExecutionKeys.APPROVAL_REQUEST_ID, "req-fluxo",
                        br.com.archflow.model.engine.ExecutionKeys.APPROVAL_PROPOSAL, Map.of("valor", 10),
                        br.com.archflow.model.engine.ExecutionKeys.APPROVAL_REQUESTED_AT,
                        "2026-09-18T11:00:00Z"))
                .build();
        return state;
    }

    @Nested
    @DisplayName("listagem")
    class Listagem {

        @Test
        @DisplayName("os dois gates aparecem na mesma fila, mais antigo primeiro")
        void juntos() {
            when(stateManager.findByStatus(FlowStatus.AWAITING_APPROVAL))
                    .thenReturn(List.of(fluxoAguardando()));
            when(retomada.pendentes(TENANT)).thenReturn(List.of(pendenciaDoLaco()));

            List<ApprovalResponse> fila = fila().listPending(TENANT);

            assertThat(fila).extracting(ApprovalResponse::requestId)
                    .as("o do laço suspendeu às 10h; o do fluxo, às 11h")
                    .containsExactly("req-laco", "req-fluxo");
            ApprovalResponse doLaco = fila.get(0);
            assertThat(doLaco.flowId()).as("a execução do laço").isEqualTo("run-1");
            assertThat(doLaco.stepId()).as("a tool que espera").isEqualTo("reiniciar_servico");
            assertThat(doLaco.proposal()).isEqualTo(Map.of("servico", "traefik"));
            assertThat(doLaco.status()).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("sem o gate do laço configurado, a fila é a de sempre")
        void semLaco() {
            when(stateManager.findByStatus(FlowStatus.AWAITING_APPROVAL))
                    .thenReturn(List.of(fluxoAguardando()));

            List<ApprovalResponse> fila = new ApprovalQueueService(stateManager, flowEngine)
                    .listPending(TENANT);

            assertThat(fila).extracting(ApprovalResponse::requestId).containsExactly("req-fluxo");
        }

        /** O store lista por tenant; "todos" não é pergunta que ele responde. */
        @Test
        @DisplayName("a visão de admin (todos os tenants) não quebra por causa do laço")
        void todosOsTenants() {
            when(stateManager.findByStatus(FlowStatus.AWAITING_APPROVAL))
                    .thenReturn(List.of(fluxoAguardando()));

            assertThat(fila().listPending("all")).extracting(ApprovalResponse::requestId)
                    .containsExactly("req-fluxo");
            verify(retomada, never()).pendentes(anyString());
        }

        /** Uma falha ao ler o store do agente não pode apagar a fila do fluxo. */
        @Test
        @DisplayName("store do laço indisponível: a fila do fluxo continua")
        void storeIndisponivel() {
            when(stateManager.findByStatus(FlowStatus.AWAITING_APPROVAL))
                    .thenReturn(List.of(fluxoAguardando()));
            when(retomada.pendentes(TENANT)).thenThrow(new IllegalStateException("banco fora"));

            assertThat(fila().listPending(TENANT)).extracting(ApprovalResponse::requestId)
                    .containsExactly("req-fluxo");
        }

        @Test
        @DisplayName("o detalhe encontra a pendência do laço pelo requestId")
        void detalhe() {
            when(stateManager.findByStatus(FlowStatus.AWAITING_APPROVAL)).thenReturn(List.of());
            when(retomada.detalhe("req-laco")).thenReturn(Optional.of(pendenciaDoLaco()));

            assertThat(fila().getDetail("req-laco").stepId()).isEqualTo("reiniciar_servico");
        }
    }

    @Nested
    @DisplayName("decisão")
    class Decisao {

        private final McpAgentRunner.Result concluido =
                new McpAgentRunner.Result("pronto", List.of());

        private void pendenciaExiste() {
            when(retomada.detalhe("req-laco")).thenReturn(Optional.of(pendenciaDoLaco()));
            when(retomada.decidir(anyString(), anyBoolean(), any())).thenReturn(
                    new RetomadaDoLaco.Conclusao(mock(McpAgentState.class), concluido, null));
        }

        /** O id diz qual mecanismo responde — o motor de fluxo não é acionado por engano. */
        @Test
        @DisplayName("APPROVED de tool vai para a retomada do laço, não para o motor")
        void aprovado() {
            pendenciaExiste();

            ApprovalResponse resposta = fila().submitDecision("req-laco",
                    new ApprovalSubmitRequest(TENANT, "APPROVED", null, "maria"));

            verify(retomada).decidir("req-laco", true, null);
            verifyNoInteractions(flowEngine);
            assertThat(resposta.status()).isEqualTo("APPROVED");
            assertThat(resposta.stepId()).isEqualTo("reiniciar_servico");
        }

        @Test
        @DisplayName("REJECTED devolve a recusa ao modelo")
        void recusado() {
            pendenciaExiste();

            fila().submitDecision("req-laco",
                    new ApprovalSubmitRequest(TENANT, "REJECTED", null, "maria"));

            verify(retomada).decidir("req-laco", false, null);
        }

        @Test
        @DisplayName("EDITED substitui os argumentos da tool")
        void editado() {
            pendenciaExiste();

            fila().submitDecision("req-laco", new ApprovalSubmitRequest(TENANT, "EDITED",
                    Map.of("servico", "nginx"), "maria"));

            verify(retomada).decidir(eq("req-laco"), eq(true), eq(Map.of("servico", "nginx")));
        }

        /** O payload editado vira argumento de chamada: outra forma seria executada como se fosse. */
        @Test
        @DisplayName("EDITED com payload que não é objeto de argumentos é recusado")
        void editadoInvalido() {
            pendenciaExiste();

            assertThatThrownBy(() -> fila().submitDecision("req-laco",
                    new ApprovalSubmitRequest(TENANT, "EDITED", "reinicia aí", "maria")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("editedPayload");
            verify(retomada, never()).decidir(anyString(), anyBoolean(), any());
        }

        @Test
        @DisplayName("decisão desconhecida continua sendo recusada antes de tudo")
        void decisaoDesconhecida() {
            assertThatThrownBy(() -> fila().submitDecision("req-laco",
                    new ApprovalSubmitRequest(TENANT, "TALVEZ", null, "maria")))
                    .isInstanceOf(IllegalArgumentException.class);
            verifyNoInteractions(retomada);
        }
    }
}
