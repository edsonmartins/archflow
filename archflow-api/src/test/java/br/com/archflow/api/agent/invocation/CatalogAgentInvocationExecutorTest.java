package br.com.archflow.api.agent.invocation;

import br.com.archflow.agent.queue.InvocationRequest;
import br.com.archflow.api.agent.mcp.McpAgentHost;
import br.com.archflow.api.agent.mcp.McpAgentRunner;
import br.com.archflow.langchain4j.mcp.McpClient;
import br.com.archflow.model.config.LLMConfigPatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static br.com.archflow.api.agent.invocation.InvocationTestSupport.realCatalog;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * O {@code mcp-agent} pela fila precisa rodar como num nó de fluxo. O caminho genérico do catálogo tira
 * {@code provider}/{@code model}/{@code temperature}/{@code maxTokens} da config (chaves do designer para
 * os demais componentes), e o {@code mcp-agent} lê o override de LLM justamente delas — pela fila, o
 * agente rodava em silêncio com o modelo padrão do deployment.
 */
@DisplayName("CatalogAgentInvocationExecutor — mcp-agent pela fila")
class CatalogAgentInvocationExecutorTest {

    /** Host que registra as options com que o laço foi chamado, sem LLM nem servidor MCP. */
    private static final class HostQueGrava implements McpAgentHost {
        final AtomicReference<McpAgentRunner.Options> options = new AtomicReference<>();
        final AtomicReference<String> mensagem = new AtomicReference<>();
        private final McpAgentRunner runner = new McpAgentRunner(null, null) {
            @Override
            public Result run(String tenantId, String systemPrompt, String userMessage, McpClient client,
                              Options opts) {
                options.set(opts);
                mensagem.set(userMessage);
                return new Result("pronto", List.of());
            }
        };

        @Override
        public McpAgentRunner runner() {
            return runner;
        }

        @Override
        public McpClient clientFor(String tenantId, String serverRef) {
            return mock(McpClient.class);
        }
    }

    @Test
    @DisplayName("o override de LLM da config chega ao laço (provider, model, temperature, maxTokens)")
    void llmOverrideReachesTheLoop() throws Exception {
        HostQueGrava host = new HostQueGrava();
        var executor = new CatalogAgentInvocationExecutor(realCatalog(), host);
        InvocationRequest request = InvocationRequest.root("tenant_demo", "mcp-agent", Map.of(
                "config", Map.of("systemPrompt", "Você ajuda.", "provider", "openrouter",
                        "model", "openai/gpt-4o", "temperature", 0.1, "maxTokens", 256),
                "message", "oi"));

        executor.execute(request);

        LLMConfigPatch step = host.options.get().stepPatch();
        assertThat(step.provider()).contains("openrouter");
        assertThat(step.model()).contains("openai/gpt-4o");
        assertThat(step.temperature().getAsDouble()).isEqualTo(0.1);
        assertThat(step.maxTokens().getAsInt()).isEqualTo(256);
        assertThat(host.mensagem.get()).isEqualTo("oi");
    }

    @Test
    @DisplayName("config sem systemPrompt falha com a mensagem do próprio mcp-agent")
    void invalidConfigFailsWithTheComponentMessage() {
        var executor = new CatalogAgentInvocationExecutor(realCatalog(), new HostQueGrava());
        InvocationRequest request = InvocationRequest.root("tenant_demo", "mcp-agent",
                Map.of("config", Map.of("model", "openai/gpt-4o"), "message", "oi"));

        assertThatThrownBy(() -> executor.execute(request)).hasMessageContaining("systemPrompt");
    }
}
