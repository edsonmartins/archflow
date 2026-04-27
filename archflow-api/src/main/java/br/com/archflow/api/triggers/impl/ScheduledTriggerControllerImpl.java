package br.com.archflow.api.triggers.impl;

import br.com.archflow.agent.queue.AgentInvocationQueue;
import br.com.archflow.agent.queue.InvocationRequest;
import br.com.archflow.api.triggers.ScheduledTriggerController;
import br.com.archflow.api.triggers.dto.ScheduledTriggerDto;
import org.quartz.CronExpression;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.ParseException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Quartz-backed implementation. Keeps a registry of {@link ScheduledTriggerDto}
 * in memory and mirrors every mutation into the Quartz {@link Scheduler}.
 *
 * <p>When Quartz fires a trigger the job submits an
 * {@link InvocationRequest} to the shared
 * {@link AgentInvocationQueue}, so a cron firing is indistinguishable
 * from a human clicking "Invoke" in the agent playground.</p>
 *
 * <p>The queue is resolved via a static registry so Quartz's
 * per-execution job instance — created by reflection, with no Spring
 * wiring — can still find it.</p>
 */
public class ScheduledTriggerControllerImpl implements ScheduledTriggerController {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTriggerControllerImpl.class);
    private static final String GROUP = "archflow-triggers";

    /** Shared so {@link TriggerJob} (instantiated by Quartz) can resolve it. */
    private static volatile AgentInvocationQueue QUEUE;
    /** Shared registry of trigger DTOs keyed by id. */
    private static final Map<String, ScheduledTriggerDto> STORE = new ConcurrentHashMap<>();

    private final Scheduler scheduler;

    public ScheduledTriggerControllerImpl(Scheduler scheduler, AgentInvocationQueue queue) {
        this.scheduler = scheduler;
        QUEUE = queue;
    }

    @Override
    public List<ScheduledTriggerDto> list() {
        return new ArrayList<>(STORE.values());
    }

    @Override
    public ScheduledTriggerDto get(String id) {
        ScheduledTriggerDto dto = STORE.get(id);
        if (dto == null) {
            throw new IllegalArgumentException("Trigger not found: " + id);
        }
        return dto;
    }

    @Override
    public synchronized ScheduledTriggerDto create(ScheduledTriggerDto incoming) {
        String id = incoming.id();
        if (id == null || id.isBlank()) {
            id = "trg-" + UUID.randomUUID().toString().substring(0, 8);
        }
        validateCron(incoming.cronExpression());

        ScheduledTriggerDto stored = new ScheduledTriggerDto(
                id,
                incoming.name(),
                incoming.cronExpression(),
                incoming.tenantId(),
                incoming.agentId(),
                incoming.payload(),
                incoming.enabled(),
                Instant.now(),
                null,
                null);
        STORE.put(id, stored);
        materialise(stored);
        return stored;
    }

    @Override
    public synchronized ScheduledTriggerDto update(String id, ScheduledTriggerDto incoming) {
        ScheduledTriggerDto existing = STORE.get(id);
        if (existing == null) {
            throw new IllegalArgumentException("Trigger not found: " + id);
        }
        validateCron(incoming.cronExpression());

        ScheduledTriggerDto stored = new ScheduledTriggerDto(
                id,
                incoming.name() != null ? incoming.name() : existing.name(),
                incoming.cronExpression(),
                incoming.tenantId(),
                incoming.agentId(),
                incoming.payload(),
                incoming.enabled(),
                existing.createdAt(),
                existing.lastFiredAt(),
                existing.lastError());
        STORE.put(id, stored);
        // Recreate the Quartz job to pick up the new schedule/payload.
        removeFromScheduler(id);
        materialise(stored);
        return stored;
    }

    @Override
    public synchronized void delete(String id) {
        ScheduledTriggerDto removed = STORE.remove(id);
        if (removed == null) {
            throw new IllegalArgumentException("Trigger not found: " + id);
        }
        removeFromScheduler(id);
    }

    @Override
    public ScheduledTriggerDto fireNow(String id) {
        ScheduledTriggerDto dto = get(id);
        try {
            submitInvocation(dto);
            STORE.put(id, new ScheduledTriggerDto(
                    dto.id(), dto.name(), dto.cronExpression(),
                    dto.tenantId(), dto.agentId(), dto.payload(),
                    dto.enabled(), dto.createdAt(),
                    Instant.now(), null));
            return STORE.get(id);
        } catch (Exception e) {
            ScheduledTriggerDto failed = new ScheduledTriggerDto(
                    dto.id(), dto.name(), dto.cronExpression(),
                    dto.tenantId(), dto.agentId(), dto.payload(),
                    dto.enabled(), dto.createdAt(),
                    dto.lastFiredAt(), e.getMessage());
            STORE.put(id, failed);
            throw new IllegalStateException(
                    "Failed to fire trigger " + id + ": " + e.getMessage(), e);
        }
    }

    // ── Internals ────────────────────────────────────────────────────

    private static void validateCron(String expr) {
        if (expr == null || expr.isBlank()) {
            throw new IllegalArgumentException("cronExpression is required");
        }
        if (!CronExpression.isValidExpression(expr)) {
            throw new IllegalArgumentException("Invalid cron expression: " + expr);
        }
    }

    private void materialise(ScheduledTriggerDto dto) {
        if (!dto.enabled()) return;

        JobDetail job = JobBuilder.newJob(TriggerJob.class)
                .withIdentity(new JobKey(dto.id(), GROUP))
                .usingJobData(new JobDataMap(Map.of("triggerId", dto.id())))
                .build();

        Trigger trigger = TriggerBuilder.newTrigger()
                .withIdentity(new TriggerKey(dto.id(), GROUP))
                .withSchedule(CronScheduleBuilder.cronSchedule(dto.cronExpression()))
                .build();

        try {
            scheduler.scheduleJob(job, trigger);
        } catch (SchedulerException e) {
            throw new IllegalStateException(
                    "Failed to schedule trigger " + dto.id() + ": " + e.getMessage(), e);
        }
    }

    private void removeFromScheduler(String id) {
        try {
            scheduler.deleteJob(new JobKey(id, GROUP));
        } catch (SchedulerException e) {
            log.warn("Failed to remove Quartz job {}: {}", id, e.getMessage());
        }
    }

    /** Submits the invocation to the shared queue. */
    static void submitInvocation(ScheduledTriggerDto dto) {
        AgentInvocationQueue queue = QUEUE;
        if (queue == null) {
            throw new IllegalStateException("AgentInvocationQueue not configured; cannot fire trigger");
        }
        queue.submit(new InvocationRequest(
                null, dto.tenantId(), dto.agentId(),
                dto.payload(), null, 0, Instant.now()));
    }

    /** Records a successful firing against the DTO store. */
    static void recordFire(String triggerId) {
        STORE.computeIfPresent(triggerId, (k, dto) -> new ScheduledTriggerDto(
                dto.id(), dto.name(), dto.cronExpression(),
                dto.tenantId(), dto.agentId(), dto.payload(),
                dto.enabled(), dto.createdAt(),
                Instant.now(), null));
    }

    /** Records an error during firing. */
    static void recordError(String triggerId, String message) {
        STORE.computeIfPresent(triggerId, (k, dto) -> new ScheduledTriggerDto(
                dto.id(), dto.name(), dto.cronExpression(),
                dto.tenantId(), dto.agentId(), dto.payload(),
                dto.enabled(), dto.createdAt(),
                dto.lastFiredAt(), message));
    }

    /**
     * Quartz-instantiated job. Resolves the queue through the static
     * bridge because Quartz creates instances with a no-arg constructor
     * and doesn't know about Spring.
     */
    public static final class TriggerJob implements org.quartz.Job {
        @Override
        public void execute(JobExecutionContext ctx) throws JobExecutionException {
            String triggerId = ctx.getJobDetail().getJobDataMap().getString("triggerId");
            ScheduledTriggerDto dto = STORE.get(triggerId);
            if (dto == null) {
                log.warn("Trigger {} fired but has been removed from the store", triggerId);
                return;
            }
            try {
                submitInvocation(dto);
                recordFire(triggerId);
                log.info("Trigger {} fired → submitted invocation for agent={} tenant={}",
                        triggerId, dto.agentId(), dto.tenantId());
            } catch (Exception e) {
                recordError(triggerId, e.getMessage());
                throw new JobExecutionException(
                        "Trigger " + triggerId + " failed: " + e.getMessage(), e);
            }
        }
    }
}
