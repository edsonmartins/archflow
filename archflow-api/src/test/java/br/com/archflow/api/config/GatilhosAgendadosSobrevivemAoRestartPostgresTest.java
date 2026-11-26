package br.com.archflow.api.config;

import br.com.archflow.agent.queue.InMemoryAgentInvocationQueue;
import br.com.archflow.api.triggers.dto.ScheduledTriggerDto;
import br.com.archflow.api.triggers.impl.ScheduledTriggerControllerImpl;
import br.com.archflow.api.triggers.store.JdbcScheduledTriggerStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.quartz.JobBuilder;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.TriggerBuilder;
import org.quartz.impl.matchers.GroupMatcher;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RCHFLW-187 com o par de produção: PostgreSQL real, Quartz em {@code JobStoreTX} (V6_1) e o registro em
 * {@code scheduled_triggers} (V6_15). Antes o restart deixava o job no banco e o gatilho em lugar nenhum; aqui o
 * "restart" é desligar o scheduler e subir outro, com store e controller novos, sobre o mesmo banco.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Gatilhos agendados sobrevivem ao restart (PostgreSQL real)")
class GatilhosAgendadosSobrevivemAoRestartPostgresTest {

    private static final String DELEGATE = "org.quartz.impl.jdbcjobstore.PostgreSQLDelegate";
    private static final String GROUP = "archflow-triggers";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16");

    private static DataSource dataSource;
    private final List<Scheduler> ligados = new ArrayList<>();

    @BeforeAll
    static void migrar() throws Exception {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        dataSource = ds;
        for (String migration : List.of("V6_1__create_quartz.sql", "V6_15__create_scheduled_triggers.sql")) {
            String ddl;
            try (var in = GatilhosAgendadosSobrevivemAoRestartPostgresTest.class.getResourceAsStream(
                    "/db/migration/" + migration)) {
                ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            try (Connection conn = dataSource.getConnection()) {
                conn.createStatement().execute(ddl);
            }
        }
    }

    @BeforeEach
    void limpar() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            for (String table : new String[]{
                    "QRTZ_SIMPLE_TRIGGERS", "QRTZ_CRON_TRIGGERS", "QRTZ_SIMPROP_TRIGGERS",
                    "QRTZ_BLOB_TRIGGERS", "QRTZ_TRIGGERS", "QRTZ_JOB_DETAILS",
                    "QRTZ_FIRED_TRIGGERS", "scheduled_triggers"}) {
                conn.createStatement().execute("DELETE FROM " + table);
            }
        }
    }

    @AfterEach
    void desligar() throws Exception {
        for (Scheduler s : ligados) {
            if (!s.isShutdown()) {
                s.shutdown(false);
            }
        }
    }

    private Scheduler subirQuartz(String nome) throws Exception {
        Scheduler s = DurableQuartzScheduler.create(dataSource, DELEGATE, "archflow-triggers-" + nome);
        s.start();
        ligados.add(s);
        return s;
    }

    private static ScheduledTriggerControllerImpl controller(Scheduler scheduler) {
        return new ScheduledTriggerControllerImpl(scheduler, new InMemoryAgentInvocationQueue(), null,
                new JdbcScheduledTriggerStore(dataSource), agentId -> true);
    }

    @Test
    @DisplayName("depois do restart o gatilho está na lista, agendado, e editar e excluir funcionam")
    void sobreviveAoRestart() throws Exception {
        Scheduler antes = subirQuartz("a");
        ScheduledTriggerDto criado = controller(antes).create(new ScheduledTriggerDto(null, "Resumo diário",
                "0 0 9 ? * MON-FRI", "tenant-a", "agente-x", Map.of("pedido", "reembolso"), true,
                null, null, null, null));
        antes.shutdown(true);

        // O mesmo SCHED_NAME do primeiro: é o que acha os jobs gravados por ele.
        Scheduler depois = DurableQuartzScheduler.create(dataSource, DELEGATE, "archflow-triggers-a");
        depois.start();
        ligados.add(depois);
        ScheduledTriggerControllerImpl reiniciado = controller(depois);
        assertThat(reiniciado.reconcileSchedules()).isZero();

        ScheduledTriggerDto lido = reiniciado.get(criado.id());
        assertThat(lido.name()).isEqualTo("Resumo diário");
        assertThat(lido.payload()).isEqualTo(Map.of("pedido", "reembolso"));
        assertThat(lido.updatedAt()).isEqualTo(criado.updatedAt());
        assertThat(lido.nextFireTime()).as("o job do Quartz sobreviveu junto").isNotNull();

        ScheduledTriggerDto editado = reiniciado.update(criado.id(), new ScheduledTriggerDto(criado.id(),
                "Resumo diário", "0 30 7 * * ?", "tenant-a", "agente-x", Map.of(), true,
                null, null, null, null), lido.updatedAt());
        assertThat(editado.cronExpression()).isEqualTo("0 30 7 * * ?");
        reiniciado.delete(criado.id());
        assertThat(reiniciado.list()).isEmpty();
        assertThat(depois.getJobKeys(GroupMatcher.jobGroupEquals(GROUP))).isEmpty();
    }

    @Test
    @DisplayName("o job órfão que o registro em memória deixou no banco é removido na subida")
    void removeOrfaoNaSubida() throws Exception {
        Scheduler antigo = subirQuartz("b");
        antigo.scheduleJob(JobBuilder.newJob(ScheduledTriggerControllerImpl.TriggerJob.class)
                        .withIdentity("trg-1272d2e3", GROUP).usingJobData("triggerId", "trg-1272d2e3").build(),
                TriggerBuilder.newTrigger().withIdentity("trg-1272d2e3", GROUP)
                        .startAt(Date.from(Instant.parse("2099-01-01T00:00:00Z"))).build());

        assertThat(controller(antigo).reconcileSchedules()).isEqualTo(1);

        assertThat(antigo.checkExists(new JobKey("trg-1272d2e3", GROUP))).isFalse();
    }
}
