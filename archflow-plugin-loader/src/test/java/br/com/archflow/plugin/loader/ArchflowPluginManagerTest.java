package br.com.archflow.plugin.loader;

import br.com.archflow.model.ai.AIAgent;
import br.com.archflow.model.ai.AIAssistant;
import br.com.archflow.model.ai.AIComponent;
import br.com.archflow.model.ai.Tool;
import br.com.archflow.model.ai.metadata.ComponentMetadata;
import br.com.archflow.model.ai.type.ComponentType;
import br.com.archflow.plugin.api.spi.ComponentPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("ArchflowPluginManager")
class ArchflowPluginManagerTest {

    private ArchflowPluginManager manager;

    @BeforeEach
    void setUp() {
        manager = new ArchflowPluginManager();
    }

    @Nested
    @DisplayName("loadFromDirectory")
    class LoadFromDirectoryTest {

        @TempDir
        Path tempDir;

        /**
         * Writes a plugin jar containing only the META-INF/services entry for
         * {@link TestComponentPlugin}; the class itself resolves from the test
         * classpath through the plugin classloader's parent delegation.
         */
        private void writePluginJar(Path jarPath, String serviceImpl) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (JarOutputStream jar = new JarOutputStream(bytes)) {
                jar.putNextEntry(new JarEntry(
                        "META-INF/services/br.com.archflow.plugin.api.spi.ComponentPlugin"));
                jar.write((serviceImpl + "\n").getBytes(StandardCharsets.UTF_8));
                jar.closeEntry();
            }
            Files.write(jarPath, bytes.toByteArray());
        }

        @Test
        @DisplayName("loads plugins registered in jar service files")
        void loadsPluginsFromJars() throws IOException {
            writePluginJar(tempDir.resolve("test-plugin.jar"), TestComponentPlugin.class.getName());
            TestComponentPlugin.LOADED.set(false);

            List<String> loaded = manager.loadFromDirectory(tempDir);

            assertThat(loaded).containsExactly(TestComponentPlugin.ID);
            assertThat(manager.getLoadedPluginIds()).contains(TestComponentPlugin.ID);
            assertThat(manager.getPlugin(TestComponentPlugin.ID)).isPresent();
            assertThat(manager.getTools()).hasSize(1);
            assertThat(TestComponentPlugin.LOADED).isTrue();
        }

        @Test
        @DisplayName("missing directory loads nothing without failing")
        void missingDirectoryLoadsNothing() {
            assertThat(manager.loadFromDirectory(tempDir.resolve("does-not-exist"))).isEmpty();
        }

        @Test
        @DisplayName("empty directory loads nothing without failing")
        void emptyDirectoryLoadsNothing() {
            assertThat(manager.loadFromDirectory(tempDir)).isEmpty();
        }

        @Test
        @DisplayName("broken service registration fails loudly, never silently")
        void brokenRegistrationFailsLoudly() throws IOException {
            writePluginJar(tempDir.resolve("broken.jar"), "br.com.archflow.DoesNotExist");

            assertThatThrownBy(() -> manager.loadFromDirectory(tempDir))
                    .isInstanceOf(PluginLoadException.class);
        }

        @Test
        @DisplayName("unload invokes lifecycle and removes the plugin")
        void unloadRemovesPlugin() throws IOException {
            writePluginJar(tempDir.resolve("test-plugin.jar"), TestComponentPlugin.class.getName());
            manager.loadFromDirectory(tempDir);
            TestComponentPlugin.UNLOADED.set(false);

            manager.unload(TestComponentPlugin.ID);

            assertThat(manager.getLoadedPluginIds()).isEmpty();
            assertThat(TestComponentPlugin.UNLOADED).isTrue();
        }
    }

    @Nested
    @DisplayName("getComponentsByType")
    class GetComponentsByTypeTest {

        @Test
        @DisplayName("should return empty list when no plugins loaded")
        void shouldReturnEmptyWhenNoPlugins() {
            List<AIAgent> agents = manager.getComponentsByType(ComponentType.AGENT);
            assertThat(agents).isEmpty();
        }
    }

    @Nested
    @DisplayName("getAssistants")
    class GetAssistantsTest {

        @Test
        @DisplayName("should return empty list when no assistants loaded")
        void shouldReturnEmptyList() {
            assertThat(manager.getAssistants()).isEmpty();
        }
    }

    @Nested
    @DisplayName("getAgents")
    class GetAgentsTest {

        @Test
        @DisplayName("should return empty list when no agents loaded")
        void shouldReturnEmptyList() {
            assertThat(manager.getAgents()).isEmpty();
        }
    }

    @Nested
    @DisplayName("getTools")
    class GetToolsTest {

        @Test
        @DisplayName("should return empty list when no tools loaded")
        void shouldReturnEmptyList() {
            assertThat(manager.getTools()).isEmpty();
        }
    }
}
