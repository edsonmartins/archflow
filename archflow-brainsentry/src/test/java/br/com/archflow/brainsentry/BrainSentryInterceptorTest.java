package br.com.archflow.brainsentry;

import br.com.archflow.agent.tool.ToolContext;
import br.com.archflow.agent.tool.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("BrainSentryInterceptor")
class BrainSentryInterceptorTest {

    @Test @DisplayName("should have order 5 (before guardrails)")
    void shouldHaveOrder5() {
        var client = mock(BrainSentryClient.class);
        var interceptor = new BrainSentryInterceptor(client);
        assertThat(interceptor.order()).isEqualTo(5);
    }

    @Test @DisplayName("should have correct name")
    void shouldHaveName() {
        var client = mock(BrainSentryClient.class);
        var interceptor = new BrainSentryInterceptor(client);
        assertThat(interceptor.getName()).isEqualTo("BrainSentryInterceptor");
    }

    @Test @DisplayName("should not modify non-string input")
    void shouldSkipNonStringInput() {
        var client = mock(BrainSentryClient.class);
        var interceptor = new BrainSentryInterceptor(client);
        var context = mock(ToolContext.class);
        when(context.getInput()).thenReturn(42); // integer, not string

        interceptor.beforeExecute(context);

        verify(context, never()).setInput(any());
    }

    @Test @DisplayName("should pass through result when capture disabled")
    void shouldPassThroughResult() {
        var client = mock(BrainSentryClient.class);
        var interceptor = new BrainSentryInterceptor(client, false);
        var context = mock(ToolContext.class);
        var result = mock(ToolResult.class);

        var returned = interceptor.afterExecute(context, result);

        assertThat(returned).isSameAs(result);
        verifyNoInteractions(client);
    }

    @Test @DisplayName("should handle client failure gracefully in beforeExecute")
    void shouldHandleClientFailure() throws Exception {
        var client = mock(BrainSentryClient.class);
        when(client.intercept(anyString(), anyInt())).thenThrow(new RuntimeException("Network error"));
        var interceptor = new BrainSentryInterceptor(client);
        var context = mock(ToolContext.class);
        when(context.getInput()).thenReturn("test prompt");

        // Should not throw
        assertThatCode(() -> interceptor.beforeExecute(context)).doesNotThrowAnyException();
    }
}
