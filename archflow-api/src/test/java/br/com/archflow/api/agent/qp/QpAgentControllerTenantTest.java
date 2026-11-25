package br.com.archflow.api.agent.qp;

import br.com.archflow.api.config.ImpersonationFilter;
import br.com.archflow.api.config.JwtAuthenticationFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * O {@code tenantId} do corpo do {@code /api/agents/qp/quote} escolhe a chave de LLM (a do tenant
 * vem antes da da plataforma). Com JWT que traz o tenant, o corpo não pode nomear outro.
 */
@DisplayName("QpAgentController: tenant do corpo não troca o tenant do token")
class QpAgentControllerTenantTest {

    private final QpAgentService service = mock(QpAgentService.class);
    private final QpAgentController controller = new QpAgentController(service);

    @AfterEach
    void limpa() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static void requisicao(String jwtTenant) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (jwtTenant != null) {
            req.setAttribute(JwtAuthenticationFilter.ATTR_TENANT, jwtTenant);
            req.setAttribute(ImpersonationFilter.ATTR_TENANT_ID, jwtTenant);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    private static QpAgentService.QpRequest corpo(String tenant) {
        return new QpAgentService.QpRequest(tenant, "c1", "v1", "TEXTO_CLIENTE", "2 caixas", List.of());
    }

    @Test
    @DisplayName("JWT tenant_a + corpo tenant_b → 403 e o agente não roda")
    void corpoComOutroTenantEhRecusado() {
        requisicao("tenant_a");

        var resposta = controller.quote(corpo("tenant_b"));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(service, never()).quote(any(QpAgentService.QpRequest.class));
    }

    @Test
    @DisplayName("JWT tenant_a + corpo tenant_a → roda como tenant_a")
    void corpoComOMesmoTenantPassa() {
        requisicao("tenant_a");
        when(service.quote(any(QpAgentService.QpRequest.class))).thenReturn(null);

        var resposta = controller.quote(corpo("tenant_a"));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
        ArgumentCaptor<QpAgentService.QpRequest> enviado = ArgumentCaptor.forClass(QpAgentService.QpRequest.class);
        verify(service).quote(enviado.capture());
        assertThat(enviado.getValue().tenantId()).isEqualTo("tenant_a");
    }

    @Test
    @DisplayName("JWT tenant_a + corpo sem tenant → usa o do token")
    void semTenantNoCorpoUsaOToken() {
        requisicao("tenant_a");

        controller.quote(corpo(null));

        ArgumentCaptor<QpAgentService.QpRequest> enviado = ArgumentCaptor.forClass(QpAgentService.QpRequest.class);
        verify(service).quote(enviado.capture());
        assertThat(enviado.getValue().tenantId()).isEqualTo("tenant_a");
    }

    @Test
    @DisplayName("sem tenant no token (auth desligada / chamada de serviço) → o do corpo, como antes")
    void semTenantNoTokenAceitaOCorpo() {
        requisicao(null);

        controller.quote(corpo("acme"));

        ArgumentCaptor<QpAgentService.QpRequest> enviado = ArgumentCaptor.forClass(QpAgentService.QpRequest.class);
        verify(service).quote(enviado.capture());
        assertThat(enviado.getValue().tenantId()).isEqualTo("acme");
    }
}
