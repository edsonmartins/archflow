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

    @Test
    @DisplayName("should include VM name")
    void shouldIncludeVmName() {
        ArchflowHealthIndicator indicator = new ArchflowHealthIndicator();
        var health = indicator.health();

        assertNotNull(health.details().get("vmName"));
    }

    @Test
    @DisplayName("should have non-negative heap usage percentage")
    void shouldHaveNonNegativeHeapUsagePercent() {
        ArchflowHealthIndicator indicator = new ArchflowHealthIndicator();
        var health = indicator.health();

        long heapPercent = (long) health.details().get("heapUsagePercent");
        assertTrue(heapPercent >= 0);
    }

    @Test
    @DisplayName("status should be UP when heap usage is below 90 percent")
    void statusShouldBeUpWhenHeapUsageBelowThreshold() {
        // Under normal test conditions heap usage should be well below 90%
        ArchflowHealthIndicator indicator = new ArchflowHealthIndicator();
        var health = indicator.health();

        long heapPercent = (long) health.details().get("heapUsagePercent");
        if (heapPercent < 90) {
            assertEquals(ArchflowHealthIndicator.Status.UP, health.status());
        }
        // DOWN path requires heap > 90% which cannot be reliably induced
        // in a unit test without JVM-level manipulation
    }

    @Test
    @DisplayName("should format heapUsed as a human-readable string")
    void shouldFormatHeapUsedAsHumanReadable() {
        ArchflowHealthIndicator indicator = new ArchflowHealthIndicator();
        var health = indicator.health();

        String heapUsed = (String) health.details().get("heapUsed");
        assertTrue(
                heapUsed.endsWith(" B") || heapUsed.endsWith("KB") || heapUsed.endsWith("MB") || heapUsed.endsWith("GB"),
                "heapUsed should be a formatted size string, was: " + heapUsed
        );
    }

    @Test
    @DisplayName("should include non-negative uptimeSeconds")
    void shouldHaveNonNegativeUptimeSeconds() {
        ArchflowHealthIndicator indicator = new ArchflowHealthIndicator();
        var health = indicator.health();

        long uptimeSeconds = (long) health.details().get("uptimeSeconds");
        assertTrue(uptimeSeconds >= 0);
    }
}
