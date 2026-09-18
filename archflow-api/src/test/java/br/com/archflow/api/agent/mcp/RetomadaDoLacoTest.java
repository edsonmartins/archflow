package br.com.archflow.api.agent.mcp;

import br.com.archflow.langchain4j.mcp.McpClient;
import br.com.archflow.langchain4j.mcp.McpModel;
import br.com.archflow.langchain4j.provider.LLMConfigResolver;
import br.com.archflow.langchain4j.provider.LLMResolutionRequest;
import br.com.archflow.model.config.ResolvedLLMConfig;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A metade que faltava do gate humano: alguém retoma o laço.
 *
 * <p>Até 18/09/2026, {@code McpAgentRunner.resume} não tinha <b>um único chamador</b>: o laço
 * suspendia, gravava estado durável e ninguém o trazia de volta. O que estes testes protegem, além
 * de "volta", é que a volta usa <b>as mesmas regras</b> da execução original — reconstruir com
 * {@code allowAll} transformaria o gate, que existe para restringir, na porta de entrada de tudo.</p>
 */
@DisplayName("RetomadaDoLaco — o laço suspenso volta, com as mesmas regras")
class RetomadaDoLacoTest {

    private static final String LER = "ler_logs";
    private static final String REINICIAR = "reiniciar_servico";
    private static final String APAGAR = "apagar_base";

    private final ClienteQueRegistra cliente = new ClienteQueRegistra();
    private InMemoryMcpAgentStateStore store;

    @BeforeEach
    void limpar() {
        store = new InMemoryMcpAgentStateStore();
    }

    private static final class ClienteQueRegistra implements McpClient {
        final List<String> chamadas = new CopyOnWriteArrayList<>();

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
                    new McpModel.Tool(LER, "lê", schema),
                    new McpModel.Tool(REINICIAR, "reinicia", schema),
                    new McpModel.Tool(APAGAR, "apaga", schema)));
        }
        @Override public CompletableFuture<McpModel.ToolResult> callTool(McpModel.ToolArguments a) {
            chamadas.add(a.name());
            return CompletableFuture.completedFuture(McpModel.ToolResult.text("feito"));
        }
    }

    /** Modelo roteirizado, com uso informado para o contador da retomada. */
    private static final class Modelo implements ChatModel {
        private final List<AiMessage> roteiro;
        int turnos;

        Modelo(List<AiMessage> roteiro) {
            this.roteiro = roteiro;
        }

        @Override public ChatResponse chat(ChatRequest request) {
            return ChatResponse.builder()
                    .aiMessage(roteiro.get(Math.min(turnos++, roteiro.size() - 1)))
                    .tokenUsage(new TokenUsage(100, 10))
                    .build();
        }
    }

    private static AiMessage chama(String tool) {
        return AiMessage.from(ToolExecutionRequest.builder()
                .id("c-" + tool).name(tool).arguments("{}").build());
    }

    /** Host de teste: entrega o runner da retomada e o client do servidor pedido. */
    private final class Host implements McpAgentHost {
        private final McpAgentRunner runner;
        final List<String> serversPedidos = new ArrayList<>();

        Host(ChatModel modelo) {
            ResolvedLLMConfig config = ResolvedLLMConfig.builder()
                    .provider("openai").model("fake").build();
            LLMConfigResolver resolver = new LLMConfigResolver() {
                @Override public ResolvedLLMConfig resolve(LLMResolutionRequest r) { return config; }
                @Override public ChatModel resolveModel(LLMResolutionRequest r) { return modelo; }
            };
            this.runner = new McpAgentRunner(resolver, config, null, 0, store);
        }

        @Override public McpAgentRunner runner() {
            return runner;
        }

        @Override public McpClient clientFor(String tenantId, String serverRef) {
            serversPedidos.add(String.valueOf(serverRef));
            return cliente;
        }
    }

    private McpAgentRunner.Options opcoes(Set<String> permitidas, Set<String> sobGate) {
        return new McpAgentRunner.Options(
                permitidas == null ? ToolAccessPolicy.allowAll()
                        : ToolAccessPolicy.allowOnly(permitidas),
                ToolTrustPolicy.untrustedByDefault(),
                ToolApprovalPolicy.requiringFor(sobGate),
                8)
                .comRetomada(new McpAgentState.Retomada("vendax", permitidas, sobGate,
                        Set.of(), null, 8, Set.of()));
    }

    /** Suspende um laço de verdade e devolve o requestId pendente. */
    private String suspender(ChatModel modelo, Set<String> permitidas, Set<String> sobGate) {
        McpAgentRunner.Result r = new Host(modelo).runner()
                .run("acme", "sys", "reinicie o serviço", cliente, opcoes(permitidas, sobGate));
        assertThat(r.isSuspended()).isTrue();
        return r.pendingApproval().requestId();
    }

    @Test
    @DisplayName("lista o pendente, executa a tool ao aprovar e o laço conclui")
    void aprovaEConclui() {
        Modelo modelo = new Modelo(List.of(chama(REINICIAR), AiMessage.from("reiniciado")));
        String requestId = suspender(modelo, Set.of(LER, REINICIAR), Set.of(REINICIAR));
        Host host = new Host(modelo);
        RetomadaDoLaco retomada = new RetomadaDoLaco(store, host);

        assertThat(retomada.pendentes("acme")).singleElement().satisfies(p -> {
            assertThat(p.requestId()).isEqualTo(requestId);
            assertThat(p.toolName()).isEqualTo(REINICIAR);
            assertThat(p.suspensoEm()).isBefore(Instant.now().plusSeconds(1));
        });

        RetomadaDoLaco.Conclusao conclusao = retomada.decidir(requestId, true, null);

        assertThat(cliente.chamadas).containsExactly(REINICIAR);
        assertThat(conclusao.resultado().isSuspended()).isFalse();
        assertThat(conclusao.resultado().finalText()).isEqualTo("reiniciado");
        assertThat(host.serversPedidos)
                .as("o servidor MCP da execução original, não um default")
                .containsExactly("vendax");
        assertThat(store.findPendingByTenant("acme"))
                .as("decisão consumida: o estado sai do store")
                .isEmpty();
    }

    /**
     * O ponto mais importante: a allowlist volta dos dados gravados. Se a retomada reconstruísse
     * com {@code allowAll}, o gate — que existe para restringir — viraria a porta de entrada.
     */
    @Test
    @DisplayName("a allowlist original continua valendo na retomada")
    void allowlistNaoAfrouxa() {
        Modelo modelo = new Modelo(List.of(chama(REINICIAR), chama(APAGAR), AiMessage.from("fim")));
        String requestId = suspender(modelo, Set.of(LER, REINICIAR), Set.of(REINICIAR));

        RetomadaDoLaco.Conclusao conclusao =
                new RetomadaDoLaco(store, new Host(modelo)).decidir(requestId, true, null);

        assertThat(cliente.chamadas)
                .as("apagar_base não estava na allowlist da execução original")
                .containsExactly(REINICIAR);
        assertThat(conclusao.resultado().toolCalls())
                .filteredOn(c -> c.name().equals(APAGAR))
                .singleElement()
                .satisfies(c -> assertThat(c.resultText()).contains("não está autorizada"));
    }

    /** O gate também não afrouxa: a segunda chamada da mesma tool suspende de novo. */
    @Test
    @DisplayName("o gate continua valendo depois da retomada")
    void gateContinuaValendo() {
        Modelo modelo = new Modelo(List.of(chama(REINICIAR), chama(REINICIAR), AiMessage.from("fim")));
        String requestId = suspender(modelo, Set.of(LER, REINICIAR), Set.of(REINICIAR));
        List<RetomadaDoLaco.Conclusao> avisos = new ArrayList<>();

        RetomadaDoLaco.Conclusao conclusao = new RetomadaDoLaco(store, new Host(modelo),
                List.of(avisos::add)).decidir(requestId, true, null);

        assertThat(conclusao.resultado().isSuspended()).isTrue();
        assertThat(conclusao.resultado().pendingApproval().requestId()).isNotEqualTo(requestId);
        assertThat(avisos).as("ainda não terminou: ninguém é avisado").isEmpty();
        assertThat(store.findPendingByTenant("acme")).hasSize(1);
    }

    @Test
    @DisplayName("recusar devolve a recusa ao modelo, sem executar a tool")
    void recusa() {
        Modelo modelo = new Modelo(List.of(chama(REINICIAR), AiMessage.from("entendi, não reinicio")));
        String requestId = suspender(modelo, Set.of(LER, REINICIAR), Set.of(REINICIAR));

        RetomadaDoLaco.Conclusao conclusao =
                new RetomadaDoLaco(store, new Host(modelo)).decidir(requestId, false, null);

        assertThat(cliente.chamadas).isEmpty();
        assertThat(conclusao.resultado().finalText()).isEqualTo("entendi, não reinicio");
    }

    @Test
    @DisplayName("argumentos editados pelo humano substituem os do modelo")
    void argumentosEditados() {
        Modelo modelo = new Modelo(List.of(chama(REINICIAR), AiMessage.from("fim")));
        String requestId = suspender(modelo, null, Set.of(REINICIAR));

        RetomadaDoLaco.Conclusao conclusao = new RetomadaDoLaco(store, new Host(modelo))
                .decidir(requestId, true, Map.of("servico", "traefik"));

        assertThat(conclusao.resultado().toolCalls()).singleElement()
                .satisfies(c -> assertThat(c.arguments()).containsEntry("servico", "traefik"));
    }

    /** O trecho retomado também gasta, e o consumo dele tem dono. */
    @Test
    @DisplayName("o consumo da retomada é contado à parte")
    void consumoDaRetomada() {
        Modelo modelo = new Modelo(List.of(chama(REINICIAR), AiMessage.from("fim")));
        String requestId = suspender(modelo, null, Set.of(REINICIAR));

        RetomadaDoLaco.Conclusao conclusao =
                new RetomadaDoLaco(store, new Host(modelo)).decidir(requestId, true, null);

        ContadorDeUso.Resumo resumo = conclusao.uso().resumo().orElseThrow();
        assertThat(resumo.turnos()).isEqualTo(1);
        assertThat(resumo.tokens()).isEqualTo(110);
        assertThat(resumo.chamadasDeTool()).isEqualTo(1);
    }

    @Test
    @DisplayName("o ouvinte é avisado quando o laço termina, e uma falha dele não desfaz a retomada")
    void ouvintes() {
        Modelo modelo = new Modelo(List.of(chama(REINICIAR), AiMessage.from("pronto")));
        String requestId = suspender(modelo, null, Set.of(REINICIAR));
        List<RetomadaDoLaco.Conclusao> avisos = new ArrayList<>();

        RetomadaDoLaco.Conclusao conclusao = new RetomadaDoLaco(store, new Host(modelo), List.of(
                c -> {
                    throw new IllegalStateException("ouvinte quebrado");
                },
                avisos::add)).decidir(requestId, true, null);

        assertThat(conclusao.resultado().finalText()).isEqualTo("pronto");
        assertThat(avisos).as("o ouvinte seguinte ainda é avisado").hasSize(1);
        assertThat(avisos.get(0).estado().runId()).isEqualTo(conclusao.estado().runId());
    }

    @Test
    @DisplayName("id desconhecido: erro claro, não silêncio")
    void idDesconhecido() {
        assertThatThrownBy(() -> new RetomadaDoLaco(store, new Host(new Modelo(List.of())))
                .decidir("nao-existe", true, null))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("nao-existe");
    }

    /**
     * Estado gravado por uma versão anterior não tem como ser retomado: adivinhar a allowlist é
     * exatamente o que esta classe existe para não fazer.
     */
    @Test
    @DisplayName("estado antigo, sem dados de retomada: recusa explicando")
    void estadoAntigo() {
        store.save(new McpAgentState("run-velho", "acme", "sys", "nonce", List.of(), List.of(), 1,
                "", new McpAgentState.PendingApproval("req-velho", REINICIAR, Map.of(), "c1"),
                Instant.now()));

        assertThatThrownBy(() -> new RetomadaDoLaco(store, new Host(new Modelo(List.of())))
                .decidir("req-velho", true, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("versão anterior");
    }

    @Test
    @DisplayName("pendentes de outro tenant não aparecem")
    void outroTenant() {
        Modelo modelo = new Modelo(List.of(chama(REINICIAR), AiMessage.from("fim")));
        suspender(modelo, null, Set.of(REINICIAR));

        assertThat(new RetomadaDoLaco(store, new Host(modelo)).pendentes("outro")).isEmpty();
    }
}
