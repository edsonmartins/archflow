package br.com.archflow.engine.scheduler;

import br.com.archflow.engine.scheduler.dlq.InMemoryDeadLetterQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("InMemoryDeadLetterQueue")
class InMemoryDeadLetterQueueTest {

    private InMemoryDeadLetterQueue dlq;

    @BeforeEach
    void setUp() {
        dlq = new InMemoryDeadLetterQueue();
    }

    @Test
    @DisplayName("should enqueue failed job")
    void shouldEnqueueFailedJob() {
        var job = ScheduledJob.of("t1", "j1", "0 0 8 * * ?", "a", Map.of());
        dlq.enqueue(job, new RuntimeException("connection timeout"));

        assertThat(dlq.size()).isEqualTo(1);
        var entries = dlq.listByTenant("t1");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).errorMessage()).isEqualTo("connection timeout");
        assertThat(entries.get(0).retryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("should increment retry count on repeated failure")
    void shouldIncrementRetryCount() {
        var job = ScheduledJob.of("t1", "j1", "0 0 8 * * ?", "a", Map.of());
        dlq.enqueue(job, new RuntimeException("fail 1"));
        dlq.enqueue(job, new RuntimeException("fail 2"));
        dlq.enqueue(job, new RuntimeException("fail 3"));

        var entries = dlq.listByTenant("t1");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).retryCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("should isolate entries by tenant")
    void shouldIsolateByTenant() {
        dlq.enqueue(ScheduledJob.of("t1", "j1", "0 0 * * * ?", "a", Map.of()), new RuntimeException("e"));
        dlq.enqueue(ScheduledJob.of("t2", "j1", "0 0 * * * ?", "a", Map.of()), new RuntimeException("e"));

        assertThat(dlq.listByTenant("t1")).hasSize(1);
        assertThat(dlq.listByTenant("t2")).hasSize(1);
        assertThat(dlq.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("should remove entry")
    void shouldRemoveEntry() {
        dlq.enqueue(ScheduledJob.of("t1", "j1", "0 0 * * * ?", "a", Map.of()), new RuntimeException("e"));

        boolean removed = dlq.remove("t1", "j1");

        assertThat(removed).isTrue();
        assertThat(dlq.size()).isZero();
    }

    @Test
    @DisplayName("should clear all entries for tenant")
    void shouldClearByTenant() {
        dlq.enqueue(ScheduledJob.of("t1", "j1", "0 0 * * * ?", "a", Map.of()), new RuntimeException("e"));
        dlq.enqueue(ScheduledJob.of("t1", "j2", "0 0 * * * ?", "a", Map.of()), new RuntimeException("e"));
        dlq.enqueue(ScheduledJob.of("t2", "j1", "0 0 * * * ?", "a", Map.of()), new RuntimeException("e"));

        int cleared = dlq.clearByTenant("t1");

        assertThat(cleared).isEqualTo(2);
        assertThat(dlq.listByTenant("t1")).isEmpty();
        assertThat(dlq.listByTenant("t2")).hasSize(1);
    }
}
