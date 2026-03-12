package br.com.archflow.plugin.api.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Plugin Exceptions")
class PluginExceptionsTest {

    @Nested
    @DisplayName("ComponentException")
    class ComponentExceptionTest {

        @Test
        @DisplayName("should create with message")
        void shouldCreateWithMessage() {
            var ex = new ComponentException("test error");
            assertThat(ex).hasMessage("test error");
            assertThat(ex).isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("should create with message and cause")
        void shouldCreateWithCause() {
            var cause = new RuntimeException("root");
            var ex = new ComponentException("wrapper", cause);
            assertThat(ex).hasCause(cause);
        }
    }

    @Nested
    @DisplayName("ComponentInitializationException")
    class ComponentInitializationExceptionTest {

        @Test
        @DisplayName("should extend ComponentException")
        void shouldExtend() {
            var ex = new ComponentInitializationException("init failed");
            assertThat(ex).isInstanceOf(ComponentException.class);
        }
    }

    @Nested
    @DisplayName("ComponentNotFoundException")
    class ComponentNotFoundExceptionTest {

        @Test
        @DisplayName("should extend ComponentException and include componentId in message")
        void shouldExtend() {
            var ex = new ComponentNotFoundException("comp-123");
            assertThat(ex).isInstanceOf(ComponentException.class);
            assertThat(ex.getMessage()).contains("comp-123");
        }
    }

    @Nested
    @DisplayName("ComponentOperationException")
    class ComponentOperationExceptionTest {

        @Test
        @DisplayName("should extend ComponentException")
        void shouldExtend() {
            var ex = new ComponentOperationException("operation failed");
            assertThat(ex).isInstanceOf(ComponentException.class);
        }

        @Test
        @DisplayName("should create with cause")
        void shouldCreateWithCause() {
            var cause = new IllegalStateException("bad state");
            var ex = new ComponentOperationException("op failed", cause);
            assertThat(ex).hasCause(cause);
        }
    }
}
