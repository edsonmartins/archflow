package br.com.archflow.plugin.loader;

import br.com.archflow.model.ai.type.ComponentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Plugin Loader Exceptions")
class PluginExceptionsTest {

    @Nested
    @DisplayName("PluginLoadException")
    class PluginLoadExceptionTest {

        @Test
        @DisplayName("should create with message")
        void shouldCreateWithMessage() {
            var ex = new PluginLoadException("load failed");

            assertThat(ex).hasMessage("load failed");
            assertThat(ex).isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("should create with message and cause")
        void shouldCreateWithCause() {
            var cause = new IllegalStateException("root");
            var ex = new PluginLoadException("load failed", cause);

            assertThat(ex).hasMessage("load failed");
            assertThat(ex).hasCause(cause);
        }
    }

    @Nested
    @DisplayName("ComponentLoadException")
    class ComponentLoadExceptionTest {

        @Test
        @DisplayName("should extend PluginLoadException")
        void shouldExtendPluginLoadException() {
            var ex = new ComponentLoadException("comp failed", ComponentType.AGENT, "agent-1");

            assertThat(ex).isInstanceOf(PluginLoadException.class);
        }

        @Test
        @DisplayName("should store component type and id")
        void shouldStoreTypeAndId() {
            var ex = new ComponentLoadException("comp failed", ComponentType.TOOL, "tool-1");

            assertThat(ex.getType()).isEqualTo(ComponentType.TOOL);
            assertThat(ex.getComponentId()).isEqualTo("tool-1");
            assertThat(ex).hasMessage("comp failed");
        }

        @Test
        @DisplayName("should create with cause")
        void shouldCreateWithCause() {
            var cause = new RuntimeException("root");
            var ex = new ComponentLoadException("comp failed", ComponentType.ASSISTANT, "asst-1", cause);

            assertThat(ex).hasCause(cause);
            assertThat(ex.getType()).isEqualTo(ComponentType.ASSISTANT);
            assertThat(ex.getComponentId()).isEqualTo("asst-1");
        }
    }
}
