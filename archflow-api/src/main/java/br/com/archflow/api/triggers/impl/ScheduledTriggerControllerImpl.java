package br.com.archflow.api.triggers.impl;

import br.com.archflow.agent.queue.AgentInvocationQueue;
import br.com.archflow.agent.queue.InvocationRequest;
import br.com.archflow.api.agent.invocation.AgentInvocationResult;
import br.com.archflow.api.agent.invocation.AgentInvocationResultStore;
import br.com.archflow.api.triggers.ScheduledTriggerController;
import br.com.archflow.api.triggers.dto.ScheduledTriggerDto;
import br.com.archflow.api.triggers.store.InMemoryScheduledTriggerStore;
import br.com.archflow.api.triggers.store.ScheduledTriggerStore;
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
import org.quartz.impl.matchers.GroupMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.ParseException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Quartz-backed implementation. Keeps the registry of {@link ScheduledTriggerDto} in a
 * {@link ScheduledTriggerStore} and mirrors every mutation into the Quartz {@link Scheduler}.
 *
 * <p>When Quartz fires a trigger the job submits an
 * {@link InvocationRequest} to the shared
 * {@link AgentInvocationQueue}, so a cron firing is indistinguishable
 * from a human clicking "Invoke" in the agent playground.</p>
 *
 * <p>O job do Quartz é criado por reflexão, sem Spring; ele acha este controller no {@code SchedulerContext}
 * do próprio scheduler. Antes o registro e a fila eram estáticos — um registro só para a JVM inteira, que
 * não sobrevivia ao restart enquanto o job, no {@code JobStoreTX}, sobrevivia (RCHFLW-187).</p>
 *
 * <p><b>Registro e Quartz não divergem (RCHFLW-140, RCHFLW-175).</b> O registro era gravado antes do
 * agendamento, e a edição apagava o job antigo antes de criar o novo: um cron que nunca dispara respondia 409
 * e ficava gravado como ativo, sem job, e na edição o agendamento que funcionava sumia. Agora:</p>
 * <ul>
 *   <li>o cron é validado antes de qualquer gravação, com o parser do Quartz e a pergunta que ele mesmo
 *       faz ao agendar — "tem um próximo disparo?" —, e um cron que nunca dispara é recusado (400);</li>
 *   <li>na edição o job é trocado numa operação só do Quartz ({@code replace}); se a gravação do registro
 *       falha depois, o Quartz volta ao que está gravado;</li>
 *   <li>ao religar um gatilho pausado a ordem se inverte: registro primeiro, job depois — ver
 *       {@link #religar};</li>
 *   <li>na criação, o registro vem antes do job (o job sem registro se remove sozinho, ver
 *       {@link #dispararPeloAgendamento}) e sai de novo se o agendamento falha;</li>
 *   <li>a leitura traz o {@code nextFireTime} do Quartz: é ele que diz se o gatilho está agendado, e não
 *       o {@code enabled} do registro.</li>
 * </ul>
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
    /** Onde o {@link TriggerJob} acha o controller dono do scheduler. */
    static final String CONTEXT_KEY = ScheduledTriggerControllerImpl.class.getName();

    private final Scheduler scheduler;
    private final AgentInvocationQueue queue;
    /** De onde sai o desfecho do último disparo; {@code null} = sem resultado a consultar. */
    private final AgentInvocationResultStore results;
    private final ScheduledTriggerStore store;
    /** {@code true} quando o agente existe no catálogo — o mesmo que o consumidor da fila vai procurar. */
    private final Predicate<String> agentInCatalog;
    /** O fuso em que o cron é interpretado. Explícito para a tela poder nomeá-lo (RCHFLW-191). */
    private final TimeZone zone = TimeZone.getDefault();

    /** Registro em memória e sem conferir o agente no catálogo — para testes do agendamento em si. */
    public ScheduledTriggerControllerImpl(Scheduler scheduler, AgentInvocationQueue queue) {
        this(scheduler, queue, null);
    }

    /** Registro em memória e sem conferir o agente no catálogo — para testes do agendamento em si. */
    public ScheduledTriggerControllerImpl(Scheduler scheduler, AgentInvocationQueue queue,
                                          AgentInvocationResultStore results) {
        this(scheduler, queue, results, new InMemoryScheduledTriggerStore(), agentId -> true);
    }

    /**
     * @param store          o registro — em memória com o {@code RAMJobStore}, JDBC com o {@code JobStoreTX}
     * @param agentInCatalog se o agente existe; um gatilho com agente desconhecido é recusado ao salvar, e
     *                       não só no disparo (RCHFLW-191)
     */
    public ScheduledTriggerControllerImpl(Scheduler scheduler, AgentInvocationQueue queue,
                                          AgentInvocationResultStore results, ScheduledTriggerStore store,
                                          Predicate<String> agentInCatalog) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.queue = queue;
        this.results = results;
        this.store = Objects.requireNonNull(store, "store");
        this.agentInCatalog = Objects.requireNonNull(agentInCatalog, "agentInCatalog");
        try {
            scheduler.getContext().put(CONTEXT_KEY, this);
        } catch (SchedulerException e) {
            throw new IllegalStateException("Cannot register the trigger registry in the Quartz scheduler", e);
        }
    }

    @Override
    public List<ScheduledTriggerDto> list() {
        return store.list().stream().map(t -> comAgenda(comDesfecho(t))).toList();
    }

    @Override
    public ScheduledTriggerDto get(String id) {
        return comAgenda(comDesfecho(store.find(id).orElseThrow(() -> naoEncontrado(id))));
    }

    /**
     * O gatilho com o desfecho do último disparo, lido do resultado da invocação enquanto ele não é
     * final. {@code COMPLETED} limpa o erro; {@code FAILED} grava a mensagem do agente.
     *
     * <p>A gravação só substitui o desfecho se o {@code lastRequestId} ainda for o consultado: um
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
        store.recordOutcome(dto.id(), dto.lastRequestId(), r.status().name(), erro);
        return store.find(dto.id()).orElse(dto);
    }

    private static boolean terminal(String status) {
        return AgentInvocationResult.Status.COMPLETED.name().equals(status)
                || AgentInvocationResult.Status.FAILED.name().equals(status);
    }

    @Override
    public synchronized ScheduledTriggerDto create(ScheduledTriggerDto incoming) {
        String id = incoming.id();
        if (id == null || id.isBlank()) {
            id = "trg-" + UUID.randomUUID().toString().substring(0, 8);
        }
        validar(incoming.name(), incoming.cronExpression(), incoming.agentId(), true, true);

        Instant agora = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        ScheduledTriggerDto stored = new ScheduledTriggerDto(
                id, incoming.name(), incoming.cronExpression(), incoming.tenantId(), incoming.agentId(),
                incoming.payload(), incoming.enabled(), agora, null, null, null, null, agora, null, null);
        if (!store.insert(stored)) {
            throw new IllegalStateException("Trigger id already in use: " + id);
        }
        // Registro antes do job: um job sem registro se remove no primeiro disparo, e um registro sem job
        // aparece como "sem agendamento" — nenhum dos dois finge estar ativo. Se o agendamento falha, o
        // registro sai: a resposta é de erro, e nada pode ficar gravado (RCHFLW-175).
        try {
            agendar(stored);
        } catch (RuntimeException e) {
            try {
                store.delete(id);
            } catch (RuntimeException naoDesfez) {
                e.addSuppressed(naoDesfez);
            }
            throw e;
        }
        return comAgenda(stored);
    }

    @Override
    public synchronized ScheduledTriggerDto update(String id, ScheduledTriggerDto incoming,
                                                   Instant expectedUpdatedAt) {
        ScheduledTriggerDto existing = store.find(id).orElseThrow(() -> naoEncontrado(id));
        if (expectedUpdatedAt != null && !expectedUpdatedAt.equals(existing.updatedAt())) {
            throw new TriggerConflictException(id, existing.updatedAt());
        }
        String name = incoming.name() != null ? incoming.name() : existing.name();
        // Pausar não agenda nada: o cron e o agente que já estavam gravados não precisam continuar valendo para
        // o gatilho poder ser desligado. Antes, um gatilho cujo agente saiu do catálogo, ou cujo cron de 7
        // campos já expirou, recebia 400 ao ser pausado e só podia ser excluído — e o primeiro seguia
        // disparando e falhando. O que a edição muda é conferido sempre, e religar confere tudo de novo.
        boolean pausado = !incoming.enabled();
        validar(name, incoming.cronExpression(), incoming.agentId(),
                !pausado || !Objects.equals(incoming.cronExpression(), existing.cronExpression()),
                !pausado || !Objects.equals(incoming.agentId(), existing.agentId()));

        ScheduledTriggerDto config = new ScheduledTriggerDto(
                id, name, incoming.cronExpression(), incoming.tenantId(), incoming.agentId(), incoming.payload(),
                incoming.enabled(), existing.createdAt(), existing.lastFiredAt(), existing.lastError(),
                existing.lastRequestId(), existing.lastStatus(), proximaVersao(existing.updatedAt()), null, null);
        if (config.enabled() && !existing.enabled()) {
            return religar(existing, config);
        }
        // O Quartz primeiro, trocando o job numa operação só: se ele recusa, o agendamento antigo segue
        // valendo e nada foi gravado. Antes o job era apagado e só depois recriado — a recusa deixava o
        // gatilho sem agendamento nenhum, com o cron recusado gravado como ativo (RCHFLW-140).
        agendar(config);
        Optional<ScheduledTriggerDto> gravado;
        try {
            gravado = store.updateConfig(config, existing.updatedAt());
        } catch (RuntimeException e) {
            ressincronizar(id, existing, e);
            throw e;
        }
        if (gravado.isEmpty()) {
            // Outra réplica gravou ou apagou o gatilho entre a leitura e a gravação: o Quartz volta a refletir
            // o que está gravado, e quem editava recebe o conflito.
            Optional<ScheduledTriggerDto> atual = store.find(id);
            RuntimeException recusa = recusaDaGravacao(id, atual);
            ressincronizar(id, atual.orElse(null), recusa);
            throw recusa;
        }
        return comAgenda(comDesfecho(gravado.get()));
    }

    /**
     * Religa um gatilho pausado: o registro passa a ativo e só depois o job entra no Quartz.
     *
     * <p>Na ordem da edição comum (Quartz, depois registro) havia uma janela com o job novo e o registro ainda
     * pausado. Um disparo nela ({@link #dispararPeloAgendamento}) ou a reconciliação da subida viam "pausado" e
     * apagavam o job: o registro ficava ativo sem agendamento, e a edição não tinha agendado nada. Com o
     * registro primeiro, um job só existe com o registro já ativo — o que os dois conferem —, e a janela
     * inversa (ativo ainda sem job) aparece como "sem agendamento", sem fingir estar ativa.</p>
     *
     * <p>Se o Quartz recusa, o registro volta à configuração e à versão anteriores, para a falha deixar tudo
     * como estava.</p>
     */
    private ScheduledTriggerDto religar(ScheduledTriggerDto existing, ScheduledTriggerDto config) {
        Optional<ScheduledTriggerDto> gravado = store.updateConfig(config, existing.updatedAt());
        if (gravado.isEmpty()) {
            // Nada foi tocado no Quartz: só a recusa.
            throw recusaDaGravacao(config.id(), store.find(config.id()));
        }
        try {
            agendar(config);
        } catch (RuntimeException e) {
            try {
                if (store.updateConfig(existing, config.updatedAt()).isEmpty()) {
                    log.warn("Gatilho {}: o Quartz recusou o religamento e outra gravação chegou antes de o "
                            + "registro voltar a pausado", config.id());
                }
            } catch (RuntimeException naoDesfez) {
                e.addSuppressed(naoDesfez);
            }
            throw e;
        }
        return comAgenda(comDesfecho(gravado.get()));
    }

    /** A recusa de uma gravação condicional que não aconteceu: o gatilho sumiu, ou mudou de versão. */
    private static RuntimeException recusaDaGravacao(String id, Optional<ScheduledTriggerDto> atual) {
        return atual.isEmpty() ? naoEncontrado(id) : new TriggerConflictException(id, atual.get().updatedAt());
    }

    @Override
    public synchronized void delete(String id) {
        if (!store.delete(id)) {
            throw naoEncontrado(id);
        }
        // Registro primeiro: se o job não sai agora, ele se remove no próximo disparo (não acha o registro)
        // ou na reconciliação da subida — nunca o contrário, um gatilho listado sem agendamento.
        try {
            scheduler.deleteJob(jobKey(id));
        } catch (SchedulerException e) {
            log.warn("Gatilho {} excluído, mas o job do Quartz não saiu agora: {}", id, e.getMessage());
        }
    }

    @Override
    public ScheduledTriggerDto fireNow(String id) {
        ScheduledTriggerDto dto = get(id);
        try {
            String requestId = submitInvocation(dto);
            recordFire(id, requestId);
            return comAgenda(store.find(id).orElse(dto));
        } catch (Exception e) {
            recordError(id, e.getMessage());
            throw new IllegalStateException(
                    "Failed to fire trigger " + id + ": " + e.getMessage(), e);
        }
    }

    /**
     * Remove os jobs do grupo de gatilhos que não têm registro ativo — os que ficaram no {@code JobStoreTX}
     * quando o registro ainda era só em memória (RCHFLW-187), ou de uma exclusão interrompida no meio.
     * Chamado na subida, depois das migrations.
     *
     * @return quantos jobs foram removidos
     */
    public synchronized int reconcileSchedules() {
        int removidos = 0;
        try {
            for (JobKey key : scheduler.getJobKeys(GroupMatcher.jobGroupEquals(GROUP))) {
                Optional<ScheduledTriggerDto> registro = store.find(key.getName());
                if (registro.isEmpty() || !registro.get().enabled()) {
                    scheduler.deleteJob(key);
                    removidos++;
                    log.warn("Job do Quartz {} sem gatilho ativo no registro: removido", key.getName());
                }
            }
        } catch (SchedulerException e) {
            throw new IllegalStateException("Failed to reconcile trigger schedules: " + e.getMessage(), e);
        }
        return removidos;
    }

    // ── Internals ────────────────────────────────────────────────────

    private static IllegalArgumentException naoEncontrado(String id) {
        return new IllegalArgumentException("Trigger not found: " + id);
    }

    /**
     * Nome, cron e agente de um gatilho que pode ser gravado (RCHFLW-191, RCHFLW-175).
     *
     * <p>O cron passa pelo {@link CronExpression} — o parser que o Quartz usa — e pela pergunta que o Quartz
     * faz ao agendar: há um próximo disparo? Sem ela, um cron como {@code 0 0 9 31 2 ?} (31 de fevereiro) ou
     * {@code 0 0 9 * * ? 2020} passava a validação e só falhava no {@code scheduleJob}, depois de gravado.</p>
     *
     * @param conferirDisparo se o cron precisa ter um próximo disparo; a sintaxe é conferida sempre
     * @param conferirAgente  se o agente precisa estar no catálogo
     */
    private void validar(String name, String cron, String agentId, boolean conferirDisparo,
                         boolean conferirAgente) {
        if (name == null || name.isBlank()) {
            throw new TriggerRejectedException(TriggerRejectedException.NAME_REQUIRED, "name is required");
        }
        if (cron == null || cron.isBlank()) {
            throw new TriggerRejectedException(TriggerRejectedException.CRON_INVALID, "cronExpression is required");
        }
        CronExpression expressao;
        try {
            expressao = new CronExpression(cron);
        } catch (ParseException e) {
            throw new TriggerRejectedException(TriggerRejectedException.CRON_INVALID,
                    "Invalid cron expression: " + cron + " (" + e.getMessage() + ")");
        }
        expressao.setTimeZone(zone);
        if (conferirDisparo && expressao.getNextValidTimeAfter(new Date()) == null) {
            throw new TriggerRejectedException(TriggerRejectedException.CRON_NEVER_FIRES,
                    "Cron expression never fires: " + cron);
        }
        if (agentId == null || agentId.isBlank() || (conferirAgente && !agentInCatalog.test(agentId))) {
            throw new TriggerRejectedException(TriggerRejectedException.AGENT_UNKNOWN,
                    "Unknown agent: " + agentId);
        }
    }

    /**
     * A versão seguinte da configuração: o instante da gravação, em milissegundos (o que o banco guarda sem
     * perder), e sempre maior que a anterior — duas gravações no mesmo milissegundo não podem ter a mesma
     * versão, senão o If-Match da segunda passaria com a leitura da primeira.
     */
    private static Instant proximaVersao(Instant anterior) {
        Instant agora = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return anterior != null && !agora.isAfter(anterior) ? anterior.plusMillis(1) : agora;
    }

    /** O job do gatilho como o registro diz: agendado se ativo (trocando o anterior), fora do Quartz se não. */
    private void agendar(ScheduledTriggerDto dto) {
        try {
            if (!dto.enabled()) {
                scheduler.deleteJob(jobKey(dto.id()));
                return;
            }
            JobDetail job = JobBuilder.newJob(TriggerJob.class)
                    .withIdentity(jobKey(dto.id()))
                    .usingJobData(new JobDataMap(Map.of("triggerId", dto.id())))
                    .build();
            Trigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity(triggerKey(dto.id()))
                    .withSchedule(CronScheduleBuilder.cronSchedule(dto.cronExpression()).inTimeZone(zone))
                    .build();
            scheduler.scheduleJob(job, Set.of(trigger), true);
        } catch (SchedulerException | RuntimeException e) {
            throw new IllegalStateException(
                    "Failed to schedule trigger " + dto.id() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Devolve ao Quartz o estado do registro ({@code gravado}; {@code null} = o gatilho não existe mais)
     * depois de uma gravação que não aconteceu. Uma falha aqui não substitui a original — vai junto dela.
     */
    private void ressincronizar(String id, ScheduledTriggerDto gravado, RuntimeException original) {
        try {
            if (gravado == null) {
                scheduler.deleteJob(jobKey(id));
            } else {
                agendar(gravado);
            }
        } catch (SchedulerException | RuntimeException e) {
            original.addSuppressed(e);
        }
    }

    /** O gatilho com a agenda que o Quartz tem agora para ele — {@code nextFireTime} nulo quando nenhuma. */
    private ScheduledTriggerDto comAgenda(ScheduledTriggerDto dto) {
        Instant proximo = null;
        try {
            Trigger trigger = scheduler.getTrigger(triggerKey(dto.id()));
            if (trigger != null && trigger.getNextFireTime() != null) {
                proximo = trigger.getNextFireTime().toInstant();
            }
        } catch (SchedulerException e) {
            // Sem saber, não se afirma que está agendado: a tela mostra "sem agendamento".
            log.warn("Agenda do gatilho {} indisponível: {}", dto.id(), e.getMessage());
        }
        return dto.withSchedule(proximo, zone.getID());
    }

    private static JobKey jobKey(String id) {
        return new JobKey(id, GROUP);
    }

    private static TriggerKey triggerKey(String id) {
        return new TriggerKey(id, GROUP);
    }

    /**
     * O disparo do cron. Um job cujo gatilho não está no registro, ou está pausado, se remove: antes ele
     * seguia disparando sem fazer nada, para sempre, depois de cada restart (RCHFLW-187).
     */
    void dispararPeloAgendamento(String triggerId) throws JobExecutionException {
        Optional<ScheduledTriggerDto> registro = store.find(triggerId);
        if (registro.isEmpty() || !registro.get().enabled()) {
            log.warn("Job do Quartz {} disparou sem gatilho ativo no registro: removido", triggerId);
            try {
                scheduler.deleteJob(jobKey(triggerId));
            } catch (SchedulerException e) {
                log.warn("Job do Quartz {} não pôde ser removido: {}", triggerId, e.getMessage());
            }
            return;
        }
        ScheduledTriggerDto dto = registro.get();
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

    /**
     * Submits the invocation to the queue.
     *
     * @return o requestId da invocação — guardado no gatilho para que o desfecho do agente seja
     *         consultável (o consumidor grava o resultado sob ele)
     */
    private String submitInvocation(ScheduledTriggerDto dto) {
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
     * Records a successful firing: a invocação está na fila, e o erro de um disparo anterior deixa de ser o
     * "último" — o desfecho desta vem do resultado dela.
     */
    private void recordFire(String triggerId, String requestId) {
        store.recordLastRun(triggerId, Instant.now(), null, requestId, AgentInvocationResult.Status.QUEUED.name());
    }

    /**
     * Records an error during firing. O status vai a {@code FAILED} (final): sem isso a leitura
     * consultaria o resultado do {@code lastRequestId} anterior, e um disparo bem-sucedido antigo
     * apagaria a falha deste.
     *
     * <p>O {@code lastRequestId} sai: este disparo não chegou a criar invocação, e o anterior não é o último.
     * Mantido, o "Ver resposta" da tela abria a execução anterior, concluída e com a resposta dela, ao lado de
     * um gatilho "com falha". Sem ele, também nenhum desfecho atrasado do anterior cobre esta falha
     * ({@code recordOutcome} só grava para o requestId que ainda é o último).</p>
     */
    private void recordError(String triggerId, String message) {
        try {
            store.find(triggerId).ifPresent(dto -> store.recordLastRun(triggerId, dto.lastFiredAt(), message,
                    null, AgentInvocationResult.Status.FAILED.name()));
        } catch (RuntimeException e) {
            log.warn("Falha do disparo do gatilho {} não pôde ser gravada: {}", triggerId, e.getMessage());
        }
    }

    /**
     * Quartz-instantiated job. Acha o controller no {@code SchedulerContext} do scheduler que o disparou,
     * porque o Quartz cria a instância com o construtor sem argumentos e não conhece o Spring.
     */
    public static final class TriggerJob implements org.quartz.Job {
        @Override
        public void execute(JobExecutionContext ctx) throws JobExecutionException {
            String triggerId = ctx.getJobDetail().getJobDataMap().getString("triggerId");
            Object dono;
            try {
                dono = ctx.getScheduler().getContext().get(CONTEXT_KEY);
            } catch (SchedulerException e) {
                throw new JobExecutionException("Trigger " + triggerId + ": registry unavailable", e);
            }
            if (!(dono instanceof ScheduledTriggerControllerImpl controller)) {
                throw new JobExecutionException("Trigger " + triggerId + ": no trigger registry in this scheduler");
            }
            controller.dispararPeloAgendamento(triggerId);
        }
    }
}
