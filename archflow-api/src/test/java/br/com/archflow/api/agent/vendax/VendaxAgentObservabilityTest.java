package br.com.archflow.api.agent.vendax;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class VendaxAgentObservabilityTest {

    @Test
    void queueGaugeAndHealthExposeSaturation() throws Exception {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), Thread.ofVirtual().factory());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new VendaxAgentMetrics(registry, executor);
        VendaxAgentHealthIndicator health = new VendaxAgentHealthIndicator(executor);
        CountDownLatch release = new CountDownLatch(1);
        try {
            executor.execute(() -> await(release));
            executor.execute(() -> { });

            assertThat(registry.get("archflow.vendax.executor.queue.depth").gauge().value())
                    .isEqualTo(1);
            assertThat(health.health().getStatus().getCode()).isEqualTo("DOWN");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
