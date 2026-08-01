package br.com.archflow.api.agent.vendax;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Métricas operacionais do caminho assíncrono VendaX Core → ArchFlow. */
public final class VendaxAgentMetrics {

    private final MeterRegistry registry;
    private final Counter received;
    private final Counter started;
    private final Counter completed;
    private final Timer duration;

    public VendaxAgentMetrics(MeterRegistry registry, ThreadPoolExecutor executor) {
        this.registry = registry;
        this.received = registry.counter("archflow.vendax.invokes.received");
        this.started = registry.counter("archflow.vendax.executions.started");
        this.completed = registry.counter("archflow.vendax.executions.completed");
        this.duration = Timer.builder("archflow.vendax.execution.duration").register(registry);
        registry.gauge("archflow.vendax.executor.queue.depth", executor,
                pool -> pool.getQueue().size());
        registry.gauge("archflow.vendax.executor.active", executor,
                ThreadPoolExecutor::getActiveCount);
    }

    public void received() { received.increment(); }

    public long started() {
        started.increment();
        return System.nanoTime();
    }

    public void completed(long startedAt) {
        completed.increment();
        duration.record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
    }

    public void failed(long startedAt, Throwable failure) {
        String cause = classify(failure);
        registry.counter("archflow.vendax.executions.failed", "cause", cause).increment();
        if (startedAt > 0) {
            duration.record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
        }
    }

    private static String classify(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String name = current.getClass().getSimpleName();
        if (name.contains("Timeout")) return "timeout";
        if (current instanceof java.util.concurrent.RejectedExecutionException) return "saturated";
        if (current instanceof java.io.IOException) return "transport";
        return "execution";
    }
}
