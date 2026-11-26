package br.com.archflow.api.approval.impl;

import br.com.archflow.api.admin.observability.impl.TraceStoreRecorder;
import br.com.archflow.api.approval.dto.ApprovalResponse;
import br.com.archflow.api.approval.dto.ApprovalSubmitRequest;
import br.com.archflow.api.audit.AuditTrail;
import br.com.archflow.api.web.workflow.WorkflowRuntimeStore;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.core.StateManager;
import br.com.archflow.model.engine.ExecutionKeys;
import br.com.archflow.model.flow.FlowResult;
import br.com.archflow.model.flow.FlowState;
import br.com.archflow.model.flow.FlowStatus;
import br.com.archflow.observability.audit.AuditAction;
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
import java.util.concurrent.CompletableFuture;

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
    /**
     * Registro das execuções (o que {@code GET /api/executions/{id}} e as telas de Execuções
     * leem). Nulo fora do servidor — o motor sozinho não tem registro.
     *
     * <p>Quem disparou a execução (/execute, AG-UI, MCP) grava PAUSED quando ela suspende no
     * portão e nunca mais olha para ela. A decisão chega por aqui, então é aqui que o registro
     * tem de seguir a execução até o fim — senão ele fica PAUSED para sempre, e o resume nem é
     * saída, porque o estado do motor já é final (RCHFLW-40).</p>
     */
    private final WorkflowRuntimeStore executionRecords;
    /**
     * A trilha de auditoria. Uma decisão humana — e a recusa automática do prazo — é uma autorização
     * com autor, e antes só existia no estado da execução: nenhuma linha APPROVE/REJECT na auditoria
     * (RCHFLW-195 item 1). Ligada por setter (como nos demais produtores); sem ela, no-op.
     */
    private volatile AuditTrail auditTrail = AuditTrail.noop();
    /**
     * Os traces da observabilidade. A recusa (humana ou do prazo) e a retomada que falha antes de
     * começar encerram a execução sem nova travessia, então o motor não chama o {@code onFlowEnd} e o
     * trace PAUSED ficava "Aguardando aprovação" para sempre. Ligado por setter; sem ele, no-op.
     */
    private volatile TraceStoreRecorder traces;

    /**
     * Quem precisa saber que uma execução acabou num portão sem aprovação — recusada por alguém ou pelo
     * prazo. A conversa que esperava um formulário dessa execução é o caso: sem o aviso ela ficava
     * SUSPENDED para sempre, e cada envio voltava com 409 (RCHFLW-194).
     */
    @FunctionalInterface
    public interface EncerramentoSemAprovacao {
        /**
         * @param flowId   a execução encerrada
         * @param porPrazo {@code true} quando ninguém decidiu a tempo (o decisor é o timeout)
         */
        void encerrada(String flowId, boolean porPrazo);
    }

    private final List<EncerramentoSemAprovacao> encerramentos =
            new java.util.concurrent.CopyOnWriteArrayList<>();

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
        this(stateManager, flowEngine, timeout, clock, retomadaDoLaco, null);
    }

    /** Com o registro de execuções acompanhando cada decisão até o estado terminal. */
    public ApprovalQueueService(StateManager stateManager, FlowEngine flowEngine,
                                Duration timeout, Clock clock,
                                br.com.archflow.api.agent.mcp.RetomadaDoLaco retomadaDoLaco,
                                WorkflowRuntimeStore executionRecords) {
        this.stateManager = Objects.requireNonNull(stateManager, "stateManager");
        this.flowEngine = Objects.requireNonNull(flowEngine, "flowEngine");
        this.timeout = timeout == null || timeout.isNegative() ? Duration.ZERO : timeout;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.retomadaDoLaco = retomadaDoLaco;
        this.executionRecords = executionRecords;
    }

    /** Liga o encerramento dos traces pausados às decisões (ver {@link #traces}). */
    public void setTraces(TraceStoreRecorder traces) {
        this.traces = traces;
    }

    /**
     * Fecha o trace PAUSED da execução com o motivo. Como o registro, não decide o resultado da
     * decisão — ela já está gravada no estado do motor —, então uma falha aqui só vai para o log.
     */
    private void encerrarTrace(String flowId, java.util.function.Supplier<String> motivo) {
        TraceStoreRecorder recorder = traces;
        if (recorder == null) {
            return;
        }
        try {
            FlowState state = stateManager.loadState(flowId);
            recorder.encerrarPausa(state != null ? state.getTenantId() : null, flowId, motivo.get());
        } catch (RuntimeException e) {
            log.warn("Não foi possível encerrar o trace pausado de {}: {}", flowId, e.getMessage());
        }
    }

    /** Liga a trilha de auditoria às decisões (ver {@link #auditTrail}). */
    public void setAuditTrail(AuditTrail auditTrail) {
        if (auditTrail != null) {
            this.auditTrail = auditTrail;
        }
    }

    /**
     * Uma linha por decisão: o pedido, a execução, a decisão e o comentário. O tenant vai explícito —
     * é o do pedido, e a varredura do prazo roda fora de qualquer requisição. O autor é o da
     * requisição (o mesmo que o controller gravou como decisor); a recusa do prazo leva
     * {@link #TIMEOUT_DECIDER}.
     */
    private void auditarDecisao(String requestId, String executionId, String tenantId, String decision,
                                String decidedBy, String comment, boolean automatica) {
        Map<String, String> detalhes = new java.util.HashMap<>();
        detalhes.put("requestId", requestId);
        if (executionId != null) detalhes.put("executionId", executionId);
        if (tenantId != null) detalhes.put(AuditTrail.CONTEXT_TENANT, tenantId);
        detalhes.put("decision", decision);
        if (decidedBy != null && !decidedBy.isBlank()) detalhes.put("decidedBy", decidedBy);
        if (comment != null && !comment.isBlank()) detalhes.put("comment", comment);
        AuditAction action = "REJECTED".equals(decision)
                ? AuditAction.APPROVAL_REJECTED
                : AuditAction.APPROVAL_APPROVED;
        if (automatica) {
            auditTrail.record(TIMEOUT_DECIDER, TIMEOUT_DECIDER, action, "approval", requestId, true, null,
                    detalhes);
        } else {
            auditTrail.record(action, "approval", requestId, true, null, detalhes);
        }
    }

    /**
     * {@code true} quando há prazo <b>global</b> configurado. Um nó pode ter o próprio prazo
     * ({@link ExecutionKeys#APPROVAL_TIMEOUT}) mesmo sem o global — por isso a varredura não
     * depende deste método.
     */
    public boolean hasTimeout() {
        return !timeout.isZero();
    }

    /** Avisa {@code ouvinte} de cada execução encerrada num portão sem aprovação (recusa ou prazo). */
    public void aoEncerrarSemAprovacao(EncerramentoSemAprovacao ouvinte) {
        encerramentos.add(Objects.requireNonNull(ouvinte, "ouvinte"));
    }

    /**
     * Nunca lança: a recusa já está gravada no estado do motor e no registro, e um ouvinte que falha não
     * pode virar erro para quem recusou.
     */
    private void avisarEncerramento(String flowId) {
        if (encerramentos.isEmpty()) {
            return;
        }
        boolean porPrazo;
        try {
            FlowState decided = stateManager.loadState(flowId);
            porPrazo = decided != null
                    && TIMEOUT_DECIDER.equals(variable(decided, ExecutionKeys.APPROVAL_DECIDED_BY));
        } catch (RuntimeException e) {
            porPrazo = false;
        }
        for (EncerramentoSemAprovacao ouvinte : encerramentos) {
            try {
                ouvinte.encerrada(flowId, porPrazo);
            } catch (RuntimeException e) {
                log.warn("Aviso do encerramento sem aprovação de {} falhou: {}", flowId, e.getMessage(), e);
            }
        }
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

    /**
     * As do gate do laço de agente, na mesma forma das do nó de fluxo.
     *
     * <p>"Todos os tenants" vai ao store como veio: os dois stores o respondem. A fila devolvia
     * vazio aqui, e o admin — que a UI manda como {@code all} — via "Nenhuma aprovação pendente" e
     * badge 0 enquanto um estorno esperava decisão (RCHFLW-141).</p>
     */
    private java.util.stream.Stream<ApprovalResponse> pendenciasDoLaco(String tenantId) {
        if (retomadaDoLaco == null) {
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
        return getDetail(requestId, null);
    }

    /**
     * Detalhe de uma aprovação, pendente ou já decidida.
     *
     * <p>Decidido, o pedido sai da fila e o detalhe respondia 404: quem abria o link depois da
     * decisão ficava sem saber quem decidiu, quando e por quê — embora o estado durável tivesse
     * tudo isso no histórico de aprovações. Com o {@code flowId} (a execução, que a fila e o detalhe
     * da execução conhecem), a resposta vem desse histórico: o status é a decisão e os campos de
     * decisão são os que o motor gravou sob o lock dela. Sem {@code flowId} não há busca entre
     * todos os estados terminais — seria varrer a tabela inteira a cada link aberto.</p>
     *
     * @param flowId execução que emitiu o pedido; nulo ou em branco, só pendentes são encontrados
     * @throws NoSuchElementException se o id é desconhecido (ou decidido e sem {@code flowId})
     */
    public ApprovalResponse getDetail(String requestId, String flowId) {
        return getDetail(requestId, flowId, null);
    }

    /**
     * Como {@link #getDetail(String, String)}, restrito a um tenant (RCHFLW-39).
     *
     * <p>Um pedido de outro tenant é respondido como inexistente, com a mesma exceção de um id
     * desconhecido: responder "existe, mas não é seu" já entregaria a quem não pode que o id vale.</p>
     *
     * @param tenantScope tenant que o pedido precisa ter; {@code null} aceita qualquer um (admin com
     *                    "all", ou servidor sem autenticação)
     */
    public ApprovalResponse getDetail(String requestId, String flowId, String tenantScope) {
        Objects.requireNonNull(requestId, "requestId");
        return findPendingByRequestId(requestId)
                .filter(state -> inScope(tenantScope, state.getTenantId()))
                // Vencida e ainda não varrida, ela some da fila — e o detalhe dizia PENDING, com os
                // botões ativos, até o sweeper passar (RCHFLW-126). O status já é EXPIRED; a recusa em
                // si continua sendo só do sweeper, como system:timeout: ler não decide nada.
                .map(state -> toResponse(state, isExpired(state) ? "EXPIRED" : "PENDING"))
                .or(() -> pendenciaDoLaco(requestId)
                        .filter(p -> inScope(tenantScope, p.tenantId()))
                        .map(ApprovalQueueService::doLaco))
                .or(() -> decidedDetail(requestId, flowId)
                        .filter(r -> inScope(tenantScope, r.tenantId())))
                .orElseThrow(() -> new NoSuchElementException(
                        "Approval not found or already decided: " + requestId));
    }

    /** {@code null} é "qualquer tenant"; fora isso, o tenant do item tem de ser o do recorte. */
    private static boolean inScope(String tenantScope, String itemTenant) {
        return tenantScope == null || tenantScope.equals(itemTenant);
    }

    /**
     * O pedido decidido, lido da entrada dele no histórico de aprovações da execução. Título,
     * descrição, proposta e prazo ficam nulos: as chaves que os guardam descrevem só o portão
     * corrente e podem já ser de outro pedido — mostrar a proposta errada como "a que foi
     * aprovada" seria pior do que não mostrar.
     */
    private Optional<ApprovalResponse> decidedDetail(String requestId, String flowId) {
        if (flowId == null || flowId.isBlank()) {
            return Optional.empty();
        }
        FlowState state = stateManager.loadState(flowId);
        Map<String, Object> entry = state != null ? historyEntry(state, requestId) : Map.of();
        if (entry.isEmpty()) {
            return Optional.empty();
        }
        String decidedBy = blankToNull(entry.get("decidedBy"));
        // O prazo também "decide" (sempre recusando); a fila o mostra como EXPIRED, não como
        // uma recusa de alguém.
        String status = TIMEOUT_DECIDER.equals(decidedBy) ? "EXPIRED" : blankToNull(entry.get("decision"));
        // A proposta editada, ao contrário da proposta do portão corrente, é desta entrada: mostrá-la
        // é mostrar o que foi aprovado de fato (RCHFLW-121).
        return Optional.of(new ApprovalResponse(requestId, state.getTenantId(), state.getFlowId(),
                blankToNull(entry.get("stepId")), status,
                null, null, null, null, null,
                decidedBy, parseInstant(blankToNull(entry.get("decidedAt"))), blankToNull(entry.get("comment")),
                flowNameOf(state.getTenantId(), state.getFlowId()), entry.get("editedPayload")));
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
        return submitDecision(requestId, request, null);
    }

    /**
     * Como {@link #submitDecision(String, ApprovalSubmitRequest)}, só para um pedido do tenant do
     * recorte (RCHFLW-39). Antes, qualquer usuário autenticado que soubesse o {@code requestId}
     * aprovava — ou editava os argumentos da tool — de um pedido de outro tenant. Fora do recorte, o
     * pedido é "não encontrado", como um id desconhecido, e nada é decidido.
     *
     * @param tenantScope tenant que o pedido precisa ter; {@code null} aceita qualquer um
     */
    public ApprovalResponse submitDecision(String requestId, ApprovalSubmitRequest request,
                                           String tenantScope) {
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
                pendenciaDoLaco(requestId).filter(p -> inScope(tenantScope, p.tenantId()));
        if (noLaco.isPresent()) {
            return decidirNoLaco(noLaco.get(), decision, request);
        }

        FlowState state = findPendingByRequestId(requestId)
                .filter(s -> inScope(tenantScope, s.getTenantId()))
                .orElseThrow(() -> new NoSuchElementException("Approval not found: " + requestId));
        if (isExpired(state)) {
            // Uma proposta vencida descreve um mundo que já mudou. Executá-la
            // agora pode ser pior do que não fazer nada — quem decide precisa
            // ver a situação atual, não a de ontem.
            // Em português e com o que aconteceu: a tela mostra esta mensagem a quem tentou decidir.
            throw new IllegalStateException("O prazo desta aprovação venceu"
                    + Optional.ofNullable(expiresAt(state)).map(at -> " em " + at).orElse("")
                    + " e ela não pode mais ser decidida: o sistema a recusa automaticamente "
                    + "(pedido " + requestId + ")");
        }
        Object editedPayload = "EDITED".equals(decision) ? request.editedPayload() : null;
        if ("EDITED".equals(decision) && editedPayload == null) {
            // Sem a proposta editada, "editado" seria uma aprovação comum registrada com outro nome.
            throw new IllegalArgumentException("EDITED exige editedPayload com a proposta editada");
        }
        // Capturado ANTES de submeter: a submissão troca o status e consome o
        // requestId, então o estado pendente deixa de ser encontrável.
        ApprovalResponse snapshot = toResponse(state, decision);

        boolean approved = !"REJECTED".equals(decision);

        CompletableFuture<FlowResult> run;
        try {
            // A edição vai ao motor como edição, não como "aprovado com payload": é o que a registra
            // como EDITED e faz o passo de aprovação entregar a proposta editada (RCHFLW-121).
            run = editedPayload != null
                    ? flowEngine.submitEditedApproval(state.getFlowId(), requestId, editedPayload,
                            request.responderId(), request.comment())
                    : flowEngine.submitApproval(state.getFlowId(), requestId, approved, null,
                            request.responderId(), request.comment());
        } catch (RuntimeException e) {
            // O motor é a autoridade: uma corrida (duas submissões simultâneas)
            // é barrada lá, não aqui. Também é lá que uma aprovação que não teria
            // como retomar (fluxo fora do FlowRepository) é recusada antes de
            // consumir o pedido — e a recusa chega a quem decidiu, não um sucesso.
            throw new IllegalStateException(
                    "Failed to submit approval " + requestId + ": " + e.getMessage(), e);
        }

        log.info("Aprovação {} decidida como {} por {} (flow={})",
                requestId, decision, request.responderId(), state.getFlowId());
        auditarDecisao(requestId, state.getFlowId(), state.getTenantId(), decision, request.responderId(),
                request.comment(), false);
        // Lido ANTES de acompanhar a execução: a retomada pode chegar a um segundo portão e
        // limpar as chaves do portão corrente. O histórico, não.
        Map<String, Object> decided = decisionRecord(state.getFlowId(), requestId);
        acompanharExecucao(state.getFlowId(), requestId, approved, run);
        // O decisor e a justificativa ficam no estado durável, gravados pelo motor sob o
        // mesmo lock da decisão — e a resposta os devolve de lá, não do pedido.
        return withDecision(snapshot, decided);
    }

    /**
     * O estado da execução do tenant que espera uma decisão humana, para receber dados de fora da
     * fila — o formulário de uma conversa suspensa (RCHFLW-67).
     *
     * <p>Só o portão de aprovação ({@code AWAITING_APPROVAL} com pedido vivo) leva dados à execução
     * ({@link ExecutionKeys#APPROVAL_PAYLOAD}); um fluxo apenas pausado volta pelo {@code /resume},
     * sem dados. O tenant é comparado estritamente, sem o "todos os tenants" da fila: quem envia o
     * formulário age na própria execução, não administra as dos outros.</p>
     *
     * @throws IllegalArgumentException se a execução não existe no tenant
     * @throws IllegalStateException    se ela não está esperando uma decisão ou o prazo dela venceu
     */
    public FlowState requireAwaitingInput(String tenantId, String flowId) {
        FlowState state = flowId == null || flowId.isBlank() ? null : stateManager.loadState(flowId);
        if (state == null || !br.com.archflow.model.tenant.DefaultTenant.orDefault(state.getTenantId())
                .equals(br.com.archflow.model.tenant.DefaultTenant.orDefault(tenantId))) {
            throw new IllegalArgumentException("Execução não encontrada neste tenant: " + flowId);
        }
        if (!ExecutionKeys.aguardaDecisaoHumana(state)) {
            throw new IllegalStateException("A execução " + flowId + " não está aguardando uma decisão "
                    + "humana (status " + state.getStatus() + "): só uma execução parada num nó de "
                    + "aprovação recebe os dados de um formulário");
        }
        if (isExpired(state)) {
            throw new IllegalStateException("A aprovação pendente da execução " + flowId + " expirou");
        }
        return state;
    }

    /**
     * Aprova o portão em que a execução espera, com {@code payload} como os dados da decisão, e devolve
     * a retomada — quem enviou o formulário precisa do desfecho para responder na conversa.
     *
     * <p>É o mesmo caminho de um {@code EDITED} da fila: o motor consome o pedido sob lock (um segundo
     * envio é recusado lá), grava o decisor e retoma; o registro da execução acompanha até o fim.</p>
     *
     * @throws IllegalArgumentException se a execução não existe no tenant
     * @throws IllegalStateException    se ela não espera decisão, expirou ou o motor recusou
     */
    public CompletableFuture<FlowResult> submitInput(String tenantId, String flowId, Object payload,
                                                     String responderId, String comment) {
        FlowState state = requireAwaitingInput(tenantId, flowId);
        String requestId = variable(state, ExecutionKeys.APPROVAL_REQUEST_ID);
        CompletableFuture<FlowResult> run;
        try {
            run = flowEngine.submitApproval(flowId, requestId, true, payload, responderId, comment);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "Failed to submit approval " + requestId + ": " + e.getMessage(), e);
        }
        log.info("Aprovação {} decidida por formulário por {} (flow={})", requestId, responderId, flowId);
        acompanharExecucao(flowId, requestId, true, run);
        return run;
    }

    /**
     * A entrada do histórico de aprovações ({@link ExecutionKeys#APPROVAL_HISTORY}) que o motor
     * gravou para este pedido, ou um mapa vazio quando o estado não a tem (motor falso, versão
     * anterior). É ela que responde quem decidiu: a resposta não repete o que o chamador mandou.
     */
    private Map<String, Object> decisionRecord(String flowId, String requestId) {
        try {
            FlowState decided = stateManager.loadState(flowId);
            return decided != null ? historyEntry(decided, requestId) : Map.of();
        } catch (RuntimeException e) {
            log.warn("Não foi possível ler a decisão gravada de {} (flow={}): {}",
                    requestId, flowId, e.getMessage());
        }
        return Map.of();
    }

    /** A entrada de {@code requestId} no histórico de aprovações do estado, ou um mapa vazio. */
    private static Map<String, Object> historyEntry(FlowState state, String requestId) {
        Object history = rawVariable(state, ExecutionKeys.APPROVAL_HISTORY);
        if (history instanceof java.util.Collection<?> entries) {
            for (Object entry : entries) {
                if (entry instanceof Map<?, ?> map && requestId.equals(String.valueOf(map.get("requestId")))) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> typed = (Map<String, Object>) map;
                    return typed;
                }
            }
        }
        return Map.of();
    }

    private static ApprovalResponse withDecision(ApprovalResponse r, Map<String, Object> decided) {
        return new ApprovalResponse(r.requestId(), r.tenantId(), r.flowId(), r.stepId(), r.status(),
                r.title(), r.description(), r.proposal(), r.createdAt(), r.expiresAt(),
                blankToNull(decided.get("decidedBy")), parseInstant(blankToNull(decided.get("decidedAt"))),
                blankToNull(decided.get("comment")), r.flowName(), decided.get("editedPayload"));
    }

    private static String blankToNull(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
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
        auditarDecisao(pendencia.requestId(), pendencia.runId(), pendencia.tenantId(), decision,
                request.responderId(), request.comment(), false);

        log.info("Aprovação {} de tool decidida como {} por {} (run={}, tool={})",
                pendencia.requestId(), decision, request.responderId(), pendencia.runId(),
                pendencia.toolName());
        // O decisor aqui é o que o servidor resolveu (o controller substitui o do corpo), mas o
        // store do laço ainda não o guarda: só este retorno e o log o carregam.
        return new ApprovalResponse(pendencia.requestId(), pendencia.tenantId(), pendencia.runId(),
                pendencia.toolName(), decision, null, "Execução da tool " + pendencia.toolName(),
                pendencia.argumentos(), pendencia.suspensoEm(), null,
                blankToNull(request.responderId()), clock.instant(), blankToNull(request.comment()),
                null, argumentos);
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
     * <p>O prazo é o do nó ({@link ExecutionKeys#APPROVAL_TIMEOUT}) quando ele tem um, senão o
     * global. O nó escolhe <i>quando</i> o pedido vence, nunca <i>o quê</i> acontece: um nó que
     * pedisse "aprovar no timeout" é ignorado — quem desenha o fluxo não desliga a governança.
     * Sem prazo global, só vencem os pedidos de nós com prazo próprio.</p>
     *
     * @return quantas foram expiradas
     */
    public int expireStale() {
        List<FlowState> expired = pendingStates().stream().filter(this::isExpired).toList();
        int resolved = 0;
        for (FlowState state : expired) {
            String requestId = variable(state, ExecutionKeys.APPROVAL_REQUEST_ID);
            if (requestId == null || requestId.isBlank()) {
                continue;
            }
            Duration prazo = effectiveTimeout(state);
            try {
                CompletableFuture<FlowResult> run = flowEngine.submitApproval(state.getFlowId(),
                        requestId, false, null, TIMEOUT_DECIDER, "expirada apos " + prazo);
                resolved++;
                log.info("Aprovação {} expirada após {} (flow={}) — recusada automaticamente",
                        requestId, prazo, state.getFlowId());
                auditarDecisao(requestId, state.getFlowId(), state.getTenantId(), "REJECTED", TIMEOUT_DECIDER,
                        "expirada apos " + prazo, true);
                acompanharExecucao(state.getFlowId(), requestId, false, run);
            } catch (RuntimeException e) {
                // Uma decisão humana concorrente já pode ter consumido o id;
                // não é erro, é corrida perdida. Segue para as demais.
                log.debug("Não foi possível expirar a aprovação {}: {}", requestId, e.getMessage());
            }
        }
        return resolved;
    }

    // ── helpers ──────────────────────────────────────────────────

    /**
     * Faz o registro da execução seguir a decisão (RCHFLW-40).
     *
     * <p>Aprovada, a execução volta a RUNNING e o registro acompanha a retomada até o status
     * terminal dela — inclusive PAUSED de novo, num segundo portão. O {@code markResumed} vem
     * antes de ligar o callback, e não antes de submeter: se a submissão falhasse o registro
     * ficaria RUNNING sem nada rodando; e o callback de uma retomada que já acabou roda na hora,
     * depois dele, então um fim rápido nunca é sobrescrito por RUNNING.</p>
     *
     * <p>Recusada (por alguém ou pelo prazo), o motor grava STOPPED e devolve uma future já
     * completa, sem evento de lifecycle: o registro é encerrado aqui, com o motivo lido do
     * estado durável — o mesmo lugar que responde quem decidiu.</p>
     *
     * <p>Quem disparou a execução também escreve o fim dela, e uma decisão rápida (cliente
     * automático) chega antes de a travessia original desenrolar: o callback de lá grava depois
     * desta escrita o PAUSED ou o CANCELLED que a travessia viu. É o store que o barra
     * ({@link WorkflowRuntimeStore#isStaleStatus}), e não cada chamador: são três pontos de
     * entrada, e um novo herdaria a corrida.</p>
     *
     * <p>A falha da retomada nunca é engolida: vai para o log como erro e para o registro como
     * FAILED (o log acontece mesmo sem registro configurado).</p>
     */
    private void acompanharExecucao(String flowId, String requestId, boolean approved,
                                    CompletableFuture<FlowResult> run) {
        if (!approved) {
            registrar(flowId, store -> {
                // O portão estava "aguardando" no detalhe da execução; recusado, ele não roda de
                // novo, então é aqui que o passo ganha o desfecho — senão ficaria aguardando para
                // sempre numa execução encerrada. Antes do completeExecution, que fecha o registro.
                gateRejected(store, flowId);
                // A recusa encerra a execução sem lifecycle do motor, então ninguém mais grava a
                // duração: ficava a do trecho até o portão (19 ms para 5 min de espera, RCHFLW-127).
                // Zero aqui é "sem travessia medida" — o store conta do startedAt até agora. Antes do
                // completeExecution: no store JDBC ele solta o lock da execução, e uma escrita depois
                // o recriaria para nunca mais sair.
                store.recordDuration(flowId, 0L);
                store.completeExecution(flowId, "STOPPED", motivoDoEncerramento(flowId));
            });
            encerrarTrace(flowId, () -> motivoDoEncerramento(flowId));
            avisarEncerramento(flowId);
            return;
        }
        registrar(flowId, store -> {
            gateDecided(store, flowId);
            store.markResumed(flowId);
        });
        if (run == null) {
            return;
        }
        run.whenComplete((result, err) -> {
            if (err != null) {
                log.error("Aprovação {} registrada, mas a retomada do fluxo {} falhou: {}",
                        requestId, flowId, errorOf(err), err);
                // Falha antes da travessia (preparo, limite de concorrência): ninguém fechou a pausa.
                // Falha dentro dela: a travessia já gravou o ERROR e isto não muda nada.
                encerrarTrace(flowId, () -> errorOf(err));
            }
            registrar(flowId, store -> store.completeExecution(flowId, statusOf(result, err), errorOf(err)));
        });
    }

    /**
     * Escreve no registro sem deixar a escrita decidir o resultado: quando ela roda, a decisão já
     * está gravada no estado do motor. Uma falha do store aqui viraria um erro para quem decidiu
     * — que então tentaria de novo uma decisão já tomada — ou sumiria dentro do callback.
     */
    private void registrar(String flowId, java.util.function.Consumer<WorkflowRuntimeStore> escrita) {
        if (executionRecords == null) {
            return;
        }
        try {
            escrita.accept(executionRecords);
        } catch (RuntimeException e) {
            log.error("Não foi possível atualizar o registro da execução {} após a decisão: {}",
                    flowId, e.getMessage(), e);
        }
    }

    /**
     * Por que a execução acabou sem rodar o resto: quem recusou e o comentário, ou o prazo.
     * Lido do estado que o motor acabou de gravar sob o lock da decisão, não do pedido — o
     * registro mostra o que o estado durável diz, e não uma segunda versão dele.
     */
    private String motivoDoEncerramento(String flowId) {
        FlowState decided = stateManager.loadState(flowId);
        String step = decided != null ? variable(decided, ExecutionKeys.APPROVAL_STEP_ID) : null;
        String title = decided != null ? variable(decided, ExecutionKeys.APPROVAL_TITLE) : null;
        String by = decided != null ? variable(decided, ExecutionKeys.APPROVAL_DECIDED_BY) : null;
        String comment = decided != null ? variable(decided, ExecutionKeys.APPROVAL_COMMENT) : null;
        // Em pt-BR, com o título do portão (o que o aprovador viu) e o id entre parênteses, e o prazo legível. Antes:
        // "Rejected at approval gate 'ap': no decision within PT2M (approval timeout)" na UI em pt-BR (RCHFLW-188,
        // item 18) — inglês, o id cru do passo e a duração ISO.
        String gate = nomeDoPortao(title, step);
        if (TIMEOUT_DECIDER.equals(by)) {
            return "Recusado na aprovação " + gate + ": ninguém decidiu em " + prazoLegivel(effectiveTimeout(decided))
                    + " (prazo da aprovação)";
        }
        StringBuilder motivo = new StringBuilder("Recusado na aprovação ").append(gate);
        if (by != null && !by.isBlank()) {
            motivo.append(" por ").append(by);
        }
        if (comment != null && !comment.isBlank()) {
            motivo.append(": ").append(comment);
        }
        return motivo.toString();
    }

    /** «Título» (id), «id» sem título, ou só "do fluxo" quando o estado não diz qual portão. */
    static String nomeDoPortao(String title, String step) {
        boolean temTitulo = title != null && !title.isBlank();
        boolean temPasso = step != null && !step.isBlank();
        if (temTitulo) {
            return "«" + title.trim() + "»" + (temPasso ? " (" + step + ")" : "");
        }
        return temPasso ? "«" + step + "»" : "do fluxo";
    }

    /** {@code PT2M} → "2 min", {@code PT1H30M} → "1 h 30 min", {@code PT45S} → "45 s", {@code P1D} → "24 h". */
    static String prazoLegivel(Duration prazo) {
        if (prazo == null) {
            return "o prazo";
        }
        long horas = prazo.toHours();
        int minutos = prazo.toMinutesPart();
        int segundos = prazo.toSecondsPart();
        List<String> partes = new java.util.ArrayList<>();
        if (horas > 0) {
            partes.add(horas + " h");
        }
        if (minutos > 0) {
            partes.add(minutos + " min");
        }
        if (segundos > 0 || partes.isEmpty()) {
            partes.add(segundos + " s");
        }
        return String.join(" ", partes);
    }

    /**
     * Marca o passo do portão como REJECTED no registro, com quem recusou (lido do estado durável,
     * como o motivo). Nunca lança: o desfecho da execução, gravado logo depois, importa mais.
     */
    private void gateRejected(WorkflowRuntimeStore store, String flowId) {
        try {
            FlowState decided = stateManager.loadState(flowId);
            String step = decided != null ? variable(decided, ExecutionKeys.APPROVAL_STEP_ID) : null;
            if (step == null || step.isBlank()) {
                return;
            }
            Map<String, Object> patch = new java.util.LinkedHashMap<>();
            patch.put("status", "REJECTED");
            patch.put("finishedAt", clock.instant().toString());
            String by = variable(decided, ExecutionKeys.APPROVAL_DECIDED_BY);
            if (by != null && !by.isBlank()) {
                patch.put("decidedBy", by);
            }
            store.recordStep(flowId, step, patch);
        } catch (RuntimeException e) {
            log.warn("Não foi possível marcar o portão recusado no registro de {}: {}", flowId, e.getMessage());
        }
    }

    /**
     * Grava no passo do portão aprovado quem aprovou (RCHFLW-188, item 17). Só o recusado ganhava o
     * {@code decidedBy} ({@link #gateRejected}): no detalhe da execução, "quem autorizou" ficava sem resposta
     * justamente quando o fluxo seguiu. Só o decisor: o status do passo é da retomada (RUNNING → COMPLETED). Nunca
     * lança, como o gateRejected.
     */
    private void gateDecided(WorkflowRuntimeStore store, String flowId) {
        try {
            FlowState decided = stateManager.loadState(flowId);
            String step = decided != null ? variable(decided, ExecutionKeys.APPROVAL_STEP_ID) : null;
            String by = decided != null ? variable(decided, ExecutionKeys.APPROVAL_DECIDED_BY) : null;
            if (step == null || step.isBlank() || by == null || by.isBlank()) {
                return;
            }
            store.recordStep(flowId, step, Map.of("decidedBy", by));
        } catch (RuntimeException e) {
            log.warn("Não foi possível gravar quem aprovou o portão no registro de {}: {}", flowId, e.getMessage());
        }
    }

    /** Mesma regra dos pontos de entrada (/execute, AG-UI, resume) para o status do registro. */
    private static String statusOf(FlowResult result, Throwable err) {
        if (err != null || result == null) {
            return "FAILED";
        }
        return result.getStatus() != null ? result.getStatus().name() : "COMPLETED";
    }

    /** A causa de fundo: a future embrulha em CompletionException e o motor em FlowEngineException. */
    private static String errorOf(Throwable err) {
        if (err == null) {
            return null;
        }
        Throwable cause = err;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.toString();
    }

    private List<FlowState> pendingStates() {
        return stateManager.findByStatus(FlowStatus.AWAITING_APPROVAL);
    }

    private Optional<FlowState> findPendingByRequestId(String requestId) {
        return pendingStates().stream()
                .filter(state -> requestId.equals(variable(state, ExecutionKeys.APPROVAL_REQUEST_ID)))
                .findFirst();
    }

    /**
     * Prazo da solicitação, ou {@code null} quando não há timeout (nem do nó nem global) ou
     * o estado não registra quando foi solicitada (fluxos suspensos por versões
     * anteriores). Sem instante de início não há como expirar com segurança.
     */
    private Instant expiresAt(FlowState state) {
        Duration prazo = effectiveTimeout(state);
        if (prazo.isZero()) {
            return null;
        }
        Instant requestedAt = parseInstant(variable(state, ExecutionKeys.APPROVAL_REQUESTED_AT));
        return requestedAt == null ? null : requestedAt.plus(prazo);
    }

    /**
     * O prazo deste pedido: o do nó, quando ele gravou um válido e positivo; senão o global
     * ({@link Duration#ZERO} = sem prazo). Um valor ilegível no estado não pode deixar o pedido
     * sem prazo nenhum — cai no global, como um nó sem prazo.
     */
    private Duration effectiveTimeout(FlowState state) {
        String own = state != null ? variable(state, ExecutionKeys.APPROVAL_TIMEOUT) : null;
        if (own != null && !own.isBlank()) {
            try {
                Duration parsed = Duration.parse(own.trim());
                if (!parsed.isNegative() && !parsed.isZero()) {
                    return parsed;
                }
            } catch (RuntimeException e) {
                log.warn("Prazo da aprovação ilegível no estado ({}); vale o global {}", own, timeout);
            }
        }
        return timeout;
    }

    /**
     * O prazo do pedido que a execução espera, ou {@code null} quando ela não espera decisão ou não
     * tem prazo. É a mesma regra da fila: o detalhe da execução não pode dizer "aguardando" de um
     * pedido que a fila já trata como vencido (RCHFLW-126).
     */
    public Instant deadlineOf(FlowState state) {
        return ExecutionKeys.aguardaDecisaoHumana(state) ? expiresAt(state) : null;
    }

    /** Se o pedido que a execução espera já venceu (e o sweeper ainda não o recusou). */
    public boolean isPendingExpired(FlowState state) {
        return ExecutionKeys.aguardaDecisaoHumana(state) && isExpired(state);
    }

    /**
     * Quanto falta para o próximo pedido pendente vencer, ou vazio quando nenhum vence no futuro.
     *
     * <p>O sweeper usa isto para rodar no vencimento, e não só no intervalo fixo: com 5 min de
     * intervalo, um pedido vencido ficava minutos fora da fila mas PENDING na API e "aguardando" no
     * detalhe da execução (RCHFLW-126). Só prazos futuros contam — um vencido que não pôde ser
     * recusado não pode pôr a varredura em laço.</p>
     */
    public Optional<Duration> untilNextExpiry() {
        Instant now = clock.instant();
        return pendingStates().stream()
                .map(this::expiresAt)
                .filter(Objects::nonNull)
                .filter(deadline -> deadline.isAfter(now))
                .min(Comparator.naturalOrder())
                .map(deadline -> Duration.between(now, deadline));
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
                // "" é o marcador de "limpo" (o portão anterior tinha, este não): não é um título.
                blankToNull(variable(state, ExecutionKeys.APPROVAL_TITLE)),
                variable(state, ExecutionKeys.APPROVAL_DESCRIPTION),
                rawVariable(state, ExecutionKeys.APPROVAL_PROPOSAL),
                parseInstant(variable(state, ExecutionKeys.APPROVAL_REQUESTED_AT)),
                // Nulo quando não há timeout configurado — o campo informa o
                // prazo real, não um valor inventado.
                expiresAt(state),
                null, null, null,
                flowNameOf(state.getTenantId(), state.getFlowId()), null);
    }

    /**
     * O nome do fluxo da execução, lido do registro de execuções. O estado do motor só conhece a
     * execução; a fila mostrava "exec-249a1515 · ap" onde a pessoa procura o fluxo (RCHFLW-129).
     * Nulo sem registro (motor sozinho) ou sem nome — a tela cai no id, nunca num nome inventado.
     */
    private String flowNameOf(String tenantId, String executionId) {
        if (executionRecords == null || executionId == null) {
            return null;
        }
        try {
            Map<String, Object> execution = executionRecords.getExecution(tenantId, executionId);
            return execution != null ? blankToNull(execution.get("workflowName")) : null;
        } catch (RuntimeException e) {
            log.debug("Nome do fluxo da execução {} indisponível: {}", executionId, e.getMessage());
            return null;
        }
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
