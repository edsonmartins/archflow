package br.com.archflow.api.mcp.server;

import br.com.archflow.api.agent.mcp.McpAgentHost;
import br.com.archflow.api.flow.RunInputSeeding;
import br.com.archflow.api.flow.WorkflowDeserializer;
import br.com.archflow.api.web.workflow.WorkflowRuntimeStore;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.langchain4j.mcp.JsonRpc;
import br.com.archflow.langchain4j.mcp.McpModel;
import br.com.archflow.langchain4j.mcp.server.AbstractMcpServer;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.enums.ExecutionStatus;
import br.com.archflow.model.error.ExecutionError;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowResult;
import br.com.archflow.model.tenant.DefaultTenant;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Exposes every stored workflow as an MCP tool ({@code workflow_<id>}),
 * so any MCP client (Claude, IDEs, other agents) can list and run
 * archflow workflows. The consumption counterpart of the MCP-client
 * support the platform already has.
 *
 * <p>Tool calls run through the same engine wiring as
 * {@code POST /api/workflows/{id}/execute} — an execution record is
 * created and completed, so runs triggered over MCP show up in the
 * executions history like any other run.
 */
public class WorkflowMcpServer extends AbstractMcpServer {

    static final String TOOL_PREFIX = "workflow_";

    /**
     * Tenant da requisição MCP em curso. O {@code tools/call} roda numa thread virtual (ver
     * {@link SpringMcpServerController}), onde não há {@code RequestAttributes}: ler o
     * {@code TenantContext} ali devolveria sempre o tenant padrão e esconderia o defeito. Quem tem a
     * requisição captura o tenant na thread do servlet e o vincula aqui por
     * {@link #handleRequest(JsonRpc.Request, String)}; um {@link ScopedValue} (e não um ThreadLocal)
     * porque o vínculo some sozinho ao fim da chamada, sem vazar para a próxima tarefa da thread.
     */
    private static final ScopedValue<String> TENANT_DA_CHAMADA = ScopedValue.newInstance();

    private final WorkflowRuntimeStore store;
    private final WorkflowDeserializer deserializer;
    private final FlowEngine flowEngine;
    private final FlowRepository flowRepository;
    private final ObjectMapper json;
    /** Host de MCP dos nós {@code mcp-agent}; nulo = instalação sem MCP (o nó falha dizendo isso). */
    private final McpAgentHost mcpAgentHost;

    public WorkflowMcpServer(WorkflowRuntimeStore store,
                             WorkflowDeserializer deserializer,
                             FlowEngine flowEngine,
                             FlowRepository flowRepository,
                             ObjectMapper json) {
        this(store, deserializer, flowEngine, flowRepository, json, null);
    }

    /**
     * @param mcpAgentHost o mesmo host que o {@code POST /execute} injeta: sem ele, um fluxo com nó
     *                     {@code mcp-agent} chamado como tool MCP falhava com "nenhum McpAgentHost no
     *                     contexto" (RCHFLW-48), embora rodasse pela lista
     */
    public WorkflowMcpServer(WorkflowRuntimeStore store,
                             WorkflowDeserializer deserializer,
                             FlowEngine flowEngine,
                             FlowRepository flowRepository,
                             ObjectMapper json,
                             McpAgentHost mcpAgentHost) {
        super("archflow", "1.0.0", McpModel.ServerCapabilities.toolsOnly());
        this.mcpAgentHost = mcpAgentHost;
        this.store = Objects.requireNonNull(store, "store");
        this.deserializer = Objects.requireNonNull(deserializer, "deserializer");
        this.flowEngine = Objects.requireNonNull(flowEngine, "flowEngine");
        this.flowRepository = Objects.requireNonNull(flowRepository, "flowRepository");
        this.json = Objects.requireNonNull(json, "json");
    }

    /**
     * Despacha a requisição com o tenant de quem a fez vinculado — é o tenant com que as execuções
     * disparadas por {@code tools/call} são criadas (estado, aprovações, chaves).
     *
     * @param tenantId tenant resolvido na thread da requisição; nulo ou em branco = tenant padrão
     */
    public JsonRpc.Response handleRequest(JsonRpc.Request request, String tenantId) {
        return ScopedValue.where(TENANT_DA_CHAMADA, DefaultTenant.orDefault(tenantId))
                .call(() -> handleRequest(request));
    }

    /**
     * Sem vínculo (outro transporte, chamada direta) = tenant padrão, nunca nulo: nulo virava
     * "SYSTEM" no motor e a aprovação do fluxo não aparecia em fila nenhuma (RCHFLW-13).
     */
    private static String tenantDaChamada() {
        return TENANT_DA_CHAMADA.orElse(DefaultTenant.ID);
    }

    @Override
    public List<McpModel.Tool> listTools() {
        List<McpModel.Tool> tools = new ArrayList<>();
        for (Map<String, Object> workflow : store.workflows(tenantDaChamada())) {
            String id = String.valueOf(workflow.get("id"));
            tools.add(new McpModel.Tool(
                    TOOL_PREFIX + id,
                    toolDescription(workflow),
                    Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "input", Map.of(
                                            "type", "string",
                                            "description", "Input passed to the workflow's entry step")),
                            "required", List.of())));
        }
        return tools;
    }

    @Override
    public CompletableFuture<McpModel.ToolResult> callTool(McpModel.ToolArguments arguments) {
        String name = arguments.name();
        if (name == null || !name.startsWith(TOOL_PREFIX)) {
            return CompletableFuture.completedFuture(
                    McpModel.ToolResult.error("Unknown tool: " + name));
        }
        String workflowId = name.substring(TOOL_PREFIX.length());
        String tenantId = tenantDaChamada();
        // Workflow de outro tenant = desconhecido, como no REST (RCHFLW-73): o tools/list também só
        // anuncia os do tenant, então um nome adivinhado não passa a ser executável.
        Map<String, Object> workflow = store.getWorkflow(tenantId, workflowId);
        if (workflow == null) {
            return CompletableFuture.completedFuture(
                    McpModel.ToolResult.error("Unknown workflow: " + workflowId));
        }

        var execution = store.createExecution(tenantId, workflowId, workflowName(workflow));
        String executionId = String.valueOf(execution.get("id"));

        // Any failure before the engine takes over must still complete the
        // execution record, or every failed call leaves a ghost RUNNING row.
        CompletableFuture<FlowResult> run;
        try {
            Map<String, Object> flowJson = new HashMap<>(workflow);
            flowJson.put("id", executionId);
            Flow flow = deserializer.toFlow(flowJson);
            flowRepository.save(flow);

            ExecutionContext ctx = new DefaultExecutionContext(
                    tenantId, "mcp", executionId,
                    MessageWindowChatMemory.builder().maxMessages(20).build());
            // Mesma regra do REST e do AG-UI: sem chave "input", os argumentos inteiros viram o input.
            RunInputSeeding.seedVariables(ctx, arguments.arguments());
            // Depois dos argumentos, como no REST: um argumento com a chave do host não pode apontar
            // o nó para outro servidor MCP.
            McpAgentHost.inject(ctx, mcpAgentHost);
            run = flowEngine.execute(flow, ctx);
        } catch (RuntimeException e) {
            store.completeExecution(executionId, "FAILED", rootMessage(e));
            return CompletableFuture.completedFuture(McpModel.ToolResult.error(
                    "Workflow failed to start (" + executionId + "): " + rootMessage(e)));
        }

        // MCP tool calls are request/response: wait for the run to finish and
        // return its output. The execution record is completed either way so
        // the run appears in the executions history.
        return run.handle((result, err) -> {
            if (err != null) {
                store.completeExecution(executionId, "FAILED", rootMessage(err));
                return McpModel.ToolResult.error(
                        "Workflow failed (" + executionId + "): " + rootMessage(err));
            }
            String status = result != null && result.getStatus() != null
                    ? result.getStatus().name() : "COMPLETED";
            // Um passo que falha sem aresta de erro não lança: o fluxo volta como FlowResult FAILED.
            // Devolver isso como resultado normal (isError=false, status só dentro do texto) fazia o
            // cliente MCP ler sucesso, e o registro da execução ficava sem o motivo (RCHFLW-34).
            // CANCELLED também não é uma resposta que o chamador possa usar como saída.
            if (result != null && (result.getStatus() == ExecutionStatus.FAILED
                    || result.getStatus() == ExecutionStatus.CANCELLED)) {
                String error = errorsOf(result);
                store.completeExecution(executionId, status, error);
                String verb = result.getStatus() == ExecutionStatus.FAILED ? "failed" : "was cancelled";
                return McpModel.ToolResult.error("Workflow " + verb + " (" + executionId + ")"
                        + (error != null ? ": " + error : ""));
            }
            store.completeExecution(executionId, status, null);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("executionId", executionId);
            payload.put("status", status);
            payload.put("output", result != null ? result.getOutput().orElse(null) : null);
            return McpModel.ToolResult.text(toJson(payload));
        });
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private static String toolDescription(Map<String, Object> workflow) {
        String name = workflowName(workflow);
        String description = null;
        if (workflow.get("metadata") instanceof Map<?, ?> meta && meta.get("description") != null) {
            description = meta.get("description").toString();
        }
        String text = description == null || description.isBlank()
                ? "Runs the archflow workflow \"" + name + "\""
                : "Runs the archflow workflow \"" + name + "\": " + description;
        return text;
    }

    private static String workflowName(Map<String, Object> workflow) {
        if (workflow.get("metadata") instanceof Map<?, ?> meta && meta.get("name") != null) {
            return meta.get("name").toString();
        }
        return String.valueOf(workflow.get("id"));
    }

    /**
     * Mensagens dos erros que o motor pôs no resultado (os passos que falharam). Sem nenhuma, nulo:
     * inventar um texto apagaria a diferença entre "falhou sem dizer" e "falhou dizendo isto" — a
     * mesma regra de {@code SimpleStepResult.failed}.
     */
    private static String errorsOf(FlowResult result) {
        List<ExecutionError> errors = result.getErrors();
        if (errors == null || errors.isEmpty()) {
            return null;
        }
        String joined = errors.stream()
                .map(ExecutionError::message)
                .filter(m -> m != null && !m.isBlank())
                .collect(Collectors.joining("; "));
        return joined.isEmpty() ? null : joined;
    }

    private static String rootMessage(Throwable err) {
        Throwable cause = err;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.toString();
    }
}
