package br.com.archflow.langchain4j.core.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LangChainAdapterException")
class LangChainAdapterExceptionTest {

    @Test
    @DisplayName("message-only constructor preserves message and has null cause")
    void messageOnlyConstructor_preservesMessageAndNullCause() {
        String message = "adapter configuration failed";

        LangChainAdapterException exception = new LangChainAdapterException(message);

        assertThat(exception.getMessage()).isEqualTo(message);
        assertThat(exception.getCause()).isNull();
    }

    @Test
    @DisplayName("message+cause constructor preserves both message and cause")
    void messageCauseConstructor_preservesBothMessageAndCause() {
        String message = "adapter execution error";
        Throwable cause = new RuntimeException("underlying error");

        LangChainAdapterException exception = new LangChainAdapterException(message, cause);

        assertThat(exception.getMessage()).isEqualTo(message);
        assertThat(exception.getCause()).isSameAs(cause);
    }

    @Test
    @DisplayName("is a subclass of RuntimeException")
    void isRuntimeExceptionSubclass() {
        LangChainAdapterException exception = new LangChainAdapterException("test");

        assertThat(exception).isInstanceOf(RuntimeException.class);
    }
}
