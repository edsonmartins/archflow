package br.com.archflow.observability.health;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.time.Duration;
import java.time.Instant;

/**
 * Custom health indicator for archflow platform.
 *
 * <p>Reports health status including:
 * <ul>
 *   <li>Uptime</li>
 *   <li>Memory usage</li>
 *   <li>Component status</li>
 * </ul>
 */
@Component
public class ArchflowHealthIndicator implements HealthIndicator {

    private static final Instant START_TIME = Instant.now();
    private static final long MEMORY_THRESHOLD_PERCENT = 90;

    @Override
    public Health health() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        long heapUsed = memoryBean.getHeapMemoryUsage().getUsed();
        long heapMax = memoryBean.getHeapMemoryUsage().getMax();
        long heapPercent = heapMax > 0 ? (heapUsed * 100) / heapMax : 0;

        Duration uptime = Duration.between(START_TIME, Instant.now());

        Health.Builder builder = heapPercent < MEMORY_THRESHOLD_PERCENT
                ? Health.up()
                : Health.down();

        return builder
                .withDetail("version", "1.0.0")
                .withDetail("uptime", formatDuration(uptime))
                .withDetail("uptimeSeconds", uptime.getSeconds())
                .withDetail("heap.used.mb", heapUsed / (1024 * 1024))
                .withDetail("heap.max.mb", heapMax / (1024 * 1024))
                .withDetail("heap.percent", heapPercent)
                .withDetail("processors", Runtime.getRuntime().availableProcessors())
                .build();
    }

    private String formatDuration(Duration duration) {
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        long seconds = duration.toSecondsPart();
        return String.format("%dh %dm %ds", hours, minutes, seconds);
    }
}
