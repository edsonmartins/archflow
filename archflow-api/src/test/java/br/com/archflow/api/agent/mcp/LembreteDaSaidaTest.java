package br.com.archflow.api.agent.mcp;

import br.com.archflow.api.agent.vendax.VendaxResult;
import br.com.archflow.langchain4j.mcp.McpClient;
import br.com.archflow.langchain4j.mcp.McpModel;
import br.com.archflow.langchain4j.provider.LLMConfigResolver;
import br.com.archflow.langchain4j.provider.LLMResolutionRequest;
import br.com.archflow.model.config.ResolvedLLMConfig;
import br.com.archflow.model.engine.ExecutionContext;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code lembreteDaSaida}: o texto da cobrança de saída vem do nó, e a cobrança é contada no uso.
 *
 * <p>Medido no VendaX em 14/11 (VEN-1083): o deepseek-v4-flash anunciava a próxima leitura ("Vou
 * tentar sem comparação…") e a cobrança padrão mandava responder já, com o que tinha — 8 de 120
 * execuções do Agente da Diretoria falharam assim. E a volta extra se perdia em {@code llmTurns},
 * indistinguível de uma leitura a mais.</p>
 */
@DisplayName("laço MCP: lembrete da saída")
class LembreteDaSaidaTest {

    private static final ResolvedLLMConfig CONFIG = ResolvedLLMConfig.builder()
            .provider("openrouter").model("deepseek/deepseek-v4-flash").maxTokens(4096).build();

    /** Grava as mensagens de cada turno; responde em texto e, depois, chama a tool de saída. */
    private static final class Modelo implements ChatModel {
        final List<List<ChatMessage>> pedidos = new ArrayList<>();

        @Override public ChatResponse chat(ChatRequest request) {
            pedidos.add(List.copyOf(request.messages()));
            AiMessage ai = pedidos.size() == 1
                    ? AiMessage.from("Ambas as chamadas retornaram recusa. Vou tentar só com outubro.")
                    : AiMessage.from(List.of(ToolExecutionRequest.builder()
                            .id("c1").name("responder_diretoria").arguments("{}").build()));
            return ChatResponse.builder().aiMessage(ai).build();
        }

        String ultimaFalaDoUsuario() {
            List<ChatMessage> m = pedidos.get(1);
            return ((UserMessage) m.get(m.size() - 1)).singleText();
        }
    }

    private record Resolvedor(ChatModel model) implements LLMConfigResolver {
        @Override public ResolvedLLMConfig resolve(LLMResolutionRequest r) { return CONFIG; }
        @Override public ChatModel resolveModel(LLMResolutionRequest r) { return model; }
    }

    private static final McpClient CLIENTE = new McpClient() {
        @Override public void connect() { }
        @Override public CompletableFuture<McpModel.ServerInfo> initialize() {
            return CompletableFuture.completedFuture(null);
        }
        @Override public void initialized() { }
        @Override public boolean isConnected() { return true; }
        @Override public void close() { }
        @Override public McpModel.ServerMetadata getServerMetadata() {
            return new McpModel.ServerMetadata("fake");
        }
        @Override public McpModel.ServerCapabilities getServerCapabilities() {
            return McpModel.ServerCapabilities.toolsOnly();
        }
        @Override public CompletableFuture<List<McpModel.Tool>> listTools() {
            return CompletableFuture.completedFuture(List.of(new McpModel.Tool("responder_diretoria", "r",
                    Map.of("type", "object", "properties", Map.of()))));
        }
        @Override public CompletableFuture<McpModel.ToolResult> callTool(McpModel.ToolArguments a) {
            return CompletableFuture.completedFuture(McpModel.ToolResult.text("{\"ok\":true}"));
        }
    };

    private static McpAgentRunner.Options opcoes(ContadorDeUso uso) {
        return new McpAgentRunner.Options(ToolAccessPolicy.allowAll(), ToolTrustPolicy.untrustedByDefault(),
                ToolApprovalPolicy.none(), 5, null, null, Set.of("responder_diretoria"), null)
                .comUso(uso);
    }

    @Nested
    @DisplayName("no runner")
    class NoRunner {

        @Test
        @DisplayName("com lembrete, a cobrança diz o texto do nó")
        void textoDoNo() {
            Modelo modelo = new Modelo();
            String lembrete = "Continue: se falta um número, chame a próxima leitura; "
                    + "se já tem o que precisa, responda chamando responder_diretoria.";

            new McpAgentRunner(new Resolvedor(modelo), CONFIG).run("acme", "sys", "por que caiu?", CLIENTE,
                    opcoes(new ContadorDeUso()).comLembreteDaSaida(lembrete));

            assertThat(modelo.ultimaFalaDoUsuario()).isEqualTo(lembrete);
        }

        @Test
        @DisplayName("sem lembrete, a frase de sempre")
        void semLembrete() {
            Modelo modelo = new Modelo();

            new McpAgentRunner(new Resolvedor(modelo), CONFIG).run("acme", "sys", "por que caiu?", CLIENTE,
                    opcoes(new ContadorDeUso()).comLembreteDaSaida("   "));

            assertThat(modelo.ultimaFalaDoUsuario())
                    .isEqualTo(McpAgentRunner.cobrancaDeSaida(Set.of("responder_diretoria")));
        }

        @Test
        @DisplayName("a cobrança é contada no uso, separada dos turnos")
        void contaNoUso() {
            ContadorDeUso uso = new ContadorDeUso();

            new McpAgentRunner(new Resolvedor(new Modelo()), CONFIG).run("acme", "sys", "por que caiu?",
                    CLIENTE, opcoes(uso).encerrandoNaSaida(Set.of("responder_diretoria")));

            var r = uso.resumo().orElseThrow();
            assertThat(r.turnos()).isEqualTo(2);
            assertThat(r.cobrancasDeSaida()).isEqualTo(1);
            assertThat(VendaxResult.Uso.de(r, null).outputReminders()).isEqualTo(1);
        }

        @Test
        @DisplayName("sem cobrança, zero — não ausente")
        void semCobrancaZero() {
            ContadorDeUso uso = new ContadorDeUso();
            uso.somar("openrouter", "m", 1, 1);

            assertThat(VendaxResult.Uso.de(uso.resumo().orElseThrow(), null).outputReminders()).isZero();
        }

        @Test
        @DisplayName("o trecho depois do prazo conta só as cobranças dele")
        void menos() {
            ContadorDeUso uso = new ContadorDeUso();
            uso.somar("openrouter", "m", 1, 1);
            uso.somarCobrancaDeSaida();
            var antes = uso.resumo().orElseThrow();
            uso.somar("openrouter", "m", 1, 1);

            assertThat(uso.resumo().orElseThrow().menos(antes).cobrancasDeSaida()).isZero();
        }
    }

    @Nested
    @DisplayName("no componente")
    class NoComponente {

        private final McpAgentRunner runner = mock(McpAgentRunner.class);
        private final McpAgentHost host = mock(McpAgentHost.class);
        private final ExecutionContext contexto = mock(ExecutionContext.class);

        @Test
        @DisplayName("o lembrete chega ao runner e à retomada")
        void repassa() {
            when(contexto.get(McpAgentHost.CONTEXT_KEY)).thenReturn(Optional.of(host));
            when(contexto.getTenantId()).thenReturn("t1");
            when(host.runner()).thenReturn(runner);
            when(host.clientFor(any(), any())).thenReturn(mock(McpClient.class));
            when(host.toolCeiling(any())).thenReturn(Set.of());
            McpAgentRunner.ToolCall saida = new McpAgentRunner.ToolCall("responder_diretoria", Map.of(), "{}",
                    false, ToolTrust.UNTRUSTED);
            when(runner.run(any(), any(), any(), any(), any(McpAgentRunner.Options.class)))
                    .thenReturn(new McpAgentRunner.Result("ok", List.of(saida)));

            McpAgentComponent componente = new McpAgentComponent();
            componente.initialize(Map.of("systemPrompt", "dr", "saidaDaTool", List.of("responder_diretoria"),
                    "exigirSaidaDaTool", true, "lembreteDaSaida", "continue"));
            componente.execute("execute", "pedido", contexto);

            org.mockito.ArgumentCaptor<McpAgentRunner.Options> captor =
                    org.mockito.ArgumentCaptor.forClass(McpAgentRunner.Options.class);
            org.mockito.Mockito.verify(runner).run(any(), any(), any(), any(), captor.capture());
            assertThat(captor.getValue().lembreteDaSaida()).isEqualTo("continue");
            assertThat(captor.getValue().retomada().lembreteDaSaida()).isEqualTo("continue");
        }

        @Test
        @DisplayName("sem exigirSaidaDaTool, o lembrete nunca seria dito: o nó é recusado")
        void semExigirRecusa() {
            assertThatThrownBy(() -> new McpAgentComponent().initialize(Map.of("systemPrompt", "dr",
                    "saidaDaTool", List.of("responder_diretoria"), "lembreteDaSaida", "continue")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("lembreteDaSaida");
        }

        @Test
        @DisplayName("lembrete que não é texto é recusado")
        void naoTexto() {
            assertThatThrownBy(() -> new McpAgentComponent().initialize(Map.of("systemPrompt", "dr",
                    "saidaDaTool", List.of("r"), "exigirSaidaDaTool", true, "lembreteDaSaida", 7)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
