package br.com.archflow.engine.validation;

import br.com.archflow.model.flow.Flow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("ValidationContext")
class ValidationContextTest {

    @Test
    @DisplayName("should store and retrieve flow")
    void shouldStoreFlow() {
        var flow = mock(Flow.class);
        var context = new ValidationContext(flow);

        assertThat(context.getFlow()).isSameAs(flow);
    }

    @Test
    @DisplayName("should store and retrieve attributes")
    void shouldStoreAttributes() {
        var flow = mock(Flow.class);
        var context = new ValidationContext(flow);

        context.setAttribute("key", "value");
        context.setAttribute("count", 42);

        assertThat(context.getAttribute("key")).isEqualTo("value");
        assertThat(context.getAttribute("count")).isEqualTo(42);
    }

    @Test
    @DisplayName("should return null for missing attributes")
    void shouldReturnNullForMissing() {
        var flow = mock(Flow.class);
        var context = new ValidationContext(flow);

        assertThat(context.getAttribute("missing")).isNull();
    }

    @Test
    @DisplayName("should overwrite existing attributes")
    void shouldOverwriteAttributes() {
        var flow = mock(Flow.class);
        var context = new ValidationContext(flow);

        context.setAttribute("key", "original");
        context.setAttribute("key", "updated");

        assertThat(context.getAttribute("key")).isEqualTo("updated");
    }
}
