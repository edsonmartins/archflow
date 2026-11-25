package br.com.archflow.api.triggers.impl;

import br.com.archflow.agent.queue.AgentInvocationQueue;
import br.com.archflow.agent.queue.InvocationRequest;
import br.com.archflow.api.agent.invocation.AgentInvocationResult;
import br.com.archflow.api.agent.invocation.AgentInvocationResultStore;
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
import java.util.Optional;
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
 *
 * <p><b>Desfecho do disparo (RCHFLW-104).</b> O disparo só enfileira; quem roda o agente é o consumidor
 * da fila, e a falha dele só chegava ao resultado da invocação. O gatilho ficava com
 * {@code lastError} nulo e a tela dizia "0 com falha" com todas as invocações falhando — nos disparos
 * automáticos, a falha não aparecia em lugar nenhum. Agora, ao ler um gatilho cujo último disparo
 * ainda não tem desfecho, o registro consulta o resultado do {@code lastRequestId} e grava status e
 * erro no gatilho. Ler o resultado, e não receber um aviso do consumidor, é o que vale também para o
 * cron e para outra réplica: o resultado é o mesmo que a consulta por requestId devolve. Gravado o
 * desfecho final, ele fica no gatilho mesmo depois de a retenção apagar o resultado.</p>
 */
public class ScheduledTriggerControllerImpl implements ScheduledTriggerController {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTriggerControllerImpl.class);
    private static final String GROUP = "archflow-triggers";

    /** Shared so {@link TriggerJob} (instantiated by Quartz) can resolve it. */
    private static volatile AgentInvocationQueue QUEUE;
    /** Shared registry of trigger DTOs keyed by id. */
    private static final Map<String, ScheduledTriggerDto> STORE = new ConcurrentHashMap<>();

    private final Scheduler scheduler;
    /** De onde sai o desfecho do último disparo; {@code null} = sem resultado a consultar. */
    private final AgentInvocationResultStore results;

    public ScheduledTriggerControllerImpl(Scheduler scheduler, AgentInvocationQueue queue) {
        this(scheduler, queue, null);
    }

    public ScheduledTriggerControllerImpl(Scheduler scheduler, AgentInvocationQueue queue,
                                          AgentInvocationResultStore results) {
        this.scheduler = scheduler;
        this.results = results;
        QUEUE = queue;
    }

    @Override
    public List<ScheduledTriggerDto> list() {
        List<ScheduledTriggerDto> out = new ArrayList<>();
        for (ScheduledTriggerDto dto : STORE.values()) {
            out.add(comDesfecho(dto));
        }
        return out;
    }

    @Override
    public ScheduledTriggerDto get(String id) {
        ScheduledTriggerDto dto = STORE.get(id);
        if (dto == null) {
            throw new IllegalArgumentException("Trigger not found: " + id);
        }
        return comDesfecho(dto);
    }

    /**
     * O gatilho com o desfecho do último disparo, lido do resultado da invocação enquanto ele não é
     * final. {@code COMPLETED} limpa o erro; {@code FAILED} grava a mensagem do agente.
     *
     * <p>A gravação só substitui o gatilho se o {@code lastRequestId} ainda for o consultado: um
     * disparo que chegou no meio já é o "último", e o desfecho do anterior não pode cobri-lo.</p>
     */
    private ScheduledTriggerDto comDesfecho(ScheduledTriggerDto dto) {
        if (results == null || dto.lastRequestId() == null || terminal(dto.lastStatus())) {
            return dto;
        }
        Optional<AgentInvocationResult> resultado;
        try {
            resultado = results.find(dto.lastRequestId());
        } catch (RuntimeException e) {
            // Banco fora, por exemplo: a lista continua, com o que o gatilho já sabia.
            log.warn("Desfecho do disparo {} do gatilho {} indisponível: {}",
                    dto.lastRequestId(), dto.id(), e.getMessage());
            return dto;
        }
        if (resultado.isEmpty() || resultado.get().status().name().equals(dto.lastStatus())) {
            return dto;
        }
        AgentInvocationResult r = resultado.get();
        String erro = r.status() == AgentInvocationResult.Status.FAILED
                ? (r.error() == null || r.error().isBlank() ? "agent failed without an error message" : r.error())
                : null;
        ScheduledTriggerDto atualizado = STORE.computeIfPresent(dto.id(), (k, atual) ->
                dto.lastRequestId().equals(atual.lastRequestId())
                        ? comUltimo(atual, atual.lastFiredAt(), erro, atual.lastRequestId(), r.status().name())
                        : atual);
        return atualizado == null ? dto : atualizado;
    }

    private static boolean terminal(String status) {
        return AgentInvocationResult.Status.COMPLETED.name().equals(status)
                || AgentInvocationResult.Status.FAILED.name().equals(status);
    }

    private static ScheduledTriggerDto comUltimo(ScheduledTriggerDto dto, Instant lastFiredAt, String lastError,
                                                 String lastRequestId, String lastStatus) {
        return new ScheduledTriggerDto(
                dto.id(), dto.name(), dto.cronExpression(),
                dto.tenantId(), dto.agentId(), dto.payload(),
                dto.enabled(), dto.createdAt(),
                lastFiredAt, lastError, lastRequestId, lastStatus);
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
                existing.lastError(),
                existing.lastRequestId(),
                existing.lastStatus());
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
            String requestId = submitInvocation(dto);
            recordFire(id, requestId);
            return STORE.get(id);
        } catch (Exception e) {
            recordError(id, e.getMessage());
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

    /**
     * Submits the invocation to the shared queue.
     *
     * @return o requestId da invocação — guardado no gatilho para que o desfecho do agente seja
     *         consultável (o consumidor grava o resultado sob ele)
     */
    static String submitInvocation(ScheduledTriggerDto dto) {
        AgentInvocationQueue queue = QUEUE;
        if (queue == null) {
            throw new IllegalStateException("AgentInvocationQueue not configured; cannot fire trigger");
        }
        InvocationRequest request = new InvocationRequest(
                null, dto.tenantId(), dto.agentId(),
                dto.payload(), null, 0, Instant.now());
        queue.submit(request);
        return request.requestId();
    }

    /**
     * Records a successful firing against the DTO store: a invocação está na fila, e o erro de um
     * disparo anterior deixa de ser o "último" — o desfecho desta vem do resultado dela.
     */
    static void recordFire(String triggerId, String requestId) {
        STORE.computeIfPresent(triggerId, (k, dto) -> comUltimo(dto, Instant.now(), null, requestId,
                AgentInvocationResult.Status.QUEUED.name()));
    }

    /**
     * Records an error during firing. O status vai a {@code FAILED} (final): sem isso a leitura
     * consultaria o resultado do {@code lastRequestId} anterior, e um disparo bem-sucedido antigo
     * apagaria a falha deste.
     */
    static void recordError(String triggerId, String message) {
        STORE.computeIfPresent(triggerId, (k, dto) -> comUltimo(dto, dto.lastFiredAt(), message,
                dto.lastRequestId(), AgentInvocationResult.Status.FAILED.name()));
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
                String requestId = submitInvocation(dto);
                recordFire(triggerId, requestId);
                log.info("Trigger {} fired → submitted invocation {} for agent={} tenant={}",
                        triggerId, requestId, dto.agentId(), dto.tenantId());
            } catch (Exception e) {
                recordError(triggerId, e.getMessage());
                throw new JobExecutionException(
                        "Trigger " + triggerId + " failed: " + e.getMessage(), e);
            }
        }
    }
}
