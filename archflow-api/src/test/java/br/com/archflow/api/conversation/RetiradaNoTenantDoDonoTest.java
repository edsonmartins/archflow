package br.com.archflow.api.conversation;

import br.com.archflow.agent.streaming.ArchflowDomain;
import br.com.archflow.agent.streaming.ArchflowEvent;
import br.com.archflow.agent.streaming.ArchflowEventType;
import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.api.approval.impl.ApprovalQueueService;
import br.com.archflow.api.conversation.impl.ConversationControllerImpl;
import br.com.archflow.api.web.workflow.InMemoryWorkflowRuntimeStore;
import br.com.archflow.conversation.ConversationManager;
import br.com.archflow.conversation.domain.Conversation;
import br.com.archflow.conversation.form.InMemoryFormRegistry;
import br.com.archflow.conversation.message.ConversationMessage;
import br.com.archflow.conversation.service.ConversationService;
import br.com.archflow.conversation.service.DefaultConversationService;
import br.com.archflow.conversation.state.InMemorySuspendedConversationStore;
import br.com.archflow.langchain4j.provider.LLMConfigResolver;
import br.com.archflow.model.config.ResolvedLLMConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * RCHFLW-194 com RCHFLW-181: a recusa de uma execução retira o formulário da conversa que a esperava.
 * Uma conversa suspensa antes da transcrição durável não tem registro — o tenant dela só está no contexto
 * da suspensão. A retirada lia o tenant antes e apagava a suspensão antes de gravar o aviso: o registro
 * nascia no tenant padrão, o dono passava a receber 404, e o {@code interaction/cancel} ia para o canal
 * {@code __default__:<conversa>}, que o chat aberto do dono não escuta.
 */
@DisplayName("Formulário retirado no tenant do dono de uma conversa só suspensa")
class RetiradaNoTenantDoDonoTest {

    /** Registry que guarda o canal de cada evento publicado. */
    private static final class RegistryQueAnota extends EventStreamRegistry {
        final List<Map.Entry<String, ArchflowEvent>> eventos = new CopyOnWriteArrayList<>();

        @Override
        public int broadcast(String channel, ArchflowEvent event) {
            eventos.add(Map.entry(channel, event));
            return super.broadcast(channel, event);
        }
    }

    private RegistryQueAnota registry;
    private ConversationManager manager;
    private ConversationService service;
    private ConversationResumeService resume;

    @BeforeEach
    void monta() {
        registry = new RegistryQueAnota();
        manager = new ConversationManager(Duration.ofMinutes(30), new InMemorySuspendedConversationStore());
        service = new DefaultConversationService(manager);
        ResolvedLLMConfig config = new ResolvedLLMConfig("test", "m", 0.0, 256, 30, Map.of());
        ConversationReplyService reply = new ConversationReplyService(service, registry,
                mock(LLMConfigResolver.class), config);
        resume = new ConversationResumeService(new ConversationControllerImpl(service,
                InMemoryFormRegistry.withTemplates()), service, reply, mock(ApprovalQueueService.class),
                new InMemoryWorkflowRuntimeStore(), registry);
    }

    @AfterEach
    void desmonta() {
        registry.shutdown();
    }

    @Test
    @DisplayName("recusada a execução, o registro e o aviso ficam no tenant do contexto, e os eventos saem no "
            + "canal dele")
    void recusaNoTenantDoDono() {
        // Direto pelo manager, sem mensagem: o estado de uma suspensão gravada antes da transcrição durável.
        manager.suspend("conv-legada", "wf-1", "exec-1",
                InMemoryFormRegistry.withTemplates().require("support-ticket"), Duration.ofMinutes(30),
                Map.of(DefaultConversationService.OWNER_METADATA, "tenant-b"));

        resume.encerrarEsperas("exec-1", false);

        assertThat(service.getById("conv-legada")).isEmpty();
        assertThat(service.getRecord("conv-legada")).map(Conversation::tenantId).contains("tenant-b");
        assertThat(service.listConversationIds("tenant-b")).contains("conv-legada");
        assertThat(service.getMessages("conv-legada")).extracting(ConversationMessage::content)
                .singleElement().asString().contains("A aprovação da execução exec-1 foi recusada");
        assertThat(registry.eventos).extracting(Map.Entry::getKey).isNotEmpty()
                .allMatch("tenant-b:conv-legada"::equals);
        assertThat(registry.eventos).extracting(Map.Entry::getValue)
                .anyMatch(e -> e.getDomain() == ArchflowDomain.INTERACTION && e.getType() == ArchflowEventType.CANCEL)
                .anyMatch(e -> e.getDomain() == ArchflowDomain.CHAT && e.getType() == ArchflowEventType.MESSAGE);
    }
}
