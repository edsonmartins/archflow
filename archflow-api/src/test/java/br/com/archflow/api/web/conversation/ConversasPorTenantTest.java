package br.com.archflow.api.web.conversation;

import br.com.archflow.api.config.ImpersonationFilter;
import br.com.archflow.api.config.JwtAuthenticationFilter;
import br.com.archflow.api.conversation.ConversationController;
import br.com.archflow.api.conversation.ConversationReplyService;
import br.com.archflow.api.conversation.ConversationResumeService;
import br.com.archflow.api.approval.impl.ApprovalQueueService;
import br.com.archflow.api.web.workflow.InMemoryWorkflowRuntimeStore;
import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.conversation.form.FormRegistry;
import br.com.archflow.conversation.form.InMemoryFormRegistry;
import br.com.archflow.api.conversation.dto.ResumeRequest;
import br.com.archflow.api.conversation.dto.SuspendRequest;
import br.com.archflow.api.conversation.impl.ConversationControllerImpl;
import br.com.archflow.conversation.ConversationManager;
import br.com.archflow.conversation.form.FormData;
import br.com.archflow.conversation.message.ConversationMessage;
import br.com.archflow.conversation.service.ConversationService;
import br.com.archflow.conversation.service.DefaultConversationService;
import br.com.archflow.conversation.state.InMemorySuspendedConversationStore;
import br.com.archflow.conversation.state.SuspendedConversation;
import br.com.archflow.model.tenant.DefaultTenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RCHFLW-71: a conversa é do tenant que a criou. Lista, detalhe, mensagens, envio, suspensão,
 * retomada e cancelamento só alcançam as do tenant da requisição; a de outro tenant é 404.
 *
 * <p>O tenant chega pelo {@link ImpersonationFilter} real, como em produção.
 */
@DisplayName("Conversas separadas por tenant (RCHFLW-71)")
class ConversasPorTenantTest {

    private ConversationService service;
    private ConversationReplyService reply;
    private SpringConversationController controller;

    @BeforeEach
    void setUp() {
        service = new DefaultConversationService(
                new ConversationManager(Duration.ofMinutes(30), new InMemorySuspendedConversationStore()));
        reply = mock(ConversationReplyService.class);
        FormRegistry forms = InMemoryFormRegistry.withTemplates();
        ConversationControllerImpl impl = new ConversationControllerImpl(service, forms);
        // A fila simulada: aqui só importa o tenant da conversa, não a execução que o envio continua
        // (essa está em ConversaSuspensaRetomaOFluxoTest).
        ConversationResumeService resume = new ConversationResumeService(impl, service, reply,
                mock(ApprovalQueueService.class), new InMemoryWorkflowRuntimeStore(), mock(EventStreamRegistry.class));
        controller = new SpringConversationController(impl, service, reply, resume, forms);
    }

    @AfterEach
    void limpa() {
        RequestContextHolder.resetRequestAttributes();
    }

    private <T> T comoTenant(String tenant, Callable<T> acao) throws Exception {
        return naRequisicao(false, req -> {
            if (tenant != null) {
                req.addHeader(ImpersonationFilter.HEADER_TENANT, tenant);
            }
        }, acao);
    }

    private void comoTenant(String tenant, Runnable acao) throws Exception {
        comoTenant(tenant, () -> {
            acao.run();
            return null;
        });
    }

    private <T> T superadminPersonificando(String tenant, Callable<T> acao) throws Exception {
        return naRequisicao(true, req -> {
            req.setAttribute(JwtAuthenticationFilter.ATTR_USER_ID, "root");
            req.setAttribute(JwtAuthenticationFilter.ATTR_ROLES, List.of("superadmin"));
            req.addHeader(ImpersonationFilter.HEADER_IMPERSONATE, tenant);
        }, acao);
    }

    private <T> T naRequisicao(boolean impersonation, Consumer<MockHttpServletRequest> prepara,
                               Callable<T> acao) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/conversations");
        prepara.accept(req);
        AtomicReference<T> resultado = new AtomicReference<>();
        AtomicReference<Exception> falha = new AtomicReference<>();
        new ImpersonationFilter(impersonation).doFilter(req, new MockHttpServletResponse(), (rq, rs) -> {
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
            try {
                resultado.set(acao.call());
            } catch (Exception e) {
                falha.set(e);
            } finally {
                RequestContextHolder.resetRequestAttributes();
            }
        });
        if (falha.get() != null) {
            throw falha.get();
        }
        return resultado.get();
    }

    private String criaConversa(String tenant) throws Exception {
        return comoTenant(tenant, () -> controller.create().getBody().get("id"));
    }

    private List<String> idsListados(String tenant) throws Exception {
        return comoTenant(tenant, () -> controller.listConversations(null, null, null, 0, 100))
                .items().stream().map(SpringConversationController.ConversationDto::id).toList();
    }

    @Test
    @DisplayName("a conversa nova grava o tenant de quem a criou e só aparece na lista dele")
    void listaSoDoTenant() throws Exception {
        String doA = criaConversa("tenant-a");
        String doB = criaConversa("tenant-b");

        assertThat(idsListados("tenant-a")).containsExactly(doA);
        assertThat(idsListados("tenant-b")).containsExactly(doB);
        assertThat(idsListados(null)).isEmpty();
        assertThat(comoTenant("tenant-a", () -> controller.getConversation(doA)).tenantId())
                .as("antes vinha \"default\" para toda conversa criada pela UI")
                .isEqualTo("tenant-a");
    }

    @Test
    @DisplayName("no tenant B, detalhe, mensagens, envio e cancelamento da conversa do A são 404")
    void conversaDeOutroTenantEhInexistente() throws Exception {
        String doA = criaConversa("tenant-a");
        comoTenant("tenant-a", () -> controller.sendMessage(doA,
                new SpringConversationController.SendMessageRequest("segredo do A")));

        assertThatThrownBy(() -> comoTenant("tenant-b", () -> controller.getConversation(doA)))
                .isInstanceOf(ConversationController.NotFoundException.class);
        assertThatThrownBy(() -> comoTenant("tenant-b", () -> controller.getMessages(doA)))
                .isInstanceOf(ConversationController.NotFoundException.class);
        assertThatThrownBy(() -> comoTenant("tenant-b", () -> controller.sendMessage(doA,
                new SpringConversationController.SendMessageRequest("intrusão"))))
                .isInstanceOf(ConversationController.NotFoundException.class);
        assertThatThrownBy(() -> comoTenant("tenant-b", () -> controller.cancel(doA)))
                .isInstanceOf(ConversationController.NotFoundException.class);

        verify(reply, never()).replyAsync(eq(doA), eq("tenant-b"));
        assertThat(comoTenant("tenant-a", () -> controller.getMessages(doA)))
                .extracting(SpringConversationController.MessageDto::content)
                .containsExactly("Conversa iniciada", "segredo do A");
    }

    @Test
    @DisplayName("a resposta do agente sai no canal do tenant dono da conversa")
    void respostaNoCanalDoDono() throws Exception {
        String doA = criaConversa("tenant-a");

        comoTenant("tenant-a", () -> controller.sendMessage(doA,
                new SpringConversationController.SendMessageRequest("oi")));

        verify(reply).replyAsync(doA, "tenant-a");
    }

    @Test
    @DisplayName("enviar para um id ainda sem conversa a cria no tenant de quem enviou")
    void envioCriaNoTenantDoRemetente() throws Exception {
        comoTenant("tenant-a", () -> controller.sendMessage("conv-nova",
                new SpringConversationController.SendMessageRequest("primeira")));

        assertThat(idsListados("tenant-a")).containsExactly("conv-nova");
        assertThat(idsListados("tenant-b")).isEmpty();
        assertThatThrownBy(() -> comoTenant("tenant-b", () -> controller.getMessages("conv-nova")))
                .isInstanceOf(ConversationController.NotFoundException.class);
    }

    @Test
    @DisplayName("POST /suspend grava no contexto o tenant de quem suspendeu")
    void suspendGravaOTenantNoContexto() throws Exception {
        // Serviço simulado: aqui só importa o contexto que o controller entrega ao serviço.
        ConversationService simulado = mock(ConversationService.class);
        var impl = new ConversationControllerImpl(simulado, InMemoryFormRegistry.withTemplates());
        when(simulado.suspend(anyString(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(SuspendedConversation.builder().conversationId("conv-form").resumeToken("tok")
                        .expiresAt(Instant.now().plusSeconds(60)).build());

        comoTenant("tenant-a", () -> impl.suspend(
                new SuspendRequest("conv-form", "wf-1", "exec-1", "support-ticket", 10)));

        verify(simulado).suspend(eq("conv-form"), eq("wf-1"), eq("exec-1"), any(), any(),
                eq(Map.of("tenantId", "tenant-a")));
    }

    @Test
    @DisplayName("conversa suspensa é do tenant do contexto; retomar ou suspender de novo pelo B é 404")
    void suspensaPertenceAoTenant() throws Exception {
        // O mesmo contexto que o ConversationControllerImpl.suspend grava (teste acima).
        String token = service.suspend("conv-form", "wf-1", "exec-1",
                FormData.builder().id("f").title("Confirma?")
                        .addField(FormData.FormField.text("ok", "Ok").build()).build(), Duration.ofMinutes(10),
                Map.of("tenantId", "tenant-a")).getResumeToken();

        assertThat(idsListados("tenant-a")).containsExactly("conv-form");
        assertThat(idsListados("tenant-b")).isEmpty();

        assertThatThrownBy(() -> comoTenant("tenant-b",
                () -> controller.resume(new ResumeRequest(token, Map.of("ok", true)))))
                .isInstanceOf(ConversationController.NotFoundException.class);
        assertThatThrownBy(() -> comoTenant("tenant-b", () -> controller.suspend(
                new SuspendRequest("conv-form", "wf-1", "exec-1", "support-ticket", 10))))
                .isInstanceOf(ConversationController.NotFoundException.class);
        assertThatThrownBy(() -> comoTenant("tenant-b", () -> controller.cancel("conv-form")))
                .isInstanceOf(ConversationController.NotFoundException.class);
        assertThat(service.getByToken(token)).as("o B não consumiu nem cancelou o token").isPresent();

        assertThat(comoTenant("tenant-a",
                () -> controller.resume(new ResumeRequest(token, Map.of("ok", true)))).conversationId())
                .isEqualTo("conv-form");
    }

    @Test
    @DisplayName("a conversa criada pelo POST /suspend continua do tenant depois que o estado é removido")
    void suspensaPeloControllerNaoMudaDeTenantAoCancelar() throws Exception {
        // Sem execução: a conversa nasce suspensa só com o formulário.
        comoTenant("tenant-a", () -> controller.suspend(
                new SuspendRequest("conv-x", null, null, "support-ticket", 10)));
        assertThat(service.getById("conv-x")).isPresent();

        // O cancelamento apaga o estado (e o tenant do contexto junto) e deixa uma mensagem de sistema
        // sem metadados; a expiração e a limpeza apagam do mesmo jeito.
        comoTenant("tenant-a", () -> controller.cancel("conv-x"));
        assertThat(service.getById("conv-x")).isEmpty();

        assertThat(idsListados("tenant-a")).containsExactly("conv-x");
        assertThat(idsListados(null)).as("antes caía no tenant padrão").isEmpty();
        assertThatThrownBy(() -> comoTenant(null, () -> controller.getConversation("conv-x")))
                .isInstanceOf(ConversationController.NotFoundException.class);
        assertThatThrownBy(() -> comoTenant(null, () -> controller.sendMessage("conv-x",
                new SpringConversationController.SendMessageRequest("intrusão"))))
                .isInstanceOf(ConversationController.NotFoundException.class);
        assertThat(comoTenant("tenant-a", () -> controller.getConversation("conv-x")).tenantId())
                .isEqualTo("tenant-a");
    }

    @Test
    @DisplayName("a conversa suspensa retomada continua do tenant, com o tenant só no contexto original")
    void retomadaMantemOTenantDoContextoOriginal() throws Exception {
        // Só o contexto traz o tenant: as mensagens que o serviço grava não têm metadados — é o caso de
        // um estado no JDBC cujas mensagens (em memória) não sobreviveram a um reinício.
        String token = service.suspend("conv-form", "wf-1", "exec-1",
                FormData.builder().id("f").title("Confirma?")
                        .addField(FormData.FormField.text("ok", "Ok").build()).build(), Duration.ofMinutes(10),
                Map.of("tenantId", "tenant-a")).getResumeToken();

        comoTenant("tenant-a", () -> controller.resume(new ResumeRequest(token, Map.of("ok", "sim"))));
        assertThat(service.getById("conv-form")).get()
                .extracting(state -> state.getContext().get("tenantId"))
                .as("a retomada move o contexto para originalContext")
                .isNull();

        assertThat(idsListados("tenant-a")).containsExactly("conv-form");
        assertThat(idsListados(null)).as("antes a conversa retomada aparecia no tenant padrão").isEmpty();
        assertThatThrownBy(() -> comoTenant(null, () -> controller.getMessages("conv-form")))
                .isInstanceOf(ConversationController.NotFoundException.class);
        assertThat(comoTenant("tenant-a", () -> controller.getMessages("conv-form")))
                .extracting(SpringConversationController.MessageDto::content)
                .anyMatch(content -> content.startsWith("Form submitted:"));
    }

    @Test
    @DisplayName("conversa sem tenant gravado (anterior à regra) é do tenant padrão")
    void conversaLegadaEhDoTenantPadrao() throws Exception {
        service.addMessage(ConversationMessage.user("conv-legada", "de antes"));

        assertThat(idsListados(null)).containsExactly("conv-legada");
        assertThat(idsListados(DefaultTenant.ID)).containsExactly("conv-legada");
        assertThat(comoTenant(null, () -> controller.getConversation("conv-legada")).tenantId())
                .isEqualTo(DefaultTenant.ID);
        assertThat(idsListados("tenant-a")).isEmpty();
    }

    @Test
    @DisplayName("o superadmin personificando o A cria e vê as conversas do A, e não as vê personificando o B")
    void superadminPersonificando() throws Exception {
        String criada = superadminPersonificando("tenant-a", () -> controller.create().getBody().get("id"));

        assertThat(idsListados("tenant-a")).containsExactly(criada);
        assertThat(superadminPersonificando("tenant-a", () -> controller.getConversation(criada)).tenantId())
                .isEqualTo("tenant-a");
        assertThatThrownBy(() -> superadminPersonificando("tenant-b", () -> controller.getConversation(criada)))
                .isInstanceOf(ConversationController.NotFoundException.class);
        verify(reply, never()).replyAsync(anyString(), anyString());
    }
}
