package br.com.archflow.langchain4j.realtime.spi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RealtimeException}.
 */
@DisplayName("RealtimeException")
class RealtimeExceptionTest {

    @Nested
    @DisplayName("is a checked exception")
    class CheckedExceptionHierarchy {

        @Test
        @DisplayName("extends Exception, not RuntimeException")
        void isCheckedExceptionNotRuntime() {
            RealtimeException ex = new RealtimeException("test");

            assertThat(ex).isInstanceOf(Exception.class);
            assertThat(ex).isNotInstanceOf(RuntimeException.class);
        }
    }

    @Nested
    @DisplayName("message-only constructor")
    class MessageOnlyConstructor {

        @Test
        @DisplayName("preserves the supplied message")
        void messageIsPreserved() {
            RealtimeException ex = new RealtimeException("session provisioning failed");

            assertThat(ex.getMessage()).isEqualTo("session provisioning failed");
        }

        @Test
        @DisplayName("cause is null when not provided")
        void causeIsNullByDefault() {
            RealtimeException ex = new RealtimeException("oops");

            assertThat(ex.getCause()).isNull();
        }
    }

    @Nested
    @DisplayName("message + cause constructor")
    class MessageAndCauseConstructor {

        @Test
        @DisplayName("preserves both message and cause")
        void messageAndCauseArePreserved() {
            Throwable cause = new IllegalStateException("underlying I/O error");
            RealtimeException ex = new RealtimeException("communication failed", cause);

            assertThat(ex.getMessage()).isEqualTo("communication failed");
            assertThat(ex.getCause()).isSameAs(cause);
        }

        @Test
        @DisplayName("cause message is accessible via getCause")
        void causeMessageIsAccessible() {
            Throwable cause = new RuntimeException("root cause");
            RealtimeException ex = new RealtimeException("wrapped", cause);

            assertThat(ex.getCause().getMessage()).isEqualTo("root cause");
        }
    }

    @Nested
    @DisplayName("can be thrown and caught as Exception")
    class ThrowableAsChecked {

        @Test
        @DisplayName("can be thrown and caught as checked Exception")
        void canBeThrownAndCaughtAsCheckedException() {
            Exception caught = null;
            try {
                throwRealtimeException();
            } catch (Exception e) {
                caught = e;
            }

            assertThat(caught)
                    .isNotNull()
                    .isInstanceOf(RealtimeException.class)
                    .hasMessage("thrown from test");
        }

        // Helper: declared to throw checked RealtimeException
        private void throwRealtimeException() throws RealtimeException {
            throw new RealtimeException("thrown from test");
        }
    }
}
