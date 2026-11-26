package br.com.archflow.api.admin.observability.impl;

import br.com.archflow.agent.metrics.AggregatedMetrics;
import br.com.archflow.agent.metrics.MetricsCollector;
import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.agent.streaming.RunningFlowsRegistry;
import br.com.archflow.api.admin.observability.ObservabilityDtos.AuditEntryDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.RunningFlowDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.FilterDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.MetricSeriesDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.MetricStatsDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.MetricsSnapshotDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.OverviewDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.PageDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.PersonaStatDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.TraceDetailDto;
import br.com.archflow.api.admin.observability.ObservabilityDtos.TraceSummaryDto;
import br.com.archflow.api.audit.AuditTrail;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.exceptions.FlowException;
import br.com.archflow.engine.exceptions.FlowNotFoundException;
import br.com.archflow.observability.audit.AuditAction;
import br.com.archflow.observability.audit.AuditEvent;
import br.com.archflow.observability.audit.AuditRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Aggregator-only service that powers the admin observability surface.
 *
 * <p>This service owns zero state of its own — everything is delegated to
 * existing producers:
 * <ul>
 *   <li>{@link MetricsCollector} for counters, gauges and histograms</li>
 *   <li>{@link InMemoryTraceStore} for the trace rolling window</li>
 *   <li>{@link AuditRepository} for audit log queries</li>
 *   <li>{@link EventStreamRegistry} for active SSE emitter snapshots</li>
 * </ul>
 *
 * <p>The service is the single place in the codebase that translates
 * internal types into the public {@link ObservabilityDtos}, so every
 * framework binding (Spring WebFlux, Jetty, etc.) can reuse the same
 * logic.
 *
 * <p>All dependencies are optional — the service degrades gracefully to
 * empty responses when a collector is absent, so tests and Spring Boot
 * auto-configuration can wire components independently.
 */
public class ObservabilityService {

    private final MetricsCollector metricsCollector;
    private final InMemoryTraceStore traceStore;
    private final AuditRepository auditRepository;
    private final EventStreamRegistry eventStreamRegistry;
    private final RunningFlowsRegistry runningFlowsRegistry;
    /**
     * Quem para a execução de fato. Sem ele o cancelar do Running só tirava a linha da lista e o
     * fluxo seguia até o fim, com todos os efeitos (RCHFLW-72).
     */
    private final FlowEngine flowEngine;
    /** Define o "hoje" da Visão geral; o fuso do servidor, como o Workspace. */
    private final Clock clock;

    public ObservabilityService(
            MetricsCollector metricsCollector,
            InMemoryTraceStore traceStore,
            AuditRepository auditRepository,
            EventStreamRegistry eventStreamRegistry) {
        this(metricsCollector, traceStore, auditRepository, eventStreamRegistry, null);
    }

    public ObservabilityService(
            MetricsCollector metricsCollector,
            InMemoryTraceStore traceStore,
            AuditRepository auditRepository,
            EventStreamRegistry eventStreamRegistry,
            RunningFlowsRegistry runningFlowsRegistry) {
        this(metricsCollector, traceStore, auditRepository, eventStreamRegistry, runningFlowsRegistry, null);
    }

    public ObservabilityService(
            MetricsCollector metricsCollector,
            InMemoryTraceStore traceStore,
            AuditRepository auditRepository,
            EventStreamRegistry eventStreamRegistry,
            RunningFlowsRegistry runningFlowsRegistry,
            FlowEngine flowEngine) {
        this(metricsCollector, traceStore, auditRepository, eventStreamRegistry, runningFlowsRegistry, flowEngine,
                Clock.systemDefaultZone());
    }

    public ObservabilityService(
            MetricsCollector metricsCollector,
            InMemoryTraceStore traceStore,
            AuditRepository auditRepository,
            EventStreamRegistry eventStreamRegistry,
            RunningFlowsRegistry runningFlowsRegistry,
            FlowEngine flowEngine,
            Clock clock) {
        this.clock = clock != null ? clock : Clock.systemDefaultZone();
        this.metricsCollector = metricsCollector;
        this.traceStore = traceStore != null ? traceStore : new InMemoryTraceStore();
        this.auditRepository = auditRepository;
        this.eventStreamRegistry = eventStreamRegistry;
        this.runningFlowsRegistry = runningFlowsRegistry;
        this.flowEngine = flowEngine;
    }

    public InMemoryTraceStore traceStore() {
        return traceStore;
    }

    // ── Overview ──────────────────────────────────────────────────

    public OverviewDto getOverview(String tenantId) {
        // "Hoje" é o dia do fuso do servidor, o mesmo do Workspace (WorkspaceControllerImpl usa
        // Clock.systemDefaultZone()). Com UTC aqui, às 01:33 em -03:00 a Visão geral contava desde as
        // 21:00 de ontem e divergia do Workspace no mesmo instante (RCHFLW-195 item 5).
        ZoneId zona = clock.getZone();
        Instant startOfDay = LocalDate.now(clock).atStartOfDay(zona).toInstant();

        List<TraceSummaryDto> todayTraces = traceStore.query(
                tenantId, null, null, null, startOfDay, null);

        // Uma execução é um trace (o recorder reaproveita o da pausa na retomada). Pausada à espera de
        // aprovação não é sucesso nem erro: entra no total e fica fora das taxas, que são sobre as que
        // terminaram. Antes todo trace não-OK era erro e a pausa inflava a taxa de erro (RCHFLW-177).
        long total = todayTraces.size();
        long successes = todayTraces.stream().filter(t -> isStatus(t, TraceStoreRecorder.STATUS_OK)).count();
        long errors = todayTraces.stream().filter(t -> isStatus(t, TraceStoreRecorder.STATUS_ERROR)).count();
        long paused = todayTraces.stream().filter(t -> isStatus(t, TraceStoreRecorder.STATUS_PAUSED)).count();
        long finished = successes + errors;
        double successRate = finished == 0 ? 0d : (double) successes / finished;
        double errorRate = finished == 0 ? 0d : (double) errors / finished;

        double avgLatency = todayTraces.stream()
                .mapToLong(TraceSummaryDto::durationMs)
                .average()
                .orElse(0d);
        double p95 = percentile(todayTraces.stream()
                .map(TraceSummaryDto::durationMs)
                .sorted()
                .toList(), 0.95);

        List<PersonaStatDto> topPersonas = topPersonas(todayTraces, 5);
        List<Double> spark = latencySparkline(todayTraces, 12);

        int activeStreams = 0;
        if (eventStreamRegistry != null) {
            try {
                activeStreams = eventStreamRegistry.getStats().activeEmitters();
            } catch (Exception ignored) {
                activeStreams = 0;
            }
        }

        long auditCount = 0;
        if (auditRepository != null) {
            try {
                AuditRepository.AuditQuery hoje = AuditRepository.AuditQuery.builder()
                        .startTime(startOfDay)
                        .endTime(Instant.now());
                // O total do dia é do mesmo tenant dos traces ao lado, não da plataforma toda.
                if (tenantId != null && !tenantId.isBlank()) {
                    hoje.contextEquals(AuditTrail.CONTEXT_TENANT, tenantId);
                }
                auditCount = auditRepository.count(hoje);
            } catch (Exception ignored) {
                auditCount = 0;
            }
        }

        return new OverviewDto(
                total,
                successRate,
                avgLatency,
                p95,
                errorRate,
                activeStreams,
                auditCount,
                topPersonas,
                spark,
                paused);
    }

    private static boolean isStatus(TraceSummaryDto trace, String status) {
        return trace.status() != null && trace.status().equalsIgnoreCase(status);
    }

    // ── Traces ────────────────────────────────────────────────────

    public PageDto<TraceSummaryDto> listTraces(FilterDto filter) {
        FilterDto f = filter == null ? FilterDto.empty() : filter;
        List<TraceSummaryDto> all = traceStore.query(
                f.tenantId(),
                f.personaId(),
                f.status(),
                f.search(),
                f.from(),
                f.to());
        long total = all.size();
        int fromIdx = Math.min(f.page() * f.pageSize(), all.size());
        int toIdx = Math.min(fromIdx + f.pageSize(), all.size());
        List<TraceSummaryDto> page = all.subList(fromIdx, toIdx);
        return PageDto.of(new ArrayList<>(page), f.page(), f.pageSize(), total);
    }

    public Optional<TraceDetailDto> getTrace(String traceId) {
        return traceStore.findById(traceId);
    }

    // ── Metrics ───────────────────────────────────────────────────

    public MetricsSnapshotDto getMetricsSnapshot(String tenantId) {
        if (metricsCollector == null) {
            return new MetricsSnapshotDto(Instant.now(), Map.of(), Map.of(), Map.of());
        }
        AggregatedMetrics agg = metricsCollector.getAggregatedMetrics();
        Map<String, MetricStatsDto> stats = new HashMap<>();
        agg.stats().forEach((name, s) -> stats.put(
                name,
                new MetricStatsDto(s.count(), s.min(), s.max(), s.mean(), s.median())));

        return new MetricsSnapshotDto(
                agg.timestamp(),
                new HashMap<>(agg.counters()),
                new HashMap<>(agg.values()),
                stats);
    }

    /**
     * Build a synthetic time-bucketed series for a given metric by
     * sampling the latencies of recent traces. The current
     * {@link MetricsCollector} does not retain historical buckets, so
     * this projection is derived from {@link InMemoryTraceStore}.
     */
    public List<MetricSeriesDto> getMetricSeries(
            String tenantId, String metric, int bucketSeconds, int buckets) {
        if (buckets <= 0) buckets = 12;
        if (bucketSeconds <= 0) bucketSeconds = 300;
        Instant now = Instant.now();
        Instant windowStart = now.minusSeconds((long) buckets * bucketSeconds);

        List<TraceSummaryDto> traces = traceStore.query(
                tenantId, null, null, null, windowStart, null);

        List<Instant> bucketStarts = new ArrayList<>(buckets);
        List<Double> values = new ArrayList<>(buckets);
        for (int i = 0; i < buckets; i++) {
            Instant start = windowStart.plusSeconds((long) i * bucketSeconds);
            Instant end = start.plusSeconds(bucketSeconds);
            double bucketValue = aggregateBucket(metric, traces, start, end);
            bucketStarts.add(start);
            values.add(bucketValue);
        }
        return List.of(new MetricSeriesDto(metric == null ? "latency_ms" : metric, bucketStarts, values));
    }

    // ── Audit ─────────────────────────────────────────────────────

    public PageDto<AuditEntryDto> listAudit(FilterDto filter) {
        FilterDto f = filter == null ? FilterDto.empty() : filter;
        Optional<AuditRepository.AuditQuery> consulta = buildAuditQuery(f);
        if (auditRepository == null || consulta.isEmpty()) {
            return PageDto.of(List.of(), f.page(), f.pageSize(), 0);
        }
        AuditRepository.AuditQuery query = consulta.get();
        query.limit(f.pageSize());
        query.offset(f.page() * f.pageSize());
        List<AuditEvent> events = auditRepository.query(query);
        long total = auditRepository.count(buildAuditQuery(f).orElseThrow());
        List<AuditEntryDto> mapped = events.stream().map(this::toAuditDto).toList();
        return PageDto.of(mapped, f.page(), f.pageSize(), total);
    }

    /**
     * O CSV da auditoria com <b>os mesmos filtros da tela</b> e <b>todas</b> as linhas que batem.
     *
     * <p>Antes o handler só repassava tenant e período (ação, autor e busca ficavam de fora) e o
     * serviço cortava em 500 linhas sem aviso: com o filtro {@code LOGIN_SUCCESS} vinham 500 linhas de
     * todas as ações, e um arquivo incompleto passava por completo numa investigação (RCHFLW-180). Agora
     * lê a consulta da listagem página a página até acabar. O fim da janela fica preso no instante do
     * export: um evento gravado no meio da leitura deslocaria as páginas (ordem decrescente) e
     * repetiria linhas.</p>
     *
     * <p>Começa com o BOM UTF-8, para uma planilha em pt-BR abrir acentos certos, e traz o contexto
     * do evento (tenant, execução, nome) numa coluna — era o que identificava a quem o evento
     * pertencia (RCHFLW-164).</p>
     */
    public String exportAuditCsv(FilterDto filter) {
        FilterDto f = filter == null ? FilterDto.empty() : filter;
        FilterDto janela = new FilterDto(f.from(), f.to() != null ? f.to() : Instant.now(), f.tenantId(),
                f.personaId(), f.status(), f.actor(), f.action(), f.search(), 0, EXPORT_PAGE_SIZE);
        StringBuilder sb = new StringBuilder(CSV_BOM);
        sb.append("id,timestamp,action,userId,username,resourceType,resourceId,success,errorMessage,ipAddress,"
                + "context\n");
        if (auditRepository == null || buildAuditQuery(janela).isEmpty()) {
            return sb.toString();
        }
        int offset = 0;
        while (true) {
            AuditRepository.AuditQuery query = buildAuditQuery(janela).orElseThrow();
            query.limit(EXPORT_PAGE_SIZE);
            query.offset(offset);
            List<AuditEvent> events = auditRepository.query(query);
            for (AuditEvent event : events) {
                appendCsvRow(sb, toAuditDto(event));
            }
            if (events.size() < EXPORT_PAGE_SIZE) {
                break;
            }
            offset += events.size();
        }
        return sb.toString();
    }

    /** Linhas por consulta ao ler o export; o arquivo traz todas as páginas, este é só o passo. */
    static final int EXPORT_PAGE_SIZE = 500;

    /** BOM UTF-8: sem ele o Excel em pt-BR abre o CSV como ANSI e estraga os acentos. */
    static final String CSV_BOM = "\uFEFF";

    private static void appendCsvRow(StringBuilder sb, AuditEntryDto e) {
        sb.append(csv(e.id())).append(',')
                .append(csv(e.timestamp() == null ? "" : e.timestamp().toString())).append(',')
                .append(csv(e.action())).append(',')
                .append(csv(e.userId())).append(',')
                .append(csv(e.username())).append(',')
                .append(csv(e.resourceType())).append(',')
                .append(csv(e.resourceId())).append(',')
                .append(e.success()).append(',')
                .append(csv(e.errorMessage())).append(',')
                .append(csv(e.ipAddress())).append(',')
                .append(csv(contextoEmTexto(e.context()))).append('\n');
    }

    /** {@code chave=valor; ...} em ordem de chave, para o arquivo sair igual a cada export. */
    static String contextoEmTexto(Map<String, String> context) {
        if (context == null || context.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        new java.util.TreeMap<>(context).forEach((k, v) -> {
            if (out.length() > 0) {
                out.append("; ");
            }
            out.append(k).append('=').append(v == null ? "" : v);
        });
        return out.toString();
    }

    // ── Running flows ─────────────────────────────────────────────

    /**
     * Returns a snapshot of currently executing flows.
     *
     * @param tenantId optional tenant filter; null returns all tenants
     * @return list of running flow snapshots, empty when none
     */
    public List<RunningFlowDto> listRunningFlows(String tenantId) {
        if (runningFlowsRegistry == null) return List.of();

        List<RunningFlowsRegistry.ActiveFlow> flows = tenantId != null
                ? runningFlowsRegistry.snapshotForTenant(tenantId)
                : runningFlowsRegistry.snapshot();

        return flows.stream()
                .map(f -> new RunningFlowDto(
                        f.flowId(),
                        f.tenantId(),
                        f.startedAt(),
                        f.currentStepId(),
                        f.stepIndex(),
                        f.stepCount(),
                        f.durationMs()))
                .toList();
    }

    /**
     * Cancela no motor uma execução em andamento, depois de validar o dono.
     *
     * <p>O pedido vai ao {@link FlowEngine#cancel}, o mesmo de {@code POST /api/executions/{id}/cancel}:
     * o passo em curso termina (a interrupção é cooperativa), o resultado dele é descartado e nenhum
     * passo seguinte roda. A linha do Running não sai aqui: sai quando o fluxo para de fato, pelo
     * {@code RegistryFlowLifecycleListener}. Tirá-la antes era o defeito — a lista dizia "parado"
     * enquanto o fluxo seguia rodando, agora invisível (RCHFLW-72).</p>
     *
     * @param callerTenantId  authenticated caller's tenant
     * @param flowId          flow to cancel
     * @throws IllegalArgumentException if the flow is not currently running (no registry entry, or
     *                                  the engine no longer has it active)
     * @throws SecurityException        if the caller's tenant does not own the flow
     * @throws IllegalStateException    if this service was built without a {@link FlowEngine}
     */
    public void cancelFlow(String callerTenantId, String flowId) {
        if (runningFlowsRegistry == null) {
            throw new IllegalArgumentException("Flow not running: " + flowId);
        }

        var active = runningFlowsRegistry.find(flowId)
                .orElseThrow(() -> new IllegalArgumentException("Flow not running: " + flowId));

        // Cross-tenant protection: only the owning tenant (or superadmin with null) may cancel
        if (callerTenantId != null && !callerTenantId.equals(active.tenantId())) {
            throw new SecurityException("Tenant " + callerTenantId
                    + " is not authorized to cancel flow " + flowId
                    + " owned by " + active.tenantId());
        }

        // Sem motor não há como parar: responder 200 seria repetir o defeito (a tela diria "parado"
        // e o fluxo seguiria). Falha explícita, que a tela mostra como erro.
        if (flowEngine == null) {
            throw new IllegalStateException("Cancelamento indisponível: a observabilidade não tem um FlowEngine");
        }
        try {
            flowEngine.cancel(flowId);
        } catch (FlowException e) {
            // O registro ainda listava a execução, mas o motor já não a tem ativa: terminou entre a
            // leitura e o cancelar. Para quem chamou é "não está rodando" — o mesmo 404. O
            // DefaultFlowEngine embrulha o FlowNotFoundException; outro motor pode lançá-lo direto.
            if (causedByNotFound(e)) {
                throw new IllegalArgumentException("Flow not running: " + flowId, e);
            }
            throw e;
        }
    }

    private static boolean causedByNotFound(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof FlowNotFoundException) {
                return true;
            }
        }
        return false;
    }

    // ── helpers ───────────────────────────────────────────────────

    /**
     * A consulta da listagem e do export — uma só, para os dois não divergirem (RCHFLW-180). Vazio
     * quando o filtro não pode casar com nada (uma ação que não existe).
     */
    private Optional<AuditRepository.AuditQuery> buildAuditQuery(FilterDto f) {
        AuditRepository.AuditQuery q = AuditRepository.AuditQuery.builder()
                .sortBy("timestamp")
                .sortDescending(true);
        if (f.from() != null) q.startTime(f.from());
        if (f.to() != null) q.endTime(f.to());
        // O autor casa com o id ou com o username, que é o que a tela mostra (RCHFLW-195 item 10).
        if (f.actor() != null && !f.actor().isBlank()) q.actor(f.actor().trim());
        // O FilterDto sempre trouxe tenantId, mas a consulta o ignorava: a auditoria e o export de
        // qualquer tenant listavam os eventos de todos (RCHFLW-39). O tenant está no contexto do
        // evento, gravado pelo AuditTrail; nulo continua sendo "todos" (admin com "all").
        if (f.tenantId() != null && !f.tenantId().isBlank()) {
            q.contextEquals(AuditTrail.CONTEXT_TENANT, f.tenantId());
        }
        if (f.action() != null && !f.action().isBlank()) {
            Optional<AuditAction> action = parseAction(f.action().trim());
            if (action.isEmpty()) {
                // Uma ação que não existe não casa com nada. Antes o filtro era só ignorado — o
                // comentário dizia "nada", mas a consulta voltava todas as ações como se filtradas.
                return Optional.empty();
            }
            q.action(action.get());
        }
        return Optional.of(q);
    }

    /** O nome do enum ({@code LOGIN_SUCCESS}, o que a tela mostra) ou o código ({@code login.success}). */
    private static Optional<AuditAction> parseAction(String value) {
        for (AuditAction a : AuditAction.values()) {
            if (a.name().equalsIgnoreCase(value) || a.getCode().equalsIgnoreCase(value)) {
                return Optional.of(a);
            }
        }
        return Optional.empty();
    }

    private AuditEntryDto toAuditDto(AuditEvent e) {
        Map<String, String> ctx = e.getContext() == null ? new HashMap<>() : new HashMap<>(e.getContext());
        return new AuditEntryDto(
                e.getId(),
                e.getTimestamp(),
                e.getAction() == null ? null : e.getAction().name(),
                e.getUserId(),
                e.getUsername(),
                e.getResourceType(),
                e.getResourceId(),
                e.isSuccess(),
                e.getErrorMessage(),
                ctx,
                e.getIpAddress(),
                e.getSessionId(),
                e.getTraceId());
    }

    private static double percentile(List<Long> sortedValues, double percentile) {
        if (sortedValues.isEmpty()) return 0d;
        int idx = (int) Math.ceil(percentile * sortedValues.size()) - 1;
        idx = Math.max(0, Math.min(idx, sortedValues.size() - 1));
        return sortedValues.get(idx).doubleValue();
    }

    private static List<PersonaStatDto> topPersonas(List<TraceSummaryDto> traces, int limit) {
        Map<String, long[]> stats = new HashMap<>(); // [count, successes]
        for (TraceSummaryDto t : traces) {
            if (t.personaId() == null) continue;
            long[] s = stats.computeIfAbsent(t.personaId(), k -> new long[]{0, 0});
            s[0]++;
            if ("OK".equalsIgnoreCase(t.status())) s[1]++;
        }
        List<PersonaStatDto> out = new ArrayList<>();
        for (Map.Entry<String, long[]> e : stats.entrySet()) {
            long count = e.getValue()[0];
            long ok = e.getValue()[1];
            double rate = count == 0 ? 0d : (double) ok / count;
            out.add(new PersonaStatDto(e.getKey(), count, rate));
        }
        out.sort(Comparator.comparingLong(PersonaStatDto::executionCount).reversed());
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    private static List<Double> latencySparkline(List<TraceSummaryDto> traces, int buckets) {
        if (traces.isEmpty()) {
            return Collections.nCopies(buckets, 0d);
        }
        List<Long> recent = traces.stream()
                .sorted(Comparator.comparing(TraceSummaryDto::startedAt))
                .map(TraceSummaryDto::durationMs)
                .toList();
        int bucketSize = Math.max(1, recent.size() / buckets);
        List<Double> out = new ArrayList<>(buckets);
        for (int i = 0; i < buckets; i++) {
            int fromIdx = i * bucketSize;
            int toIdx = Math.min(fromIdx + bucketSize, recent.size());
            if (fromIdx >= recent.size()) {
                out.add(0d);
                continue;
            }
            double avg = recent.subList(fromIdx, toIdx).stream()
                    .mapToLong(Long::longValue)
                    .average()
                    .orElse(0d);
            out.add(avg);
        }
        return out;
    }

    private static double aggregateBucket(
            String metric, List<TraceSummaryDto> traces, Instant from, Instant to) {
        String key = metric == null ? "latency_ms" : metric.toLowerCase();
        List<TraceSummaryDto> inBucket = traces.stream()
                .filter(t -> !t.startedAt().isBefore(from) && t.startedAt().isBefore(to))
                .toList();
        if (inBucket.isEmpty()) return 0d;
        return switch (key) {
            case "throughput", "count" -> inBucket.size();
            case "error_rate" -> {
                // Mesma regra da Visão geral: a pausa à espera de aprovação não é erro nem entra no
                // denominador (RCHFLW-177).
                long errs = inBucket.stream().filter(t -> isStatus(t, TraceStoreRecorder.STATUS_ERROR)).count();
                long done = inBucket.stream().filter(t -> !isStatus(t, TraceStoreRecorder.STATUS_PAUSED)).count();
                yield done == 0 ? 0d : (double) errs / done;
            }
            default -> inBucket.stream()
                    .mapToLong(TraceSummaryDto::durationMs)
                    .average()
                    .orElse(0d);
        };
    }

    private static String csv(String value) {
        if (value == null) return "";
        boolean needsQuotes = value.contains(",") || value.contains("\"") || value.contains("\n");
        if (!needsQuotes) return value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
