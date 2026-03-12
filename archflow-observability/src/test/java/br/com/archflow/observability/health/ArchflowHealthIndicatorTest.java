package br.com.archflow.observability.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ArchflowHealthIndicator")
class ArchflowHealthIndicatorTest {

    @Test
    @DisplayName("should report UP status under normal conditions")
    void shouldReportUpStatus() {
        ArchflowHealthIndicator indicator = new ArchflowHealthIndicator();
        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
    }

    @Test
    @DisplayName("should include version in health details")
    void shouldIncludeVersion() {
        ArchflowHealthIndicator indicator = new ArchflowHealthIndicator();
        Health health = indicator.health();

        assertEquals("1.0.0", health.getDetails().get("version"));
    }

    @Test
    @DisplayName("should include uptime in health details")
    void shouldIncludeUptime() {
        ArchflowHealthIndicator indicator = new ArchflowHealthIndicator();
        Health health = indicator.health();

        assertNotNull(health.getDetails().get("uptime"));
        assertNotNull(health.getDetails().get("uptimeSeconds"));
    }

    @Test
    @DisplayName("should include memory info in health details")
    void shouldIncludeMemoryInfo() {
        ArchflowHealthIndicator indicator = new ArchflowHealthIndicator();
        Health health = indicator.health();

        assertNotNull(health.getDetails().get("heap.used.mb"));
        assertNotNull(health.getDetails().get("heap.max.mb"));
        assertNotNull(health.getDetails().get("heap.percent"));
    }

    @Test
    @DisplayName("should include processor count")
    void shouldIncludeProcessorCount() {
        ArchflowHealthIndicator indicator = new ArchflowHealthIndicator();
        Health health = indicator.health();

        int processors = (int) health.getDetails().get("processors");
        assertTrue(processors > 0);
    }
}
