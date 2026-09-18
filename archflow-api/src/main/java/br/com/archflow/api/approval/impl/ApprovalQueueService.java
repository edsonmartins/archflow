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

import java.time.Clock;
import java.time.Duration;
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

    /** Identidade registrada como decisor quando quem decide é o timeout. */
    static final String TIMEOUT_DECIDER = "system:timeout";

    private final StateManager stateManager;
    private final FlowEngine flowEngine;
    private final Duration timeout;
    private final Clock clock;
    /**
     * O outro gate humano: o que suspende <b>dentro</b> do laço de agente, antes de executar uma
     * tool. Nulo numa instalação sem laço MCP.
     *
     * <p>São dois mecanismos — nó do grafo e gate no laço —, e uma fila só. Para quem decide, a
     * diferença não existe: é sempre "isto vai acontecer, pode?". Duas filas exigiriam que o
     * operador soubesse de qual mecanismo veio cada pedido para saber onde respondê-lo.</p>
     */
    private final br.com.archflow.api.agent.mcp.RetomadaDoLaco retomadaDoLaco;

    /** Sem prazo — as aprovações esperam indefinidamente. */
    public ApprovalQueueService(StateManager stateManager, FlowEngine flowEngine) {
        this(stateManager, flowEngine, Duration.ZERO, Clock.systemUTC());
    }

    /**
     * @param timeout prazo de uma aprovação pendente; {@code null}, zero ou
     *                negativo desliga a expiração
     */
    public ApprovalQueueService(StateManager stateManager, FlowEngine flowEngine, Duration timeout) {
        this(stateManager, flowEngine, timeout, Clock.systemUTC());
    }

    /** Relógio injetável — o teste não precisa esperar o prazo passar de verdade. */
    public ApprovalQueueService(StateManager stateManager, FlowEngine flowEngine,
                                Duration timeout, Clock clock) {
        this(stateManager, flowEngine, timeout, clock, null);
    }

    /** Com o gate do laço de agente na mesma fila. */
    public ApprovalQueueService(StateManager stateManager, FlowEngine flowEngine,
                                Duration timeout, Clock clock,
                                br.com.archflow.api.agent.mcp.RetomadaDoLaco retomadaDoLaco) {
        this.stateManager = Objects.requireNonNull(stateManager, "stateManager");
        this.flowEngine = Objects.requireNonNull(flowEngine, "flowEngine");
        this.timeout = timeout == null || timeout.isNegative() ? Duration.ZERO : timeout;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.retomadaDoLaco = retomadaDoLaco;
    }

    /** {@code true} quando há prazo configurado. */
    public boolean hasTimeout() {
        return !timeout.isZero();
    }

    /**
     * Aprovações pendentes, mais antigas primeiro (as que esperam há mais tempo
     * aparecem no topo). {@code tenantId} nulo, em branco ou {@code "all"}
     * atravessa todos os tenants — é o que o admin envia ao não personificar.
     */
    public List<ApprovalResponse> listPending(String tenantId) {
        return java.util.stream.Stream.concat(
                        pendingStates().stream()
                .filter(state -> matchesTenant(state, tenantId))
                // Expirada não é acionável: não deve ser oferecida a decisão.
                // O sweeper a resolve; enquanto não roda, ela some da fila.
                .filter(state -> !isExpired(state))
                                .map(state -> toResponse(state, "PENDING"))
                                .filter(Objects::nonNull),
                        pendenciasDoLaco(tenantId))
                .sorted(Comparator.comparing(
                        ApprovalResponse::createdAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /** As do gate do laço de agente, na mesma forma das do nó de fluxo. */
    private java.util.stream.Stream<ApprovalResponse> pendenciasDoLaco(String tenantId) {
        if (retomadaDoLaco == null) {
            return java.util.stream.Stream.empty();
        }
        // O store lista por tenant; "todos os tenants" não é uma pergunta que ele responde, e
        // varrer tenant a tenant exigiria conhecer a lista deles.
        if (todosOsTenants(tenantId)) {
            return java.util.stream.Stream.empty();
        }
        try {
            return retomadaDoLaco.pendentes(tenantId).stream().map(ApprovalQueueService::doLaco);
        } catch (RuntimeException e) {
            log.warn("Falha ao listar aprovações do laço de agente (tenant={}): {}",
                    tenantId, e.getMessage());
            return java.util.stream.Stream.empty();
        }
    }

    private static boolean todosOsTenants(String tenantId) {
        return tenantId == null || tenantId.isBlank() || ALL_TENANTS.equalsIgnoreCase(tenantId);
    }

    /**
     * A pendência do laço na forma do DTO da fila: {@code flowId} é a execução do laço e
     * {@code stepId}, a tool que espera. A proposta é o argumento que o modelo emitiu — é sobre ele
     * que a pessoa decide, e é ele que um {@code EDITED} substitui.
     */
    private static ApprovalResponse doLaco(br.com.archflow.api.agent.mcp.RetomadaDoLaco.Pendencia p) {
        return new ApprovalResponse(p.requestId(), p.tenantId(), p.runId(), p.toolName(),
                "PENDING", "Execução da tool " + p.toolName() + " aguardando aprovação",
                p.argumentos(), p.suspensoEm(), null);
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
                .or(() -> pendenciaDoLaco(requestId).map(ApprovalQueueService::doLaco))
                .orElseThrow(() -> new NoSuchElementException(
                        "Approval not found or already decided: " + requestId));
    }

    private Optional<br.com.archflow.api.agent.mcp.RetomadaDoLaco.Pendencia> pendenciaDoLaco(
            String requestId) {
        return retomadaDoLaco == null ? Optional.empty() : retomadaDoLaco.detalhe(requestId);
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

        // O id diz qual mecanismo responde: o gate do laço grava no store do agente, o nó de fluxo
        // no estado do motor. Um id não existe nos dois.
        Optional<br.com.archflow.api.agent.mcp.RetomadaDoLaco.Pendencia> noLaco =
                pendenciaDoLaco(requestId);
        if (noLaco.isPresent()) {
            return decidirNoLaco(noLaco.get(), decision, request);
        }

        FlowState state = findPendingByRequestId(requestId)
                .orElseThrow(() -> new NoSuchElementException("Approval not found: " + requestId));
        if (isExpired(state)) {
            // Uma proposta vencida descreve um mundo que já mudou. Executá-la
            // agora pode ser pior do que não fazer nada — quem decide precisa
            // ver a situação atual, não a de ontem.
            throw new IllegalStateException("Approval expired: " + requestId);
        }
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

    /**
     * Aplica a decisão ao laço de agente: aprovado executa a tool e o laço segue; recusado devolve
     * a recusa ao modelo, que pode propor outra coisa.
     *
     * <p>{@code EDITED} substitui os argumentos — e só vale como mapa: o que entra aqui vai direto
     * para a chamada da tool, e um payload de outra forma seria executado como se fosse o que a
     * pessoa quis.</p>
     */
    private ApprovalResponse decidirNoLaco(
            br.com.archflow.api.agent.mcp.RetomadaDoLaco.Pendencia pendencia, String decision,
            ApprovalSubmitRequest request) {
        Map<String, Object> argumentos = null;
        if ("EDITED".equals(decision)) {
            if (!(request.editedPayload() instanceof Map<?, ?> mapa)) {
                throw new IllegalArgumentException("EDITED numa aprovação de tool exige "
                        + "editedPayload como objeto de argumentos");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> convertido = (Map<String, Object>) mapa;
            argumentos = convertido;
        }
        boolean aprovado = !"REJECTED".equals(decision);
        retomadaDoLaco.decidir(pendencia.requestId(), aprovado, argumentos);

        log.info("Aprovação {} de tool decidida como {} por {} (run={}, tool={})",
                pendencia.requestId(), decision, request.responderId(), pendencia.runId(),
                pendencia.toolName());
        return new ApprovalResponse(pendencia.requestId(), pendencia.tenantId(), pendencia.runId(),
                pendencia.toolName(), decision, "Execução da tool " + pendencia.toolName(),
                pendencia.argumentos(), pendencia.suspensoEm(), null);
    }

    /** Número de aprovações pendentes — usado pelo badge da navbar. */
    public int pendingCount(String tenantId) {
        return listPending(tenantId).size();
    }

    /**
     * Resolve as aprovações vencidas aplicando a decisão de timeout.
     *
     * <p>A decisão é sempre <b>REJECTED</b>: o timeout significa que ninguém
     * olhou, e "ninguém olhou" nunca pode virar "pode executar". Um fluxo
     * suspenso indefinidamente também é um vazamento — segura estado e aparece
     * como pendência viva numa fila que nunca esvazia.
     *
     * @return quantas foram expiradas
     */
    public int expireStale() {
        if (!hasTimeout()) {
            return 0;
        }
        List<FlowState> expired = pendingStates().stream().filter(this::isExpired).toList();
        int resolved = 0;
        for (FlowState state : expired) {
            String requestId = variable(state, ExecutionKeys.APPROVAL_REQUEST_ID);
            if (requestId == null || requestId.isBlank()) {
                continue;
            }
            try {
                flowEngine.submitApproval(state.getFlowId(), requestId, false, null,
                        TIMEOUT_DECIDER, "expirada apos " + timeout);
                resolved++;
                log.info("Aprovação {} expirada após {} (flow={}) — recusada automaticamente",
                        requestId, timeout, state.getFlowId());
            } catch (RuntimeException e) {
                // Uma decisão humana concorrente já pode ter consumido o id;
                // não é erro, é corrida perdida. Segue para as demais.
                log.debug("Não foi possível expirar a aprovação {}: {}", requestId, e.getMessage());
            }
        }
        return resolved;
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

    /**
     * Prazo da solicitação, ou {@code null} quando não há timeout configurado ou
     * o estado não registra quando foi solicitada (fluxos suspensos por versões
     * anteriores). Sem instante de início não há como expirar com segurança.
     */
    private Instant expiresAt(FlowState state) {
        if (!hasTimeout()) {
            return null;
        }
        Instant requestedAt = parseInstant(variable(state, ExecutionKeys.APPROVAL_REQUESTED_AT));
        return requestedAt == null ? null : requestedAt.plus(timeout);
    }

    private boolean isExpired(FlowState state) {
        Instant deadline = expiresAt(state);
        return deadline != null && clock.instant().isAfter(deadline);
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
    private ApprovalResponse toResponse(FlowState state, String status) {
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
                // Nulo quando não há timeout configurado — o campo informa o
                // prazo real, não um valor inventado.
                expiresAt(state));
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
