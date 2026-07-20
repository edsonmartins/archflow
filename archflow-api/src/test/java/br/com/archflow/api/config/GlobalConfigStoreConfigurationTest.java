package br.com.archflow.api.config;

import br.com.archflow.api.admin.store.GlobalConfigStore;
import br.com.archflow.api.admin.store.InMemoryGlobalConfigStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O default em memória do {@link GlobalConfigStore} é mutuamente exclusivo com
 * o bean durável de {@code JdbcPersistenceConfiguration} via a property
 * {@code archflow.persistence.jdbc.enabled}.
 */
@DisplayName("GlobalConfigStoreConfiguration")
class GlobalConfigStoreConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(GlobalConfigStoreConfiguration.class);

    @Test
    @DisplayName("property ausente: default em memória presente")
    void inMemoryByDefault() {
        runner.run(ctx -> assertThat(ctx.getBean(GlobalConfigStore.class))
                .isInstanceOf(InMemoryGlobalConfigStore.class));
    }

    @Test
    @DisplayName("jdbc.enabled=false: default em memória presente")
    void inMemoryWhenExplicitlyDisabled() {
        runner.withPropertyValues("archflow.persistence.jdbc.enabled=false")
                .run(ctx -> assertThat(ctx).hasSingleBean(InMemoryGlobalConfigStore.class));
    }

    @Test
    @DisplayName("jdbc.enabled=true: default em memória NÃO é registrado (cede ao durável)")
    void absentWhenJdbcEnabled() {
        runner.withPropertyValues("archflow.persistence.jdbc.enabled=true")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(GlobalConfigStore.class));
    }
}
