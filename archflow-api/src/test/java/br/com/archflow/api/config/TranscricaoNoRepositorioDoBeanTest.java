package br.com.archflow.api.config;

import br.com.archflow.conversation.ConversationManager;
import br.com.archflow.conversation.domain.InMemoryConversationRepository;
import br.com.archflow.conversation.message.ConversationMessage;
import br.com.archflow.conversation.service.ConversationService;
import br.com.archflow.conversation.state.InMemorySuspendedConversationStore;
import br.com.archflow.model.tenant.DefaultTenant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RCHFLW-181: o bean de {@link ConversationService} grava a transcrição no {@code ConversationRepository} do
 * contexto — o JDBC sob {@code archflow.persistence.jdbc.enabled=true}. Antes o bean era montado só com o
 * manager, as mensagens ficavam num mapa do serviço e as tabelas {@code conversations}/{@code
 * conversation_messages} continuavam com 0 linhas, embora o log anunciasse o histórico durável.
 */
@DisplayName("Bean de conversas grava no repositório do contexto (RCHFLW-181)")
class TranscricaoNoRepositorioDoBeanTest {

    @Test
    void gravaNoRepositorio() {
        InMemoryConversationRepository repositorio = new InMemoryConversationRepository();
        ConversationService service = new ArchflowBeanConfiguration().conversationService(
                new ConversationManager(Duration.ofMinutes(30), new InMemorySuspendedConversationStore()),
                repositorio);

        service.addMessage(ConversationMessage.user("conv-1", "oi"));

        assertThat(repositorio.findByIdAcrossTenants("conv-1")).isPresent();
        assertThat(repositorio.listMessages(DefaultTenant.ID, "conv-1"))
                .extracting(m -> m.content()).containsExactly("oi");
    }
}
