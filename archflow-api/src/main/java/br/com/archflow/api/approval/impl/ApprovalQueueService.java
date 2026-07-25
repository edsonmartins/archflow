package br.com.archflow.api.approval.impl;

import br.com.archflow.api.approval.dto.ApprovalResponse;
import br.com.archflow.api.approval.dto.ApprovalSubmitRequest;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.core.StateManager;
import br.com.archflow.model.engine.ExecutionKeys;
import br.com.archflow.model.flow.FlowState;
import br.com.archflow.model.flow.FlowStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * Fila de aprovações humanas, montada a partir do <b>estado durável dos fluxos</b>.
 *
 * <p>Antes esta fachada lia um {@code ApprovalRegistry} em memória cujo
 * {@code register()} não tinha nenhum produtor: a fila estava permanentemente
 * vazia e, se não estivesse, não sobreviveria a um restart. Em paralelo, o
 * {@link FlowEngine} já tinha um gate durável
 * ({@code requestApproval}/{@code submitApproval}) que nenhum endpoint
 * alcançava. Eram dois mecanismos desconectados; sobrou o durável.
 *
 * <p>A fonte de verdade é o {@link StateManager}: um fluxo pendente é um
 * {@link FlowState} em {@link FlowStatus#AWAITING_APPROVAL}, e o
 * {@code requestId} vive nas suas variáveis persistidas. Decidir delega a
 * {@link FlowEngine#submitApproval}, que valida o id, consome-o sob lock e
 * retoma (ou para) a execução.
 */
public class ApprovalQueueService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalQueueService.class);

    /** Sentinela que o admin usa para "todos os tenants". */
    private static final String ALL_TENANTS = "all";

    private final StateManager stateManager;
    private final FlowEngine flowEngine;

    public ApprovalQueueService(StateManager stateManager, FlowEngine flowEngine) {
        this.stateManager = Objects.requireNonNull(stateManager, "stateManager");
        this.flowEngine = Objects.requireNonNull(flowEngine, "flowEngine");
    }

    /**
     * Aprovações pendentes, mais antigas primeiro (as que esperam há mais tempo
     * aparecem no topo). {@code tenantId} nulo, em branco ou {@code "all"}
     * atravessa todos os tenants — é o que o admin envia ao não personificar.
     */
    public List<ApprovalResponse> listPending(String tenantId) {
        return pendingStates().stream()
                .filter(state -> matchesTenant(state, tenantId))
                .map(state -> toResponse(state, "PENDING"))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(
                        ApprovalResponse::createdAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /**
     * Detalhe de uma aprovação pendente. A busca é por {@code requestId} entre
     * todos os tenants porque o link que o operador abre não carrega tenant.
     *
     * @throws NoSuchElementException se o id é desconhecido ou já foi decidido
     */
    public ApprovalResponse getDetail(String requestId) {
        Objects.requireNonNull(requestId, "requestId");
        return findPendingByRequestId(requestId)
                .map(state -> toResponse(state, "PENDING"))
                .orElseThrow(() -> new NoSuchElementException(
                        "Approval not found or already decided: " + requestId));
    }

    /**
     * Submete a decisão ao motor. APPROVED/EDITED retomam o fluxo do ponto de
     * suspensão; REJECTED o encerra.
     */
    public ApprovalResponse submitDecision(String requestId, ApprovalSubmitRequest request) {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(request, "request");

        String decision = request.decision() == null ? "" : request.decision().toUpperCase();
        if (!List.of("APPROVED", "REJECTED", "EDITED").contains(decision)) {
            throw new IllegalArgumentException(
                    "Unknown decision '" + request.decision() + "' — expected APPROVED|REJECTED|EDITED");
        }

        FlowState state = findPendingByRequestId(requestId)
                .orElseThrow(() -> new NoSuchElementException("Approval not found: " + requestId));
        // Capturado ANTES de submeter: a submissão troca o status e consome o
        // requestId, então o estado pendente deixa de ser encontrável.
        ApprovalResponse snapshot = toResponse(state, decision);

        boolean approved = !"REJECTED".equals(decision);
        Object editedPayload = "EDITED".equals(decision) ? request.editedPayload() : null;

        try {
            flowEngine.submitApproval(state.getFlowId(), requestId, approved, editedPayload,
                    request.responderId(), request.comment());
        } catch (RuntimeException e) {
            // O motor é a autoridade: uma corrida (duas submissões simultâneas)
            // é barrada lá, não aqui.
            throw new IllegalStateException(
                    "Failed to submit approval " + requestId + ": " + e.getMessage(), e);
        }

        log.info("Aprovação {} decidida como {} por {} (flow={})",
                requestId, decision, request.responderId(), state.getFlowId());
        // O decisor e a justificativa ficam no estado durável, gravados pelo
        // motor sob o mesmo lock da decisão — não só aqui no log.
        return snapshot;
    }

    /** Número de aprovações pendentes — usado pelo badge da navbar. */
    public int pendingCount(String tenantId) {
        return listPending(tenantId).size();
    }

    // ── helpers ──────────────────────────────────────────────────

    private List<FlowState> pendingStates() {
        return stateManager.findByStatus(FlowStatus.AWAITING_APPROVAL);
    }

    private Optional<FlowState> findPendingByRequestId(String requestId) {
        return pendingStates().stream()
                .filter(state -> requestId.equals(variable(state, ExecutionKeys.APPROVAL_REQUEST_ID)))
                .findFirst();
    }

    private static boolean matchesTenant(FlowState state, String tenantId) {
        if (tenantId == null || tenantId.isBlank() || ALL_TENANTS.equalsIgnoreCase(tenantId)) {
            return true;
        }
        return tenantId.equals(state.getTenantId());
    }

    /**
     * @return {@code null} quando o estado não carrega um requestId vivo — um
     *         fluxo em AWAITING_APPROVAL sem id pendente não é uma aprovação
     *         acionável e não deve poluir a fila.
     */
    private static ApprovalResponse toResponse(FlowState state, String status) {
        String requestId = variable(state, ExecutionKeys.APPROVAL_REQUEST_ID);
        if (requestId == null || requestId.isBlank()) {
            return null;
        }
        return new ApprovalResponse(
                requestId,
                state.getTenantId(),
                state.getFlowId(),
                variable(state, ExecutionKeys.APPROVAL_STEP_ID) != null
                        ? variable(state, ExecutionKeys.APPROVAL_STEP_ID)
                        : state.getCurrentStepId(),
                status,
                variable(state, ExecutionKeys.APPROVAL_DESCRIPTION),
                rawVariable(state, ExecutionKeys.APPROVAL_PROPOSAL),
                parseInstant(variable(state, ExecutionKeys.APPROVAL_REQUESTED_AT)),
                // O gate do motor não expira sozinho: uma aprovação fica pendente
                // até alguém decidir. Sem prazo a informar, o campo vai nulo em
                // vez de inventar um.
                null);
    }

    private static Object rawVariable(FlowState state, String key) {
        Map<String, Object> vars = state.getVariables();
        return vars != null ? vars.get(key) : null;
    }

    private static String variable(FlowState state, String key) {
        Object value = rawVariable(state, key);
        return value != null ? String.valueOf(value) : null;
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
