package br.com.archflow.observability.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ArchflowHealthIndicator")
class ArchflowHealthIndicatorTest {

    @Test
    @DisplayName("should report UP status under normal conditions")
    void shouldReportUpStatus() {
        ArchflowHealthIndicator indicator = new ArchflowHealthIndicator();
        ArchflowHealthIndicator.HealthResult health = indicator.health();

        assertEquals(ArchflowHealthIndicator.Status.UP, health.status());
    }

    @Test
    @DisplayName("should include uptime in health details")
    void shouldIncludeUptime() {
        ArchflowHealthIndicator indicator = new ArchflowHealthIndicator();
        var health = indicator.health();

        assertNotNull(health.details().get("uptime"));
        assertNotNull(health.details().get("uptimeSeconds"));
    }

    @Test
    @DisplayName("should include memory info in health details")
    void shouldIncludeMemoryInfo() {
        ArchflowHealthIndicator indicator = new ArchflowHealthIndicator();
        var health = indicator.health();

        assertNotNull(health.details().get("heapUsed"));
        assertNotNull(health.details().get("heapMax"));
        assertNotNull(health.details().get("heapUsagePercent"));
    }

    @Test
    @DisplayName("should include processor count")
    void shouldIncludeProcessorCount() {
        ArchflowHealthIndicator indicator = new ArchflowHealthIndicator();
        var health = indicator.health();

        int processors = (int) health.details().get("processors");
        assertTrue(processors > 0);
    }

    @Test
    @DisplayName("should include Java version")
    void shouldIncludeJavaVersion() {
        ArchflowHealthIndicator indicator = new ArchflowHealthIndicator();
        var health = indicator.health();

        assertNotNull(health.details().get("javaVersion"));
    }
}
