package br.com.archflow.api.config;

import br.com.archflow.engine.core.StateManager;
import br.com.archflow.engine.persistence.RepositoryStateManager;
import br.com.archflow.engine.persistence.jdbc.JdbcStateRepository;
import br.com.archflow.observability.audit.AuditRepository;
import br.com.archflow.observability.audit.JdbcAuditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Liga a persistência durável (PostgreSQL) sem o deployer precisar declarar
 * bean a bean, quando {@code archflow.persistence.jdbc.enabled=true}.
 *
 * <p>Toggle pela mesma propriedade que desliga os defaults em memória de
 * {@code ArchflowBeanConfiguration} (mutuamente exclusivos via
 * {@code @ConditionalOnProperty}) — determinístico, sem depender da ordem de
 * processamento das configurações.
 *
 * <p>Quando habilitado, um {@link DataSource} é <b>obrigatório</b> (injetado
 * nos métodos de bean) — tipicamente fornecido por
 * {@code spring-boot-starter-jdbc} + {@code spring.datasource.*}. Sem ele o
 * contexto falha com mensagem clara, o que é o comportamento correto: declarar
 * JDBC sem banco é erro de configuração.
 *
 * <p>Cobre os stores cuja interface encaixa de forma limpa:
 * <ul>
 *   <li>{@link StateManager} — estado de execução dos fluxos (resume sobrevive
 *       a restart);</li>
 *   <li>{@link AuditRepository} — trilha de auditoria.</li>
 * </ul>
 * O {@code FlowRepository} JDBC depende de um {@code FlowJsonCodec} específico
 * da implementação de {@code Flow} do deployment, então é declarado por quem
 * sobe o serviço (uma linha {@code @Bean}); ver
 * {@code docs/development/production-persistence.md}.
 */
@Configuration
@ConditionalOnProperty(name = "archflow.persistence.jdbc.enabled", havingValue = "true")
public class JdbcPersistenceConfiguration {

    private static final Logger log = LoggerFactory.getLogger(JdbcPersistenceConfiguration.class);

    @Bean
    public StateManager stateManager(DataSource dataSource) {
        log.info("JDBC persistence ativo: StateManager durável (RepositoryStateManager/JdbcStateRepository)");
        return new RepositoryStateManager(new JdbcStateRepository(dataSource));
    }

    @Bean
    public AuditRepository auditRepository(DataSource dataSource) {
        log.info("JDBC persistence ativo: AuditRepository durável (JdbcAuditRepository)");
        return new JdbcAuditRepository(dataSource);
    }

    /**
     * Scheduler Quartz durável ({@code JobStoreTX}/{@code JDBCJobStore}) — triggers
     * agendados sobrevivem a restart, ao contrário do {@code RAMJobStore} default de
     * {@code ArchflowBeanConfiguration} (que recua via {@code @ConditionalOnMissingBean}).
     * Requer as tabelas {@code QRTZ_*} (migration {@code V001__create_quartz.sql}). O
     * delegate JDBC é configurável (default PostgreSQL).
     */
    @Bean(destroyMethod = "shutdown")
    public org.quartz.Scheduler quartzScheduler(
            DataSource dataSource,
            @Value("${archflow.persistence.quartz.driver-delegate:"
                    + "org.quartz.impl.jdbcjobstore.PostgreSQLDelegate}") String driverDelegate)
            throws org.quartz.SchedulerException {
        log.info("JDBC persistence ativo: Quartz Scheduler durável (JobStoreTX/JDBCJobStore)");
        // Cria sem iniciar: getScheduler() não abre conexão. O start() (que faz
        // I/O — recuperação de triggers) é adiado para depois do refresh, após
        // eventuais migrations, como no seeder de admin.
        return DurableQuartzScheduler.create(dataSource, driverDelegate, "archflow-triggers");
    }

    @Bean
    public org.springframework.boot.ApplicationRunner quartzSchedulerStarter(
            org.quartz.Scheduler quartzScheduler) {
        return args -> {
            if (!quartzScheduler.isStarted()) {
                quartzScheduler.start();
                log.info("Quartz Scheduler durável iniciado (JobStoreTX)");
            }
        };
    }
}
