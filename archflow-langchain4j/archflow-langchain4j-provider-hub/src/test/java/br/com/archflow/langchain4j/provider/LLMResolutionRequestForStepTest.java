package br.com.archflow.langchain4j.provider;

import br.com.archflow.model.ai.AIComponent;
import br.com.archflow.model.ai.metadata.ComponentMetadata;
import br.com.archflow.model.ai.type.ComponentType;
import br.com.archflow.model.config.FlowConfiguration;
import br.com.archflow.model.config.LLMConfig;
import br.com.archflow.model.config.LLMConfigPatch;
import br.com.archflow.model.config.MonitoringConfig;
import br.com.archflow.model.config.ResolvedLLMConfig;
import br.com.archflow.model.config.RetryPolicy;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowMetadata;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepConnection;
import br.com.archflow.model.flow.StepResult;
import br.com.archflow.model.flow.StepType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LLMResolutionRequest.forStep — monta a cadeia a partir de Flow + FlowStep")
class LLMResolutionRequestForStepTest {

    private final DefaultLLMConfigResolver resolver = new DefaultLLMConfigResolver(LLMProviderHub.getInstance());

    private static ResolvedLLMConfig platform() {
        return ResolvedLLMConfig.builder()
                .provider("openai").model("platform-model").temperature(0.2).maxTokens(1024).build();
    }

    private static Flow flowWithPatch(LLMConfigPatch flowPatch) {
        FlowConfiguration cfg = new FlowConfiguration() {
            @Override public long getTimeout() { return 0; }
            @Override public RetryPolicy getRetryPolicy() { return null; }
            @Override public LLMConfig getLLMConfig() { return null; }
            @Override public MonitoringConfig getMonitoringConfig() { return null; }
            @Override public LLMConfigPatch getLLMPatch() { return flowPatch; }
        };
        return new Flow() {
            @Override public String getId() { return "flow-1"; }
            @Override public FlowMetadata getMetadata() { return null; }
            @Override public List<FlowStep> getSteps() { return List.of(); }
            @Override public FlowConfiguration getConfiguration() { return cfg; }
            @Override public void validate() { }
        };
    }

    private static FlowStep stepWithPatch(LLMConfigPatch stepPatch) {
        return new FlowStep() {
            @Override public String getId() { return "step-1"; }
            @Override public StepType getType() { return null; }
            @Override public List<StepConnection> getConnections() { return List.of(); }
            @Override public CompletableFuture<StepResult> execute(ExecutionContext c) {
                return CompletableFuture.completedFuture(null);
            }
            @Override public LLMConfigPatch getLLMPatch() { return stepPatch; }
        };
    }

    @Test
    @DisplayName("precedência step > flow > tenant > platform")
    void precedenceFromFlowAndStep() {
        Flow flow = flowWithPatch(LLMConfigPatch.builder().model("flow-model").maxTokens(2048).build());
        FlowStep step = stepWithPatch(LLMConfigPatch.builder().model("step-model").build());
        LLMConfigPatch tenant = LLMConfigPatch.builder().temperature(0.3).build();

        LLMResolutionRequest req = LLMResolutionRequest.forStep(platform(), "t1", tenant, flow, step);
        ResolvedLLMConfig out = resolver.resolve(req);

        assertThat(out.model()).isEqualTo("step-model");   // step vence
        assertThat(out.maxTokens()).isEqualTo(2048);       // flow
        assertThat(out.temperature()).isEqualTo(0.3);      // tenant
        assertThat(out.provider()).isEqualTo("openai");    // platform
        assertThat(req.tenantId()).isEqualTo("t1");
    }

    @Test
    @DisplayName("flow e step nulos => só platform + tenant")
    void nullFlowAndStep() {
        LLMConfigPatch tenant = LLMConfigPatch.builder().maxTokens(512).build();
        LLMResolutionRequest req = LLMResolutionRequest.forStep(platform(), "t1", tenant, null, null);
        ResolvedLLMConfig out = resolver.resolve(req);

        assertThat(out.model()).isEqualTo("platform-model");
        assertThat(out.maxTokens()).isEqualTo(512);
        assertThat(req.flowPatch().isEmpty()).isTrue();
        assertThat(req.stepPatch().isEmpty()).isTrue();
    }

    private static AIComponent agentWithProperties(Map<String, Object> properties) {
        return new AIComponent() {
            @Override public void initialize(Map<String, Object> config) { }
            @Override public ComponentMetadata getMetadata() {
                return new ComponentMetadata("a", "Agent", "desc", ComponentType.AGENT, "1.0.0",
                        Set.of(), List.of(), properties, Set.of());
            }
            @Override public Object execute(String op, Object in, ExecutionContext c) { return null; }
            @Override public void shutdown() { }
        };
    }

    @Nested
    @DisplayName("tier agent (AIComponent.metadata.properties)")
    class WithAgent {

        @Test
        @DisplayName("agent vence flow; step vence agent")
        void agentTierPrecedence() {
            Flow flow = flowWithPatch(LLMConfigPatch.builder().model("flow-model").maxTokens(2048).build());
            AIComponent agent = agentWithProperties(Map.of("model", "agent-model"));

            // sem step → agent vence flow
            ResolvedLLMConfig a = resolver.resolve(
                    LLMResolutionRequest.forStep(platform(), "t1", null, flow, null, agent));
            assertThat(a.model()).isEqualTo("agent-model");
            assertThat(a.maxTokens()).isEqualTo(2048);   // herdado do flow

            // com step definindo model → step vence agent
            FlowStep step = stepWithPatch(LLMConfigPatch.builder().model("step-model").build());
            ResolvedLLMConfig b = resolver.resolve(
                    LLMResolutionRequest.forStep(platform(), "t1", null, flow, step, agent));
            assertThat(b.model()).isEqualTo("step-model");
        }

        @Test
        @DisplayName("lê maxTokens de properties como string numérica")
        void coercesPropertiesString() {
            AIComponent agent = agentWithProperties(Map.of("maxTokens", "8192"));
            ResolvedLLMConfig out = resolver.resolve(
                    LLMResolutionRequest.forStep(platform(), "t1", null, null, null, agent));
            assertThat(out.maxTokens()).isEqualTo(8192);
        }
    }
}
