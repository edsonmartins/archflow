package br.com.archflow.api.config;

import br.com.archflow.api.admin.store.InMemoryGlobalConfigStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Default em memória do store de configuração admin — mutuamente exclusivo
 * com o bean durável de {@code JdbcPersistenceConfiguration} via a mesma
 * property (determinístico, sem depender de ordem de processamento).
 *
 * <p>Config admin é recuperável (o controller recai nos defaults embutidos),
 * então o modo em memória gera apenas um WARN do
 * {@link ProductionReadinessGuard}, não uma violação fatal.
 */
@Configuration
public class GlobalConfigStoreConfiguration {

    @Bean
    @ConditionalOnProperty(name = "archflow.persistence.jdbc.enabled",
            havingValue = "false", matchIfMissing = true)
    public InMemoryGlobalConfigStore inMemoryGlobalConfigStore() {
        return new InMemoryGlobalConfigStore();
    }
}
