package br.com.archflow.agent.tool;

import br.com.archflow.model.engine.ImmutableExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("InterceptableToolExecutor")
class InterceptableToolExecutorTest {

    private InterceptableToolExecutor executor;

    @BeforeEach
    void setUp() {
        executor = InterceptableToolExecutor.builder()
                .tool("echo", (input, ctx) -> ToolResult.success(input))
                .build();
    }

    @Test
    void executesRegisteredToolSuccessfully() throws Exception {
        var ctx = ImmutableExecutionContext.builder().tenantId("t1").build();
        ToolResult<?> result = executor.execute("echo", "hello", ctx);
        assertThat(result).isNotNull();
    }

    @Test
    void throwsOnUnknownTool() {
        var ctx = ImmutableExecutionContext.builder().tenantId("t1").build();
        assertThatThrownBy(() -> executor.execute("missing", "x", ctx))
                .isInstanceOf(Exception.class);
    }

    @Test
    void hasTool() {
        assertThat(executor.hasTool("echo")).isTrue();
        assertThat(executor.hasTool("missing")).isFalse();
    }

    @Test
    void registerAndUnregister() {
        executor.registerTool("new-tool", (input, ctx) -> ToolResult.success("new"));
        assertThat(executor.hasTool("new-tool")).isTrue();
        executor.unregisterTool("new-tool");
        assertThat(executor.hasTool("new-tool")).isFalse();
    }

    @Test
    void trackerIsNotNull() {
        assertThat(executor.getTracker()).isNotNull();
    }

    @Test
    void interceptorChainIsNotNull() {
        assertThat(executor.getInterceptorChain()).isNotNull();
    }

    @Test
    void executeTracksInTracker() throws Exception {
        var ctx = ImmutableExecutionContext.builder().tenantId("t1").build();
        executor.execute("echo", "x", ctx);
        assertThat(executor.getTracker().getStats().totalExecutions()).isGreaterThan(0);
    }

    @Test
    void executeThrowingToolPropagatesAndTracksFailure() {
        var failExecutor = InterceptableToolExecutor.builder()
                .tool("bomb", (input, ctx) -> { throw new RuntimeException("boom"); })
                .build();
        var ctx = ImmutableExecutionContext.builder().tenantId("t1").build();
        assertThatThrownBy(() -> failExecutor.execute("bomb", "x", ctx))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("boom");
        assertThat(failExecutor.getTracker().getStats().failed()).isEqualTo(1);
    }
}
