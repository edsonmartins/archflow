package br.com.archflow.api.web.admin;

import br.com.archflow.api.admin.observability.ObservabilityController;
import br.com.archflow.api.admin.observability.ObservabilityDtos.*;
import br.com.archflow.api.config.TenantScope;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * HTTP da observabilidade. O recorte de tenant de cada endpoint vem da identidade
 * ({@link TenantScope}, RCHFLW-39): sem {@code tenantId}, o tenant da requisição; {@code "all"} ou
 * outro tenant só para admin (403 sem o papel). Antes o parâmetro era opcional e a ausência valia
 * "todos os tenants" — qualquer usuário autenticado lia traces, auditoria e execuções de todos, e
 * cancelava a execução de outro tenant pulando a checagem de dono.
 *
 * <p>Com a auth desligada (dev), o {@code tenantId} segue como veio, e a ausência continua sendo
 * "todos" — o comportamento anterior.
 */
@RestController
@RequestMapping("/api/admin/observability")
public class SpringObservabilityController {

    private final ObservabilityController delegate;
    private final TenantScope tenantScope;

    public SpringObservabilityController(ObservabilityController delegate, TenantScope tenantScope) {
        this.delegate = delegate;
        this.tenantScope = tenantScope;
    }

    /**
     * O tenant que o serviço filtra: {@code null} é "todos" para ele, então o {@code "all"} do admin
     * vira {@code null} aqui (o serviço filtraria por um tenant chamado "all" e não acharia nada).
     */
    private String recorte(String tenantId) {
        return tenantScope.forItem(tenantId);
    }

    @GetMapping("/overview")
    public OverviewDto getOverview(@RequestParam(required = false) String tenantId) {
        return delegate.getOverview(recorte(tenantId));
    }

    @GetMapping("/traces")
    public PageDto<TraceSummaryDto> listTraces(
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var filter = new FilterDto(
                from != null ? Instant.parse(from) : null,
                to != null ? Instant.parse(to) : null,
                recorte(tenantId), null, status, null, null, search, page, size);
        return delegate.listTraces(filter);
    }

    /**
     * Um trace de outro tenant é 404, como um id desconhecido: "existe, mas não é seu" já diria a
     * quem não pode que o id vale. Com a auth desligada o {@code tenantId} não entra na busca, como
     * antes do parâmetro existir ({@link TenantScope#forItemLookup}).
     */
    @GetMapping("/traces/{id}")
    public ResponseEntity<TraceDetailDto> getTrace(@PathVariable String id,
                                                   @RequestParam(required = false) String tenantId) {
        String escopo = tenantScope.forItemLookup(tenantId);
        return delegate.getTrace(id)
                .filter(trace -> TenantScope.covers(escopo, trace.tenantId()))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * O snapshot não tem recorte por tenant: o {@code MetricsCollector} agrega a plataforma inteira,
     * inclusive contadores por nome de tool ({@code tool_calls_<nome>}). Conferir só o
     * {@code tenantId} e devolver o agregado entregaria a um usuário do tenant A os números e as tools
     * dos outros, então ele exige o papel admin (403 sem ele), como a visão "all". O
     * {@code tenantId} continua conferido antes, para que um pedido de outro tenant tenha o mesmo
     * 403 dos demais endpoints.
     */
    @GetMapping("/metrics")
    public MetricsSnapshotDto getMetrics(@RequestParam(required = false) String tenantId) {
        String escopo = recorte(tenantId);
        tenantScope.requirePlatformWide("The metrics snapshot");
        return delegate.getMetricsSnapshot(escopo);
    }

    @GetMapping("/metrics/series")
    public List<MetricSeriesDto> getMetricSeries(
            @RequestParam(required = false) String tenantId,
            @RequestParam String metric,
            @RequestParam(defaultValue = "60") int bucketSeconds,
            @RequestParam(defaultValue = "60") int buckets) {
        return delegate.getMetricSeries(recorte(tenantId), metric, bucketSeconds, buckets);
    }

    @GetMapping("/audit")
    public PageDto<AuditEntryDto> listAudit(
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var filter = new FilterDto(
                from != null ? Instant.parse(from) : null,
                to != null ? Instant.parse(to) : null,
                recorte(tenantId), null, null, null, action, search, page, size);
        return delegate.listAudit(filter);
    }

    @GetMapping("/audit/export")
    public String exportAuditCsv(
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        var filter = new FilterDto(
                from != null ? Instant.parse(from) : null,
                to != null ? Instant.parse(to) : null,
                recorte(tenantId), null, null, null, null, null, 0, Integer.MAX_VALUE);
        return delegate.exportAuditCsv(filter);
    }

    @GetMapping("/running")
    public List<RunningFlowDto> listRunningFlows(@RequestParam(required = false) String tenantId) {
        return delegate.listRunningFlows(recorte(tenantId));
    }

    /**
     * Cancela uma execução do recorte. O serviço trata {@code null} como "qualquer dono" — antes,
     * omitir o parâmetro bastava para cancelar a execução de outro tenant. Agora o recorte só é nulo
     * para o admin com "all" (ou com a auth desligada).
     *
     * <p>A execução de outro tenant e a que não está rodando têm a mesma resposta (404, mesma
     * mensagem): respostas diferentes revelariam quais ids de outros tenants estão em execução.</p>
     *
     * <p>Sucesso é 204: o cancelamento foi pedido ao motor e não há corpo a devolver. Era um 200 sem
     * corpo, que o cliente da UI não consegue ler como JSON — a tela tomava o sucesso por falha
     * (RCHFLW-72).</p>
     */
    @PostMapping("/running/{flowId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelFlow(@RequestParam(required = false) String tenantId,
                                                          @PathVariable String flowId) {
        String escopo = recorte(tenantId);
        try {
            delegate.cancelFlow(escopo, flowId);
        } catch (SecurityException | IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Flow not running: " + flowId, "status", 404));
        }
        return ResponseEntity.noContent().build();
    }
}
