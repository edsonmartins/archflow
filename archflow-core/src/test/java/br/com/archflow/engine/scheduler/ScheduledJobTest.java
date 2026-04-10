package br.com.archflow.engine.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("ScheduledJob")
class ScheduledJobTest {

    @Test
    @DisplayName("should create job with required fields")
    void shouldCreateWithRequiredFields() {
        var job = ScheduledJob.of("tenant-1", "job-1", "0 0 8 * * ?", "agent-1", Map.of("key", "value"));

        assertThat(job.tenantId()).isEqualTo("tenant-1");
        assertThat(job.jobId()).isEqualTo("job-1");
        assertThat(job.cronExpression()).isEqualTo("0 0 8 * * ?");
        assertThat(job.agentId()).isEqualTo("agent-1");
        assertThat(job.payload()).containsEntry("key", "value");
        assertThat(job.enabled()).isTrue();
        assertThat(job.createdAt()).isNotNull();
    }

    @Test
    @DisplayName("should reject null tenantId")
    void shouldRejectNullTenantId() {
        assertThatThrownBy(() -> ScheduledJob.of(null, "job-1", "0 0 * * * ?", "agent-1", Map.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("tenantId");
    }

    @Test
    @DisplayName("should reject null jobId")
    void shouldRejectNullJobId() {
        assertThatThrownBy(() -> ScheduledJob.of("t", null, "0 0 * * * ?", "agent-1", Map.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("jobId");
    }

    @Test
    @DisplayName("should create copy with new cron")
    void shouldCreateCopyWithNewCron() {
        var job = ScheduledJob.of("t", "j", "0 0 8 * * ?", "a", Map.of());
        var updated = job.withCron("0 0 9 * * ?");

        assertThat(updated.cronExpression()).isEqualTo("0 0 9 * * ?");
        assertThat(updated.tenantId()).isEqualTo("t");
        assertThat(updated.jobId()).isEqualTo("j");
    }

    @Test
    @DisplayName("should create copy with enabled toggled")
    void shouldToggleEnabled() {
        var job = ScheduledJob.of("t", "j", "0 0 8 * * ?", "a", Map.of());
        assertThat(job.enabled()).isTrue();

        var disabled = job.withEnabled(false);
        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.jobId()).isEqualTo("j");
    }

    @Test
    @DisplayName("should default payload to empty map when null")
    void shouldDefaultPayload() {
        var job = new ScheduledJob("t", "j", "0 0 * * * ?", "a", null, true, null);
        assertThat(job.payload()).isEmpty();
    }
}
