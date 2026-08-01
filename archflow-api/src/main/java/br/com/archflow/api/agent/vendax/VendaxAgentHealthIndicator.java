package br.com.archflow.api.agent.vendax;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import java.util.concurrent.ThreadPoolExecutor;

/** Readiness do executor: fila cheia não pode continuar parecendo saudável. */
public final class VendaxAgentHealthIndicator implements HealthIndicator {

    private final ThreadPoolExecutor executor;

    public VendaxAgentHealthIndicator(ThreadPoolExecutor executor) {
        this.executor = executor;
    }

    @Override
    public Health health() {
        int queued = executor.getQueue().size();
        int remaining = executor.getQueue().remainingCapacity();
        Health.Builder health = executor.isShutdown() || remaining == 0
                ? Health.down()
                : Health.up();
        return health.withDetail("active", executor.getActiveCount())
                .withDetail("queueDepth", queued)
                .withDetail("queueRemainingCapacity", remaining)
                .withDetail("maximumConcurrency", executor.getMaximumPoolSize())
                .build();
    }
}
