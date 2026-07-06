package br.com.archflow.api.config;

import br.com.archflow.engine.core.StateManager;
import br.com.archflow.engine.persistence.RepositoryStateManager;
import br.com.archflow.observability.audit.AuditRepository;
import br.com.archflow.observability.audit.JdbcAuditRepository;
import br.com.archflow.security.apikey.ApiKeyService;
import br.com.archflow.security.apikey.JdbcApiKeyRepository;
import br.com.archflow.security.auth.JdbcUserRepository;
import br.com.archflow.security.auth.UserRepository;
import br.com.archflow.security.password.PasswordService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica o auto-config de persistência JDBC: liga os stores duráveis quando
 * {@code archflow.persistence.jdbc.enabled=true}; fica desligado caso
 * contrário; exige um DataSource quando habilitado.
 */
@DisplayName("JdbcPersistenceConfiguration")
class JdbcPersistenceConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(JdbcPersistenceConfiguration.class);

    @Test
    @DisplayName("desligado por padrão (property ausente) — config não cria beans")
    void disabledByDefault() {
        runner.withUserConfiguration(DataSourceConfig.class)
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(StateManager.class);
                    assertThat(ctx).doesNotHaveBean(AuditRepository.class);
                });
    }

    @Test
    @DisplayName("habilitado + DataSource: liga StateManager durável e AuditRepository")
    void wiresDurableStores() {
        runner.withPropertyValues("archflow.persistence.jdbc.enabled=true")
                .withUserConfiguration(DataSourceConfig.class)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(StateManager.class);
                    assertThat(ctx.getBean(StateManager.class))
                            .isInstanceOf(RepositoryStateManager.class);
                    assertThat(ctx.getBean(AuditRepository.class))
                            .isInstanceOf(JdbcAuditRepository.class);
                    assertThat(ctx.getBean(UserRepository.class))
                            .isInstanceOf(JdbcUserRepository.class);
                    assertThat(ctx.getBean(ApiKeyService.ApiKeyRepository.class))
                            .isInstanceOf(JdbcApiKeyRepository.class);
                    // Scheduler durável (JobStoreTX), criado sem conectar: getMetaData()
                    // lê a config, não o banco — logo funciona com o DataSource stub.
                    assertThat(ctx.getBean(org.quartz.Scheduler.class).getMetaData()
                            .getJobStoreClass().getName()).contains("JobStoreTX");
                });
    }

    @Test
    @DisplayName("habilitado sem DataSource: contexto falha (dependência obrigatória)")
    void failsWithoutDataSource() {
        runner.withPropertyValues("archflow.persistence.jdbc.enabled=true")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Configuration
    static class DataSourceConfig {
        @Bean
        DataSource dataSource() {
            return new StubDataSource();
        }

        /** O bean userRepository/adminUserSeeder da config depende de PasswordService. */
        @Bean
        PasswordService passwordService() {
            return new PasswordService();
        }
    }

    /** DataSource stub — os repositórios JDBC só guardam a referência no ctor. */
    static class StubDataSource implements DataSource {
        @Override public Connection getConnection() { throw new UnsupportedOperationException(); }
        @Override public Connection getConnection(String u, String p) { throw new UnsupportedOperationException(); }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() { return null; }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
