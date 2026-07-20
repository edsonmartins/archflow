package br.com.archflow.plugins.assistants;

import br.com.archflow.plugin.api.spi.ComponentPlugin;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Garante que os plugins deste módulo estão registrados via SPI —
 * sem o arquivo META-INF/services o catálogo carrega vazio em silêncio.
 */
class ComponentPluginSpiRegistrationTest {

    @Test
    void assistantPluginIsDiscoverableViaServiceLoader() {
        boolean found = ServiceLoader.load(ComponentPlugin.class).stream()
                .anyMatch(p -> p.type() == TechSupportAssistant.class);
        assertTrue(found, "TechSupportAssistant não está registrado em META-INF/services");
    }
}
