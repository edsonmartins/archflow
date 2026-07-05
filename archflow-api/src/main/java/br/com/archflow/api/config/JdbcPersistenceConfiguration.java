package br.com.archflow.api.config;

import br.com.archflow.engine.core.StateManager;
import br.com.archflow.engine.persistence.RepositoryStateManager;
import br.com.archflow.engine.persistence.jdbc.JdbcStateRepository;
import br.com.archflow.model.security.Role;
import br.com.archflow.model.security.User;
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
     * dentro do método de bean.
     */
    @Bean
    public org.springframework.boot.ApplicationRunner adminUserSeeder(
            UserRepository userRepository,
            PasswordService passwordService,
            Environment environment,
            @Value("${archflow.security.admin-password:${ARCHFLOW_ADMIN_PASSWORD:}}") String adminPassword) {
        return args -> seedAdminIfAbsent(userRepository, passwordService, environment, adminPassword);
    }

    /**
     * Semeia o usuário administrador de bootstrap de forma idempotente — só cria
     * quando ausente, para não sobrescrever uma senha já rotacionada em restarts.
     * A resolução da senha replica {@code ArchflowBeanConfiguration.userRepository}:
     * fixa em dev/test; aleatória (logada uma vez) caso contrário.
     */
    private void seedAdminIfAbsent(UserRepository repository, PasswordService passwordService,
            Environment environment, String adminPassword) {
        if (repository.findByUsername("admin").isPresent()) {
            return;
        }
        String resolved = adminPassword;
        if (resolved == null || resolved.isBlank()) {
            if (Profiles.isDevLike(environment)) {
                resolved = "admin123";
                log.warn("Using fixed development admin password (dev/test profile). "
                        + "Set archflow.security.admin-password for real deployments.");
            } else {
                resolved = PasswordService.generateRandomPassword(24);
                log.warn("No admin password configured — generated a random one for user 'admin': {} "
                        + "(set archflow.security.admin-password or ARCHFLOW_ADMIN_PASSWORD to control it)",
                        resolved);
            }
        }
        User admin = new User();
        admin.setUsername("admin");
        admin.setEmail("admin@archflow.local");
        admin.setPasswordHash(passwordService.hash(resolved));
        admin.setFirstName("System");
        admin.setLastName("Administrator");
        admin.setEnabled(true);
        admin.addRole(Role.createAdminRole());
        repository.save(admin);
        log.info("Default admin user created in durable store (username: admin)");
    }
}
