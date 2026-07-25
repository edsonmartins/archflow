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
import java.util.Objects;

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
 *
 * <p>Toda execução exige uma {@link ToolAccessPolicy}, aplicada em dois pontos:
 * ao montar o catálogo enviado ao modelo e novamente antes de cada
 * {@code callTool}. A segunda checagem não é redundante — o modelo pode emitir
 * um nome de tool que não estava no catálogo.
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

    public Result run(String tenantId, String systemPrompt, String userMessage,
                      McpClient client, ToolAccessPolicy policy) {
        return run(tenantId, systemPrompt, userMessage, client, policy, DEFAULT_MAX_ITERATIONS);
    }

    public Result run(String tenantId, String systemPrompt, String userMessage,
                      McpClient client, ToolAccessPolicy policy, int maxIterations) {
        Objects.requireNonNull(policy, "policy é obrigatória — use ToolAccessPolicy.allowAll() "
                + "para declarar explicitamente que o agente pode usar todas as tools do server");

        ChatModel model = llmConfigResolver.resolveModel(
                LLMResolutionRequest.builder(platformDefault).tenantId(tenantId).build());

        List<ToolSpecification> tools;
        try {
            tools = allowedTools(client.listTools().get(), policy, tenantId);
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
                Map<String, Object> args = Map.of();
                String resultText;
                boolean isError = true;

                if (!policy.isAllowed(req.name())) {
                    // Segunda checagem da política: o catálogo enviado ao modelo já
                    // estava filtrado, mas um modelo pode emitir um nome que não
                    // estava lá. A tool NÃO é executada.
                    resultText = "ERRO: a tool '" + req.name()
                            + "' não está autorizada para este agente.";
                    log.warn("Tool '{}' negada pela política de acesso (tenant={})",
                            req.name(), tenantId);
                } else {
                    try {
                        args = parseArguments(req.arguments());
                        McpModel.ToolResult tr = client.callTool(
                                new McpModel.ToolArguments(req.name(), args)).get();
                        resultText = textOf(tr);
                        isError = tr.isError();
                    } catch (MalformedToolArgumentsException e) {
                        // Antes: argumentos ilegíveis viravam Map.of() e a tool era
                        // executada assim mesmo. Agora a tool não roda e o modelo
                        // recebe o erro, podendo repetir a chamada corretamente.
                        resultText = "ERRO: argumentos inválidos para a tool " + req.name()
                                + " (" + e.getMessage() + "). Repita a chamada com um objeto JSON válido.";
                        log.warn("Argumentos inválidos para a tool MCP {}: {}",
                                req.name(), e.getMessage());
                    } catch (Exception e) {
                        resultText = "ERRO ao executar a tool " + req.name() + ": " + e.getMessage();
                        log.warn("Falha na tool MCP {}: {}", req.name(), e.getMessage());
                    }
                }

                toolCalls.add(new ToolCall(req.name(), args, resultText, isError));
                messages.add(ToolExecutionResultMessage.from(req, resultText));
            }
        }

        log.warn("Loop de tool-calling atingiu maxIterations={} sem resposta final", maxIterations);
        return new Result(lastText, toolCalls);
    }

    /** Sinaliza que o modelo emitiu argumentos que não são um objeto JSON. */
    static final class MalformedToolArgumentsException extends RuntimeException {
        MalformedToolArgumentsException(String message) {
            super(message);
        }
    }

    /**
     * Argumentos ausentes/vazios são legítimos (tool sem parâmetros) e viram um
     * mapa vazio. Qualquer outro conteúdo que não desserialize como objeto JSON
     * é erro e <b>impede</b> a execução da tool — invocá-la com argumentos vazios
     * seria executar uma ação diferente da que o modelo pediu.
     */
    private Map<String, Object> parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = mapper.readValue(argumentsJson, Map.class);
            return parsed != null ? parsed : Map.of();
        } catch (Exception e) {
            throw new MalformedToolArgumentsException(
                    "esperado um objeto JSON, recebido: " + truncate(argumentsJson));
        }
    }

    private static String truncate(String value) {
        return value.length() <= 200 ? value : value.substring(0, 200) + "…";
    }

    /**
     * Catálogo efetivamente enviado ao modelo: só as tools que a política
     * autoriza. O que foi descartado vai para o log — um catálogo silenciosamente
     * reduzido se parece com um server que não expõe a tool.
     */
    private static List<ToolSpecification> allowedTools(List<McpModel.Tool> discovered,
                                                        ToolAccessPolicy policy,
                                                        String tenantId) {
        List<McpModel.Tool> allowed = new ArrayList<>();
        List<String> denied = new ArrayList<>();
        for (McpModel.Tool tool : discovered) {
            if (policy.isAllowed(tool.name())) {
                allowed.add(tool);
            } else {
                denied.add(tool.name());
            }
        }
        if (!denied.isEmpty()) {
            log.info("Política de acesso ocultou {} de {} tools do MCP server (tenant={}): {}",
                    denied.size(), discovered.size(), tenantId, denied);
        }
        if (allowed.isEmpty() && !discovered.isEmpty()) {
            log.warn("Nenhuma das {} tools do MCP server é permitida pela política (tenant={}) — "
                    + "o agente rodará sem tools", discovered.size(), tenantId);
        }
        return McpToolSpecifications.from(allowed);
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
