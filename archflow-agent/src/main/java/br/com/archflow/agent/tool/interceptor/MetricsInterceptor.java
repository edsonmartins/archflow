package br.com.archflow.agent.tool.interceptor;

import br.com.archflow.agent.tool.ToolContext;
import br.com.archflow.agent.tool.ToolInterceptor;
import br.com.archflow.agent.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Interceptor que coleta métricas de execução de tools.
 *
 * <p>Métricas coletadas:
 * <ul>
 *   <li>Contador de execuções por tool (sucesso/erro)</li>
 *   <li>Tempo de execução por tool</li>
 *   <li>Tempo de execução por status</li>
 * </ul>
 *
 * <p>Nota: Esta versão é simplificada e não depende de Micrometer.
 * Para métricas completas com Micrometer, adicione a dependência ao projeto.
 */
public class MetricsInterceptor implements ToolInterceptor {

    private static final Logger log = LoggerFactory.getLogger(MetricsInterceptor.class);

    private final MetricsCollector collector;

    public MetricsInterceptor() {
        this.collector = new SimpleMetricsCollector();
    }

    @Override
    public void beforeExecute(ToolContext context) {
        // Marca o início da medição no contexto
        context.setAttribute("_metrics.startTime", System.nanoTime());
    }

    @Override
    public ToolResult afterExecute(ToolContext context, ToolResult result) {
        Long startTime = context.getAttribute("_metrics.startTime");
        if (startTime == null) {
            return result;
        }

        String toolName = context.getToolName();
        long durationNanos = System.nanoTime() - startTime;
        String status = result.getStatus().name().toLowerCase();

        // Registra métricas
        collector.recordExecution(toolName, status, durationNanos);

        log.trace("[{}] Métricas registradas para tool {}: status={}, duration={}ms",
                context.getExecutionId(), toolName, status,
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(durationNanos));

        return result;
    }

    @Override
    public void onError(ToolContext context, Throwable error) {
        Long startTime = context.getAttribute("_metrics.startTime");
        if (startTime == null) {
            return;
        }

        String toolName = context.getToolName();
        long durationNanos = System.nanoTime() - startTime;

        // Registra métricas de erro
        collector.recordExecution(toolName, "error", durationNanos);

        log.trace("[{}] Métricas de erro registradas para tool {}: duration={}ms",
                context.getExecutionId(), toolName,
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(durationNanos));
    }

    @Override
    public int order() {
        // Métricas devem ser coletadas após logging
        return Integer.MIN_VALUE + 200;
    }

    @Override
    public String getName() {
        return "MetricsInterceptor";
    }

    public MetricsCollector getCollector() {
        return collector;
    }

    /**
     * Interface para coletor de métricas.
     */
    public interface MetricsCollector {
        void recordExecution(String toolName, String status, long durationNanos);
        MetricsSnapshot getSnapshot();
    }

    /**
     * Implementação simples de coletor de métricas (em memória).
     */
    public static class SimpleMetricsCollector implements MetricsCollector {
        private final java.util.concurrent.ConcurrentHashMap<String, ToolMetrics> metrics = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public void recordExecution(String toolName, String status, long durationNanos) {
            String key = toolName + ":" + status;
            ToolMetrics toolMetrics = metrics.computeIfAbsent(key,
                    k -> new ToolMetrics(toolName, status));
            toolMetrics.record(durationNanos);
        }

        @Override
        public MetricsSnapshot getSnapshot() {
            return new MetricsSnapshot(new java.util.ArrayList<>(metrics.values()));
        }

        public void clear() {
            metrics.clear();
        }
    }

    /**
     * Métricas de uma tool específica.
     */
    public static class ToolMetrics {
        private final String toolName;
        private final String status;
        private final java.util.concurrent.atomic.AtomicLong count = new java.util.concurrent.atomic.AtomicLong(0);
        private final java.util.concurrent.atomic.AtomicLong totalDurationNanos = new java.util.concurrent.atomic.AtomicLong(0);
        private volatile long minDurationNanos = Long.MAX_VALUE;
        private volatile long maxDurationNanos = 0;

        public ToolMetrics(String toolName, String status) {
            this.toolName = toolName;
            this.status = status;
        }

        public void record(long durationNanos) {
            count.incrementAndGet();
            totalDurationNanos.addAndGet(durationNanos);

            // Atualiza min/max de forma simples (não é thread-perfect, mas suficiente para stats)
            if (durationNanos < minDurationNanos) {
                minDurationNanos = durationNanos;
            }
            if (durationNanos > maxDurationNanos) {
                maxDurationNanos = durationNanos;
            }
        }

        public String getToolName() {
            return toolName;
        }

        public String getStatus() {
            return status;
        }

        public long getCount() {
            return count.get();
        }

        public double getAverageDurationMillis() {
            long count = this.count.get();
            if (count == 0) {
                return 0;
            }
            return (totalDurationNanos.get() / count) / 1_000_000.0;
        }

        public long getMinDurationMillis() {
            return minDurationNanos == Long.MAX_VALUE ? 0 : minDurationNanos / 1_000_000;
        }

        public long getMaxDurationMillis() {
            return maxDurationNanos / 1_000_000;
        }
    }

    /**
     * Snapshot das métricas coletadas.
     */
    public record MetricsSnapshot(java.util.List<ToolMetrics> tools) {
        public long getTotalExecutions() {
            return tools.stream().mapToLong(ToolMetrics::getCount).sum();
        }

        public java.util.List<ToolMetrics> getTools() {
            return tools;
        }
    }

    public static MetricsInterceptor create() {
        return new MetricsInterceptor();
    }
}
