package br.com.archflow.observability.health;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Archflow platform health check.
 *
 * <p>Framework-agnostic health indicator that reports uptime, memory usage
 * and component status. When running under Spring Boot, this can be
 * registered as a {@code HealthContributor} via a thin adapter in the
 * bootstrap module.
 */
public class ArchflowHealthIndicator {

    private static final Instant START_TIME = Instant.now();
    private static final long MEMORY_THRESHOLD_PERCENT = 90;

    public enum Status { UP, DOWN }

    public record HealthResult(Status status, Map<String, Object> details) {}

    public HealthResult health() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        long heapUsed = memoryBean.getHeapMemoryUsage().getUsed();
        long heapMax = memoryBean.getHeapMemoryUsage().getMax();
        long heapPercent = heapMax > 0 ? (heapUsed * 100) / heapMax : 0;

        Duration uptime = Duration.between(START_TIME, Instant.now());

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("uptime", formatDuration(uptime));
        details.put("uptimeSeconds", uptime.toSeconds());
        details.put("heapUsed", formatBytes(heapUsed));
        details.put("heapMax", formatBytes(heapMax));
        details.put("heapUsagePercent", heapPercent);
        details.put("processors", Runtime.getRuntime().availableProcessors());
        details.put("javaVersion", System.getProperty("java.version"));
        details.put("vmName", System.getProperty("java.vm.name"));

        Status status = heapPercent < MEMORY_THRESHOLD_PERCENT ? Status.UP : Status.DOWN;
        return new HealthResult(status, details);
    }

    private static String formatDuration(Duration duration) {
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        long seconds = duration.toSecondsPart();
        return String.format("%dh %dm %ds", hours, minutes, seconds);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
