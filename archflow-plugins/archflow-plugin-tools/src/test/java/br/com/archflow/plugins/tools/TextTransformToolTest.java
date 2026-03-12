package br.com.archflow.plugins.tools;

import br.com.archflow.model.ai.domain.Result;
import br.com.archflow.model.ai.metadata.ComponentMetadata;
import br.com.archflow.model.ai.type.ComponentType;
import br.com.archflow.model.engine.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("TextTransformTool")
class TextTransformToolTest {

    private TextTransformTool tool;
    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        tool = new TextTransformTool();
        context = mock(ExecutionContext.class);
        tool.initialize(Map.of());
    }

    @Nested
    @DisplayName("metadata")
    class MetadataTest {

        @Test
        @DisplayName("should return correct metadata")
        void shouldReturnMetadata() {
            ComponentMetadata meta = tool.getMetadata();

            assertThat(meta.id()).isEqualTo("text-transform-tool");
            assertThat(meta.type()).isEqualTo(ComponentType.TOOL);
            assertThat(meta.version()).isEqualTo("1.0.0");
            assertThat(meta.capabilities()).contains("text-processing", "transform");
            assertThat(meta.operations()).hasSize(4);
        }
    }

    @Nested
    @DisplayName("execute operations")
    class ExecuteTest {

        @Test
        @DisplayName("should convert to uppercase")
        void shouldUppercase() throws Exception {
            var result = tool.execute("uppercase", "hello world", context);
            assertThat(result).isEqualTo("HELLO WORLD");
        }

        @Test
        @DisplayName("should convert to lowercase")
        void shouldLowercase() throws Exception {
            var result = tool.execute("lowercase", "HELLO WORLD", context);
            assertThat(result).isEqualTo("hello world");
        }

        @Test
        @DisplayName("should reverse text")
        void shouldReverse() throws Exception {
            var result = tool.execute("reverse", "hello", context);
            assertThat(result).isEqualTo("olleh");
        }

        @Test
        @DisplayName("should count words")
        void shouldCountWords() throws Exception {
            var result = tool.execute("wordcount", "one two three", context);
            assertThat(result).isEqualTo(3);
        }

        @Test
        @DisplayName("should count zero words for blank text")
        void shouldCountZeroForBlank() throws Exception {
            var result = tool.execute("wordcount", "   ", context);
            assertThat(result).isEqualTo(0);
        }

        @Test
        @DisplayName("should reject unsupported operation")
        void shouldRejectUnsupported() {
            assertThatThrownBy(() -> tool.execute("encrypt", "text", context))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unsupported operation");
        }

        @Test
        @DisplayName("should throw when not initialized")
        void shouldThrowWhenNotInitialized() {
            var uninitTool = new TextTransformTool();

            assertThatThrownBy(() -> uninitTool.execute("uppercase", "text", context))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not initialized");
        }
    }

    @Nested
    @DisplayName("execute with params map")
    class ExecuteParamsTest {

        @Test
        @DisplayName("should execute via params map")
        void shouldExecuteViaParams() {
            Result result = tool.execute(Map.of("operation", "uppercase", "text", "hello"), context);

            assertThat(result.success()).isTrue();
            assertThat(result.output()).isEqualTo("HELLO");
        }

        @Test
        @DisplayName("should fail for missing operation")
        void shouldFailForMissingOp() {
            Result result = tool.execute(Map.of("text", "hello"), context);

            assertThat(result.success()).isFalse();
            assertThat(result.messages()).anyMatch(m -> m.contains("operation"));
        }

        @Test
        @DisplayName("should fail for missing text")
        void shouldFailForMissingText() {
            Result result = tool.execute(Map.of("operation", "uppercase"), context);

            assertThat(result.success()).isFalse();
            assertThat(result.messages()).anyMatch(m -> m.contains("text"));
        }
    }

    @Nested
    @DisplayName("parameters")
    class ParametersTest {

        @Test
        @DisplayName("should describe parameters")
        void shouldDescribeParams() {
            var params = tool.getParameters();

            assertThat(params).hasSize(2);
            assertThat(params.get(0).name()).isEqualTo("operation");
            assertThat(params.get(0).required()).isTrue();
            assertThat(params.get(1).name()).isEqualTo("text");
        }

        @Test
        @DisplayName("should validate valid parameters")
        void shouldValidateValid() {
            assertThatNoException().isThrownBy(() ->
                    tool.validateParameters(Map.of("operation", "uppercase", "text", "hello")));
        }

        @Test
        @DisplayName("should reject invalid operation")
        void shouldRejectInvalidOp() {
            assertThatThrownBy(() -> tool.validateParameters(Map.of("operation", "encrypt", "text", "x")))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("should reject null params")
        void shouldRejectNullParams() {
            assertThatThrownBy(() -> tool.validateParameters(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("lifecycle")
    class LifecycleTest {

        @Test
        @DisplayName("should shutdown and clear state")
        void shouldShutdown() {
            tool.shutdown();

            assertThatThrownBy(() -> tool.execute("uppercase", "text", context))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
