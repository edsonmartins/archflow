package br.com.archflow.api.agent.mcp;

import br.com.archflow.langchain4j.mcp.McpClient;
import br.com.archflow.langchain4j.mcp.McpModel;
import br.com.archflow.langchain4j.mcp.client.HttpMcpClient;
import br.com.archflow.langchain4j.provider.LLMConfigResolver;
import br.com.archflow.langchain4j.provider.LLMResolutionRequest;
import br.com.archflow.model.config.ResolvedLLMConfig;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Duas coisas que o laço passou a fazer para quem o hospeda.
 *
 * <ol>
 *   <li><b>Parar num código terminal.</b> Devolver o erro da tool ao modelo é o certo para quase
 *       tudo; para "o agente não foi contratado" ({@code -32001}, no VendaX) é gastar turnos e
 *       segundos de um vendedor que espera, para chegar ao mesmo lugar.</li>
 *   <li><b>Contar o consumo turno a turno.</b> A tabela de uso do Core estava vazia; e somar só no
 *       fim deixaria sem custo justamente as execuções que falham.</li>
 * </ol>
 */
@DisplayName("laço MCP: encerramento por código e contagem de uso")
class EncerramentoEUsoTest {

    private static final ResolvedLLMConfig CONFIG = ResolvedLLMConfig.builder()
            .provider("openrouter").model("google/gemini-2.5-flash-lite").maxTokens(4096).build();

    /** Cliente cuja tool falha com um código JSON-RPC, embrulhado como o client real embrulha. */
    private static final class ClienteQueRecusa implements McpClient {
        private final int codigo;
        int chamadas;

        ClienteQueRecusa(int codigo) {
            this.codigo = codigo;
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
            return CompletableFuture.completedFuture(List.of(new McpModel.Tool(
                    "plano_negociacao", "plano", Map.of("type", "object", "properties", Map.of()))));
        }
        @Override public CompletableFuture<McpModel.ToolResult> callTool(McpModel.ToolArguments a) {
            chamadas++;
            return CompletableFuture.failedFuture(new CompletionException(
                    new HttpMcpClient.McpRpcException(codigo, "o agente NS não foi contratado")));
        }
    }

    /** Respostas roteirizadas; depois do roteiro, repete a última. */
    private static final class Modelo implements ChatModel {
        private final List<ChatResponse> roteiro;
        private final int estouraNoTurno;
        int turnos;

        Modelo(List<ChatResponse> roteiro) {
            this(roteiro, -1);
        }

        Modelo(List<ChatResponse> roteiro, int estouraNoTurno) {
            this.roteiro = roteiro;
            this.estouraNoTurno = estouraNoTurno;
        }

        @Override public ChatResponse chat(ChatRequest request) {
            int turno = ++turnos;
            if (turno == estouraNoTurno) {
                // Não é falha de transporte (essa é repetida); é o provedor recusando de vez.
                throw new IllegalArgumentException("o provedor recusou o pedido");
            }
            return roteiro.get(Math.min(turno - 1, roteiro.size() - 1));
        }
    }

    private static final class Resolvedor implements LLMConfigResolver {
        private final ChatModel model;
        private final ResolvedLLMConfig config;

        Resolvedor(ChatModel model, ResolvedLLMConfig config) {
            this.model = model;
            this.config = config;
        }

        @Override public ResolvedLLMConfig resolve(LLMResolutionRequest r) { return config; }
        @Override public ChatModel resolveModel(LLMResolutionRequest r) { return model; }
    }

    private static ChatResponse chamaPlano(TokenUsage uso) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(ToolExecutionRequest.builder()
                        .id("c1").name("plano_negociacao").arguments("{}").build()))
                .tokenUsage(uso)
                .build();
    }

    private static ChatResponse texto(String t, TokenUsage uso) {
        return ChatResponse.builder().aiMessage(AiMessage.from(t)).tokenUsage(uso).build();
    }

    private static McpAgentRunner runner(ChatModel modelo) {
        return new McpAgentRunner(new Resolvedor(modelo, CONFIG), CONFIG);
    }

    private static McpAgentRunner.Options opcoes() {
        return new McpAgentRunner.Options(ToolAccessPolicy.allowAll());
    }

    @Nested
    @DisplayName("código terminal")
    class CodigoTerminal {

        @Test
        @DisplayName("-32001 declarado: o laço para na hora, sem devolver o erro ao modelo")
        void paraNaHora() {
            Modelo modelo = new Modelo(List.of(chamaPlano(null), texto("tento de novo?", null)));
            ClienteQueRecusa cliente = new ClienteQueRecusa(-32001);

            McpAgentRunner.Result r = runner(modelo).run("acme", "sys", "faz 8?", cliente,
                    opcoes().encerrandoEm(Set.of(-32001)));

            assertThat(r.isEncerrado()).isTrue();
            assertThat(r.encerradoPor().errorCode()).isEqualTo(-32001);
            assertThat(r.encerradoPor().name()).isEqualTo("plano_negociacao");
            assertThat(modelo.turnos)
                    .as("nenhuma volta a mais muda 'não contratado' — e cada uma custa ao vendedor")
                    .isEqualTo(1);
            assertThat(cliente.chamadas).isEqualTo(1);
        }

        /** Sem declaração, nada muda: o erro volta ao modelo, como sempre voltou. */
        @Test
        @DisplayName("sem códigos declarados, o erro volta ao modelo como antes")
        void semDeclaracaoNadaMuda() {
            Modelo modelo = new Modelo(List.of(chamaPlano(null), texto("não consegui", null)));

            McpAgentRunner.Result r = runner(modelo).run("acme", "sys", "faz 8?",
                    new ClienteQueRecusa(-32001), opcoes());

            assertThat(r.isEncerrado()).isFalse();
            assertThat(r.finalText()).isEqualTo("não consegui");
            assertThat(r.toolCalls()).singleElement()
                    .satisfies(c -> {
                        assertThat(c.isError()).isTrue();
                        assertThat(c.errorCode())
                                .as("o código fica registrado mesmo quando não encerra")
                                .isEqualTo(-32001);
                    });
        }

        /** Argumento errado é corrigível: é para isso que o erro volta ao modelo. */
        @Test
        @DisplayName("outro código continua voltando ao modelo")
        void outroCodigoContinua() {
            Modelo modelo = new Modelo(List.of(chamaPlano(null), texto("corrigi", null)));

            McpAgentRunner.Result r = runner(modelo).run("acme", "sys", "faz 8?",
                    new ClienteQueRecusa(-32602), opcoes().encerrandoEm(Set.of(-32001)));

            assertThat(r.isEncerrado()).isFalse();
            assertThat(modelo.turnos).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("contagem de uso")
    class Uso {

        @Test
        @DisplayName("soma os turnos e rotula com provider/modelo da configuração")
        void somaOsTurnos() {
            Modelo modelo = new Modelo(List.of(
                    chamaPlano(new TokenUsage(600, 40)),
                    texto("pronto", new TokenUsage(150, 22))));
            ContadorDeUso contador = new ContadorDeUso();

            runner(modelo).run("acme", "sys", "faz 8?", new ClienteQueRecusa(-32602),
                    opcoes().comUso(contador));

            ContadorDeUso.Resumo resumo = contador.resumo().orElseThrow();
            assertThat(resumo.modelo()).isEqualTo("openrouter/google/gemini-2.5-flash-lite");
            assertThat(resumo.tokensEntrada()).isEqualTo(750);
            assertThat(resumo.tokensSaida()).isEqualTo(62);
            assertThat(resumo.tokens()).isEqualTo(812);
            assertThat(resumo.provedor()).isEqualTo("openrouter");
            assertThat(resumo.turnos()).isEqualTo(2);
            assertThat(resumo.chamadasDeTool())
                    .as("a chamada foi ao servidor, mesmo tendo voltado erro")
                    .isEqualTo(1);
            assertThat(resumo.execucaoId()).isEqualTo(contador.execucaoId());
        }

        /** Só conta o que foi ao servidor: tool negada não sai do runner. */
        @Test
        @DisplayName("tool negada pela política não conta como chamada")
        void negadaNaoConta() {
            Modelo modelo = new Modelo(List.of(chamaPlano(null), texto("ok", null)));
            ClienteQueRecusa cliente = new ClienteQueRecusa(-32602);
            ContadorDeUso contador = new ContadorDeUso();

            runner(modelo).run("acme", "sys", "faz 8?", cliente,
                    new McpAgentRunner.Options(ToolAccessPolicy.allowOnly(Set.of("outra")))
                            .comUso(contador));

            assertThat(cliente.chamadas).isZero();
            assertThat(contador.resumo().orElseThrow().chamadasDeTool()).isZero();
        }

        /**
         * O motivo de somar a cada turno: a execução que falhou também gastou, e é essa que um teto
         * de custo mais precisa ver.
         */
        @Test
        @DisplayName("execução que falha no meio mantém o que já gastou")
        void falhaNoMeioContaOQueGastou() {
            Modelo modelo = new Modelo(List.of(chamaPlano(new TokenUsage(600, 40))), 2);
            ContadorDeUso contador = new ContadorDeUso();

            assertThatThrownBy(() -> runner(modelo).run("acme", "sys", "faz 8?",
                    new ClienteQueRecusa(-32602), opcoes().comUso(contador)))
                    .hasMessageContaining("recusou");

            assertThat(contador.resumo().orElseThrow().tokens()).isEqualTo(640);
        }

        /** Provedor que não informa uso: sabe-se o modelo, não os tokens — e não se inventa zero. */
        @Test
        @DisplayName("sem uso informado pelo provedor, tokens ficam nulos, não zero")
        void provedorSemUso() {
            Modelo modelo = new Modelo(List.of(texto("pronto", null)));
            ContadorDeUso contador = new ContadorDeUso();

            runner(modelo).run("acme", "sys", "oi", new ClienteQueRecusa(-32602),
                    opcoes().comUso(contador));

            ContadorDeUso.Resumo resumo = contador.resumo().orElseThrow();
            assertThat(resumo.modelo()).isEqualTo("openrouter/google/gemini-2.5-flash-lite");
            assertThat(resumo.tokens()).isNull();
        }

        @Test
        @DisplayName("sem contador, o laço roda igual")
        void semContador() {
            Modelo modelo = new Modelo(List.of(texto("pronto", new TokenUsage(1, 1))));

            McpAgentRunner.Result r = runner(modelo).run("acme", "sys", "oi",
                    new ClienteQueRecusa(-32602), opcoes());

            assertThat(r.finalText()).isEqualTo("pronto");
        }

        /** Um resolvedor sem config (mock, legado) não pode derrubar o laço por causa de um rótulo. */
        @Test
        @DisplayName("sem configuração resolvida, o uso sai sem modelo — e o laço não cai")
        void semRotulo() {
            Modelo modelo = new Modelo(List.of(texto("pronto", new TokenUsage(10, 5))));
            ContadorDeUso contador = new ContadorDeUso();

            new McpAgentRunner(new Resolvedor(modelo, null), CONFIG)
                    .run("acme", "sys", "oi", new ClienteQueRecusa(-32602), opcoes().comUso(contador));

            ContadorDeUso.Resumo resumo = contador.resumo().orElseThrow();
            assertThat(resumo.modelo()).isNull();
            assertThat(resumo.tokens()).isEqualTo(15);
        }
    }
}
