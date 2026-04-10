package br.com.archflow.engine.scheduler;

import br.com.archflow.engine.scheduler.dlq.InMemoryDeadLetterQueue;
import br.com.archflow.engine.scheduler.quartz.QuartzTenantScheduler;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

@DisplayName("QuartzTenantScheduler")
class QuartzTenantSchedulerTest {

    private QuartzTenantScheduler scheduler;
    private InMemoryDeadLetterQueue dlq;
    private List<ScheduledJob> triggeredJobs;

    @BeforeEach
    void setUp() {
        triggeredJobs = new CopyOnWriteArrayList<>();
        dlq = new InMemoryDeadLetterQueue();
        scheduler = new QuartzTenantScheduler(triggeredJobs::add, dlq);
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    @Test
    @DisplayName("should schedule and list jobs by tenant")
    void shouldScheduleAndListJobs() {
        scheduler.schedule(ScheduledJob.of("tenant-A", "job-1", "0 0 8 * * ?", "agent-1", Map.of()));
        scheduler.schedule(ScheduledJob.of("tenant-A", "job-2", "0 0 9 * * ?", "agent-2", Map.of()));
        scheduler.schedule(ScheduledJob.of("tenant-B", "job-1", "0 0 10 * * ?", "agent-3", Map.of()));

        List<ScheduledJob> tenantA = scheduler.listByTenant("tenant-A");
        List<ScheduledJob> tenantB = scheduler.listByTenant("tenant-B");

        assertThat(tenantA).hasSize(2);
        assertThat(tenantB).hasSize(1);
        assertThat(tenantA).extracting(ScheduledJob::jobId).containsExactlyInAnyOrder("job-1", "job-2");
    }

    @Test
    @DisplayName("should reject duplicate job for same tenant")
    void shouldRejectDuplicate() {
        scheduler.schedule(ScheduledJob.of("t", "j", "0 0 8 * * ?", "a", Map.of()));

        assertThatThrownBy(() ->
                scheduler.schedule(ScheduledJob.of("t", "j", "0 0 9 * * ?", "a", Map.of()))
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("should allow same jobId in different tenants")
    void shouldAllowSameJobIdDifferentTenants() {
        scheduler.schedule(ScheduledJob.of("tenant-A", "weekly", "0 0 8 * * ?", "a1", Map.of()));
        scheduler.schedule(ScheduledJob.of("tenant-B", "weekly", "0 0 8 * * ?", "a2", Map.of()));

        assertThat(scheduler.listByTenant("tenant-A")).hasSize(1);
        assertThat(scheduler.listByTenant("tenant-B")).hasSize(1);
    }

    @Test
    @DisplayName("should cancel a job")
    void shouldCancelJob() {
        scheduler.schedule(ScheduledJob.of("t", "j", "0 0 8 * * ?", "a", Map.of()));
        assertThat(scheduler.listByTenant("t")).hasSize(1);

        boolean cancelled = scheduler.cancel("t", "j");

        assertThat(cancelled).isTrue();
        assertThat(scheduler.listByTenant("t")).isEmpty();
    }

    @Test
    @DisplayName("should return false when cancelling non-existent job")
    void shouldReturnFalseForNonExistentCancel() {
        assertThat(scheduler.cancel("t", "no-such-job")).isFalse();
    }

    @Test
    @DisplayName("should reschedule job with new cron")
    void shouldRescheduleJob() {
        scheduler.schedule(ScheduledJob.of("t", "j", "0 0 8 * * ?", "a", Map.of()));

        scheduler.reschedule("t", "j", "0 0 20 * * ?");

        var job = scheduler.getJob("t", "j");
        assertThat(job).isPresent();
        assertThat(job.get().cronExpression()).isEqualTo("0 0 20 * * ?");
    }

    @Test
    @DisplayName("should throw when rescheduling non-existent job")
    void shouldThrowOnRescheduleNonExistent() {
        assertThatThrownBy(() -> scheduler.reschedule("t", "nope", "0 0 8 * * ?"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("should cancel all jobs for a tenant")
    void shouldCancelAllByTenant() {
        scheduler.schedule(ScheduledJob.of("t1", "j1", "0 0 8 * * ?", "a", Map.of()));
        scheduler.schedule(ScheduledJob.of("t1", "j2", "0 0 9 * * ?", "a", Map.of()));
        scheduler.schedule(ScheduledJob.of("t2", "j1", "0 0 10 * * ?", "a", Map.of()));

        int cancelled = scheduler.cancelAllByTenant("t1");

        assertThat(cancelled).isEqualTo(2);
        assertThat(scheduler.listByTenant("t1")).isEmpty();
        assertThat(scheduler.listByTenant("t2")).hasSize(1);
    }

    @Test
    @DisplayName("should get specific job")
    void shouldGetJob() {
        scheduler.schedule(ScheduledJob.of("t", "j", "0 0 8 * * ?", "agent-x", Map.of("k", "v")));

        var job = scheduler.getJob("t", "j");

        assertThat(job).isPresent();
        assertThat(job.get().agentId()).isEqualTo("agent-x");
        assertThat(job.get().payload()).containsEntry("k", "v");
    }

    @Test
    @DisplayName("should return empty for non-existent job")
    void shouldReturnEmptyForNonExistent() {
        assertThat(scheduler.getJob("t", "nope")).isEmpty();
    }

    @Test
    @DisplayName("should pause and resume job")
    void shouldPauseAndResumeJob() {
        scheduler.schedule(ScheduledJob.of("t", "j", "0 0 8 * * ?", "a", Map.of()));

        scheduler.pause("t", "j");
        var paused = scheduler.getJob("t", "j");
        assertThat(paused).isPresent();
        assertThat(paused.get().enabled()).isFalse();

        scheduler.resume("t", "j");
        var resumed = scheduler.getJob("t", "j");
        assertThat(resumed).isPresent();
        assertThat(resumed.get().enabled()).isTrue();
    }
}
