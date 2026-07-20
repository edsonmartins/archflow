package br.com.archflow.api.agent.mcp;

import br.com.archflow.langchain4j.mcp.McpClient;
import br.com.archflow.langchain4j.mcp.McpModel;
import br.com.archflow.langchain4j.provider.LLMConfigResolver;
import br.com.archflow.langchain4j.provider.LLMResolutionRequest;
import br.com.archflow.model.config.ResolvedLLMConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Loop de tool-calling NATIVO server-side — o motor que faltava no ArchFlow
 * (o AG-UI é single-turn e delega ao browser; o conversation é textual). Dado
 * um MCP client conectado (ex.: o VendaX Core), resolve um {@link ChatModel}
 * tenant-aware, expõe as tools do server como {@link ToolSpecification} e roda
 * o multi-turn: modelo → tool call → execução MCP → resultado de volta ao
 * modelo, até não haver mais chamadas ou atingir {@code maxIterations}.
 *
 * <p>Usa o {@link McpClient} diretamente (um server por execução, escopo por
 * tenant) — a {@code McpToolRegistry} multiplexa vários servers, camada que
 * não é necessária aqui.
 */
public class McpAgentRunner {

    private static final Logger log = LoggerFactory.getLogger(McpAgentRunner.class);
    private static final int DEFAULT_MAX_ITERATIONS = 8;

    private final LLMConfigResolver llmConfigResolver;
    private final ResolvedLLMConfig platformDefault;
    private final ObjectMapper mapper = new ObjectMapper();

    public McpAgentRunner(LLMConfigResolver llmConfigResolver, ResolvedLLMConfig platformDefault) {
        this.llmConfigResolver = llmConfigResolver;
        this.platformDefault = platformDefault;
    }

    /** Uma chamada de tool executada no loop (para o chamador extrair, ex., o quote). */
    public record ToolCall(String name, Map<String, Object> arguments, String resultText, boolean isError) {
    }

    /** Resultado do loop: texto final do assistente + as tools executadas na ordem. */
    public record Result(String finalText, List<ToolCall> toolCalls) {
        /** Última chamada (bem-sucedida) da tool de nome {@code name}, se houver. */
        public ToolCall lastSuccessfulCall(String name) {
            ToolCall found = null;
            for (ToolCall c : toolCalls) {
                if (c.name().equals(name) && !c.isError()) {
                    found = c;
                }
            }
            return found;
        }
    }

    public Result run(String tenantId, String systemPrompt, String userMessage, McpClient client) {
        return run(tenantId, systemPrompt, userMessage, client, DEFAULT_MAX_ITERATIONS);
    }

    public Result run(String tenantId, String systemPrompt, String userMessage,
                      McpClient client, int maxIterations) {
        ChatModel model = llmConfigResolver.resolveModel(
                LLMResolutionRequest.builder(platformDefault).tenantId(tenantId).build());

        List<ToolSpecification> tools;
        try {
            tools = McpToolSpecifications.from(client.listTools().get());
        } catch (Exception e) {
            throw new RuntimeException("Falha ao listar tools do MCP server: " + e.getMessage(), e);
        }

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt));
        messages.add(UserMessage.from(userMessage));
        List<ToolCall> toolCalls = new ArrayList<>();
        String lastText = "";

        for (int i = 1; i <= maxIterations; i++) {
            ChatResponse response = model.chat(ChatRequest.builder()
                    .messages(messages)
                    .toolSpecifications(tools)
                    .build());
            AiMessage ai = response.aiMessage();
            messages.add(ai);
            lastText = ai.text() != null ? ai.text() : lastText;

            if (!ai.hasToolExecutionRequests()) {
                return new Result(lastText, toolCalls);
            }

            for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
                Map<String, Object> args = parseArguments(req.arguments());
                String resultText;
                boolean isError;
                try {
                    McpModel.ToolResult tr = client.callTool(
                            new McpModel.ToolArguments(req.name(), args)).get();
                    resultText = textOf(tr);
                    isError = tr.isError();
                } catch (Exception e) {
                    resultText = "ERRO ao executar a tool " + req.name() + ": " + e.getMessage();
                    isError = true;
                    log.warn("Falha na tool MCP {}: {}", req.name(), e.getMessage());
                }
                toolCalls.add(new ToolCall(req.name(), args, resultText, isError));
                messages.add(ToolExecutionResultMessage.from(req, resultText));
            }
        }

        log.warn("Loop de tool-calling atingiu maxIterations={} sem resposta final", maxIterations);
        return new Result(lastText, toolCalls);
    }

    private Map<String, Object> parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = mapper.readValue(argumentsJson, Map.class);
            return parsed != null ? parsed : Map.of();
        } catch (Exception e) {
            log.warn("Argumentos de tool não-JSON, usando vazio: {}", argumentsJson);
            return Map.of();
        }
    }

    private String textOf(McpModel.ToolResult result) {
        // O contrato do VendaX Core devolve o payload em content[0].text (JSON
        // string). Concatena todos os textos por robustez.
        StringBuilder sb = new StringBuilder();
        for (McpModel.ToolContent c : result.content()) {
            if (c.text() != null) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(c.text());
            }
        }
        return sb.toString();
    }
}
