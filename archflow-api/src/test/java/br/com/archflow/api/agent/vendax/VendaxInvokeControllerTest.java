package br.com.archflow.api.agent.vendax;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;

/**
 * O endpoint de invoke fica fora do filtro JWT (chamada de máquina), então quem protege é ele
 * mesmo. Estes testes existem para que ninguém o deixe aberto sem perceber.
 */
@DisplayName("VendaxInvokeController — quem pode mandar o agente rodar")
class VendaxInvokeControllerTest {

    private final VendaxAgentDispatcher dispatcher = mock(VendaxAgentDispatcher.class);
    private final VendaxInvokeController controller = new VendaxInvokeController(dispatcher);

    private static VendaxInvoke valid() {
        return new VendaxInvoke("1.0", "tenant-1", "conv-1", "QP", "msg-1", "oi",
                "STRONG", "intencao-pedido", "trace-1", null, "cliente-1", "vendedor-1", null);
    }

    private void keyIs(String key) {
        ReflectionTestUtils.setField(controller, "invokeKey", key);
    }

    @Test
    @DisplayName("sem chave configurada → 503 e nada executa (fail closed)")
    void unconfigured() {
        keyIs("");

        var response = controller.invoke(valid(), "Bearer qualquer");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verify(dispatcher, never()).dispatch(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("chave errada ou ausente → 401")
    void wrongKey() {
        keyIs("segredo");

        assertThat(controller.invoke(valid(), "Bearer outro").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(controller.invoke(valid(), null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(dispatcher, never()).dispatch(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("chave correta → 202 e o agente é acionado")
    void accepted() {
        keyIs("segredo");

        var response = controller.invoke(valid(), "Bearer segredo");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(dispatcher).dispatch(org.mockito.ArgumentMatchers.any());
    }

    /**
     * 202 faz o Core marcar o evento como publicado. Aceitar um envelope incompleto seria perdê-lo:
     * o Core não tentaria de novo.
     */
    @Test
    @DisplayName("envelope incompleto → 400 antes de aceitar")
    void incompleteEnvelope() {
        keyIs("segredo");
        VendaxInvoke semConversa = new VendaxInvoke("1.0", "tenant-1", null, "QP", "msg-1", "oi",
                "STRONG", "r", "t", null, null, null, null);

        assertThat(controller.invoke(semConversa, "Bearer segredo").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        verify(dispatcher, never()).dispatch(org.mockito.ArgumentMatchers.any());
    }

    /** Sanidade do mock não usado: o executor não é exercitado aqui. */
    @Test
    @DisplayName("dispatcher recebe o invoke tal como veio")
    void passesInvokeThrough() {
        keyIs("segredo");
        ExecutorService unused = mock(ExecutorService.class);
        assertThat(unused).isNotNull();

        VendaxInvoke invoke = valid();
        controller.invoke(invoke, "Bearer segredo");

        verify(dispatcher).dispatch(invoke);
    }

    @Test
    @DisplayName("executor saturado → 503 para o Core tentar novamente, nunca 202 falso")
    void saturated() {
        keyIs("segredo");
        doThrow(new java.util.concurrent.RejectedExecutionException()).when(dispatcher)
                .dispatch(org.mockito.ArgumentMatchers.any());

        assertThat(controller.invoke(valid(), "Bearer segredo").getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }
}
