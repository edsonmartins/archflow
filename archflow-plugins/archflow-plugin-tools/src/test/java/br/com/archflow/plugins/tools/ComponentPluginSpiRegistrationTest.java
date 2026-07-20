package br.com.archflow.plugins.tools;

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
    void toolPluginIsDiscoverableViaServiceLoader() {
        boolean found = ServiceLoader.load(ComponentPlugin.class).stream()
                .anyMatch(p -> p.type() == TextTransformTool.class);
        assertTrue(found, "TextTransformTool não está registrado em META-INF/services");
    }
}
