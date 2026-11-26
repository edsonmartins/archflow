package br.com.archflow.api.web.conversation;

import br.com.archflow.agent.persistence.InMemoryFlowRepository;
import br.com.archflow.agent.streaming.ArchflowDomain;
import br.com.archflow.agent.streaming.ArchflowEvent;
import br.com.archflow.agent.streaming.ArchflowEventType;
import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.api.agent.mcp.McpAgentHost;
import br.com.archflow.api.approval.dto.ApprovalSubmitRequest;
import br.com.archflow.api.approval.impl.ApprovalQueueService;
import br.com.archflow.api.audit.AuditTrail;
import br.com.archflow.api.config.JwtAuthenticationFilter;
import br.com.archflow.api.conversation.ConversationReplyService;
import br.com.archflow.api.conversation.ConversationResumeService;
import br.com.archflow.api.conversation.dto.ConversationResponse;
import br.com.archflow.api.conversation.dto.ResumeRequest;
import br.com.archflow.api.conversation.dto.SuspendRequest;
import br.com.archflow.api.conversation.impl.ConversationControllerImpl;
import br.com.archflow.api.flow.FlowEngineFactory;
import br.com.archflow.api.flow.HumanApprovalStep;
import br.com.archflow.api.flow.InMemoryStateManager;
import br.com.archflow.api.flow.SimpleFlow;
import br.com.archflow.api.flow.SimpleStepResult;
import br.com.archflow.api.flow.WorkflowDeserializer;
import br.com.archflow.api.web.workflow.InMemoryWorkflowRuntimeStore;
import br.com.archflow.api.web.workflow.SpringWorkflowCrudController;
import br.com.archflow.conversation.ConversationManager;
import br.com.archflow.conversation.form.FormData;
import br.com.archflow.conversation.form.FormRegistry;
import br.com.archflow.conversation.form.InMemoryFormRegistry;
import br.com.archflow.conversation.message.ConversationMessage;
import br.com.archflow.conversation.service.ConversationService;
import br.com.archflow.conversation.service.DefaultConversationService;
import br.com.archflow.conversation.state.InMemorySuspendedConversationStore;
import br.com.archflow.conversation.state.SuspendedConversation;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.core.StateManager;
import br.com.archflow.langchain4j.provider.LLMConfigResolver;
import br.com.archflow.model.config.ResolvedLLMConfig;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.engine.ExecutionKeys;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowMetadata;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepConnection;
import br.com.archflow.model.flow.StepResult;
import br.com.archflow.model.flow.StepType;
import br.com.archflow.model.tenant.DefaultTenant;
import br.com.archflow.observability.audit.InMemoryAuditRepository;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RCHFLW-67: a conversa suspensa mostra o formulário e, enviado, continua a execução associada.
 *
 * <p>Antes o {@code formId} era descartado ({@code form=null}, o chat não tinha o que mostrar), o
 * evento de suspensão ia para uma lista de assinantes vazia e a retomada só trocava o status para
 * RESUMED e gravava "Form submitted: ...": nenhuma execução avançava e nenhuma resposta chegava.
 *
 * <p>Motor, fila de aprovações, registro de execuções e controllers são os de verdade; a execução é
 * iniciada pelo {@code POST /api/workflows/{id}/execute} e para num nó de aprovação — o único ponto em
 * que uma execução recebe dados de fora. Só o desserializador é trocado, por um que monta
 * {@code gate → responder}.
 */
@DisplayName("Conversa suspensa: formulário e retomada do fluxo (RCHFLW-67)")
class ConversaSuspensaRetomaOFluxoTest {

    /** Registry que guarda, em ordem, cada evento publicado e o canal em que saiu. */
    private static final class RegistryQueAnota extends EventStreamRegistry {
        final BlockingQueue<Map.Entry<String, ArchflowEvent>> eventos = new LinkedBlockingQueue<>();

        @Override
        public int broadcast(String channel, ArchflowEvent event) {
            eventos.add(Map.entry(channel, event));
            return super.broadcast(channel, event);
        }
    }

    /** Store de suspensas que pode falhar ao gravar a retomada — a falha que vem depois do motor. */
    private static final class StoreQueFalhaAoRetomar extends InMemorySuspendedConversationStore {
        volatile boolean falhar;

        @Override
        public void save(SuspendedConversation conversation) {
            if (falhar && conversation.getStatus() == SuspendedConversation.ConversationStatus.RESUMED) {
                throw new IllegalStateException("banco indisponível");
            }
            super.save(conversation);
        }
    }

    private static final String CANAL = DefaultTenant.ID + ":conv-1";

    private StateManager stateManager;
    private FlowEngine engine;
    private InMemoryWorkflowRuntimeStore store;
    private ApprovalQueueService queue;
    private SpringWorkflowCrudController crud;
    private ConversationService service;
    private RegistryQueAnota registry;
    private final StoreQueFalhaAoRetomar suspensas = new StoreQueFalhaAoRetomar();
    private SpringConversationController controller;
    private final AtomicInteger respostasDoFluxo = new AtomicInteger();

    @BeforeEach
    void monta() {
        InMemoryFlowRepository flowRepository = new InMemoryFlowRepository();
        stateManager = new InMemoryStateManager();
        engine = FlowEngineFactory.create(flowRepository, null, null, stateManager);
        store = new InMemoryWorkflowRuntimeStore();
        queue = new ApprovalQueueService(stateManager, engine, Duration.ofMinutes(5), Clock.systemUTC(), null, store);
        WorkflowDeserializer deserializer = json -> fluxoComPortao(String.valueOf(json.get("id")));
        crud = new SpringWorkflowCrudController(store, deserializer, engine, flowRepository,
                new AuditTrail(InMemoryAuditRepository::new), mock(McpAgentHost.class));

        service = new DefaultConversationService(
                new ConversationManager(Duration.ofMinutes(30), suspensas));
        registry = new RegistryQueAnota();
        FormRegistry forms = InMemoryFormRegistry.withTemplates();
        ConversationControllerImpl impl = new ConversationControllerImpl(service, forms);
        ConversationReplyService reply = new ConversationReplyService(service, registry, agenteQueResponde(),
                new ResolvedLLMConfig("test", "m", 0.0, 256, 30, Map.of()));
        ConversationResumeService resume = new ConversationResumeService(impl, service, reply, queue, store,
                registry);
        controller = new SpringConversationController(impl, service, reply, resume, forms);

        // Quem envia o formulário é o do token, como na fila de aprovações.
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/conversations/resume");
        request.setAttribute(JwtAuthenticationFilter.ATTR_USERNAME, "maria.atendente");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void desmonta() {
        RequestContextHolder.resetRequestAttributes();
        registry.shutdown();
    }

    /** Agente da conversa (provider fora do catálogo: o caminho bloqueante). */
    private static LLMConfigResolver agenteQueResponde() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(anyString())).thenReturn("resposta do agente");
        LLMConfigResolver resolver = mock(LLMConfigResolver.class);
        when(resolver.resolve(any())).thenReturn(new ResolvedLLMConfig("test", "m", 0.0, 256, 30, Map.of()));
        when(resolver.resolveModel(any())).thenReturn(model);
        return resolver;
    }

    /** gate (APPROVAL) → responder, que devolve o que o formulário trouxe para a execução. */
    private Flow fluxoComPortao(String flowId) {
        HumanApprovalStep gate = new HumanApprovalStep("gate", List.of(aresta("gate", "responder")),
                Map.of("description", "Dados do cliente"), () -> engine);
        FlowStep responder = new FlowStep() {
            @Override public String getId() { return "responder"; }
            @Override public StepType getType() { return StepType.TOOL; }
            @Override public List<StepConnection> getConnections() { return List.of(); }
            @Override public CompletableFuture<StepResult> execute(ExecutionContext c) {
                respostasDoFluxo.incrementAndGet();
                Object dados = c.get(ExecutionKeys.APPROVAL_PAYLOAD).orElse(Map.of());
                Object assunto = dados instanceof Map<?, ?> m ? m.get("subject") : null;
                return CompletableFuture.completedFuture(
                        SimpleStepResult.ok("responder", "Chamado aberto: " + assunto, 1));
            }
        };
        return new SimpleFlow(flowId, new FlowMetadata("Atendimento", "", "1.0.0", null, null, List.of()),
                List.of(gate, responder));
    }

    private static StepConnection aresta(String from, String to) {
        return new StepConnection() {
            @Override public String getSourceId() { return from; }
            @Override public String getTargetId() { return to; }
            @Override public Optional<String> getCondition() { return Optional.empty(); }
            @Override public boolean isErrorPath() { return false; }
        };
    }

    /** {@code POST /api/workflows/{id}/execute} → [workflowId, executionId], já parada no portão. */
    private String[] executaAtePausar() throws Exception {
        var created = crud.create(new HashMap<>(Map.of("metadata", Map.of("name", "Atendimento"))));
        String workflowId = String.valueOf(created.getBody().get("id"));
        String executionId = String.valueOf(crud.execute(workflowId, Map.of("input", "oi")).getBody()
                .get("executionId"));
        aguardaRegistro(executionId, "PAUSED");
        return new String[] {workflowId, executionId};
    }

    private Map<String, Object> aguardaRegistro(String executionId, String status) throws Exception {
        long prazo = System.currentTimeMillis() + 10_000;
        Map<String, Object> record = store.getExecution(DefaultTenant.ID, executionId);
        while (!status.equals(String.valueOf(record.get("status"))) && System.currentTimeMillis() < prazo) {
            Thread.sleep(20);
            record = store.getExecution(DefaultTenant.ID, executionId);
        }
        assertThat(record.get("status")).as("status do registro de %s", executionId).isEqualTo(status);
        return record;
    }

    /** O próximo evento do canal da conversa com o domínio e o tipo pedidos. */
    private ArchflowEvent aguardaEvento(ArchflowDomain domain, ArchflowEventType type) throws Exception {
        long prazo = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < prazo) {
            Map.Entry<String, ArchflowEvent> e = registry.eventos.poll(100, TimeUnit.MILLISECONDS);
            if (e != null && CANAL.equals(e.getKey()) && e.getValue().getDomain() == domain
                    && e.getValue().getType() == type) {
                return e.getValue();
            }
        }
        throw new AssertionError("nenhum evento " + domain + "/" + type + " no canal " + CANAL);
    }

    @Test
    @DisplayName("suspend resolve o formulário pelo formId, devolve-o, e o chat o recebe pelo GET e pelo stream")
    void suspendResolveOFormulario() throws Exception {
        String[] execucao = executaAtePausar();

        ConversationResponse resposta = controller.suspend(
                new SuspendRequest("conv-1", execucao[0], execucao[1], "support-ticket", 30));

        assertThat(resposta.form()).isInstanceOf(FormData.class);
        assertThat(((FormData) resposta.form()).getId()).isEqualTo("support-ticket");
        var conversa = controller.getConversation("conv-1");
        assertThat(conversa.status()).isEqualTo("SUSPENDED");
        assertThat(conversa.resumeToken()).isEqualTo(resposta.resumeToken());
        assertThat(conversa.formData()).isNotNull();
        assertThat(conversa.formData().fields()).extracting(SpringConversationController.FormFieldDto::id)
                .containsExactly("category", "subject", "message", "email");

        ArchflowEvent form = aguardaEvento(ArchflowDomain.INTERACTION, ArchflowEventType.FORM);
        assertThat(form.getData()).containsEntry("formId", "support-ticket")
                .containsEntry("resumeToken", resposta.resumeToken())
                .containsEntry("title", "Contact Support")
                .containsEntry("fields", conversa.formData().fields());
    }

    @Test
    @DisplayName("o GET e o evento do formulário dizem a execução que o envio continua — o chat sabe que exige "
            + "execution:manage")
    void formularioDizAExecucao() throws Exception {
        // QA2 RCHFLW-136: enviar o formulário ligado a uma execução exige execution:manage. Sem a execução no
        // GET nem no evento, a UI não tinha como desabilitar o envio de quem o servidor recusa.
        String[] execucao = executaAtePausar();

        controller.suspend(new SuspendRequest("conv-1", execucao[0], execucao[1], "support-ticket", 30));

        assertThat(controller.getConversation("conv-1").executionId()).isEqualTo(execucao[1]);
        ArchflowEvent form = aguardaEvento(ArchflowDomain.INTERACTION, ArchflowEventType.FORM);
        assertThat(form.getData()).containsEntry("executionId", execucao[1]);
    }

    @Test
    @DisplayName("formulário sem execução: o GET e o evento não trazem executionId")
    void formularioSemExecucao() throws Exception {
        controller.suspend(new SuspendRequest("conv-1", null, null, "support-ticket", 30));

        assertThat(controller.getConversation("conv-1").executionId()).isNull();
        ArchflowEvent form = aguardaEvento(ArchflowDomain.INTERACTION, ArchflowEventType.FORM);
        assertThat(form.getData()).doesNotContainKey("executionId");
    }

    @Test
    @DisplayName("formId desconhecido é erro claro (400) e não cria conversa")
    void formIdDesconhecido() {
        assertThatThrownBy(() -> controller.suspend(new SuspendRequest("conv-1", null, null, "dados-cliente", 30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dados-cliente")
                .hasMessageContaining("support-ticket");
        assertThatThrownBy(() -> controller.suspend(new SuspendRequest("conv-1", null, null, null, 30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("formId é obrigatório");

        assertThat(service.getMessages("conv-1")).isEmpty();
        assertThat(service.getById("conv-1")).isEmpty();
    }

    @Test
    @DisplayName("enviar o formulário continua a execução uma vez, com os dados, e a resposta do fluxo vem ao chat")
    void retomarContinuaAExecucao() throws Exception {
        String[] execucao = executaAtePausar();
        String token = controller.suspend(
                new SuspendRequest("conv-1", execucao[0], execucao[1], "support-ticket", 30)).resumeToken();
        assertThat(respostasDoFluxo).hasValue(0);

        ConversationResponse retomada = controller.resume(new ResumeRequest(token,
                Map.of("category", "Billing", "subject", "Fatura em dobro")));

        assertThat(retomada.status()).isEqualTo("RESUMED");
        aguardaRegistro(execucao[1], "COMPLETED");
        assertThat(respostasDoFluxo).as("o passo depois do portão rodou uma vez").hasValue(1);
        assertThat(stateManager.loadState(execucao[1]).getVariables())
                .containsEntry(ExecutionKeys.APPROVAL_DECIDED_BY, "maria.atendente");

        aguardaEvento(ArchflowDomain.CHAT, ArchflowEventType.START);
        ArchflowEvent mensagem = aguardaEvento(ArchflowDomain.CHAT, ArchflowEventType.MESSAGE);
        assertThat(mensagem.getData()).containsEntry("role", "assistant")
                .containsEntry("content", "Chamado aberto: Fatura em dobro");
        aguardaEvento(ArchflowDomain.CHAT, ArchflowEventType.END);
        assertThat(service.getMessages("conv-1"))
                .filteredOn(m -> m.role() == ConversationMessage.Role.ASSISTANT)
                .extracting(ConversationMessage::content)
                .containsExactly("Chamado aberto: Fatura em dobro");

        // O mesmo token de novo não continua a execução uma segunda vez.
        assertThatThrownBy(() -> controller.resume(new ResumeRequest(token, Map.of("subject", "de novo"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("não está aguardando o formulário");
        assertThat(respostasDoFluxo).hasValue(1);
    }

    @Test
    @DisplayName("execução que não espera decisão não pode receber o formulário: falha na suspensão")
    void execucaoQueNaoEspera() throws Exception {
        String[] execucao = executaAtePausar();
        String requestId = (String) stateManager.loadState(execucao[1]).getVariables()
                .get(ExecutionKeys.APPROVAL_REQUEST_ID);
        queue.submitDecision(requestId, new ApprovalSubmitRequest("all", "APPROVED", null, "diretor"));
        aguardaRegistro(execucao[1], "COMPLETED");

        assertThatThrownBy(() -> controller.suspend(
                new SuspendRequest("conv-1", execucao[0], execucao[1], "support-ticket", 30)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("não está aguardando uma decisão humana");
        assertThatThrownBy(() -> controller.suspend(
                new SuspendRequest("conv-1", "outro-fluxo", execucao[1], "support-ticket", 30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("é do fluxo " + execucao[0]);
        assertThatThrownBy(() -> controller.suspend(
                new SuspendRequest("conv-1", execucao[0], null, "support-ticket", 30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("executionId é obrigatório");
        assertThat(service.getMessages("conv-1")).as("nenhuma conversa criada").isEmpty();
    }

    @Test
    @DisplayName("sem execução associada, quem responde ao formulário é o agente da conversa")
    void semExecucaoRespondeOAgente() throws Exception {
        String token = controller.suspend(new SuspendRequest("conv-1", null, null, "support-ticket", 30))
                .resumeToken();

        controller.resume(new ResumeRequest(token, Map.of("subject", "Ajuda")));

        ArchflowEvent mensagem = aguardaEvento(ArchflowDomain.CHAT, ArchflowEventType.MESSAGE);
        assertThat(mensagem.getData()).containsEntry("content", "resposta do agente");
    }

    @Test
    @DisplayName("a resposta da retomada usa os papéis do GET /messages (minúsculos): o chat a desenha com ela")
    void retomadaComPapeisDoGet() throws Exception {
        String[] execucao = executaAtePausar();
        String token = controller.suspend(
                new SuspendRequest("conv-1", execucao[0], execucao[1], "support-ticket", 30)).resumeToken();

        ConversationResponse retomada = controller.resume(new ResumeRequest(token, Map.of("subject", "Oi")));

        assertThat(retomada.messages()).extracting(ConversationResponse.MessageResponse::role)
                .contains("system", "user")
                .containsExactlyElementsOf(controller.getMessages("conv-1").stream()
                        .limit(retomada.messages().size())
                        .map(SpringConversationController.MessageDto::role).toList());
    }

    @Test
    @DisplayName("se a conversa falha ao retomar depois que o fluxo continuou, a resposta do fluxo ainda vem ao chat")
    void falhaDaConversaDepoisDoMotor() throws Exception {
        String[] execucao = executaAtePausar();
        String token = controller.suspend(
                new SuspendRequest("conv-1", execucao[0], execucao[1], "support-ticket", 30)).resumeToken();
        suspensas.falhar = true;

        assertThatThrownBy(() -> controller.resume(new ResumeRequest(token, Map.of("subject", "Fatura"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("continuou com os dados do formulário")
                .hasMessageContaining("banco indisponível")
                .hasMessageContaining("não reenvie");

        aguardaRegistro(execucao[1], "COMPLETED");
        ArchflowEvent mensagem = aguardaEvento(ArchflowDomain.CHAT, ArchflowEventType.MESSAGE);
        assertThat(mensagem.getData()).containsEntry("content", "Chamado aberto: Fatura");
        assertThat(respostasDoFluxo).hasValue(1);
    }
}
