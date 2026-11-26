package br.com.archflow.api.triggers;

import br.com.archflow.agent.queue.InMemoryAgentInvocationQueue;
import br.com.archflow.api.triggers.ScheduledTriggerController.TriggerRejectedException;
import br.com.archflow.api.triggers.dto.ScheduledTriggerDto;
import br.com.archflow.api.triggers.impl.ScheduledTriggerControllerImpl;
import br.com.archflow.api.triggers.store.InMemoryScheduledTriggerStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.CronTrigger;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.JobListener;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.TriggerKey;
import org.quartz.impl.StdSchedulerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pausar e religar um gatilho agendado.
 *
 * <p>Dois defeitos achados na revisão das correções de RCHFLW-140/175/191: pausar passava pela validação
 * completa — um gatilho cujo agente saiu do catálogo, ou cujo cron expirou, não podia ser desligado, só
 * excluído —, e religar agendava o job antes de o registro dizer "ativo", de modo que um disparo nessa janela
 * via o registro pausado e apagava o job que a própria edição acabara de criar.</p>
 */
@DisplayName("Gatilho agendado: pausar e religar")
class GatilhoPausarEReligarTest {

    private static final String GROUP = "archflow-triggers";
    private static final String CRON_OK = "0 0 9 ? * MON-FRI";
    /** Cron de 7 campos cujo ano já passou: valia quando foi gravado, hoje não tem próximo disparo. */
    private static final String CRON_EXPIRADO = "0 0 9 * * ? 2020";

    private Scheduler real;
    private InMemoryScheduledTriggerStore store;
    private final Set<String> catalogo = new HashSet<>(Set.of("agente-x"));
    private final AtomicBoolean quartzRecusa = new AtomicBoolean();
    /** Faz o job disparar logo depois do {@code scheduleJob}, antes de o {@code update} terminar. */
    private final AtomicBoolean dispararNaJanela = new AtomicBoolean();
    private final AtomicReference<CountDownLatch> disparou = new AtomicReference<>();
    private ScheduledTriggerController controller;

    @BeforeEach
    void setUp() throws Exception {
        Properties props = new Properties();
        props.setProperty("org.quartz.scheduler.instanceName", "gatilho-pausar-religar");
        props.setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
        props.setProperty("org.quartz.threadPool.threadCount", "1");
        props.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
        StdSchedulerFactory factory = new StdSchedulerFactory();
        factory.initialize(props);
        real = factory.getScheduler();
        real.getListenerManager().addJobListener(new JobListener() {
            @Override
            public String getName() {
                return "disparou";
            }

            @Override
            public void jobToBeExecuted(JobExecutionContext context) {
            }

            @Override
            public void jobExecutionVetoed(JobExecutionContext context) {
            }

            @Override
            public void jobWasExecuted(JobExecutionContext context, JobExecutionException e) {
                CountDownLatch latch = disparou.get();
                if (latch != null) {
                    latch.countDown();
                }
            }
        });
        real.start();
        Scheduler proxy = (Scheduler) Proxy.newProxyInstance(Scheduler.class.getClassLoader(),
                new Class<?>[] {Scheduler.class}, (p, method, args) -> {
                    if (method.getName().equals("scheduleJob") && quartzRecusa.get()) {
                        throw new SchedulerException("JobStore indisponível");
                    }
                    Object r;
                    try {
                        r = method.invoke(real, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                    if (method.getName().equals("scheduleJob") && dispararNaJanela.getAndSet(false)) {
                        // O cron dispara entre o agendamento e a gravação do registro.
                        CountDownLatch latch = new CountDownLatch(1);
                        disparou.set(latch);
                        JobKey key = ((org.quartz.JobDetail) args[0]).getKey();
                        real.triggerJob(key);
                        assertThat(latch.await(5, TimeUnit.SECONDS)).as("o job não disparou").isTrue();
                    }
                    return r;
                });
        store = new InMemoryScheduledTriggerStore();
        controller = new ScheduledTriggerControllerImpl(proxy, new InMemoryAgentInvocationQueue(), null, store,
                catalogo::contains);
    }

    @AfterEach
    void tearDown() throws Exception {
        real.shutdown(false);
    }

    private static ScheduledTriggerDto gatilho(String id, String cron, String agente, boolean ativo) {
        return new ScheduledTriggerDto(id, "Resumo diário", cron, "tenant-a", agente, Map.of("pedido", "x"),
                ativo, null, null, null, null);
    }

    /** Um gatilho gravado no passado, quando o cron ainda disparava — o que a validação de hoje recusaria. */
    private ScheduledTriggerDto gravadoAntes(String cron, boolean ativo) {
        Instant antes = Instant.now().minus(400, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        ScheduledTriggerDto t = new ScheduledTriggerDto("trg-antigo", "Resumo diário", cron, "tenant-a",
                "agente-x", Map.of(), ativo, antes, null, null, null, null, antes, null, null);
        assertThat(store.insert(t)).isTrue();
        return t;
    }

    private String cronNoQuartz(String id) throws SchedulerException {
        CronTrigger t = (CronTrigger) real.getTrigger(new TriggerKey(id, GROUP));
        return t == null ? null : t.getCronExpression();
    }

    @Test
    @DisplayName("pausar um gatilho cujo agente saiu do catálogo: o job sai e o registro fica pausado")
    void pausarComAgenteForaDoCatalogo() throws Exception {
        ScheduledTriggerDto t = controller.create(gatilho(null, CRON_OK, "agente-x", true));
        catalogo.remove("agente-x");

        ScheduledTriggerDto pausado = controller.update(t.id(), gatilho(t.id(), CRON_OK, "agente-x", false));

        assertThat(pausado.enabled()).isFalse();
        assertThat(cronNoQuartz(t.id())).as("o job seguiria disparando e falhando").isNull();
    }

    @Test
    @DisplayName("pausar um gatilho cujo cron expirou: aceito, sem precisar trocar o cron")
    void pausarComCronExpirado() {
        ScheduledTriggerDto t = gravadoAntes(CRON_EXPIRADO, true);

        ScheduledTriggerDto pausado = controller.update(t.id(), gatilho(t.id(), CRON_EXPIRADO, "agente-x", false));

        assertThat(pausado.enabled()).isFalse();
        assertThat(pausado.cronExpression()).isEqualTo(CRON_EXPIRADO);
    }

    @Test
    @DisplayName("pausar não abre exceção para o que a edição muda: agente novo desconhecido ou cron novo morto")
    void pausarConfereOQueMuda() {
        ScheduledTriggerDto t = controller.create(gatilho(null, CRON_OK, "agente-x", true));

        assertThatThrownBy(() -> controller.update(t.id(), gatilho(t.id(), CRON_OK, "agente-que-nao-existe", false)))
                .isInstanceOfSatisfying(TriggerRejectedException.class,
                        e -> assertThat(e.code()).isEqualTo(TriggerRejectedException.AGENT_UNKNOWN));
        assertThatThrownBy(() -> controller.update(t.id(), gatilho(t.id(), CRON_EXPIRADO, "agente-x", false)))
                .isInstanceOfSatisfying(TriggerRejectedException.class,
                        e -> assertThat(e.code()).isEqualTo(TriggerRejectedException.CRON_NEVER_FIRES));
        assertThat(controller.get(t.id()).enabled()).isTrue();
    }

    @Test
    @DisplayName("religar confere tudo de novo: cron expirado ou agente fora do catálogo são recusados")
    void religarConfereTudo() {
        ScheduledTriggerDto expirado = gravadoAntes(CRON_EXPIRADO, false);
        assertThatThrownBy(() -> controller.update(expirado.id(),
                gatilho(expirado.id(), CRON_EXPIRADO, "agente-x", true)))
                .isInstanceOfSatisfying(TriggerRejectedException.class,
                        e -> assertThat(e.code()).isEqualTo(TriggerRejectedException.CRON_NEVER_FIRES));

        ScheduledTriggerDto t = controller.create(gatilho(null, CRON_OK, "agente-x", false));
        catalogo.remove("agente-x");
        assertThatThrownBy(() -> controller.update(t.id(), gatilho(t.id(), CRON_OK, "agente-x", true)))
                .isInstanceOfSatisfying(TriggerRejectedException.class,
                        e -> assertThat(e.code()).isEqualTo(TriggerRejectedException.AGENT_UNKNOWN));
    }

    @Test
    @DisplayName("religar com o cron disparando no meio da edição: o job novo não é apagado")
    void religarComDisparoNaJanela() throws Exception {
        ScheduledTriggerDto t = controller.create(gatilho(null, CRON_OK, "agente-x", false));
        dispararNaJanela.set(true);

        ScheduledTriggerDto religado = controller.update(t.id(), gatilho(t.id(), CRON_OK, "agente-x", true));

        assertThat(dispararNaJanela.get()).as("o disparo na janela não aconteceu").isFalse();
        assertThat(cronNoQuartz(t.id())).as("o disparo viu o registro pausado e apagou o job").isEqualTo(CRON_OK);
        assertThat(religado.nextFireTime()).isNotNull();
        assertThat(controller.get(t.id()).enabled()).isTrue();
    }

    @Test
    @DisplayName("o Quartz recusa o religamento: o registro volta a pausado, com a versão de antes")
    void religarRecusadoPeloQuartz() throws Exception {
        ScheduledTriggerDto t = controller.create(gatilho(null, CRON_OK, "agente-x", false));
        quartzRecusa.set(true);

        assertThatThrownBy(() -> controller.update(t.id(), gatilho(t.id(), "0 30 7 * * ?", "agente-x", true)))
                .isInstanceOf(IllegalStateException.class);

        quartzRecusa.set(false);
        ScheduledTriggerDto lido = controller.get(t.id());
        assertThat(lido.enabled()).isFalse();
        assertThat(lido.cronExpression()).isEqualTo(CRON_OK);
        assertThat(lido.updatedAt()).isEqualTo(t.updatedAt());
        assertThat(cronNoQuartz(t.id())).isNull();
    }
}
