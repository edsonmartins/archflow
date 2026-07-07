package br.com.archflow.api.config;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.Scheduler;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integração com PostgreSQL real: aplica a migration {@code V6_1__create_quartz.sql}
 * (DDL oficial {@code QRTZ_*}) e verifica que {@link DurableQuartzScheduler} produz
 * um scheduler com {@code JobStoreTX} e que um job agendado é gravado nas tabelas
 * (portanto sobrevive a restart), ao contrário do {@code RAMJobStore}.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Quartz Scheduler durável (PostgreSQL real)")
class DurableQuartzSchedulerPostgresTest {

    private static final String DELEGATE = "org.quartz.impl.jdbcjobstore.PostgreSQLDelegate";

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static DataSource dataSource;

    @BeforeAll
    static void applyMigration() throws Exception {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        dataSource = ds;

        String ddl;
        try (var in = DurableQuartzSchedulerPostgresTest.class.getResourceAsStream(
                "/db/migration/V6_1__create_quartz.sql")) {
            ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute(ddl);
        }
    }

    @BeforeEach
    void cleanTables() throws SQLException {
        // As sub-tabelas de trigger têm FK para QRTZ_TRIGGERS (sem CASCADE), e
        // QRTZ_TRIGGERS tem FK para QRTZ_JOB_DETAILS: apaga na ordem filhos→pais.
        try (Connection conn = dataSource.getConnection()) {
            for (String table : new String[]{
                    "QRTZ_SIMPLE_TRIGGERS", "QRTZ_CRON_TRIGGERS", "QRTZ_SIMPROP_TRIGGERS",
                    "QRTZ_BLOB_TRIGGERS", "QRTZ_TRIGGERS", "QRTZ_JOB_DETAILS",
                    "QRTZ_FIRED_TRIGGERS"}) {
                conn.createStatement().execute("DELETE FROM " + table);
            }
        }
    }

    @Test
    @DisplayName("usa JobStoreTX (JDBC), não RAMJobStore")
    void usesDurableJobStore() throws Exception {
        Scheduler scheduler = DurableQuartzScheduler.create(dataSource, DELEGATE, "test-durable");
        try {
            assertThat(scheduler.getMetaData().getJobStoreClass().getName())
                    .contains("JobStoreTX");
            assertThat(scheduler.getMetaData().isJobStoreSupportsPersistence()).isTrue();
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    @DisplayName("job agendado é persistido nas tabelas QRTZ_*")
    void scheduledJobIsPersisted() throws Exception {
        Scheduler scheduler = DurableQuartzScheduler.create(dataSource, DELEGATE, "test-persist");
        scheduler.start();
        try {
            JobDetail job = JobBuilder.newJob(NoOpJob.class)
                    .withIdentity("job1", "grp")
                    .storeDurably()
                    .build();
            Trigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity("trig1", "grp")
                    .forJob(job)
                    .startAt(Date.from(Instant.now().plusSeconds(3600))) // futuro: não dispara
                    .build();
            scheduler.scheduleJob(job, trigger);

            assertThat(countRows("QRTZ_JOB_DETAILS", "JOB_NAME", "job1")).isEqualTo(1);
            assertThat(countRows("QRTZ_TRIGGERS", "TRIGGER_NAME", "trig1")).isEqualTo(1);
        } finally {
            scheduler.shutdown();
        }
    }

    private static int countRows(String table, String column, String value) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?")) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /** Job de teste sem efeito — nunca dispara (trigger no futuro). */
    public static class NoOpJob implements Job {
        @Override
        public void execute(JobExecutionContext context) {
            // no-op
        }
    }
}
