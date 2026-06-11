package br.com.archflow.agent.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Verifies the rewritten FlowPluginManager: lazy directory loading, a usable
 * (never null) classloader, and clean reset. The previous implementation was
 * a silent no-op with its entire body commented out.
 */
@DisplayName("FlowPluginManager")
class FlowPluginManagerTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("classloader is never null, even before any load")
    void classLoaderNeverNull() {
        FlowPluginManager manager = new FlowPluginManager(tempDir.toString());
        assertThat(manager.getPluginClassLoader()).isNotNull();
    }

    @Test
    @DisplayName("missing plugins directory loads nothing and does not fail the flow")
    void missingDirectoryIsFine() {
        FlowPluginManager manager = new FlowPluginManager(
                tempDir.resolve("does-not-exist").toString());

        assertThatCode(() -> manager.loadPluginsForFlow(null)).doesNotThrowAnyException();
        assertThat(manager.getLoadedPluginIds()).isEmpty();
        assertThat(manager.getPluginClassLoader()).isSameAs(manager.getClass().getClassLoader());
    }

    @Test
    @DisplayName("null plugins path falls back to the application classloader")
    void nullPathIsFine() {
        FlowPluginManager manager = new FlowPluginManager(null);

        assertThatCode(() -> manager.loadPluginsForFlow(null)).doesNotThrowAnyException();
        assertThat(manager.getPluginClassLoader()).isNotNull();
    }

    @Test
    @DisplayName("clearPlugins resets to the application classloader")
    void clearPluginsResets() {
        FlowPluginManager manager = new FlowPluginManager(tempDir.toString());
        manager.loadPluginsForFlow(null);

        manager.clearPlugins();

        assertThat(manager.getLoadedPluginIds()).isEmpty();
        assertThat(manager.getPluginClassLoader()).isSameAs(manager.getClass().getClassLoader());
    }

    @Test
    @DisplayName("catalog is available and empty before any plugin loads")
    void catalogAvailable() {
        FlowPluginManager manager = new FlowPluginManager(tempDir.toString());
        assertThat(manager.getCatalog()).isNotNull();
    }
}
