package br.com.archflow.api.triggers;

import br.com.archflow.agent.queue.InMemoryAgentInvocationQueue;
import br.com.archflow.api.triggers.dto.ScheduledTriggerDto;
import br.com.archflow.api.triggers.impl.ScheduledTriggerControllerImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.quartz.impl.StdSchedulerFactory;

import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ScheduledTriggerControllerImpl")
class ScheduledTriggerControllerImplTest {

    private Scheduler scheduler;
    private InMemoryAgentInvocationQueue queue;
    private ScheduledTriggerController controller;

    @BeforeEach
    void setUp() throws Exception {
        Properties props = new Properties();
        props.setProperty("org.quartz.scheduler.instanceName", "test");
        props.setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
        props.setProperty("org.quartz.threadPool.threadCount", "1");
        props.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
        StdSchedulerFactory factory = new StdSchedulerFactory();
        factory.initialize(props);
        scheduler = factory.getScheduler();
        scheduler.start();
        queue = new InMemoryAgentInvocationQueue();
        controller = new ScheduledTriggerControllerImpl(scheduler, queue);
    }

    @AfterEach
    void tearDown() throws Exception {
        scheduler.shutdown(false);
    }

    @Test
    @DisplayName("create + get + list")
    void createGetList() {
        ScheduledTriggerDto created = controller.create(new ScheduledTriggerDto(
                null, "nightly", "0 0 2 * * ?", "tenant-a", "summary-agent",
                Map.of("mode", "digest"), true, null, null, null));

        assertThat(created.id()).startsWith("trg-");
        assertThat(controller.get(created.id()).name()).isEqualTo("nightly");
        assertThat(controller.list()).hasSize(1);
    }

    @Test
    @DisplayName("rejects invalid cron expression")
    void rejectsBadCron() {
        assertThatThrownBy(() -> controller.create(new ScheduledTriggerDto(
                null, "broken", "not a cron", "t", "a",
                null, true, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid cron");
    }

    @Test
    @DisplayName("update preserves createdAt and re-schedules")
    void updateReschedules() {
        ScheduledTriggerDto orig = controller.create(new ScheduledTriggerDto(
                null, "t1", "0 0 2 * * ?", "tenant", "a1",
                null, true, null, null, null));

        ScheduledTriggerDto updated = controller.update(orig.id(), new ScheduledTriggerDto(
                orig.id(), "t1-renamed", "0 0 3 * * ?", "tenant", "a1",
                null, true, null, null, null));

        assertThat(updated.name()).isEqualTo("t1-renamed");
        assertThat(updated.cronExpression()).isEqualTo("0 0 3 * * ?");
        assertThat(updated.createdAt()).isEqualTo(orig.createdAt());
    }

    @Test
    @DisplayName("fireNow submits invocation synchronously")
    void fireNow() {
        ScheduledTriggerDto created = controller.create(new ScheduledTriggerDto(
                null, "manual", "0 0 0 * * ?", "tenant-x", "agent-y",
                Map.of("k", "v"), true, null, null, null));

        int before = queue.size();
        ScheduledTriggerDto fired = controller.fireNow(created.id());
        assertThat(queue.size()).isEqualTo(before + 1);
        assertThat(fired.lastFiredAt()).isNotNull();
        assertThat(fired.lastError()).isNull();
    }

    @Test
    @DisplayName("delete removes from store and scheduler")
    void delete() {
        ScheduledTriggerDto created = controller.create(new ScheduledTriggerDto(
                null, "x", "0 0 0 * * ?", "t", "a", null, true, null, null, null));
        controller.delete(created.id());
        assertThatThrownBy(() -> controller.get(created.id()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
