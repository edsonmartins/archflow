package br.com.archflow.api.conversation;

import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.conversation.service.ConversationService;
import br.com.archflow.langchain4j.provider.LLMConfigResolver;
import br.com.archflow.langchain4j.provider.LLMResolutionRequest;
import br.com.archflow.model.config.ResolvedLLMConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A resposta da conversa resolvia o LLM sem tenant: o resolver tratava toda chamada como "nenhum
 * tenant", e nem a chave do tenant nem os modelos permitidos dele (RCHFLW-79) valiam aqui.
 */
@DisplayName("conversa: o tenant da requisição entra na resolução do LLM")
class ConversationReplyTenantTest {

    private final List<LLMResolutionRequest> pedidos = new CopyOnWriteArrayList<>();

    private ConversationReplyService servico() {
        LLMConfigResolver resolver = mock(LLMConfigResolver.class);
        when(resolver.resolveModel(any())).thenAnswer(inv -> {
            pedidos.add(inv.getArgument(0));
            // Para aqui: o que se confere é o pedido de resolução, não a resposta.
            throw new IllegalStateException("[LLM-MODELO] parou no teste");
        });
        return new ConversationReplyService(mock(ConversationService.class), mock(EventStreamRegistry.class),
                resolver, new ResolvedLLMConfig("openrouter", "m", 0.0, 256, 30, Map.of()));
    }

    @Test
    @DisplayName("tenant da conversa vai no pedido")
    void tenantVaiNoPedido() {
        servico().reply("c1", "acme");

        assertThat(pedidos).singleElement().extracting(LLMResolutionRequest::tenantId).isEqualTo("acme");
    }

    @Test
    @DisplayName("o canal de fallback ('default') não vira tenant")
    void fallbackNaoViraTenant() {
        servico().reply("c1", "default");

        assertThat(pedidos).singleElement().extracting(LLMResolutionRequest::tenantId).isNull();
    }
}
