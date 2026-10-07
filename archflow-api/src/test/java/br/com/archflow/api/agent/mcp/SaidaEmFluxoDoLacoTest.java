package br.com.archflow.api.agent.mcp;

import br.com.archflow.langchain4j.mcp.McpClient;
import br.com.archflow.langchain4j.mcp.McpModel;
import br.com.archflow.langchain4j.provider.LLMConfigResolver;
import br.com.archflow.langchain4j.provider.LLMResolutionRequest;
import br.com.archflow.model.config.ResolvedLLMConfig;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O laço do agente entregando texto enquanto o modelo escreve.
 *
 * <p>O provedor aqui é simulado: nenhum teste chama modelo de verdade, e nenhum conhece um agente
 * ou uma aplicação.</p>
 */
@DisplayName("laço MCP: saída em fluxo")
class SaidaEmFluxoDoLacoTest {

    private static final ResolvedLLMConfig CONFIG = ResolvedLLMConfig.builder()
            .provider("simulado").model("modelo-simulado").maxTokens(1024).build();

    /** Uma saída que anota tudo. */
    private static final class Anotadora implements SaidaDeTexto {
        final List<String> deltas = new CopyOnWriteArrayList<>();
        final List<String> tools = new CopyOnWriteArrayList<>();
        final CompletableFuture<Void> cancelamento = new CompletableFuture<>();
        final boolean transmite;

        Anotadora() {
            this(true);
        }

        Anotadora(boolean transmite) {
            this.transmite = transmite;
        }

        @Override public boolean transmite() { return transmite; }
        @Override public void delta(String texto) { deltas.add(texto); }
        @Override public void tool(String nome, boolean inicio, Long duracaoMs) {
            tools.add(nome + ":" + (inicio ? "INICIO" : "FIM"));
        }
        @Override public CompletableFuture<Void> cancelamento() { return cancelamento; }
        String texto() { return String.join("", deltas); }
    }

    /** Um turno de um modelo em streaming: pedaços de texto e, opcionalmente, uma tool. */
    private record Turno(List<String> pedacos, String tool) {
        static Turno texto(String... pedacos) {
            return new Turno(List.of(pedacos), null);
        }

        static Turno comTool(String preambulo, String tool) {
            return new Turno(preambulo.isEmpty() ? List.of() : List.of(preambulo), tool);
        }
    }

    private static final class ModeloEmFluxo implements StreamingChatModel {
        private final List<Turno> roteiro;
        final AtomicBoolean handleCancelado = new AtomicBoolean();
        final CountDownLatch comecou = new CountDownLatch(1);
        /** Se verdadeiro, depois do primeiro pedaço o modelo "trava" até ser cancelado. */
        private final boolean travaDepoisDoPrimeiro;
        int turnos;

        ModeloEmFluxo(List<Turno> roteiro) {
            this(roteiro, false);
        }

        ModeloEmFluxo(List<Turno> roteiro, boolean travaDepoisDoPrimeiro) {
            this.roteiro = roteiro;
            this.travaDepoisDoPrimeiro = travaDepoisDoPrimeiro;
        }

        @Override
        public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
            Turno turno = roteiro.get(Math.min(turnos++, roteiro.size() - 1));
            StreamingHandle handle = new StreamingHandle() {
                @Override public void cancel() { handleCancelado.set(true); }
                @Override public boolean isCancelled() { return handleCancelado.get(); }
            };
            Thread.ofVirtual().start(() -> {
                for (String pedaco : turno.pedacos()) {
                    handler.onPartialResponse(new PartialResponse(pedaco),
                            new PartialResponseContext(handle));
                    comecou.countDown();
                    if (travaDepoisDoPrimeiro) {
                        return;
                    }
                }
                AiMessage ai = turno.tool() == null
                        ? AiMessage.from(String.join("", turno.pedacos()))
                        : AiMessage.from(String.join("", turno.pedacos()),
                                List.of(ToolExecutionRequest.builder()
                                        .id("c" + turnos).name(turno.tool()).arguments("{}").build()));
                handler.onCompleteResponse(ChatResponse.builder().aiMessage(ai).build());
            });
        }
    }

    private static final class Resolvedor implements LLMConfigResolver {
        private final StreamingChatModel streaming;
        private final ChatModel bloqueante;

        Resolvedor(StreamingChatModel streaming, ChatModel bloqueante) {
            this.streaming = streaming;
            this.bloqueante = bloqueante;
        }

        @Override public ResolvedLLMConfig resolve(LLMResolutionRequest r) { return CONFIG; }
        @Override public ChatModel resolveModel(LLMResolutionRequest r) { return bloqueante; }
        @Override public StreamingChatModel resolveStreamingModel(LLMResolutionRequest r) {
            if (streaming == null) {
                throw new UnsupportedOperationException("sem streaming");
            }
            return streaming;
        }
    }

    private static final class Cliente implements McpClient {
        final boolean comTool;

        Cliente(boolean comTool) {
            this.comTool = comTool;
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
            return CompletableFuture.completedFuture(comTool
                    ? List.of(new McpModel.Tool("consulta", "consulta",
                            Map.of("type", "object", "properties", Map.of())))
                    : List.of());
        }
        @Override public CompletableFuture<McpModel.ToolResult> callTool(McpModel.ToolArguments a) {
            return CompletableFuture.completedFuture(McpModel.ToolResult.text("dado"));
        }
    }

    private static ChatModel bloqueante(String texto) {
        return new ChatModel() {
            @Override public ChatResponse chat(ChatRequest request) {
                return ChatResponse.builder().aiMessage(AiMessage.from(texto)).build();
            }
        };
    }

    private static McpAgentRunner runner(StreamingChatModel streaming, ChatModel bloqueante) {
        return new McpAgentRunner(new Resolvedor(streaming, bloqueante), CONFIG);
    }

    private static McpAgentRunner.Options opcoes(SaidaDeTexto saida, ContadorDeUso uso) {
        return new McpAgentRunner.Options(ToolAccessPolicy.allowAll()).comSaida(saida).comUso(uso);
    }

    @Test
    @DisplayName("sem tools, o texto sai pedaço a pedaço e a soma é o texto do resultado")
    void semToolsSaiNaHora() {
        ModeloEmFluxo modelo = new ModeloEmFluxo(List.of(Turno.texto("Olá", ", ", "mundo")));
        Anotadora saida = new Anotadora();
        ContadorDeUso uso = new ContadorDeUso();

        McpAgentRunner.Result r = runner(modelo, bloqueante("não deve ser usado"))
                .run("t1", "sys", "oi", new Cliente(false), opcoes(saida, uso));

        assertThat(saida.deltas).containsExactly("Olá", ", ", "mundo");
        assertThat(saida.texto()).isEqualTo(r.finalText());
        assertThat(uso.resumo()).get().extracting(ContadorDeUso.Resumo::msAtePrimeiroToken)
                .as("com streaming há primeiro token a medir")
                .isNotNull();
    }

    @Test
    @DisplayName("com tools e mais de um turno, só o texto da resposta final vira delta")
    void comToolsSoORespostaFinal() {
        ModeloEmFluxo modelo = new ModeloEmFluxo(List.of(
                Turno.comTool("vou consultar...", "consulta"),
                Turno.texto("Resposta ", "final")));
        Anotadora saida = new Anotadora();

        McpAgentRunner.Result r = runner(modelo, bloqueante("x"))
                .run("t1", "sys", "pergunta", new Cliente(true), opcoes(saida, null));

        assertThat(saida.texto())
                .as("o preâmbulo do turno que chamou a tool não é resposta")
                .isEqualTo("Resposta final")
                .isEqualTo(r.finalText());
        assertThat(saida.tools).containsExactly("consulta:INICIO", "consulta:FIM");
    }

    @Test
    @DisplayName("provedor sem streaming: um único delta com o texto inteiro")
    void semStreamingUmDelta() {
        Anotadora saida = new Anotadora();

        McpAgentRunner.Result r = runner(null, bloqueante("texto inteiro"))
                .run("t1", "sys", "oi", new Cliente(false), opcoes(saida, null));

        assertThat(saida.deltas).containsExactly("texto inteiro");
        assertThat(r.finalText()).isEqualTo("texto inteiro");
    }

    @Test
    @DisplayName("saída que não transmite (resultado estruturado): nenhum delta, modelo bloqueante")
    void naoTransmiteNaoEmiteDelta() {
        ModeloEmFluxo modelo = new ModeloEmFluxo(List.of(Turno.texto("não deve aparecer")));
        Anotadora saida = new Anotadora(false);

        McpAgentRunner.Result r = runner(modelo, bloqueante("{\"ok\":true}"))
                .run("t1", "sys", "oi", new Cliente(false), opcoes(saida, null));

        assertThat(saida.deltas).isEmpty();
        assertThat(modelo.turnos).as("o modelo em streaming nem foi chamado").isZero();
        assertThat(r.finalText()).isEqualTo("{\"ok\":true}");
    }

    @Test
    @DisplayName("cancelar no meio: o laço desiste na hora e manda o provedor parar")
    void cancelarNoMeio() throws Exception {
        ModeloEmFluxo modelo = new ModeloEmFluxo(List.of(Turno.texto("começo", "nunca chega")), true);
        Anotadora saida = new Anotadora();
        List<Throwable> falha = Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<Long> terminou = new CompletableFuture<>();

        Thread laco = Thread.ofVirtual().start(() -> {
            try {
                runner(modelo, bloqueante("x"))
                        .run("t1", "sys", "oi", new Cliente(false), opcoes(saida, null));
            } catch (Throwable t) {
                falha.add(t);
            }
            terminou.complete(System.nanoTime());
        });

        assertThat(modelo.comecou.await(2, TimeUnit.SECONDS)).isTrue();
        long pediuEm = System.nanoTime();
        saida.cancelamento.complete(null);
        long ate = terminou.get(2, TimeUnit.SECONDS);

        assertThat(TimeUnit.NANOSECONDS.toMillis(ate - pediuEm))
                .as("do pedido de cancelamento até parar de consumir o modelo")
                .isLessThan(300);
        assertThat(falha).singleElement().isInstanceOf(ExecucaoCancelada.class);
        assertThat(modelo.handleCancelado).isTrue();
        assertThat(saida.texto()).isEqualTo("começo");
        laco.join();
    }

    @Test
    @DisplayName("já cancelado antes do turno: nem chama o modelo")
    void canceladoAntesDeComecar() {
        ModeloEmFluxo modelo = new ModeloEmFluxo(List.of(Turno.texto("x")));
        Anotadora saida = new Anotadora();
        saida.cancelamento.complete(null);

        assertThatThrownBy(() -> runner(modelo, bloqueante("x"))
                .run("t1", "sys", "oi", new Cliente(false), opcoes(saida, null)))
                .isInstanceOf(ExecucaoCancelada.class);
        assertThat(modelo.turnos).isZero();
    }
}
