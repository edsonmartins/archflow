package br.com.archflow.api.conversation;

import br.com.archflow.agent.streaming.ArchflowDomain;
import br.com.archflow.agent.streaming.ArchflowEvent;
import br.com.archflow.agent.streaming.ArchflowEventType;
import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.api.approval.impl.ApprovalQueueService;
import br.com.archflow.api.conversation.dto.ConversationResponse;
import br.com.archflow.api.conversation.dto.ResumeRequest;
import br.com.archflow.api.web.workflow.WorkflowRuntimeStore;
import br.com.archflow.conversation.message.ConversationMessage;
import br.com.archflow.conversation.service.ConversationService;
import br.com.archflow.conversation.state.SuspendedConversation;
import br.com.archflow.model.error.ExecutionError;
import br.com.archflow.model.enums.ExecutionStatus;
import br.com.archflow.model.flow.FlowResult;
import br.com.archflow.model.tenant.DefaultTenant;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

/**
 * Liga a conversa suspensa à execução que ela espera: valida a execução na suspensão, publica o
 * formulário no stream do chat e, no envio, continua a execução com os dados e entrega a resposta.
 *
 * <p>Antes a retomada só trocava o status para RESUMED e gravava "Form submitted: ...": nenhum fluxo
 * continuava, nenhuma resposta era gerada, e o evento de suspensão ia para uma lista de assinantes
 * vazia, então o chat também nunca recebia o formulário pelo stream (RCHFLW-67).
 *
 * <p>Com {@code executionId}, a execução tem de estar parada num nó de aprovação: é o único ponto em
 * que o motor recebe dados de fora e continua (o envio aprova o portão com os dados do formulário,
 * pelo mesmo caminho da fila de aprovações). A resposta é a saída do fluxo. Sem {@code executionId},
 * não há fluxo a continuar e quem responde é o agente da conversa ({@link ConversationReplyService}),
 * com o formulário enviado no histórico.
 *
 * <p>Tudo pelo tenant da requisição, resolvido no servidor — nunca um tenant do corpo.
 */
@Service
public class ConversationResumeService {

    private static final Logger log = LoggerFactory.getLogger(ConversationResumeService.class);

    private final ConversationController conversations;
    private final ConversationService conversationService;
    private final ConversationReplyService replies;
    private final ApprovalQueueService approvals;
    private final WorkflowRuntimeStore executions;
    private final EventStreamRegistry registry;
    private final ObjectMapper json = new ObjectMapper();

    public ConversationResumeService(ConversationController conversations,
                                     ConversationService conversationService,
                                     ConversationReplyService replies,
                                     ApprovalQueueService approvals,
                                     WorkflowRuntimeStore executions,
                                     EventStreamRegistry registry) {
        this.conversations = Objects.requireNonNull(conversations, "conversations");
        this.conversationService = Objects.requireNonNull(conversationService, "conversationService");
        this.replies = Objects.requireNonNull(replies, "replies");
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        this.executions = Objects.requireNonNull(executions, "executions");
        this.registry = Objects.requireNonNull(registry, "registry");
        // A recusa e o prazo chegam pela fila, não pela conversa: é dela que vem o aviso para tirar o
        // formulário da conversa que esperava essa execução (RCHFLW-194).
        approvals.aoEncerrarSemAprovacao(this::encerrarEsperas);
    }

    /**
     * Status do registro de uma execução que não volta mais a esperar uma decisão: o envio do formulário
     * não tem mais o que continuar.
     */
    private static final Set<String> TERMINAL = Set.of("COMPLETED", "FAILED", "STOPPED", "CANCELLED");

    /**
     * A execução acabou num portão sem aprovação: cada conversa que esperava o formulário dela perde o
     * formulário e recebe o aviso. Antes ela ficava SUSPENDED, e cada envio mostrava o 409 "não está
     * aguardando uma decisão humana" e devolvia o formulário — o chat travava (RCHFLW-194).
     */
    void encerrarEsperas(String executionId, boolean porPrazo) {
        String motivo = porPrazo
                ? "A aprovação da execução " + executionId + " expirou sem decisão"
                : "A aprovação da execução " + executionId + " foi recusada";
        for (SuspendedConversation waiting : conversationService.getActiveConversations()) {
            if (executionId.equals(waiting.getWorkflowExecutionId())) {
                retirarFormulario(waiting.getConversationId(), motivo);
            }
        }
    }

    /**
     * Tira o formulário da conversa e avisa quem a acompanha: a fala do sistema no histórico e no stream,
     * e o {@code interaction/cancel} que faz o chat aberto retirar o formulário. Nada acontece se a
     * conversa já não esperava (retomada ou cancelada no meio).
     *
     * <p>O tenant é lido do registro depois da retirada, não antes: uma conversa que só existia suspensa
     * (gravada antes da transcrição durável) não tinha registro, e lido antes ele caía no tenant padrão —
     * o aviso ia para o canal errado e o dono perdia a conversa. A retirada cria o registro com o tenant do
     * contexto da suspensão antes de apagá-la.</p>
     */
    private void retirarFormulario(String conversationId, String motivo) {
        if (conversationService.withdrawForm(conversationId).isEmpty()) {
            return;
        }
        String tenantId = DefaultTenant.orDefault(conversationService.getRecord(conversationId)
                .map(br.com.archflow.conversation.domain.Conversation::tenantId).orElse(null));
        replies.publishReply(conversationId, tenantId, ConversationMessage.system(conversationId,
                motivo + ": o formulário foi retirado e esta conversa não continua mais essa execução."));
        announceFormWithdrawn(tenantId, conversationId);
        log.info("Formulário da conversa {} retirado: {}", conversationId, motivo);
    }

    /**
     * Avisa o chat aberto ({@code interaction/cancel}) de que a conversa não espera mais o formulário —
     * cancelada ou com o formulário retirado. Sem isto um segundo visualizador seguia com o formulário na
     * tela e o campo livre travado.
     */
    public void announceFormWithdrawn(String tenantId, String conversationId) {
        ArchflowEvent event = ArchflowEvent.builder()
                .domain(ArchflowDomain.INTERACTION)
                .type(ArchflowEventType.CANCEL)
                .tenantId(tenantId)
                .correlationId(conversationId)
                .executionId(tenantId + ":" + conversationId)
                .data(Map.of("conversationId", conversationId))
                .build();
        registry.broadcast(tenantId + ":" + conversationId, event);
    }

    /**
     * Confere, antes de suspender, a execução que o envio do formulário vai continuar. Falhar aqui, e
     * não no envio, evita uma conversa travada num formulário cujo envio não teria o que retomar.
     *
     * @throws IllegalArgumentException execução ausente no tenant, de outro fluxo, ou {@code workflowId}
     *                                  sem {@code executionId}
     * @throws IllegalStateException    execução que não espera uma decisão humana
     */
    public void validateTarget(String tenantId, String workflowId, String executionId) {
        if (isBlank(executionId)) {
            if (!isBlank(workflowId)) {
                throw new IllegalArgumentException("executionId é obrigatório quando workflowId é informado: "
                        + "o envio do formulário continua uma execução parada num nó de aprovação do fluxo "
                        + workflowId + ", e não inicia uma nova");
            }
            return;
        }
        Map<String, Object> record = executions.getExecution(tenantId, executionId);
        if (record == null) {
            throw new IllegalArgumentException("Execução não encontrada neste tenant: " + executionId);
        }
        String executionWorkflow = record.get("workflowId") == null ? null : record.get("workflowId").toString();
        if (!isBlank(workflowId) && !workflowId.equals(executionWorkflow)) {
            throw new IllegalArgumentException("A execução " + executionId + " é do fluxo " + executionWorkflow
                    + ", não de " + workflowId);
        }
        approvals.requireAwaitingInput(tenantId, executionId);
    }

    /**
     * Publica o formulário no canal da conversa ({@code interaction/form}): o chat aberto passa a
     * mostrá-lo sem recarregar e bloqueia o envio de mensagens livres enquanto a conversa espera.
     *
     * <p>{@code form} vem com as chaves do evento de formulário do protocolo ({@code formId},
     * {@code title}, {@code description}, {@code fields}, como em {@code InteractionEvent.form}), e os
     * campos no formato do {@code formData} do {@code GET /api/conversations/{id}} — o chat monta o
     * mesmo formulário pelos dois caminhos.</p>
     */
    public void announceForm(String tenantId, String conversationId, String resumeToken,
                             Map<String, Object> form) {
        Map<String, Object> data = new LinkedHashMap<>(form);
        data.put("conversationId", conversationId);
        data.put("resumeToken", resumeToken);
        ArchflowEvent event = ArchflowEvent.builder()
                .domain(ArchflowDomain.INTERACTION)
                .type(ArchflowEventType.FORM)
                .tenantId(tenantId)
                .correlationId(conversationId)
                .executionId(tenantId + ":" + conversationId)
                .data(data)
                .build();
        registry.broadcast(tenantId + ":" + conversationId, event);
    }

    /**
     * Envia o formulário: continua a execução (uma vez) e só então marca a conversa como retomada.
     *
     * <p>A ordem importa. O motor consome o pedido de aprovação sob lock, então um segundo envio
     * concorrente é recusado lá; e uma recusa do motor (execução já decidida pela fila, prazo vencido)
     * deixa a conversa esperando, com o erro para quem enviou, em vez de RESUMED sem nada rodando. O
     * {@code synchronized} fecha a janela entre conferir que a conversa espera e retomá-la. O inverso —
     * o motor aceita e a conversa falha ao ser retomada (expirou no meio, o store falhou) — não tem
     * como desfazer o fluxo: a resposta dele ainda é entregue ao chat e o erro diz que não se reenvie.
     *
     * @param responderId quem enviou, resolvido do token — fica gravado como o decisor do portão
     */
    public synchronized ConversationResponse resume(ResumeRequest request, String tenantId, String responderId) {
        SuspendedConversation state = conversationService.getByToken(request.resumeToken())
                .orElseThrow(() -> new ConversationController.NotFoundException(
                        "Conversation not found or expired for token: " + request.resumeToken()));
        if (state.getStatus() != SuspendedConversation.ConversationStatus.WAITING) {
            throw new IllegalStateException("A conversa " + state.getConversationId()
                    + " não está aguardando o formulário (status " + state.getStatus() + ")");
        }
        String conversationId = state.getConversationId();
        String executionId = state.getWorkflowExecutionId();
        Map<String, Object> formData = request.formData() != null ? request.formData() : Map.of();
        // Antes de qualquer efeito: com execução, o envio APROVA o portão dela. Um formulário vazio
        // pela API aprovava o gate e o fluxo concluía (RCHFLW-184); recusado aqui, a conversa continua
        // esperando e o pedido continua pendente.
        List<String> missing = state.getForm() != null ? state.getForm().missingRequired(formData) : List.of();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Campos obrigatórios do formulário "
                    + state.getForm().getId() + " sem valor: " + String.join(", ", missing));
        }

        CompletableFuture<FlowResult> run = null;
        if (!isBlank(executionId)) {
            String formId = state.getForm() != null ? state.getForm().getId() : null;
            try {
                run = approvals.submitInput(tenantId, executionId, formData, responderId,
                        "Formulário " + formId + " enviado na conversa " + conversationId);
            } catch (IllegalStateException e) {
                String terminal = terminalStatus(tenantId, executionId);
                if (terminal == null) {
                    // A execução ainda pode voltar a esperar (o prazo venceu e a varredura não passou, o
                    // portão é de outro tipo): a conversa continua esperando, com o motivo para quem enviou.
                    throw e;
                }
                // Encerrada de vez — por uma recusa que o aviso da fila não alcançou, pelo Stop, por outra
                // réplica —, o formulário não tem mais o que continuar: devolvê-lo travava o chat.
                retirarFormulario(conversationId, "A execução " + executionId + " terminou com " + terminal);
                throw new IllegalStateException("A execução " + executionId + " terminou com " + terminal
                        + " e não recebe mais este formulário: ele foi retirado da conversa", e);
            }
        }

        ConversationResponse response;
        try {
            response = conversations.resume(request);
        } catch (RuntimeException e) {
            if (run == null) {
                throw e;
            }
            // O motor já consumiu a aprovação e continuou o fluxo com os dados: não há como desfazer.
            // A resposta dele ainda vai ao chat (a fala fica no histórico, que não depende do estado
            // da suspensa), e quem enviou recebe um erro que diz isso — um 404 "token expirado" o
            // faria reenviar um formulário que o fluxo já recebeu.
            run.whenComplete((result, err) -> deliver(conversationId, tenantId, executionId, result, err));
            log.error("A execução {} continuou com o formulário da conversa {}, mas a conversa não pôde ser "
                    + "marcada como retomada", executionId, conversationId, e);
            throw new IllegalStateException("A execução " + executionId + " continuou com os dados do "
                    + "formulário, mas a conversa " + conversationId + " não pôde ser marcada como retomada ("
                    + messageOf(e) + "). A resposta do fluxo será publicada no chat; não reenvie o "
                    + "formulário.", e);
        }

        if (run != null) {
            // Ligado depois da retomada da conversa: um fluxo que termina na hora não publica a resposta
            // antes do "Form submitted" que a precede.
            run.whenComplete((result, err) -> deliver(conversationId, tenantId, executionId, result, err));
        } else {
            replies.replyAsync(conversationId, tenantId);
        }
        return response;
    }

    /** O status terminal do registro da execução, ou {@code null} se ela ainda pode esperar uma decisão. */
    private String terminalStatus(String tenantId, String executionId) {
        Map<String, Object> record = executions.getExecution(tenantId, executionId);
        Object status = record == null ? null : record.get("status");
        return status != null && TERMINAL.contains(status.toString()) ? status.toString() : null;
    }

    /** A resposta do fluxo continuado, no chat: a saída dele, ou o que impediu que houvesse uma. */
    private void deliver(String conversationId, String tenantId, String executionId,
                         FlowResult result, Throwable err) {
        try {
            if (err != null || result == null) {
                Throwable cause = err instanceof CompletionException && err.getCause() != null ? err.getCause() : err;
                replies.publishFailure(conversationId, tenantId, "A execução " + executionId
                        + " falhou ao continuar: " + (cause == null ? "sem resultado" : messageOf(cause)));
                return;
            }
            ExecutionStatus status = result.getStatus();
            if (status == ExecutionStatus.COMPLETED) {
                String text = result.getOutput().map(this::text).orElse(null);
                replies.publishReply(conversationId, tenantId, isBlank(text)
                        ? ConversationMessage.system(conversationId,
                                "A execução " + executionId + " terminou sem saída para responder.")
                        : ConversationMessage.assistant(conversationId, text));
                return;
            }
            if (status == ExecutionStatus.PAUSED) {
                replies.publishReply(conversationId, tenantId, ConversationMessage.system(conversationId,
                        "A execução " + executionId + " parou de novo e aguarda outra decisão humana."));
                return;
            }
            replies.publishFailure(conversationId, tenantId, "A execução " + executionId + " terminou com "
                    + status + errorsOf(result.getErrors()));
        } catch (RuntimeException e) {
            log.error("Falha ao entregar na conversa {} o desfecho da execução {}", conversationId,
                    executionId, e);
        }
    }

    /** Texto como veio; qualquer outra saída, como JSON — é o que o fluxo devolveu, sem reinterpretar. */
    private String text(Object output) {
        if (output instanceof String s) {
            return s;
        }
        try {
            return json.writeValueAsString(output);
        } catch (Exception e) {
            return String.valueOf(output);
        }
    }

    private static String errorsOf(List<ExecutionError> errors) {
        if (errors == null || errors.isEmpty()) {
            return "";
        }
        return ": " + errors.stream().map(ExecutionError::message).filter(Objects::nonNull)
                .collect(Collectors.joining("; "));
    }

    private static String messageOf(Throwable t) {
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
