package br.com.archflow.engine.scheduler.dlq;

import br.com.archflow.engine.scheduler.ScheduledJob;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Implementação in-memory da {@link DeadLetterQueue}.
 *
 * <p>Útil para desenvolvimento e testes. Em produção, considere uma
 * implementação persistente (Redis, JDBC, etc.).
 */
public class InMemoryDeadLetterQueue implements DeadLetterQueue {
    private static final Logger logger = Logger.getLogger(InMemoryDeadLetterQueue.class.getName());

    private final Map<String, FailedJobEntry> entries = new ConcurrentHashMap<>();

    @Override
    public void enqueue(ScheduledJob job, Exception exception) {
        String key = dlqKey(job.tenantId(), job.jobId());
        FailedJobEntry existing = entries.get(key);

        if (existing != null) {
            entries.put(key, existing.incrementRetry());
        } else {
            entries.put(key, FailedJobEntry.of(job, exception));
        }

        logger.info(() -> String.format("Job enqueued to DLQ: tenant=%s, job=%s",
                job.tenantId(), job.jobId()));
    }

    @Override
    public List<FailedJobEntry> listByTenant(String tenantId) {
        String prefix = tenantId + ":";
        return entries.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .map(Map.Entry::getValue)
                .toList();
    }

    @Override
    public boolean remove(String tenantId, String jobId) {
        return entries.remove(dlqKey(tenantId, jobId)) != null;
    }

    @Override
    public int clearByTenant(String tenantId) {
        String prefix = tenantId + ":";
        List<String> toRemove = entries.keySet().stream()
                .filter(k -> k.startsWith(prefix))
                .toList();
        toRemove.forEach(entries::remove);
        return toRemove.size();
    }

    @Override
    public int size() {
        return entries.size();
    }

    private String dlqKey(String tenantId, String jobId) {
        return tenantId + ":" + jobId;
    }
}
