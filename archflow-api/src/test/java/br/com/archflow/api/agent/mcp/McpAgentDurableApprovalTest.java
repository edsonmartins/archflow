package br.com.archflow.api.agent.mcp;

import br.com.archflow.langchain4j.mcp.McpClient;
import br.com.archflow.langchain4j.mcp.McpModel;
import br.com.archflow.langchain4j.provider.LLMConfigResolver;
import br.com.archflow.langchain4j.provider.LLMResolutionRequest;
import br.com.archflow.model.config.ResolvedLLMConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O gate humano existia apenas como <b>aresta do grafo</b>: dava para aprovar
 * entre passos, não no meio de um raciocínio — que é onde uma remediação de fato
 * é proposta. E o transcript vivia em heap, então um crash no meio de oito turnos
 * perdia tudo.
 *
 * <p>Estes testes percorrem o circuito e, no caso decisivo, simulam restart:
 * um runner <b>novo</b> retoma a partir do estado persistido.
 */
@DisplayName("Laço MCP — suspensão durável para aprovação humana")
class McpAgentDurableApprovalTest {

    private static final String READ = "ler_logs";
    private static final String WRITE = "reiniciar_servico";

    private InMemoryMcpAgentStateStore store;
    private RecordingClient client;

    @BeforeEach
    void setUp() {
        store = new InMemoryMcpAgentStateStore();
        client = new RecordingClient();
    }

    // ── Fakes ────────────────────────────────────────────────────────

    private static class RecordingClient implements McpClient {
        final List<String> invoked = new CopyOnWriteArrayList<>();
        final List<Map<String, Object>> arguments = new CopyOnWriteArrayList<>();

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
            Map<String, Object> schema = Map.of(
                    "type", "object",
                    "required", List.of("servico"),
                    "properties", Map.of("servico", Map.of("type", "string")));
            return CompletableFuture.completedFuture(List.of(
                    new McpModel.Tool(READ, "lê logs", schema),
                    new McpModel.Tool(WRITE, "reinicia serviço", schema)));
        }

        @Override public CompletableFuture<McpModel.ToolResult> callTool(McpModel.ToolArguments a) {
            invoked.add(a.name());
            arguments.add(a.arguments());
            return CompletableFuture.completedFuture(McpModel.ToolResult.text("feito: " + a.name()));
        }
    }

    private static final class ScriptedChatModel implements ChatModel {
        private final List<AiMessage> script;
        final List<List<ChatMessage>> transcripts = new ArrayList<>();
        private int turn;

        ScriptedChatModel(List<AiMessage> script) {
            this.script = script;
        }

        @Override public ChatResponse chat(ChatRequest request) {
            transcripts.add(List.copyOf(request.messages()));
            return ChatResponse.builder()
                    .aiMessage(script.get(Math.min(turn++, script.size() - 1)))
                    .build();
        }
    }

    private static final class Resolver implements LLMConfigResolver {
        private final ChatModel model;

        Resolver(ChatModel model) {
            this.model = model;
        }

        @Override public ResolvedLLMConfig resolve(LLMResolutionRequest r) {
            return ResolvedLLMConfig.builder().provider("openai").model("fake").build();
        }
        @Override public ChatModel resolveModel(LLMResolutionRequest r) {
            return model;
        }
        @Override public StreamingChatModel resolveStreamingModel(LLMResolutionRequest r) {
            throw new UnsupportedOperationException();
        }
    }

    private McpAgentRunner runnerWith(ChatModel model) {
        return new McpAgentRunner(new Resolver(model),
                ResolvedLLMConfig.builder().provider("openai").model("fake").build(),
                null, 0, store);
    }

    private static AiMessage calls(String tool, String args) {
        return AiMessage.from(ToolExecutionRequest.builder()
                .id("call-" + tool).name(tool).arguments(args).build());
    }

    private static McpAgentRunner.Options gating(String... gatedTools) {
        return new McpAgentRunner.Options(
                ToolAccessPolicy.allowAll(),
                ToolTrustPolicy.untrustedByDefault(),
                ToolApprovalPolicy.requiringFor(List.of(gatedTools)),
                8);
    }

    // ── Suspensão ────────────────────────────────────────────────────

    @Test
    @DisplayName("suspende ANTES de executar a tool sob gate")
    void suspendsBeforeExecuting() {
        var model = new ScriptedChatModel(List.of(calls(WRITE, "{\"servico\":\"traefik\"}")));

        var result = runnerWith(model).run("acme", "sys", "reinicia o traefik", client, gating(WRITE));

        assertThat(result.isSuspended()).isTrue();
        assertThat(client.invoked)
                .as("pedir aprovacao DEPOIS de rodar a tool nao e aprovacao, e notificacao")
                .isEmpty();
        assertThat(result.pendingApproval().toolName()).isEqualTo(WRITE);
        assertThat(result.pendingApproval().arguments()).containsEntry("servico", "traefik");
    }

    @Test
    @DisplayName("tool fora do gate executa normalmente, sem suspender")
    void ungatedToolRunsThrough() {
        var model = new ScriptedChatModel(List.of(
                calls(READ, "{\"servico\":\"traefik\"}"),
                AiMessage.from("os logs mostram timeout")));

        var result = runnerWith(model).run("acme", "sys", "ver logs", client, gating(WRITE));

        assertThat(result.isSuspended()).isFalse();
        assertThat(client.invoked).containsExactly(READ);
        assertThat(result.finalText()).isEqualTo("os logs mostram timeout");
    }

    // ── Retomada ─────────────────────────────────────────────────────

    @Test
    @DisplayName("um runner NOVO retoma do estado persistido — sobrevive a restart")
    void resumesAfterRestart() {
        var beforeCrash = new ScriptedChatModel(List.of(
                calls(READ, "{\"servico\":\"traefik\"}"),
                calls(WRITE, "{\"servico\":\"traefik\"}")));
        var suspended = runnerWith(beforeCrash)
                .run("acme", "sys", "conserta o traefik", client, gating(WRITE));
        assertThat(suspended.isSuspended()).isTrue();
        assertThat(client.invoked).containsExactly(READ);

        // Restart: instância nova do runner e do modelo; só o store persiste.
        var afterRestart = new ScriptedChatModel(List.of(AiMessage.from("serviço reiniciado")));
        var resumed = runnerWith(afterRestart).resume(
                suspended.pendingApproval().requestId(), true, null, client, gating(WRITE));

        assertThat(resumed.isSuspended()).isFalse();
        assertThat(client.invoked).containsExactly(READ, WRITE);
        assertThat(resumed.finalText()).isEqualTo("serviço reiniciado");

        // O transcript foi reconstruído: o modelo retomado vê o histórico inteiro.
        List<ChatMessage> seen = afterRestart.transcripts.get(0);
        assertThat(seen).hasSizeGreaterThan(3);
        assertThat(seen.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(resumed.toolCalls()).extracting(McpAgentRunner.ToolCall::name)
                .as("as chamadas de antes da suspensao nao podem se perder")
                .containsExactly(READ, WRITE);
    }

    @Test
    @DisplayName("o nonce da cerca sobrevive — senão a regra do prompt deixaria de casar")
    void fenceNonceSurvivesResume() {
        var before = new ScriptedChatModel(List.of(calls(WRITE, "{\"servico\":\"x\"}")));
        var suspended = runnerWith(before).run("acme", "sys", "u", client, gating(WRITE));
        String nonceNoPrompt = nonceFrom(before.transcripts.get(0));

        var after = new ScriptedChatModel(List.of(AiMessage.from("ok")));
        runnerWith(after).resume(suspended.pendingApproval().requestId(), true, null,
                client, gating(WRITE));

        assertThat(nonceFrom(after.transcripts.get(0)))
                .as("nonce novo faria o conteudo cercado depois nao casar com a regra de antes")
                .isEqualTo(nonceNoPrompt);
    }

    private static String nonceFrom(List<ChatMessage> messages) {
        String system = ((SystemMessage) messages.get(0)).text();
        int at = system.indexOf("id=");
        return system.substring(at + 3, system.indexOf(' ', at));
    }

    @Test
    @DisplayName("recusa NÃO executa a tool; o modelo recebe a recusa e pode reagir")
    void rejectionDoesNotExecute() {
        var before = new ScriptedChatModel(List.of(calls(WRITE, "{\"servico\":\"traefik\"}")));
        var suspended = runnerWith(before).run("acme", "sys", "u", client, gating(WRITE));

        var after = new ScriptedChatModel(List.of(AiMessage.from("entendi, não vou reiniciar")));
        var resumed = runnerWith(after).resume(
                suspended.pendingApproval().requestId(), false, null, client, gating(WRITE));

        assertThat(client.invoked)
                .as("uma remediacao recusada jamais pode rodar")
                .isEmpty();
        assertThat(resumed.finalText()).isEqualTo("entendi, não vou reiniciar");
        assertThat(resumed.toolCalls()).isEmpty();
    }

    @Test
    @DisplayName("o humano pode editar os argumentos antes de aprovar")
    void humanCanEditArguments() {
        var before = new ScriptedChatModel(List.of(calls(WRITE, "{\"servico\":\"traefik\"}")));
        var suspended = runnerWith(before).run("acme", "sys", "u", client, gating(WRITE));

        var after = new ScriptedChatModel(List.of(AiMessage.from("ok")));
        runnerWith(after).resume(suspended.pendingApproval().requestId(), true,
                Map.of("servico", "traefik-canary"), client, gating(WRITE));

        assertThat(client.arguments).singleElement()
                .satisfies(a -> assertThat(a).containsEntry("servico", "traefik-canary"));
    }

    @Test
    @DisplayName("a decisão é consumida: retomar duas vezes não reexecuta")
    void decisionIsConsumedOnce() {
        var before = new ScriptedChatModel(List.of(calls(WRITE, "{\"servico\":\"x\"}")));
        var suspended = runnerWith(before).run("acme", "sys", "u", client, gating(WRITE));
        String requestId = suspended.pendingApproval().requestId();

        runnerWith(new ScriptedChatModel(List.of(AiMessage.from("ok"))))
                .resume(requestId, true, null, client, gating(WRITE));

        assertThatThrownBy(() -> runnerWith(new ScriptedChatModel(List.of(AiMessage.from("x"))))
                .resume(requestId, true, null, client, gating(WRITE)))
                .as("uma acao aprovada uma vez nao pode ser executada duas")
                .isInstanceOf(java.util.NoSuchElementException.class);
        assertThat(client.invoked).containsExactly(WRITE);
    }

    // ── Contratos ────────────────────────────────────────────────────

    @Test
    @DisplayName("argumentos fora do schema não chegam ao humano — não se pede decisão sobre lixo")
    void invalidArgumentsDoNotReachTheHuman() {
        var model = new ScriptedChatModel(List.of(
                calls(WRITE, "{\"servico\": 42}"),
                AiMessage.from("corrijo")));

        var result = runnerWith(model).run("acme", "sys", "u", client, gating(WRITE));

        assertThat(result.isSuspended())
                .as("mandar um humano decidir sobre chamada que nem passa na validacao "
                        + "desperdica o tempo dele")
                .isFalse();
        assertThat(client.invoked).isEmpty();
        assertThat(store.findPendingByTenant("acme")).isEmpty();
    }

    @Test
    @DisplayName("gate sem store falha alto — pausa que não sobrevive a restart não é suspensão")
    void gateWithoutStoreFailsLoud() {
        var runner = new McpAgentRunner(
                new Resolver(new ScriptedChatModel(List.of(calls(WRITE, "{\"servico\":\"x\"}")))),
                ResolvedLLMConfig.builder().provider("openai").model("fake").build());

        assertThatThrownBy(() -> runner.run("acme", "sys", "u", client, gating(WRITE)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("McpAgentStateStore");
    }

    @Test
    @DisplayName("a fila de pendentes por tenant enxerga o suspenso")
    void pendingQueueSeesTheSuspendedRun() {
        var model = new ScriptedChatModel(List.of(calls(WRITE, "{\"servico\":\"x\"}")));
        runnerWith(model).run("acme", "sys", "u", client, gating(WRITE));

        assertThat(store.findPendingByTenant("acme")).hasSize(1);
        assertThat(store.findPendingByTenant("outro")).isEmpty();
        assertThat(store.findPendingByTenant("all")).hasSize(1);
    }

    @Test
    @DisplayName("Options exige as três políticas explicitamente")
    void optionsRequireEveryPolicy() {
        var runner = runnerWith(new ScriptedChatModel(List.of(AiMessage.from("x"))));

        assertThatThrownBy(() -> runner.run("acme", "s", "u", client,
                new McpAgentRunner.Options(null, ToolTrustPolicy.untrustedByDefault(),
                        ToolApprovalPolicy.none(), 8)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> runner.run("acme", "s", "u", client,
                new McpAgentRunner.Options(ToolAccessPolicy.allowAll(),
                        ToolTrustPolicy.untrustedByDefault(), null, 8)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("none()");
    }

    /** O ObjectMapper precisa dar round-trip no estado — é o que o store JDBC fará. */
    @Test
    @DisplayName("McpAgentState faz round-trip em JSON")
    void stateSerializesRoundTrip() throws Exception {
        var model = new ScriptedChatModel(List.of(calls(WRITE, "{\"servico\":\"x\"}")));
        runnerWith(model).run("acme", "sys", "u", client, gating(WRITE));
        McpAgentState original = store.findPendingByTenant("acme").get(0);

        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        McpAgentState back = json.readValue(json.writeValueAsString(original), McpAgentState.class);

        assertThat(back.runId()).isEqualTo(original.runId());
        assertThat(back.fenceNonce()).isEqualTo(original.fenceNonce());
        assertThat(back.messages()).isEqualTo(original.messages());
        assertThat(back.pending().toolName()).isEqualTo(WRITE);
        assertThat(back.pending().arguments()).containsEntry("servico", "x");
    }
}
