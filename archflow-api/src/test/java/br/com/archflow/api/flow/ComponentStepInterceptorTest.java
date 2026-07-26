package br.com.archflow.api.flow;

import br.com.archflow.agent.tool.ToolContext;
import br.com.archflow.agent.tool.ToolInterceptor;
import br.com.archflow.agent.tool.ToolInterceptorChain;
import br.com.archflow.agent.tool.ToolInterceptorException;
import br.com.archflow.agent.tool.ToolResult;
import br.com.archflow.model.ai.AIComponent;
import br.com.archflow.model.ai.metadata.ComponentMetadata;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.enums.StepStatus;
import br.com.archflow.model.flow.StepResult;
import br.com.archflow.model.flow.StepType;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * O caminho de workflow era o segundo ponto de invocação de tool sem nenhuma
 * interceptação: a {@link ToolInterceptorChain} existia completa, com poder de
 * veto, e não tinha chamador em produção.
 */
@DisplayName("ComponentStep — cadeia de interceptores")
class ComponentStepInterceptorTest {

    private final AtomicInteger componentRuns = new AtomicInteger();

    /** Componente que conta execuções, para provar que o veto de fato impede. */
    private AIComponent countingComponent() {
        return new AIComponent() {
            @Override public void initialize(Map<String, Object> config) { }
            @Override public ComponentMetadata getMetadata() { return null; }
            @Override public Object execute(String operation, Object input, ExecutionContext ctx) {
                componentRuns.incrementAndGet();
                return "saída";
            }
            @Override public void shutdown() { }
        };
    }

    private ComponentCatalog catalogWith(AIComponent component) {
        ComponentCatalog catalog = mock(ComponentCatalog.class);
        when(catalog.getComponent("llm-chat")).thenReturn(Optional.of(component));
        return catalog;
    }

    private static ExecutionContext context() {
        return new DefaultExecutionContext("acme", "u", "run-1",
                MessageWindowChatMemory.builder().maxMessages(5).build());
    }

    private StepResult run(ToolInterceptorChain chain) {
        ComponentStep step = new ComponentStep("s1", StepType.TOOL, "llm-chat", "execute",
                List.of(), catalogWith(countingComponent()), chain);
        return step.execute(context()).join();
    }

    @Test
    @DisplayName("interceptor que aborta em beforeExecute impede o componente de rodar")
    void abortingInterceptorPreventsExecution() {
        ToolInterceptorChain denyAll = ToolInterceptorChain.builder()
                .addInterceptor(new ToolInterceptor() {
                    @Override public void beforeExecute(ToolContext ctx) throws ToolInterceptorException {
                        throw new ToolInterceptorException(
                                "componente " + ctx.getToolName() + " bloqueado por política");
                    }
                })
                .build();

        StepResult result = run(denyAll);

        assertThat(componentRuns)
                .as("o veto tem que acontecer ANTES da invocação, não depois")
                .hasValue(0);
        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).asString().contains("bloqueado por política");
    }

    @Test
    @DisplayName("interceptor que permite deixa o componente rodar e devolve a saída")
    void allowingInterceptorLetsExecutionThrough() {
        ToolInterceptorChain observeOnly = ToolInterceptorChain.builder()
                .addInterceptor(new ToolInterceptor() { })
                .build();

        StepResult result = run(observeOnly);

        assertThat(componentRuns).hasValue(1);
        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).contains("saída");
    }

    @Test
    @DisplayName("interceptor pode reescrever a saída em afterExecute")
    void interceptorCanRewriteTheOutput() {
        ToolInterceptorChain redacting = ToolInterceptorChain.builder()
                .addInterceptor(new ToolInterceptor() {
                    @Override public ToolResult afterExecute(ToolContext ctx, ToolResult result) {
                        return ToolResult.success("[redigido]");
                    }
                })
                .build();

        StepResult result = run(redacting);

        assertThat(componentRuns).hasValue(1);
        assertThat(result.getOutput()).contains("[redigido]");
    }

    @Test
    @DisplayName("sem cadeia configurada, o componente é invocado direto (sem regressão)")
    void noChainMeansDirectInvocation() {
        ComponentStep step = new ComponentStep("s1", StepType.TOOL, "llm-chat", "execute",
                List.of(), catalogWith(countingComponent()));

        StepResult result = step.execute(context()).join();

        assertThat(componentRuns).hasValue(1);
        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).contains("saída");
    }
}
