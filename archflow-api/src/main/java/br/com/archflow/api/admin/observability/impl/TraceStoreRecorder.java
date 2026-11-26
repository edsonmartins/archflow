package br.com.archflow.api.admin.observability.impl;

import br.com.archflow.api.admin.observability.ObservabilityDtos.SpanDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.TraceDetailDto;
import br.com.archflow.engine.core.TraceRecorder;
import br.com.archflow.model.enums.ExecutionStatus;
import br.com.archflow.model.error.ExecutionError;
import br.com.archflow.model.flow.FlowResult;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Adapter that bridges {@link TraceRecorder} (archflow-core) to
 * {@link InMemoryTraceStore} (archflow-api). Registered as a bean and
 * passed to the engine by {@code ArchflowBeanConfiguration}, so every
 * flow execution — successful or failed — produces a trace. It is the
 * store's only writer: without it the observability surface is empty.
 *
 * <p>Each flow start/end pair produces a single {@link TraceDetailDto}
 * with one root span. Step-level spans require deeper integration with
 * the {@code FlowExecutor} — this recorder captures the flow-level
 * envelope which is sufficient for the observability overview, traces
 * list, and timeline.
 */
public class TraceStoreRecorder implements TraceRecorder {

    /** Terminou com sucesso. */
    public static final String STATUS_OK = "OK";
    /** Falhou, foi cancelada ou terminou sem resultado. */
    public static final String STATUS_ERROR = "ERROR";
    /**
     * Parada à espera de uma decisão humana (portão de aprovação). Não é erro: antes todo status
     * diferente de COMPLETED virava ERROR, e cada fluxo com aprovação inflava a taxa de erro da Visão
     * geral (RCHFLW-177).
     */
    public static final String STATUS_PAUSED = "PAUSED";

    /** Atributo com o nome do fluxo, para a busca da tela de traces achar pelo nome (RCHFLW-195 item 6). */
    public static final String ATTR_FLOW_NAME = "flow.name";

    private final InMemoryTraceStore store;
    /**
     * O registro da execução no runtime store ({@code (tenant, executionId) → execução}). O motor só
     * conhece o id da execução — {@code SpringWorkflowCrudController.execute} troca o id do fluxo por
     * ele —, então sem esta consulta o trace guardava o id da execução como "fluxo" e a busca pelo nome
     * ou pelo id do fluxo não achava nada. Ausente (testes, execuções fora do runtime store): fica como
     * antes.
     */
    private final BiFunction<String, String, Map<String, Object>> execucoes;
    /** Serializa procurar-e-substituir o trace de uma execução (ver {@link #onFlowEnd}). */
    private final Object lock = new Object();
    /**
     * Execuções encerradas (recusa, prazo, cancelamento) antes de o trace da pausa existir: a decisão
     * chegou enquanto a travessia suspensa desenrolava. A pausa que chegar depois já entra encerrada.
     * Limitado — uma anotação cuja pausa nunca chega (trace descartado da janela, servidor reiniciado
     * com a aprovação durável) não pode crescer sem fim. Só acessado sob {@link #lock}.
     */
    private final Map<String, String> encerradasSemTrace = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_ENCERRADAS_SEM_TRACE;
        }
    };
    private static final int MAX_ENCERRADAS_SEM_TRACE = 1024;

    public TraceStoreRecorder(InMemoryTraceStore store) {
        this(store, null);
    }

    public TraceStoreRecorder(InMemoryTraceStore store, BiFunction<String, String, Map<String, Object>> execucoes) {
        this.store = store;
        this.execucoes = execucoes;
    }

    @Override
    public void onFlowStart(String flowId, String tenantId, String personaId) {
        // No-op — we record on completion when we have the full result.
        // A future improvement could record a RUNNING trace here and update
        // it on completion, enabling live trace visibility.
    }

    /**
     * Um trace por execução. A execução que para num portão de aprovação passa por aqui duas vezes —
     * na suspensão e no fim da retomada, ambas com o mesmo id —, e antes cada passagem virava um trace:
     * um ERROR de poucos ms na pausa e um OK no fim, e a Visão geral contava a execução duas vezes. Agora
     * a suspensão grava um trace PAUSED e a retomada o <b>substitui</b> (mesmo traceId e início), com a
     * duração das duas travessias somada — o tempo de processamento, não a espera pelo humano, que
     * distorceria a latência.
     *
     * <p>A ordem das duas passagens não é garantida: o motor aceita a decisão enquanto a travessia
     * suspensa ainda desenrola, e a retomada pode terminar antes dela. Uma pausa que chega depois do
     * desfecho da mesma execução (começou antes dele) é a parte anterior da execução: entra no trace do
     * desfecho, que continua com o status final. Procurar e substituir acontece sob {@link #lock} —
     * fora dele, duas passagens simultâneas criariam dois traces.</p>
     */
    @Override
    public void onFlowEnd(String flowId, String tenantId, FlowResult result, long durationMs) {
        // O engine chama este hook também no caminho de exceção, com um resultado
        // sintético; um resultado ausente é tratado como erro, nunca como sucesso.
        ExecutionStatus execStatus = result != null ? result.getStatus() : null;
        String status = execStatus == ExecutionStatus.COMPLETED ? STATUS_OK
                : execStatus == ExecutionStatus.PAUSED ? STATUS_PAUSED
                : STATUS_ERROR;
        List<ExecutionError> errors = result != null && result.getErrors() != null
                ? result.getErrors()
                : List.of();
        String error = errors.isEmpty() ? null : errors.get(0).message();

        String workflowId = flowId;
        Map<String, String> attrs = new HashMap<>();
        attrs.put("flow.id", flowId);
        if (tenantId != null) attrs.put("tenant.id", tenantId);
        // Fora do lock: a consulta vai ao runtime store (JDBC em produção).
        Map<String, Object> execucao = execucao(tenantId, flowId);
        if (execucao != null) {
            Object wf = execucao.get("workflowId");
            if (wf != null && !String.valueOf(wf).isBlank()) {
                workflowId = String.valueOf(wf);
                attrs.put("flow.id", workflowId);
                attrs.put("execution.id", flowId);
            }
            Object nome = execucao.get("workflowName");
            if (nome != null && !String.valueOf(nome).isBlank()) {
                attrs.put(ATTR_FLOW_NAME, String.valueOf(nome));
            }
        }

        synchronized (lock) {
            Instant inicio = Instant.now().minusMillis(durationMs);
            Optional<TraceDetailDto> anterior = store.findLatestByExecutionId(tenantId, flowId);
            String traceId = "trace-" + UUID.randomUUID().toString().substring(0, 12);
            long duracao = durationMs;
            Instant startedAt = inicio;
            if (anterior.isPresent() && STATUS_PAUSED.equals(anterior.get().status())) {
                // Retomada (ou nova pausa num segundo portão): substitui a pausa, com o início dela.
                traceId = anterior.get().traceId();
                duracao += anterior.get().durationMs();
                startedAt = anterior.get().startedAt();
            } else if (anterior.isPresent() && STATUS_PAUSED.equals(status)
                    && !inicio.isAfter(anterior.get().startedAt())) {
                // Suspensão atrasada: começou antes do desfecho já gravado, é a mesma execução.
                traceId = anterior.get().traceId();
                duracao += anterior.get().durationMs();
                status = anterior.get().status();
                error = anterior.get().error();
            } else {
                // Decidida (recusada) antes de a suspensão chegar aqui: a pausa já não é verdade.
                String motivo = encerradasSemTrace.remove(chave(tenantId, flowId));
                if (motivo != null) {
                    if (STATUS_PAUSED.equals(status)) {
                        status = STATUS_ERROR;
                    }
                    if (error == null && !motivo.isBlank()) {
                        error = motivo;
                    }
                }
            }
            store.record(montar(traceId, tenantId, workflowId, flowId, startedAt, duracao, status, error, attrs));
        }
    }

    /**
     * Encerra o trace de uma execução que estava parada num portão e terminou <b>sem nova travessia</b>:
     * recusada por alguém, recusada pelo prazo, cancelada, ou aprovada mas com a retomada falhando antes
     * de começar. Nesses caminhos o motor só grava o estado e não chama {@link #onFlowEnd}, e o trace
     * ficava "Aguardando aprovação" para sempre — a Visão geral contava a execução como aguardando e ela
     * nunca entrava nas taxas. O status é {@link #STATUS_ERROR}, o mesmo que cancelamento e término sem
     * resultado já recebem aqui; o motivo vai em {@code error}.
     *
     * <p>Só mexe numa pausa. Se o trace da execução já tem desfecho (a retomada falhou dentro da
     * travessia e ela mesma gravou o ERROR), nada muda. Se ainda não há trace — a decisão chegou
     * enquanto a travessia suspensa desenrolava —, o encerramento fica anotado e a pausa, quando chegar,
     * já entra encerrada.</p>
     */
    public void encerrarPausa(String tenantId, String executionId, String motivo) {
        if (executionId == null) {
            return;
        }
        synchronized (lock) {
            Optional<TraceDetailDto> anterior = store.findLatestByExecutionId(tenantId, executionId);
            if (anterior.isEmpty()) {
                encerradasSemTrace.put(chave(tenantId, executionId), motivo == null ? "" : motivo);
                return;
            }
            TraceDetailDto pausa = anterior.get();
            if (!STATUS_PAUSED.equals(pausa.status())) {
                return;
            }
            store.record(montar(pausa.traceId(), pausa.tenantId(), pausa.flowId(), pausa.executionId(),
                    pausa.startedAt(), pausa.durationMs(), STATUS_ERROR,
                    motivo == null || motivo.isBlank() ? null : motivo,
                    pausa.attributes() == null ? Map.of() : pausa.attributes()));
        }
    }

    private static TraceDetailDto montar(String traceId, String tenantId, String workflowId, String executionId,
                                         Instant startedAt, long duracao, String status, String error,
                                         Map<String, String> attrs) {
        SpanDto rootSpan = InMemoryTraceStore.span(
                "span-" + UUID.randomUUID().toString().substring(0, 8),
                null,
                "flow.execute",
                "INTERNAL",
                startedAt,
                duracao,
                status,
                attrs,
                List.of());
        return InMemoryTraceStore.trace(
                traceId,
                tenantId,
                null,
                workflowId,
                executionId,
                startedAt,
                duracao,
                status,
                error,
                attrs,
                List.of(rootSpan));
    }

    private static String chave(String tenantId, String executionId) {
        return (tenantId == null ? "" : tenantId) + "|" + executionId;
    }

    private Map<String, Object> execucao(String tenantId, String executionId) {
        if (execucoes == null || executionId == null) {
            return null;
        }
        try {
            return execucoes.apply(tenantId, executionId);
        } catch (RuntimeException e) {
            // O trace não pode falhar por causa do enriquecimento: sem o registro, fica o id da execução.
            return null;
        }
    }
}
