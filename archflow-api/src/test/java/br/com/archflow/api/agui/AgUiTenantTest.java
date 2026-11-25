package br.com.archflow.api.agui;

import br.com.archflow.api.config.ImpersonationFilter;
import br.com.archflow.langchain4j.provider.LLMConfigResolver;
import br.com.archflow.langchain4j.provider.LLMResolutionRequest;
import br.com.archflow.model.config.ResolvedLLMConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * O endpoint AG-UI resolvia o LLM sem tenant — nem a chave do tenant nem os modelos permitidos dele
 * (RCHFLW-79) valiam. O tenant é o da requisição (atributo do {@link ImpersonationFilter}).
 */
@DisplayName("AG-UI: o tenant da requisição entra na resolução do LLM")
class AgUiTenantTest {

    private final List<LLMResolutionRequest> pedidos = new CopyOnWriteArrayList<>();

    @AfterEach
    void limpa() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("tenant da requisição vai no pedido")
    void tenantDaRequisicaoVaiNoPedido() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(ImpersonationFilter.ATTR_TENANT_ID, "acme");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
        LLMConfigResolver resolver = mock(LLMConfigResolver.class);
        when(resolver.resolveStreamingModel(any())).thenAnswer(inv -> {
            pedidos.add(inv.getArgument(0));
            throw new IllegalStateException("[LLM-MODELO] parou no teste");
        });
        var controller = new AgUiAgentController(resolver,
                new ResolvedLLMConfig("openrouter", "m", 0.0, 256, 30, Map.of()), new ObjectMapper());

        controller.agent(new RunAgentInput(List.of(Map.of("role", "user", "content", "oi")),
                null, null, null, "t1", "r1"));

        assertThat(pedidos).singleElement().extracting(LLMResolutionRequest::tenantId).isEqualTo("acme");
    }
}
