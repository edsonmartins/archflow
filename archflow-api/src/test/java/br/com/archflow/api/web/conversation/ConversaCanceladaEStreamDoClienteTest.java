package br.com.archflow.api.web.conversation;

import br.com.archflow.agent.streaming.ArchflowDomain;
import br.com.archflow.agent.streaming.ArchflowEvent;
import br.com.archflow.agent.streaming.ArchflowEventType;
import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.api.approval.impl.ApprovalQueueService;
import br.com.archflow.api.config.GlobalExceptionHandler;
import br.com.archflow.api.conversation.ConversationReplyService;
import br.com.archflow.api.conversation.ConversationResumeService;
import br.com.archflow.api.conversation.dto.SuspendRequest;
import br.com.archflow.api.conversation.impl.ConversationControllerImpl;
import br.com.archflow.api.web.workflow.InMemoryWorkflowRuntimeStore;
import br.com.archflow.conversation.ConversationManager;
import br.com.archflow.conversation.form.FormRegistry;
import br.com.archflow.conversation.form.InMemoryFormRegistry;
import br.com.archflow.conversation.service.ConversationService;
import br.com.archflow.conversation.service.DefaultConversationService;
import br.com.archflow.conversation.state.InMemorySuspendedConversationStore;
import br.com.archflow.langchain4j.provider.LLMConfigResolver;
import br.com.archflow.model.config.ResolvedLLMConfig;
import br.com.archflow.model.tenant.DefaultTenant;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * RCHFLW-194, itens 2, 9, 10, 11 e 12 — as conversas pela API, com o serviço, o controller e o stream de
 * verdade:
 *
 * <ul>
 *   <li>cancelada, a conversa é CANCELLED (filtrável) e não recebe novos turnos; o {@code DELETE} de uma
 *   conversa ativa, nunca suspensa, não diz mais "não encontrada" (antes: ACTIVE, aceitava mensagem e o
 *   agente respondia; o delete da ativa era 404);</li>
 *   <li>o {@code DELETE} responde 204 — era um 200 de corpo vazio, que o chat tentava ler como JSON;</li>
 *   <li>a mensagem do cliente vai ao stream da conversa, com o id gravado (o segundo visualizador só via
 *   as respostas do assistente);</li>
 *   <li>o "Criada em" não vira a hora da suspensão;</li>
 *   <li>os textos de sistema e o 404 saem em português.</li>
 * </ul>
 */
@DisplayName("Conversa cancelada, criação e stream das mensagens do cliente (RCHFLW-194)")
class ConversaCanceladaEStreamDoClienteTest {

    /** Registry que guarda, em ordem, cada evento publicado e o canal em que saiu. */
    private static final class RegistryQueAnota extends EventStreamRegistry {
        final BlockingQueue<Map.Entry<String, ArchflowEvent>> eventos = new LinkedBlockingQueue<>();

        @Override
        public int broadcast(String channel, ArchflowEvent event) {
            eventos.add(Map.entry(channel, event));
            return super.broadcast(channel, event);
        }
    }

    private ConversationService service;
    private SpringConversationController controller;
    private RegistryQueAnota registry;
    private MockMvc mvc;

    @BeforeEach
    void monta() {
        service = new DefaultConversationService(
                new ConversationManager(Duration.ofMinutes(30), new InMemorySuspendedConversationStore()));
        registry = new RegistryQueAnota();
        FormRegistry forms = InMemoryFormRegistry.withTemplates();
        ConversationControllerImpl impl = new ConversationControllerImpl(service, forms);
        ChatModel model = mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn("resposta do agente");
        LLMConfigResolver resolver = mock(LLMConfigResolver.class);
        when(resolver.resolve(any())).thenReturn(new ResolvedLLMConfig("test", "m", 0.0, 256, 30, Map.of()));
        when(resolver.resolveModel(any())).thenReturn(model);
        ConversationReplyService reply = new ConversationReplyService(service, registry, resolver,
                new ResolvedLLMConfig("test", "m", 0.0, 256, 30, Map.of()));
        ConversationResumeService resume = new ConversationResumeService(impl, service, reply,
                mock(ApprovalQueueService.class), new InMemoryWorkflowRuntimeStore(), registry);
        controller = new SpringConversationController(impl, service, reply, resume, forms);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void desmonta() {
        registry.shutdown();
    }

    private String criaConversa() {
        return controller.create().getBody().get("id");
    }

    private ArchflowEvent proximoEvento(String conversationId, ArchflowDomain domain, ArchflowEventType type)
            throws InterruptedException {
        long prazo = System.currentTimeMillis() + 5_000;
        String canal = DefaultTenant.ID + ":" + conversationId;
        while (System.currentTimeMillis() < prazo) {
            Map.Entry<String, ArchflowEvent> e = registry.eventos.poll(100, TimeUnit.MILLISECONDS);
            if (e != null && canal.equals(e.getKey()) && e.getValue().getDomain() == domain
                    && e.getValue().getType() == type) {
                return e.getValue();
            }
        }
        throw new AssertionError("nenhum evento " + domain + "/" + type + " no canal " + canal);
    }

    @Test
    @DisplayName("DELETE de uma conversa ativa, nunca suspensa: 204, CANCELLED, filtrável e sem novos turnos")
    void cancelaAAtiva() throws Exception {
        String id = criaConversa();
        controller.sendMessage(id, new SpringConversationController.SendMessageRequest("oi"));

        MvcResult apagou = mvc.perform(delete("/api/conversations/" + id)).andReturn();

        assertThat(apagou.getResponse().getStatus()).isEqualTo(204);
        assertThat(apagou.getResponse().getContentAsString()).isEmpty();
        assertThat(controller.getConversation(id).status()).isEqualTo("CANCELLED");
        assertThat(controller.listConversations("CANCELLED", null, null, 0, 20).items())
                .extracting(SpringConversationController.ConversationDto::id).containsExactly(id);
        assertThat(controller.listConversations("ACTIVE", null, null, 0, 20).total()).isZero();

        MvcResult envio = mvc.perform(post("/api/conversations/" + id + "/messages")
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"ainda está aí?\"}")).andReturn();
        assertThat(envio.getResponse().getStatus()).isEqualTo(409);
        assertThat(service.getMessages(id)).extracting(m -> m.content()).doesNotContain("ainda está aí?");
        assertThatThrownBy(() -> controller.suspend(new SuspendRequest(id, null, null, "support-ticket", 30)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("cancelar a suspensa: CANCELLED (não ACTIVE), sem formulário, e o chat aberto recebe "
            + "interaction/cancel")
    void cancelaASuspensa() throws Exception {
        String id = "conv-suspensa";
        controller.suspend(new SuspendRequest(id, null, null, "support-ticket", 30));

        mvc.perform(delete("/api/conversations/" + id)).andReturn();

        var conversa = controller.getConversation(id);
        assertThat(conversa.status()).isEqualTo("CANCELLED");
        assertThat(conversa.formData()).isNull();
        assertThat(conversa.resumeToken()).isNull();
        assertThat(conversa.lastMessage()).isEqualTo("Conversa cancelada");
        assertThat(proximoEvento(id, ArchflowDomain.INTERACTION, ArchflowEventType.CANCEL).getData())
                .containsEntry("conversationId", id);
    }

    @Test
    @DisplayName("a mensagem do cliente vai ao stream (chat/message, user, com o id gravado) antes da resposta")
    void mensagemDoClienteNoStream() throws Exception {
        String id = criaConversa();

        var enviada = controller.sendMessage(id,
                new SpringConversationController.SendMessageRequest("Mensagem da aba A: já enviei as fotos"));

        ArchflowEvent doCliente = proximoEvento(id, ArchflowDomain.CHAT, ArchflowEventType.MESSAGE);
        assertThat(doCliente.getData())
                .containsEntry("role", "user")
                .containsEntry("messageId", enviada.messageId())
                .containsEntry("content", "Mensagem da aba A: já enviei as fotos");
        ArchflowEvent resposta = proximoEvento(id, ArchflowDomain.CHAT, ArchflowEventType.MESSAGE);
        assertThat(resposta.getData()).containsEntry("role", "assistant");
    }

    @Test
    @DisplayName("o 'Criada em' continua o da criação depois da suspensão")
    void criacaoNaoMudaNaSuspensao() throws Exception {
        String id = criaConversa();
        var criada = controller.getConversation(id).createdAt();
        Thread.sleep(10);

        controller.suspend(new SuspendRequest(id, null, null, "support-ticket", 30));

        assertThat(controller.getConversation(id).createdAt()).isEqualTo(criada);
    }

    @Test
    @DisplayName("textos de sistema e o 404 em português")
    void textosEmPortugues() throws Exception {
        String id = "conv-pt";
        controller.suspend(new SuspendRequest(id, null, null, "support-ticket", 30));

        assertThat(service.getMessages(id)).extracting(m -> m.content())
                .contains("Conversa suspensa: aguardando o preenchimento do formulário");
        MvcResult inexistente = mvc.perform(get("/api/conversations/nao-existe")).andReturn();
        assertThat(inexistente.getResponse().getStatus()).isEqualTo(404);
        assertThat(inexistente.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("Conversa não encontrada: nao-existe");
        assertThat(List.of(controller.getConversation(id).status())).containsExactly("SUSPENDED");
    }

    @Test
    @DisplayName("cancelar publica o aviso gravado no stream (chat/message de sistema, com o id gravado): o "
            + "segundo visualizador vê que a conversa acabou")
    void cancelarPublicaOAviso() throws Exception {
        String id = "conv-aviso";
        controller.suspend(new SuspendRequest(id, null, null, "support-ticket", 30));

        mvc.perform(delete("/api/conversations/" + id)).andReturn();

        var gravado = service.getMessages(id).get(service.getMessages(id).size() - 1);
        assertThat(gravado.content()).isEqualTo("Conversa cancelada");
        ArchflowEvent aviso = proximoEvento(id, ArchflowDomain.CHAT, ArchflowEventType.MESSAGE);
        assertThat(aviso.getData())
                .containsEntry("role", "system")
                .containsEntry("content", "Conversa cancelada")
                .containsEntry("messageId", gravado.id());
        proximoEvento(id, ArchflowDomain.CHAT, ArchflowEventType.END);
    }

    @Test
    @DisplayName("id de conversa com mais de 64 caracteres é 400 e não grava nada — o limite das colunas, "
            + "também em memória")
    void idMaiorQueAColuna() throws Exception {
        String longo = "c".repeat(65);

        MvcResult envio = mvc.perform(post("/api/conversations/" + longo + "/messages")
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"oi\"}")).andReturn();
        MvcResult suspensao = mvc.perform(post("/api/conversations/suspend")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"conversationId\":\"" + longo + "\",\"formId\":\"support-ticket\"}")).andReturn();

        assertThat(envio.getResponse().getStatus()).isEqualTo(400);
        assertThat(envio.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("máximo é 64");
        assertThat(suspensao.getResponse().getStatus()).isEqualTo(400);
        assertThat(service.getRecord(longo)).isEmpty();
        assertThat(service.getById(longo)).isEmpty();
        String limite = "c".repeat(64);
        assertThat(mvc.perform(post("/api/conversations/" + limite + "/messages")
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"oi\"}")).andReturn()
                .getResponse().getStatus()).isEqualTo(200);
    }
}
