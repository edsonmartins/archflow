package br.com.archflow.api.config;

import br.com.archflow.engine.core.StateManager;
import br.com.archflow.engine.persistence.RepositoryStateManager;
import br.com.archflow.engine.persistence.jdbc.JdbcStateRepository;
import br.com.archflow.observability.audit.AuditRepository;
import br.com.archflow.observability.audit.JdbcAuditRepository;
import br.com.archflow.security.apikey.ApiKeyService;
import br.com.archflow.security.apikey.JdbcApiKeyRepository;
import br.com.archflow.security.auth.JdbcUserRepository;
import br.com.archflow.security.auth.UserRepository;
import br.com.archflow.security.password.PasswordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

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

    @Bean
    public br.com.archflow.engine.persistence.FlowRepository flowRepository(
            DataSource dataSource,
            br.com.archflow.api.flow.WorkflowDeserializer workflowDeserializer) {
        log.info("JDBC persistence ativo: FlowRepository durável (JdbcFlowRepository/WorkflowJsonCodec)");
        return new br.com.archflow.engine.persistence.jdbc.JdbcFlowRepository(
                dataSource, new br.com.archflow.api.flow.WorkflowJsonCodec(workflowDeserializer));
    }

    @Bean
    public br.com.archflow.agent.queue.AgentInvocationQueue agentInvocationQueue(DataSource dataSource) {
        log.info("JDBC persistence ativo: AgentInvocationQueue durável (JdbcAgentInvocationQueue)");
        return new br.com.archflow.api.queue.JdbcAgentInvocationQueue(dataSource);
    }

    @Bean
    public UserRepository userRepository(DataSource dataSource) {
        log.info("JDBC persistence ativo: UserRepository durável (JdbcUserRepository)");
        return new JdbcUserRepository(dataSource);
    }

    @Bean
    public ApiKeyService.ApiKeyRepository apiKeyRepository(DataSource dataSource) {
        log.info("JDBC persistence ativo: ApiKeyRepository durável (JdbcApiKeyRepository)");
        return new JdbcApiKeyRepository(dataSource);
    }

    /**
     * Semeia o admin de bootstrap depois que o contexto sobe — como
     * {@link org.springframework.boot.ApplicationRunner}, roda após eventuais
     * migrations (Flyway) e o schema já existir, ao contrário de fazer I/O
     * dentro do método de bean. Idempotente: só cria quando ausente, para não
     * sobrescrever uma senha já rotacionada em restarts.
     */
    @Bean
    public org.springframework.boot.ApplicationRunner adminUserSeeder(
            UserRepository userRepository,
            PasswordService passwordService,
            Environment environment,
            @Value("${archflow.security.admin-password:${ARCHFLOW_ADMIN_PASSWORD:}}") String adminPassword) {
        return args -> {
            try {
                if (userRepository.findByUsername("admin").isPresent()) {
                    return;
                }
                String resolved = AdminBootstrap.resolvePassword(environment, adminPassword, log);
                userRepository.save(AdminBootstrap.buildAdmin(passwordService.hash(resolved)));
                log.info("Default admin user created in durable store (username: admin)");
            } catch (RuntimeException e) {
                // Concorrência: outra instância pode ter criado o admin entre o
                // nosso findByUsername e o save (UNIQUE username). Se agora existe,
                // seguimos; caso contrário o schema provavelmente não foi migrado.
                if (adminExistsQuietly(userRepository)) {
                    log.info("Admin user already present (likely created concurrently); skipping seed");
                    return;
                }
                throw new IllegalStateException(
                        "Falha ao semear o admin durável — verifique se a migration "
                                + "V5_1__create_security.sql (tabelas users/user_roles) foi aplicada.", e);
            }
        };
    }

    private static boolean adminExistsQuietly(UserRepository repository) {
        try {
            return repository.findByUsername("admin").isPresent();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Scheduler Quartz durável ({@code JobStoreTX}/{@code JDBCJobStore}) — triggers
     * agendados sobrevivem a restart, ao contrário do {@code RAMJobStore} default de
     * {@code ArchflowBeanConfiguration} (que recua via {@code @ConditionalOnMissingBean}).
     * Requer as tabelas {@code QRTZ_*} (migration {@code V6_1__create_quartz.sql}). O
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

    @Bean
    public br.com.archflow.conversation.state.SuspendedConversationStore suspendedConversationStore(
            DataSource dataSource) {
        log.info("JDBC persistence ativo: SuspendedConversationStore durável "
                + "(suspend/resume sobrevive a restart)");
        return new br.com.archflow.conversation.persistence.jdbc.JdbcSuspendedConversationStore(dataSource);
    }

    @Bean
    public br.com.archflow.api.web.workflow.WorkflowRuntimeStore workflowRuntimeStore(
            DataSource dataSource) {
        log.info("JDBC persistence ativo: WorkflowRuntimeStore durável (JdbcWorkflowRuntimeStore)");
        return new br.com.archflow.api.web.workflow.JdbcWorkflowRuntimeStore(dataSource);
    }

    @Bean
    public br.com.archflow.conversation.domain.ConversationRepository conversationRepository(
            DataSource dataSource) {
        log.info("JDBC persistence ativo: ConversationRepository durável (histórico de conversas)");
        return new br.com.archflow.conversation.persistence.jdbc.JdbcConversationRepository(dataSource);
    }

    @Bean
    public br.com.archflow.conversation.prompt.PromptRegistry promptRegistry(DataSource dataSource) {
        log.info("JDBC persistence ativo: PromptRegistry durável (versionamento de prompts)");
        return new br.com.archflow.conversation.persistence.jdbc.JdbcPromptRegistry(dataSource);
    }
}
