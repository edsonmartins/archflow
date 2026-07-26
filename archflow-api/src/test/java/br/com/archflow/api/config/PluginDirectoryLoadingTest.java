package br.com.archflow.api.config;

import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import br.com.archflow.plugin.loader.ArchflowPluginManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O {@code archflow-plugin-loader} estava fora do classpath do servidor: a
 * descoberta de plugins em runtime não existia e o catálogo só tinha a lista de
 * built-ins hard-coded por nome de classe.
 *
 * <p>O carregamento é <b>opt-in</b> porque abrir um jar executa
 * {@code onLoad} — código arbitrário sem sandbox, com os privilégios da JVM.
 * Estes testes garantem que a porta fica fechada sem a propriedade.
 */
@DisplayName("Carregamento de plugins por diretório")
class PluginDirectoryLoadingTest {

    /** Isola os dois beans em questão do resto do contexto. */
    @Configuration(proxyBeanMethods = false)
    static class PluginConfiguration {

        private final ArchflowBeanConfiguration delegate = new ArchflowBeanConfiguration();

        @Bean(destroyMethod = "close")
        @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
                name = "archflow.plugins.directory")
        ArchflowPluginManager archflowPluginManager(
                @org.springframework.beans.factory.annotation.Value(
                        "${archflow.plugins.directory:}") String dir) {
            return delegate.archflowPluginManager(dir);
        }

        @Bean
        ComponentCatalog componentCatalog(ObjectProvider<ArchflowPluginManager> plugins) {
            return delegate.componentCatalog(plugins);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PluginConfiguration.class);

    @Test
    @DisplayName("sem a propriedade, o manager não é criado — a porta fica fechada")
    void closedByDefault() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx)
                    .as("carregar jar executa codigo sem sandbox: nao pode ser o default")
                    .doesNotHaveBean(ArchflowPluginManager.class);
            assertThat(ctx).hasSingleBean(ComponentCatalog.class);
        });
    }

    @Test
    @DisplayName("com a propriedade apontando para diretório inexistente, sobe e não carrega nada")
    void missingDirectoryIsNotAnError() {
        runner.withPropertyValues("archflow.plugins.directory=/tmp/archflow-plugins-inexistente")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(ArchflowPluginManager.class);
                    assertThat(ctx.getBean(ArchflowPluginManager.class).getLoadedPluginIds())
                            .isEmpty();
                });
    }

    @Test
    @DisplayName("diretório vazio carrega nada e o catálogo segue com os built-ins")
    void emptyDirectoryLoadsNothing(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        runner.withPropertyValues("archflow.plugins.directory=" + dir)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(ArchflowPluginManager.class).getLoadedPluginIds())
                            .isEmpty();
                    // Os built-ins do classpath continuam presentes.
                    assertThat(ctx.getBean(ComponentCatalog.class).listComponents()).isNotEmpty();
                });
    }

    @Test
    @DisplayName("propriedade em branco é tratada como desligada")
    void blankPropertyLoadsNothing() {
        runner.withPropertyValues("archflow.plugins.directory=")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(ArchflowPluginManager.class).getLoadedPluginIds())
                            .isEmpty();
                });
    }
}
