package br.com.archflow.api.agent.mcp;

import br.com.archflow.langchain4j.mcp.McpClient;
import br.com.archflow.langchain4j.provider.LLMConfigResolver;
import br.com.archflow.langchain4j.provider.LLMResolutionRequest;
import br.com.archflow.model.config.LLMConfigPatch;
import br.com.archflow.model.config.ResolvedLLMConfig;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.StreamingChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("McpAgentRunner — resolução LLM de fluxo e passo")
class McpAgentLLMResolutionTest {

    @Test
    void sendsFlowAndStepPatchesToTheResolver() {
        AtomicReference<LLMResolutionRequest> seen = new AtomicReference<>();
        ChatModel model = new ChatModel() {
            @Override public ChatResponse chat(ChatRequest request) {
                return ChatResponse.builder().aiMessage(AiMessage.from("done")).build();
            }
        };
        LLMConfigResolver resolver = new LLMConfigResolver() {
            @Override public ResolvedLLMConfig resolve(LLMResolutionRequest request) {
                throw new UnsupportedOperationException();
            }
            @Override public ChatModel resolveModel(LLMResolutionRequest request) {
                seen.set(request);
                return model;
            }
            @Override public StreamingChatModel resolveStreamingModel(LLMResolutionRequest request) {
                throw new UnsupportedOperationException();
            }
        };
        ResolvedLLMConfig platform = ResolvedLLMConfig.builder()
                .provider("openai").model("platform-model").build();
        McpAgentRunner runner = new McpAgentRunner(resolver, platform);
        McpAgentRunner.Options options = new McpAgentRunner.Options(
                ToolAccessPolicy.allowAll(), ToolTrustPolicy.untrustedByDefault(),
                ToolApprovalPolicy.none(), 8,
                LLMConfigPatch.builder().provider("openrouter").model("flow-model").build(),
                LLMConfigPatch.builder().model("step-model").temperature(0.2).build());

        McpClient client = mock(McpClient.class);
        when(client.listTools()).thenReturn(CompletableFuture.completedFuture(List.of()));
        runner.run("tenant-1", "system", "user", client, options);

        assertThat(seen.get().tenantId()).isEqualTo("tenant-1");
        assertThat(seen.get().flowPatch().provider()).contains("openrouter");
        assertThat(seen.get().flowPatch().model()).contains("flow-model");
        assertThat(seen.get().stepPatch().model()).contains("step-model");
        assertThat(seen.get().stepPatch().temperature().getAsDouble()).isEqualTo(0.2);
    }
}
