package br.com.archflow.api.agent.qp;

import br.com.archflow.api.agent.mcp.McpAgentRunner;
import br.com.archflow.api.mcp.vendax.VendaxMcpClientProvider;
import br.com.archflow.langchain4j.provider.LLMConfigResolver;
import br.com.archflow.langchain4j.provider.LLMResolutionRequest;
import br.com.archflow.model.config.ResolvedLLMConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prova ponta a ponta da integração QP ↔ VendaX Core via MCP:
 * {@code QpAgentService → McpAgentRunner (loop real) → HttpMcpClient (HTTP real)
 * → stub MCP local}. Apenas o LLM é encenado por um {@link ChatModel} fake que
 * dirige a sequência de tools do fluxo QP (sem chave de LLM no teste).
 */
@DisplayName("QP ↔ VendaX Core (MCP) — integração E2E")
class QpAgentIntegrationTest {

    private HttpServer server;
    private String baseUrl;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<CapturedCall> calls = new CopyOnWriteArrayList<>();

    record CapturedCall(String method, String toolName, JsonNode arguments,
                        String authorization, String tenant) {
    }

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            byte[] reqBytes = exchange.getRequestBody().readAllBytes();
            JsonNode req = mapper.readTree(reqBytes);
            String method = req.path("method").asText();
            String tool = req.path("params").path("name").asText(null);
            calls.add(new CapturedCall(method, tool, req.path("params").path("arguments"),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("X-TENANT-ID")));
            Object id = req.path("id").isMissingNode() ? "1" : req.path("id").asText();
            String response = respond(method, tool, id, req);
            byte[] out = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private String respond(String method, String tool, Object id, JsonNode req) {
        if ("initialize".equals(method)) {
            return rpc(id, "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{\"tools\":{}}," +
                    "\"serverInfo\":{\"name\":\"vendax-core\",\"version\":\"1\"}}");
        }
        if ("tools/list".equals(method)) {
            return rpc(id, "{\"tools\":[" + String.join(",", List.of(
                    toolDef("resolver_sku"), toolDef("registrar_resolucao"), toolDef("simular_cotacao"),
                    toolDef("firmar_cotacao"), toolDef("enviar_pedido"), toolDef("sugerir_itens"),
                    toolDef("registrar_decisao"), toolDef("obter_eventos_operacionais"))) + "]}");
        }
        if ("tools/call".equals(method)) {
            String chave = req.path("params").path("arguments").path("chaveIdempotencia").asText("");
            String payload = switch (tool) {
                case "resolver_sku" -> "{\\\"gate\\\":\\\"RESOLVE\\\",\\\"sku\\\":\\\"ABC\\\",\\\"descricao\\\":\\\"Coca 2L\\\"}";
                case "registrar_resolucao" -> "{\\\"ok\\\":true}";
                case "simular_cotacao" -> "{\\\"total\\\":120.0}";
                case "firmar_cotacao" -> "{\\\"cotacaoId\\\":\\\"COT1\\\",\\\"total\\\":120.0,\\\"chaveIdempotencia\\\":\\\"" + chave + "\\\"}";
                case "enviar_pedido" -> "{\\\"status\\\":\\\"ACEITO\\\",\\\"pedidoId\\\":\\\"PED1\\\"}";
                case "sugerir_itens" -> "{\\\"sugestoes\\\":[],\\\"grupoControle\\\":true,\\\"intervencaoId\\\":\\\"INT1\\\"}";
                case "registrar_decisao" -> "{\\\"ok\\\":true}";
                case "obter_eventos_operacionais" -> "{\\\"eventos\\\":[]}";
                default -> "{}";
            };
            return rpc(id, "{\"content\":[{\"type\":\"text\",\"text\":\"" + payload + "\"}],\"isError\":false}");
        }
        return rpc(id, "{}");
    }

    private String toolDef(String name) {
        return "{\"name\":\"" + name + "\",\"description\":\"" + name +
                "\",\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}";
    }

    private String rpc(Object id, String result) {
        return "{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":" + result + "}";
    }

    @Test
    @DisplayName("fluxo QP+US+CS: resolver→registrar→simular→firmar→enviar→sugerir; quote, headers, idempotência e holdout")
    void quoteEndToEnd() {
        VendaxMcpClientProvider provider = new VendaxMcpClientProvider(baseUrl, "svc-token");
        ResolvedLLMConfig platformDefault = ResolvedLLMConfig.builder()
                .provider("openai").model("gpt-4o-mini").build();
        McpAgentRunner runner = new McpAgentRunner(new ScriptedResolver(), platformDefault);
        QpAgentService service = new QpAgentService(runner, provider);

        var result = service.quote(new QpAgentService.QpRequest(
                "acme", "c1", "v1", "TEXTO_CLIENTE", "quero coca 2 litros", List.of()));

        // (1) ordem das tools executadas (US inclui sugerir_itens; holdout NÃO chama registrar_decisao)
        assertThat(result.toolCalls()).extracting(McpAgentRunner.ToolCall::name).containsExactly(
                "resolver_sku", "registrar_resolucao", "simular_cotacao",
                "firmar_cotacao", "enviar_pedido", "sugerir_itens");

        // (2) quote = resultado da firmar_cotacao
        assertThat(result.quote()).contains("COT1").contains("120.0");

        // (3) toda chamada HTTP carregou Authorization Bearer + X-TENANT-ID=acme
        assertThat(calls).isNotEmpty();
        assertThat(calls).allSatisfy(c -> {
            assertThat(c.authorization()).isEqualTo("Bearer svc-token");
            assertThat(c.tenant()).isEqualTo("acme");
        });

        // (4) chaveIdempotencia reusada em firmar_cotacao E enviar_pedido, igual à devolvida
        String chaveFirmar = argOf("firmar_cotacao", "chaveIdempotencia");
        String chaveEnviar = argOf("enviar_pedido", "chaveIdempotencia");
        assertThat(chaveFirmar).isNotBlank().isEqualTo(result.chaveIdempotencia()).isEqualTo(chaveEnviar);

        // (5) US honrou o holdout: sugerir_itens veio retido (grupoControle=true) e NÃO houve registrar_decisao
        McpAgentRunner.ToolCall sugestao = result.toolCalls().stream()
                .filter(c -> c.name().equals("sugerir_itens")).findFirst().orElseThrow();
        assertThat(sugestao.resultText()).contains("grupoControle").contains("true");
        assertThat(result.toolCalls()).extracting(McpAgentRunner.ToolCall::name)
                .doesNotContain("registrar_decisao");
    }

    private String argOf(String tool, String arg) {
        return calls.stream()
                .filter(c -> "tools/call".equals(c.method()) && tool.equals(c.toolName()))
                .map(c -> c.arguments().path(arg).asText(""))
                .findFirst().orElse("");
    }

    // ── LLM encenado: dirige a sequência de tools do fluxo QP ────────

    /** Resolver que devolve um ChatModel scriptado (sem provider real). */
    private static final class ScriptedResolver implements LLMConfigResolver {
        @Override
        public ResolvedLLMConfig resolve(LLMResolutionRequest request) {
            return request.platformDefault();
        }

        @Override
        public ChatModel resolveModel(LLMResolutionRequest request) {
            return new ScriptedModel();
        }
    }

    /**
     * ChatModel que, a cada turno, olha quantos resultados de tool já vieram e
     * emite a próxima tool do fluxo QP — ou finaliza. Extrai a chaveIdempotencia
     * do system prompt para reusá-la em firmar/enviar (como o LLM real faria
     * seguindo a instrução).
     */
    private static final class ScriptedModel implements ChatModel {
        private static final List<String> SEQUENCE = List.of(
                "resolver_sku", "registrar_resolucao", "simular_cotacao",
                "firmar_cotacao", "enviar_pedido", "sugerir_itens");
        private static final Pattern CHAVE = Pattern.compile("chaveIdempotencia=\"([^\"]+)\"");

        @Override
        public ChatResponse chat(ChatRequest request) {
            long done = request.messages().stream()
                    .filter(m -> m instanceof ToolExecutionResultMessage).count();
            if (done >= SEQUENCE.size()) {
                return ChatResponse.builder().aiMessage(AiMessage.from("Cotação concluída.")).build();
            }
            String tool = SEQUENCE.get((int) done);
            String chave = extractChave(request.messages());
            String args = switch (tool) {
                case "firmar_cotacao" -> "{\"sku\":\"ABC\",\"qtd\":10,\"chaveIdempotencia\":\"" + chave + "\"}";
                case "enviar_pedido" -> "{\"chaveIdempotencia\":\"" + chave + "\"}";
                case "sugerir_itens" -> "{\"clienteRef\":\"c1\"}";
                default -> "{\"sku\":\"ABC\"}";
            };
            ToolExecutionRequest ter = ToolExecutionRequest.builder()
                    .id("call-" + done).name(tool).arguments(args).build();
            return ChatResponse.builder().aiMessage(AiMessage.from(List.of(ter))).build();
        }

        private String extractChave(List<ChatMessage> messages) {
            for (ChatMessage m : messages) {
                if (m instanceof dev.langchain4j.data.message.SystemMessage sys) {
                    Matcher matcher = CHAVE.matcher(sys.text());
                    if (matcher.find()) {
                        return matcher.group(1);
                    }
                }
            }
            return "";
        }
    }
}
