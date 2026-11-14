package br.com.archflow.api.agent.mcp;

import br.com.archflow.langchain4j.mcp.McpClient;
import br.com.archflow.langchain4j.mcp.McpModel;
import br.com.archflow.langchain4j.mcp.client.HttpMcpClient;
import br.com.archflow.langchain4j.provider.LLMConfigResolver;
import br.com.archflow.langchain4j.provider.LLMResolutionRequest;
import br.com.archflow.model.config.ResolvedLLMConfig;
import br.com.archflow.model.engine.ExecutionContext;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code encerrarNaSaida}: a primeira chamada bem-sucedida à tool de saída encerra o laço.
 *
 * <p>Medido no VendaX em 13/11 (VEN-1044), no corpus 40×3 do Agente da Diretoria: o modelo chamava
 * {@code responder_diretoria}, voltava a consultar e chamava de novo — p95 de 13,3 s contra 8,0 s do
 * mesmo agente respondendo pelo texto. Pedir no prompt que ele parasse dependia de obediência.</p>
 */
@DisplayName("laço MCP: encerrar na saída da tool")
class EncerrarNaSaidaTest {

    private static final ResolvedLLMConfig CONFIG = ResolvedLLMConfig.builder()
            .provider("openrouter").model("google/gemini-2.5-flash").maxTokens(4096).build();

    /** Respostas de tool por nome, consumidas em ordem; depois da fila, sucesso. */
    private static final class Cliente implements McpClient {
        final Map<String, Deque<Object>> respostas = new java.util.HashMap<>();
        final List<String> chamadas = new ArrayList<>();

        Cliente responde(String tool, Object... resultados) {
            respostas.put(tool, new ArrayDeque<>(List.of(resultados)));
            return this;
        }

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
            Map<String, Object> schema = Map.of("type", "object", "properties", Map.of());
            return CompletableFuture.completedFuture(List.of(
                    new McpModel.Tool("explicar_variacao", "consulta", schema),
                    new McpModel.Tool("responder_diretoria", "resposta", schema)));
        }
        @Override public CompletableFuture<McpModel.ToolResult> callTool(McpModel.ToolArguments a) {
            chamadas.add(a.name());
            Deque<Object> fila = respostas.get(a.name());
            Object r = fila == null || fila.isEmpty() ? McpModel.ToolResult.text("{\"ok\":true}") : fila.poll();
            if (r instanceof Integer codigo) {
                return CompletableFuture.failedFuture(new CompletionException(
                        new HttpMcpClient.McpRpcException(codigo, "argumento inválido")));
            }
            return CompletableFuture.completedFuture((McpModel.ToolResult) r);
        }
    }

    /** Respostas roteirizadas; depois do roteiro, repete a última. */
    private static final class Modelo implements ChatModel {
        private final List<ChatResponse> roteiro;
        int turnos;

        Modelo(ChatResponse... roteiro) {
            this.roteiro = List.of(roteiro);
        }

        @Override public ChatResponse chat(ChatRequest request) {
            return roteiro.get(Math.min(turnos++, roteiro.size() - 1));
        }
    }

    private record Resolvedor(ChatModel model) implements LLMConfigResolver {
        @Override public ResolvedLLMConfig resolve(LLMResolutionRequest r) { return CONFIG; }
        @Override public ChatModel resolveModel(LLMResolutionRequest r) { return model; }
    }

    private static int seq;

    private static ChatResponse chama(String... tools) {
        List<ToolExecutionRequest> pedidos = new ArrayList<>();
        for (String t : tools) {
            pedidos.add(ToolExecutionRequest.builder().id("c" + (++seq)).name(t).arguments("{}").build());
        }
        return ChatResponse.builder().aiMessage(AiMessage.from(pedidos)).build();
    }

    private static ChatResponse texto(String t) {
        return ChatResponse.builder().aiMessage(AiMessage.from(t)).build();
    }

    private static McpAgentRunner.Result rodar(Modelo modelo, Cliente cliente, Set<String> encerra) {
        return new McpAgentRunner(new Resolvedor(modelo), CONFIG).run("acme", "sys", "por que caiu?",
                cliente, new McpAgentRunner.Options(ToolAccessPolicy.allowAll()).encerrandoNaSaida(encerra));
    }

    @Nested
    @DisplayName("no runner")
    class NoRunner {

        /** O caso medido em dr-a05.r1: responde, volta a consultar, responde de novo. */
        @Test
        @DisplayName("sem a opção, o laço segue depois da tool de saída (como antes)")
        void semAOpcaoSegue() {
            Modelo modelo = new Modelo(chama("explicar_variacao"), chama("responder_diretoria"),
                    chama("explicar_variacao"), chama("responder_diretoria"), texto("pronto"));
            Cliente cliente = new Cliente();

            McpAgentRunner.Result r = rodar(modelo, cliente, Set.of());

            assertThat(modelo.turnos).isEqualTo(5);
            assertThat(cliente.chamadas).containsExactly("explicar_variacao", "responder_diretoria",
                    "explicar_variacao", "responder_diretoria");
            assertThat(r.finalText()).isEqualTo("pronto");
        }

        @Test
        @DisplayName("com a opção, a primeira resposta aceita encerra — como conclusão, não como 'encerrado'")
        void primeiraRespostaEncerra() {
            Modelo modelo = new Modelo(chama("explicar_variacao"), chama("responder_diretoria"),
                    chama("explicar_variacao"), chama("responder_diretoria"), texto("pronto"));
            Cliente cliente = new Cliente();

            McpAgentRunner.Result r = rodar(modelo, cliente, Set.of("responder_diretoria"));

            assertThat(modelo.turnos).as("nenhuma volta depois da resposta aceita").isEqualTo(2);
            assertThat(cliente.chamadas).containsExactly("explicar_variacao", "responder_diretoria");
            assertThat(r.isEncerrado())
                    .as("'encerrado' diz que o texto não é resposta — aqui a resposta existe")
                    .isFalse();
            assertThat(r.isSuspended()).isFalse();
            assertThat(r.lastSuccessfulCall("responder_diretoria")).isNotNull();
        }

        /** A ferramenta do DR recusa a forma errada com o motivo; o modelo precisa da volta seguinte. */
        @Test
        @DisplayName("resposta recusada pela tool (isError) não encerra: o modelo corrige")
        void recusaNaoEncerra() {
            Modelo modelo = new Modelo(chama("responder_diretoria"), chama("responder_diretoria"),
                    texto("não deveria chegar aqui"));
            Cliente cliente = new Cliente().responde("responder_diretoria",
                    McpModel.ToolResult.error("faltou 'causa'"), McpModel.ToolResult.text("{\"ok\":true}"));

            McpAgentRunner.Result r = rodar(modelo, cliente, Set.of("responder_diretoria"));

            assertThat(modelo.turnos).isEqualTo(2);
            assertThat(cliente.chamadas).hasSize(2);
            assertThat(r.toolCalls()).extracting(McpAgentRunner.ToolCall::isError).containsExactly(true, false);
        }

        @Test
        @DisplayName("-32602 (argumento inválido) também não encerra")
        void argumentoInvalidoNaoEncerra() {
            Modelo modelo = new Modelo(chama("responder_diretoria"), chama("responder_diretoria"),
                    texto("não deveria chegar aqui"));
            Cliente cliente = new Cliente().responde("responder_diretoria", -32602);

            McpAgentRunner.Result r = rodar(modelo, cliente, Set.of("responder_diretoria"));

            assertThat(modelo.turnos).isEqualTo(2);
            assertThat(r.toolCalls().get(0).errorCode()).isEqualTo(-32602);
            assertThat(r.lastSuccessfulCall("responder_diretoria")).isNotNull();
        }

        @Test
        @DisplayName("no mesmo turno, o que vem depois da resposta aceita não roda")
        void restoDoTurnoNaoRoda() {
            Modelo modelo = new Modelo(chama("responder_diretoria", "explicar_variacao"), texto("x"));
            Cliente cliente = new Cliente();

            rodar(modelo, cliente, Set.of("responder_diretoria"));

            assertThat(cliente.chamadas).containsExactly("responder_diretoria");
            assertThat(modelo.turnos).isEqualTo(1);
        }

        @Test
        @DisplayName("outra tool bem-sucedida não encerra")
        void outraToolNaoEncerra() {
            Modelo modelo = new Modelo(chama("explicar_variacao"), texto("pronto"));

            McpAgentRunner.Result r = rodar(modelo, new Cliente(), Set.of("responder_diretoria"));

            assertThat(modelo.turnos).isEqualTo(2);
            assertThat(r.finalText()).isEqualTo("pronto");
        }
    }

    @Nested
    @DisplayName("no componente")
    class NoComponente {

        private final McpAgentRunner runner = mock(McpAgentRunner.class);
        private final McpAgentHost host = mock(McpAgentHost.class);
        private final ExecutionContext contexto = mock(ExecutionContext.class);

        private McpAgentRunner.Options executar(Map<String, Object> config) {
            when(contexto.get(McpAgentHost.CONTEXT_KEY)).thenReturn(Optional.of(host));
            when(contexto.getTenantId()).thenReturn("t1");
            when(host.runner()).thenReturn(runner);
            when(host.clientFor(any(), any())).thenReturn(mock(McpClient.class));
            when(host.toolCeiling(any())).thenReturn(Set.of());
            when(runner.run(any(), any(), any(), any(), any(McpAgentRunner.Options.class)))
                    .thenReturn(new McpAgentRunner.Result("ok", List.of()));

            McpAgentComponent componente = new McpAgentComponent();
            componente.initialize(config);
            componente.execute("execute", "pedido", contexto);
            org.mockito.ArgumentCaptor<McpAgentRunner.Options> captor =
                    org.mockito.ArgumentCaptor.forClass(McpAgentRunner.Options.class);
            org.mockito.Mockito.verify(runner).run(any(), any(), any(), any(), captor.capture());
            return captor.getValue();
        }

        @Test
        @DisplayName("encerrarNaSaida repassa as tools de saída ao runner e à retomada")
        void repassa() {
            McpAgentRunner.Options o = executar(Map.of("systemPrompt", "dr",
                    "saidaDaTool", List.of("responder_diretoria"), "encerrarNaSaida", true));

            assertThat(o.toolsQueEncerram()).containsExactly("responder_diretoria");
            assertThat(o.retomada().toolsQueEncerram())
                    .as("um laço que suspende e volta continua parando na saída")
                    .containsExactly("responder_diretoria");
            assertThat(o.requiredOutputTools()).as("encerrar não é exigir").isEmpty();
        }

        @Test
        @DisplayName("desligado por padrão")
        void desligadoPorPadrao() {
            McpAgentRunner.Options o = executar(Map.of("systemPrompt", "dr",
                    "saidaDaTool", List.of("responder_diretoria")));

            assertThat(o.toolsQueEncerram()).isEmpty();
        }

        @Test
        @DisplayName("sem saidaDaTool, o nó é recusado na carga")
        void semSaidaRecusa() {
            McpAgentComponent componente = new McpAgentComponent();

            assertThatThrownBy(() -> componente.initialize(
                    Map.of("systemPrompt", "dr", "encerrarNaSaida", true)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("encerrarNaSaida");
        }
    }
}
