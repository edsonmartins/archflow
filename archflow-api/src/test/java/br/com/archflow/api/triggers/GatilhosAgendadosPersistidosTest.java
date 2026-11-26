package br.com.archflow.api.triggers;

import br.com.archflow.agent.queue.InMemoryAgentInvocationQueue;
import br.com.archflow.api.triggers.ScheduledTriggerController.TriggerConflictException;
import br.com.archflow.api.triggers.dto.ScheduledTriggerDto;
import br.com.archflow.api.triggers.impl.ScheduledTriggerControllerImpl;
import br.com.archflow.api.triggers.store.JdbcScheduledTriggerStore;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.CronTrigger;
import org.quartz.JobBuilder;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.quartz.impl.StdSchedulerFactory;
import org.quartz.impl.matchers.GroupMatcher;

import javax.sql.DataSource;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RCHFLW-187: o registro de gatilhos vivia só em memória — com o JDBC ligado, o job do Quartz sobrevivia ao
 * restart e o gatilho não: a tela listava nada e o job seguia disparando sem achar o gatilho, sem como ser
 * apagado. Aqui o registro passa pelo store JDBC sobre a migration real (V6_15) num H2 em modo PostgreSQL, e o
 * "restart" é um controller e um store novos sobre o mesmo banco. O restart com o {@code JobStoreTX} de verdade
 * está em {@code GatilhosAgendadosSobrevivemAoRestartPostgresTest}.
 */
@DisplayName("Gatilhos agendados — registro persistido e coerente com o Quartz (RCHFLW-187)")
class GatilhosAgendadosPersistidosTest {

    private static final String GROUP = "archflow-triggers";

    private JdbcDataSource dataSource;
    private final List<Scheduler> schedulers = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:gatilhos-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        String sql;
        try (InputStream in = getClass().getResourceAsStream("/db/migration/V6_15__create_scheduled_triggers.sql")) {
            assertThat(in).isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        StringBuilder semComentarios = new StringBuilder();
        for (String line : sql.split("\n")) {
            if (!line.strip().startsWith("--")) {
                semComentarios.append(line).append('\n');
            }
        }
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            for (String statement : semComentarios.toString().split(";")) {
                if (!statement.isBlank()) {
                    st.execute(statement);
                }
            }
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        for (Scheduler s : schedulers) {
            s.shutdown(false);
        }
    }

    private Scheduler scheduler() throws Exception {
        Properties props = new Properties();
        props.setProperty("org.quartz.scheduler.instanceName", "gatilhos-persistidos-" + UUID.randomUUID());
        props.setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
        props.setProperty("org.quartz.threadPool.threadCount", "1");
        props.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
        StdSchedulerFactory factory = new StdSchedulerFactory();
        factory.initialize(props);
        Scheduler s = factory.getScheduler();
        s.start();
        schedulers.add(s);
        return s;
    }

    private ScheduledTriggerControllerImpl servidor(Scheduler scheduler) {
        return new ScheduledTriggerControllerImpl(scheduler, new InMemoryAgentInvocationQueue(), null,
                new JdbcScheduledTriggerStore(dataSource), agentId -> true);
    }

    private static ScheduledTriggerDto gatilho(String cron, boolean ativo) {
        return new ScheduledTriggerDto(null, "Resumo diário", cron, "tenant-a", "agente-x",
                Map.of("pedido", "reembolso", "itens", List.of(1, 2)), ativo, null, null, null, null);
    }

    @Test
    @DisplayName("o gatilho sobrevive ao restart: configuração, payload e último disparo")
    void sobreviveAoRestart() throws Exception {
        ScheduledTriggerControllerImpl antes = servidor(scheduler());
        ScheduledTriggerDto criado = antes.create(gatilho("0 0 9 ? * MON-FRI", true));
        String requestId = antes.fireNow(criado.id()).lastRequestId();

        ScheduledTriggerControllerImpl depois = servidor(scheduler());

        assertThat(depois.list()).singleElement().satisfies(t -> {
            assertThat(t.id()).isEqualTo(criado.id());
            assertThat(t.name()).isEqualTo("Resumo diário");
            assertThat(t.cronExpression()).isEqualTo("0 0 9 ? * MON-FRI");
            assertThat(t.tenantId()).isEqualTo("tenant-a");
            assertThat(t.agentId()).isEqualTo("agente-x");
            assertThat(t.payload()).isEqualTo(Map.of("pedido", "reembolso", "itens", List.of(1, 2)));
            assertThat(t.enabled()).isTrue();
            assertThat(t.createdAt()).isEqualTo(criado.createdAt());
            assertThat(t.updatedAt()).isEqualTo(criado.updatedAt());
            assertThat(t.lastRequestId()).isEqualTo(requestId);
            assertThat(t.lastStatus()).isEqualTo("QUEUED");
        });
    }

    @Test
    @DisplayName("ativo é o que o Quartz tem agendado: sem job, o próximo disparo é nulo")
    void ativoSemAgendamentoNaoTemProximoDisparo() throws Exception {
        Scheduler scheduler = scheduler();
        ScheduledTriggerControllerImpl controller = servidor(scheduler);
        ScheduledTriggerDto criado = controller.create(gatilho("0 0 9 ? * MON-FRI", true));
        assertThat(controller.get(criado.id()).nextFireTime()).isAfter(Instant.now());
        assertThat(controller.get(criado.id()).timeZone()).isEqualTo(java.util.TimeZone.getDefault().getID());

        // O restart com RAMJobStore: o registro durável ficou, o job não.
        ScheduledTriggerControllerImpl depois = servidor(scheduler());

        ScheduledTriggerDto lido = depois.get(criado.id());
        assertThat(lido.enabled()).isTrue();
        assertThat(lido.nextFireTime()).as("aparece como agendado sem estar").isNull();
    }

    @Test
    @DisplayName("na subida, os jobs do Quartz sem gatilho no registro são removidos")
    void reconciliaJobsOrfaos() throws Exception {
        Scheduler scheduler = scheduler();
        ScheduledTriggerControllerImpl controller = servidor(scheduler);
        ScheduledTriggerDto vivo = controller.create(gatilho("0 0 9 ? * MON-FRI", true));
        // O job que o registro em memória deixou no banco antes da V6_15: no Quartz, sem registro.
        scheduler.scheduleJob(JobBuilder.newJob(ScheduledTriggerControllerImpl.TriggerJob.class)
                        .withIdentity("trg-1272d2e3", GROUP).usingJobData("triggerId", "trg-1272d2e3").build(),
                TriggerBuilder.newTrigger().withIdentity("trg-1272d2e3", GROUP)
                        .startAt(java.util.Date.from(Instant.parse("2099-01-01T00:00:00Z"))).build());

        assertThat(controller.reconcileSchedules()).isEqualTo(1);

        assertThat(scheduler.getJobKeys(GroupMatcher.jobGroupEquals(GROUP)))
                .containsExactly(new JobKey(vivo.id(), GROUP));
    }

    @Test
    @DisplayName("o job que dispara sem gatilho no registro se remove, em vez de disparar à toa para sempre")
    void jobOrfaoSeRemoveAoDisparar() throws Exception {
        Scheduler scheduler = scheduler();
        servidor(scheduler);
        scheduler.scheduleJob(JobBuilder.newJob(ScheduledTriggerControllerImpl.TriggerJob.class)
                        .withIdentity("trg-orfao", GROUP).usingJobData("triggerId", "trg-orfao").build(),
                TriggerBuilder.newTrigger().withIdentity("trg-orfao", GROUP).startNow()
                        .withSchedule(SimpleScheduleBuilder.repeatSecondlyForever()).build());

        long prazo = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (scheduler.checkExists(new JobKey("trg-orfao", GROUP)) && System.nanoTime() < prazo) {
            Thread.sleep(20);
        }
        assertThat(scheduler.checkExists(new JobKey("trg-orfao", GROUP))).isFalse();
    }

    @Test
    @DisplayName("edição com a versão lida antiga: 409 e nada muda, nem registro nem Quartz")
    void edicaoConcorrente() throws Exception {
        ScheduledTriggerControllerImpl controller = servidor(scheduler());
        ScheduledTriggerDto lidoPorA = controller.create(gatilho("0 0 3 * * ?", true));
        // B grava primeiro.
        ScheduledTriggerDto deB = controller.update(lidoPorA.id(), new ScheduledTriggerDto(lidoPorA.id(),
                lidoPorA.name(), "0 15 22 ? * FRI", "tenant-a", "agente-x", Map.of("de", "B"), true,
                null, null, null, null), lidoPorA.updatedAt());
        assertThat(deB.updatedAt()).isAfter(lidoPorA.updatedAt());

        // A salva o formulário que abriu antes, mudando só o nome.
        assertThatThrownBy(() -> controller.update(lidoPorA.id(), new ScheduledTriggerDto(lidoPorA.id(),
                "Renomeado por A", "0 0 3 * * ?", "tenant-a", "agente-x", Map.of(), true,
                null, null, null, null), lidoPorA.updatedAt()))
                .isInstanceOf(TriggerConflictException.class)
                .satisfies(e -> assertThat(((TriggerConflictException) e).currentUpdatedAt())
                        .isEqualTo(deB.updatedAt()));

        ScheduledTriggerDto atual = controller.get(lidoPorA.id());
        assertThat(atual.cronExpression()).isEqualTo("0 15 22 ? * FRI");
        assertThat(atual.payload()).isEqualTo(Map.of("de", "B"));
        assertThat(atual.name()).isEqualTo(lidoPorA.name());
    }

    @Test
    @DisplayName("disparo do cron no meio da edição não é conflito: o último disparo não muda a versão")
    void disparoNaoMudaAVersao() throws Exception {
        ScheduledTriggerControllerImpl controller = servidor(scheduler());
        ScheduledTriggerDto lido = controller.create(gatilho("0 0 3 * * ?", true));
        controller.fireNow(lido.id());

        ScheduledTriggerDto gravado = controller.update(lido.id(), new ScheduledTriggerDto(lido.id(), "Novo nome",
                "0 0 3 * * ?", "tenant-a", "agente-x", Map.of(), true, null, null, null, null), lido.updatedAt());

        assertThat(gravado.name()).isEqualTo("Novo nome");
        assertThat(gravado.lastRequestId()).as("a edição apagou o último disparo").isNotNull();
    }

    /**
     * A releitura depois do {@code UPDATE} falhava fora da transação: a edição ficava gravada, o método
     * lançava, e o controller devolvia ao Quartz o cron anterior — banco com o cron novo, Quartz com o velho.
     */
    @Test
    @DisplayName("a releitura depois da gravação falha: nada fica gravado, e registro e Quartz concordam")
    void releituraFalhaDepoisDaGravacao() throws Exception {
        Scheduler scheduler = scheduler();
        AtomicBoolean releituraFalha = new AtomicBoolean();
        // A releitura falha depois do UPDATE; a leitura do gatilho antes da edição, não.
        DataSource comFalha = falhandoNoSelect(dataSource, releituraFalha);
        ScheduledTriggerControllerImpl controller = new ScheduledTriggerControllerImpl(scheduler,
                new InMemoryAgentInvocationQueue(), null, new JdbcScheduledTriggerStore(comFalha), agentId -> true);
        ScheduledTriggerDto lido = controller.create(gatilho("0 0 3 * * ?", true));

        releituraFalha.set(true);
        assertThatThrownBy(() -> controller.update(lido.id(), new ScheduledTriggerDto(lido.id(), "Resumo diário",
                "0 30 7 * * ?", "tenant-a", "agente-x", Map.of(), true, null, null, null, null), lido.updatedAt()))
                .isInstanceOf(IllegalStateException.class);
        releituraFalha.set(false);

        ScheduledTriggerDto gravado = controller.get(lido.id());
        CronTrigger noQuartz = (CronTrigger) scheduler.getTrigger(new TriggerKey(lido.id(), GROUP));
        assertThat(gravado.cronExpression()).as("o cliente recebeu erro de uma edição gravada")
                .isEqualTo("0 0 3 * * ?");
        assertThat(gravado.updatedAt()).isEqualTo(lido.updatedAt());
        assertThat(noQuartz.getCronExpression()).isEqualTo(gravado.cronExpression());
    }

    /**
     * O {@code dataSource} em que, com {@code armado} ligado, o primeiro {@code SELECT} depois da troca de
     * configuração ({@code UPDATE ... SET name}) falha — a releitura do gatilho recém-gravado, e só ela.
     */
    private static DataSource falhandoNoSelect(DataSource ds, AtomicBoolean armado) {
        AtomicBoolean depoisDoUpdate = new AtomicBoolean();
        InvocationHandler conexoes = (p, m, a) -> {
            Object r;
            try {
                r = m.invoke(ds, a);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
            if (!(r instanceof Connection conn)) {
                return r;
            }
            return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                    (pc, mc, ac) -> {
                        if (armado.get() && mc.getName().equals("prepareStatement")) {
                            String sql = String.valueOf(ac[0]);
                            if (sql.startsWith("UPDATE scheduled_triggers SET name")) {
                                depoisDoUpdate.set(true);
                            } else if (sql.startsWith("SELECT") && depoisDoUpdate.getAndSet(false)) {
                                throw new SQLException("conexão caiu");
                            }
                        }
                        try {
                            return mc.invoke(conn, ac);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        };
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[] {DataSource.class}, conexoes);
    }

    @Test
    @DisplayName("excluir apaga o registro e o job; o gatilho não volta no restart")
    void excluir() throws Exception {
        Scheduler scheduler = scheduler();
        ScheduledTriggerControllerImpl controller = servidor(scheduler);
        ScheduledTriggerDto criado = controller.create(gatilho("0 0 3 * * ?", true));

        controller.delete(criado.id());

        assertThat(scheduler.checkExists(new JobKey(criado.id(), GROUP))).isFalse();
        assertThat(servidor(scheduler()).list()).isEmpty();
    }
}
