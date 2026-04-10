package br.com.archflow.conversation.approval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Registro de aprovações pendentes.
 *
 * <p>Mantém o mapeamento requestId → ApprovalRequest para fluxos
 * suspensos em AWAITING_APPROVAL. Suporta timeout configurável
 * e cleanup de requisições expiradas.
 *
 * <p>Quando uma decisão é recebida via {@link #submitDecision},
 * o registro notifica o {@link LearningCallback} (se registrado)
 * em caso de REJECTED ou EDITED.
 */
public class ApprovalRegistry {
    private static final Logger log = LoggerFactory.getLogger(ApprovalRegistry.class);

    private final Map<String, ApprovalRequest> pendingApprovals = new ConcurrentHashMap<>();
    private final Map<String, HumanDecisionEvent> completedDecisions = new ConcurrentHashMap<>();
    private final LearningCallback learningCallback;
    private final Decision defaultTimeoutDecision;
    private final Consumer<HumanDecisionEvent> timeoutHandler;

    public ApprovalRegistry() {
        this(null, Decision.REJECTED, null);
    }

    public ApprovalRegistry(LearningCallback learningCallback) {
        this(learningCallback, Decision.REJECTED, null);
    }

    /**
     * Cria um registry com decisão default para timeouts e handler de notificação.
     *
     * @param learningCallback     Callback para REJECTED/EDITED (pode ser null)
     * @param defaultTimeoutDecision Decisão aplicada quando timeout expira (default: REJECTED)
     * @param timeoutHandler       Handler notificado quando uma decisão de timeout é aplicada (pode ser null)
     */
    public ApprovalRegistry(LearningCallback learningCallback,
                            Decision defaultTimeoutDecision,
                            Consumer<HumanDecisionEvent> timeoutHandler) {
        this.learningCallback = learningCallback;
        this.defaultTimeoutDecision = defaultTimeoutDecision != null ? defaultTimeoutDecision : Decision.REJECTED;
        this.timeoutHandler = timeoutHandler;
    }

    /**
     * Registra uma nova requisição de aprovação.
     *
     * @param request A requisição de aprovação
     * @throws IllegalArgumentException se já existir uma requisição com o mesmo requestId
     */
    public void register(ApprovalRequest request) {
        if (pendingApprovals.containsKey(request.requestId())) {
            throw new IllegalArgumentException(
                    "Approval request already exists: " + request.requestId());
        }
        pendingApprovals.put(request.requestId(), request);
        log.info("Registered approval request: requestId={}, tenant={}, flow={}",
                request.requestId(), request.tenantId(), request.flowId());
    }

    /**
     * Submete uma decisão humana para uma requisição pendente.
     *
     * @param event O evento de decisão humana
     * @return A requisição original se encontrada e não expirada
     * @throws IllegalArgumentException se a requisição não existir
     * @throws IllegalStateException se a requisição já tiver sido decidida
     */
    public Optional<ApprovalRequest> submitDecision(HumanDecisionEvent event) {
        ApprovalRequest request = pendingApprovals.remove(event.requestId());

        if (request == null) {
            // Verificar se já foi decidida
            if (completedDecisions.containsKey(event.requestId())) {
                throw new IllegalStateException(
                        "Approval already decided: " + event.requestId());
            }
            throw new IllegalArgumentException(
                    "Approval request not found: " + event.requestId());
        }

        if (request.isExpired()) {
            log.warn("Approval request expired: requestId={}, expiredAt={}",
                    request.requestId(), request.expiresAt());
            return Optional.empty();
        }

        completedDecisions.put(event.requestId(), event);

        log.info("Decision submitted: requestId={}, decision={}",
                event.requestId(), event.decision());

        // Notificar LearningCallback para REJECTED e EDITED
        notifyLearning(event, request);

        return Optional.of(request);
    }

    /**
     * Obtém uma requisição pendente por requestId.
     */
    public Optional<ApprovalRequest> getPending(String requestId) {
        ApprovalRequest request = pendingApprovals.get(requestId);
        if (request != null && request.isExpired()) {
            pendingApprovals.remove(requestId);
            return Optional.empty();
        }
        return Optional.ofNullable(request);
    }

    /**
     * Lista todas as aprovações pendentes de um tenant.
     */
    public List<ApprovalRequest> listPendingByTenant(String tenantId) {
        return pendingApprovals.values().stream()
                .filter(r -> r.tenantId().equals(tenantId))
                .filter(r -> !r.isExpired())
                .toList();
    }

    /**
     * Lista aprovações pendentes de um fluxo específico.
     */
    public List<ApprovalRequest> listPendingByFlow(String tenantId, String flowId) {
        return pendingApprovals.values().stream()
                .filter(r -> r.tenantId().equals(tenantId) && r.flowId().equals(flowId))
                .filter(r -> !r.isExpired())
                .toList();
    }

    /**
     * Limpa requisições expiradas aplicando decisão default.
     * Retorna número de requisições processadas.
     *
     * <p>Para cada requisição expirada, aplica a decisão default configurada
     * (tipicamente REJECTED), notifica o LearningCallback e o timeoutHandler.
     *
     * @return Lista de decisões de timeout aplicadas
     */
    public List<HumanDecisionEvent> processExpired() {
        List<HumanDecisionEvent> timeoutDecisions = new ArrayList<>();
        Iterator<Map.Entry<String, ApprovalRequest>> it = pendingApprovals.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ApprovalRequest> entry = it.next();
            ApprovalRequest request = entry.getValue();
            if (request.isExpired()) {
                it.remove();

                HumanDecisionEvent timeoutEvent = new HumanDecisionEvent(
                        request.requestId(), request.tenantId(),
                        defaultTimeoutDecision, null, "SYSTEM_TIMEOUT", null);

                completedDecisions.put(request.requestId(), timeoutEvent);
                timeoutDecisions.add(timeoutEvent);

                notifyLearning(timeoutEvent, request);

                if (timeoutHandler != null) {
                    try {
                        timeoutHandler.accept(timeoutEvent);
                    } catch (Exception e) {
                        log.error("Error in timeout handler for request {}", request.requestId(), e);
                    }
                }

                log.info("Timeout applied to approval: requestId={}, decision={}",
                        request.requestId(), defaultTimeoutDecision);
            }
        }
        return timeoutDecisions;
    }

    /**
     * Cria uma tarefa executável para processamento periódico de timeouts.
     * Pode ser agendada com ScheduledExecutorService ou TenantScheduler.
     */
    public Runnable createTimeoutTask() {
        return this::processExpired;
    }

    /**
     * @deprecated Use {@link #processExpired()} que aplica decisão default.
     */
    @Deprecated
    public int cleanupExpired() {
        return processExpired().size();
    }

    /**
     * Retorna o número de aprovações pendentes.
     */
    public int pendingCount() {
        return (int) pendingApprovals.values().stream()
                .filter(r -> !r.isExpired())
                .count();
    }

    private void notifyLearning(HumanDecisionEvent event, ApprovalRequest request) {
        if (learningCallback == null) return;

        try {
            switch (event.decision()) {
                case REJECTED ->
                        learningCallback.onRejected(event.tenantId(), event.requestId(), request.proposal());
                case EDITED ->
                        learningCallback.onEdited(event.tenantId(), event.requestId(),
                                request.proposal(), event.editedPayload());
                case APPROVED -> { /* no learning needed */ }
            }
        } catch (Exception e) {
            log.error("Error in LearningCallback for request {}", event.requestId(), e);
        }
    }
}
