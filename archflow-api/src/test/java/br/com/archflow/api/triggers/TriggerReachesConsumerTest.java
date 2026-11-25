package br.com.archflow.api.triggers;

import br.com.archflow.agent.queue.InMemoryAgentInvocationQueue;
import br.com.archflow.agent.queue.InvocationRequest;
import br.com.archflow.api.agent.invocation.AgentInvocationConsumer;
import br.com.archflow.api.agent.invocation.AgentInvocationResult;
import br.com.archflow.api.agent.invocation.CatalogAgentInvocationExecutor;
import br.com.archflow.api.agent.invocation.InMemoryAgentInvocationResultStore;
import br.com.archflow.api.config.ArchflowBeanConfiguration;
import br.com.archflow.api.triggers.dto.ScheduledTriggerDto;
import br.com.archflow.api.triggers.impl.ScheduledTriggerControllerImpl;
import br.com.archflow.plugin.loader.ArchflowPluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;
import org.quartz.impl.StdSchedulerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * RCHFLW-80: "Disparar agora" (e o cron) enfileiravam e nada acontecia. O gatilho tem de chegar ao mesmo
 * consumidor do Agent lab e o agente tem de rodar.
 */
@DisplayName("Gatilho agendado chega ao consumidor da fila")
class TriggerReachesConsumerTest {

    private Scheduler scheduler;
    private AgentInvocationConsumer consumer;

    @BeforeEach
    void setUp() throws Exception {
        Properties props = new Properties();
        props.setProperty("org.quartz.scheduler.instanceName", "trigger-consumer-test");
        props.setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
        props.setProperty("org.quartz.threadPool.threadCount", "1");
        props.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
        StdSchedulerFactory factory = new StdSchedulerFactory();
        factory.initialize(props);
        scheduler = factory.getScheduler();
        scheduler.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (consumer != null) {
            consumer.close();
        }
        scheduler.shutdown(false);
    }

    @Test
    @DisplayName("fire now → invocação consumida → agente do catálogo executa → COMPLETED")
    void fireNowRunsAgent() throws Exception {
        @SuppressWarnings("unchecked")
        ObjectProvider<ArchflowPluginManager> semPlugins = mock(ObjectProvider.class);
        var catalog = new ArchflowBeanConfiguration().componentCatalog(semPlugins);
        var queue = new InMemoryAgentInvocationQueue();
        var store = new InMemoryAgentInvocationResultStore();
        var real = new CatalogAgentInvocationExecutor(catalog, null);
        BlockingQueue<InvocationRequest> consumed = new LinkedBlockingQueue<>();
        consumer = new AgentInvocationConsumer(queue, store, request -> {
            consumed.add(request);
            return real.execute(request);
        }, new AgentInvocationConsumer.Settings(2, Duration.ofMillis(20), Duration.ofMinutes(1), 3,
                Duration.ofSeconds(5)));
        consumer.start();
        ScheduledTriggerController controller = new ScheduledTriggerControllerImpl(scheduler, queue);
        ScheduledTriggerDto trigger = controller.create(new ScheduledTriggerDto(
                null, "noturno", "0 0 2 * * ?", "tenant-a", "text-transform-tool",
                Map.of("operation", "uppercase", "message", "relatório do dia"), true, null, null, null, null));

        ScheduledTriggerDto fired = controller.fireNow(trigger.id());

        InvocationRequest request = consumed.poll(10, TimeUnit.SECONDS);
        assertThat(request).as("o gatilho não chegou ao consumidor").isNotNull();
        // O disparo devolve (e o gatilho guarda) o requestId: é por ele que se consulta como o agente terminou.
        assertThat(fired.lastRequestId()).isEqualTo(request.requestId());
        assertThat(controller.get(trigger.id()).lastRequestId()).isEqualTo(request.requestId());
        assertThat(request.tenantId()).isEqualTo("tenant-a");
        assertThat(request.agentId()).isEqualTo("text-transform-tool");
        AgentInvocationResult result = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            result = store.find(request.requestId()).filter(r -> r.status().terminal()).orElse(null);
            if (result != null) {
                break;
            }
            Thread.sleep(10);
        }
        assertThat(result).as("sem resultado final").isNotNull();
        assertThat(result.status()).as("erro: %s", result.error()).isEqualTo(AgentInvocationResult.Status.COMPLETED);
        assertThat(result.output()).isEqualTo("RELATÓRIO DO DIA");
        controller.delete(trigger.id());
    }
}
