package br.com.archflow.plugins.agents;

import br.com.archflow.plugin.api.spi.ComponentPlugin;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Garante que os plugins deste módulo estão registrados via SPI —
 * sem o arquivo META-INF/services o catálogo carrega vazio em silêncio.
 */
class ComponentPluginSpiRegistrationTest {

    @Test
    void allAgentPluginsAreDiscoverableViaServiceLoader() {
        List<String> discovered = ServiceLoader.load(ComponentPlugin.class).stream()
                .map(p -> p.type().getName())
                .toList();

        for (Class<?> expected : List.of(
                ConversationalAgent.class,
                DataAnalysisAgent.class,
                ResearchAgent.class,
                MonitoringAgent.class)) {
            assertTrue(discovered.contains(expected.getName()),
                    expected.getSimpleName() + " não está registrado em META-INF/services");
        }
    }
}
