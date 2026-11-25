package br.com.archflow.api.conversation;

import br.com.archflow.agent.streaming.ArchflowEvent;
import br.com.archflow.agent.streaming.ArchflowEventType;
import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.conversation.service.ConversationService;
import br.com.archflow.langchain4j.provider.LLMConfigResolver;
import br.com.archflow.model.config.ResolvedLLMConfig;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RCHFLW-49, lado de quem publica: a resposta do chat sai só no canal do tenant de quem mandou a
 * mensagem. O fallback {@code "default:<conversa>"} — o tenant que a UI inventava — entregava a
 * resposta de qualquer tenant a quem assinasse esse canal com o id da conversa.
 */
@DisplayName("Resposta do chat só no canal do tenant")
class ConversationReplyCanalDoTenantTest {

    /** Registry que anota em que canal cada evento foi publicado. */
    private static final class RegistryQueAnota extends EventStreamRegistry {
        final List<String> canais = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch fim = new CountDownLatch(1);

        @Override
        public int broadcast(String executionId, ArchflowEvent event) {
            canais.add(executionId);
            if (event.getType() == ArchflowEventType.END) {
                fim.countDown();
            }
            return super.broadcast(executionId, event);
        }
    }

    private RegistryQueAnota registry;
    private ConversationReplyService service;

    @BeforeEach
    void monta() {
        registry = new RegistryQueAnota();
        ChatModel model = mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn("resposta");
        LLMConfigResolver resolver = mock(LLMConfigResolver.class);
        ResolvedLLMConfig plataforma = new ResolvedLLMConfig("test", "m", 0.0, 256, 30, Map.of());
        // Provider fora do catálogo: o caminho bloqueante, que basta para conferir o canal.
        when(resolver.resolve(any())).thenReturn(plataforma);
        when(resolver.resolveModel(any())).thenReturn(model);
        service = new ConversationReplyService(mock(ConversationService.class), registry, resolver, plataforma);
    }

    @AfterEach
    void desmonta() {
        registry.shutdown();
    }

    @Test
    @DisplayName("ninguém ouvindo no canal do tenant: não recorre ao canal \"default\"")
    void semFallbackParaODefault() {
        service.reply("conv-1", "tenant-a");

        assertThat(registry.canais).isNotEmpty().containsOnly("tenant-a:conv-1");
    }

    /**
     * Mensagem sem tenant (auth desligada, sem header) publica no tenant que o
     * {@code GET /api/stream/{sessionId}} resolve para a mesma requisição — o canônico.
     */
    @Test
    @DisplayName("mensagem sem tenant publica em __default__, não em \"default\"")
    void semTenantVaiParaOTenantCanonico() throws Exception {
        service.replyAsync("conv-1", null);

        assertThat(registry.fim.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(registry.canais).containsOnly("__default__:conv-1");
    }
}
