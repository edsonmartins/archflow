package br.com.archflow.api.web.orchestration;

import br.com.archflow.api.config.ImpersonationFilter;
import br.com.archflow.api.config.JwtAuthenticationFilter;
import br.com.archflow.api.orchestration.DynamicWorkflowRequest;
import br.com.archflow.api.orchestration.DynamicWorkflowService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * O {@code tenantId} do corpo do {@code /api/orchestration/run} passou a escolher a chave de LLM e os
 * modelos permitidos do planejador (RCHFLW-79). Com JWT que traz o tenant, o corpo não pode nomear
 * outro — a mesma regra do {@code /api/agents/qp/quote}.
 */
@DisplayName("orquestração: tenant do corpo não troca o tenant do token")
class SpringDynamicWorkflowControllerTenantTest {

    private final DynamicWorkflowService service = mock(DynamicWorkflowService.class);
    private final SpringDynamicWorkflowController controller = new SpringDynamicWorkflowController(service);

    @AfterEach
    void limpa() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static void requisicao(String jwtTenant) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(JwtAuthenticationFilter.ATTR_TENANT, jwtTenant);
        req.setAttribute(ImpersonationFilter.ATTR_TENANT_ID, jwtTenant);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    private static DynamicWorkflowRequest corpo(String tenant) {
        return new DynamicWorkflowRequest("audit", null, null, null, null, null, null, null, tenant);
    }

    @Test
    @DisplayName("JWT tenant_a + corpo tenant_b → 403 e nada roda")
    void corpoComOutroTenantEhRecusado() {
        requisicao("tenant_a");

        var resposta = controller.run(corpo("tenant_b"));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(service, never()).run(any());
    }

    @Test
    @DisplayName("JWT tenant_a + corpo sem tenant → roda como tenant_a")
    void semTenantNoCorpoUsaOToken() {
        requisicao("tenant_a");

        var resposta = controller.run(corpo(null));

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
        ArgumentCaptor<DynamicWorkflowRequest> enviado = ArgumentCaptor.forClass(DynamicWorkflowRequest.class);
        verify(service).run(enviado.capture());
        assertThat(enviado.getValue().tenantId()).isEqualTo("tenant_a");
        assertThat(enviado.getValue().goal()).isEqualTo("audit");
    }
}
