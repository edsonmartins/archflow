package br.com.archflow.workflow.tool.langchain4j;

import br.com.archflow.workflow.tool.WorkflowTool;
import br.com.archflow.workflow.tool.WorkflowToolResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;

/**
 * Adapts a {@link WorkflowTool} to be usable by LangChain4j agents.
 *
 * <p>This is the main bridge between the archflow workflow tool system and LangChain4j's
 * tool execution model. It converts a WorkflowTool into a format that LangChain4j agents
 * can discover, describe, and invoke.</p>
 *
 * <p>Since archflow-workflow-tool does not depend on LangChain4j directly, this adapter
 * uses generic types ({@link ToolSpec} record and Maps) instead of LangChain4j-specific
 * classes. A downstream LangChain4j module can convert these to native LangChain4j types.</p>
 *
 * <p>Example usage:
 * <pre>{@code
 * WorkflowTool tool = WorkflowTool.builder()
 *     .id("summarizer")
 *     .name("text-summarizer")
 *     .description("Summarizes text")
 *     .workflow(myWorkflow)
 *     .executor(input -> summarize(input))
 *     .inputSchema(Map.of("text", "string", "maxLength", "integer"))
 *     .build();
 *
 * LangChain4jToolAdapter adapter = new LangChain4jToolAdapter(tool);
 * ToolSpec spec = adapter.getToolSpec();
 * String result = adapter.execute("{\"text\": \"long text...\", \"maxLength\": 100}");
 * }</pre>
 *
 * @see WorkflowTool
 * @see WorkflowToolSpecificationFactory
 * @see LangChain4jToolExecutor
 */
public class LangChain4jToolAdapter {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jToolAdapter.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final WorkflowTool workflowTool;
    private final ToolSpec toolSpec;

    /**
     * Creates a new adapter for the given workflow tool.
     *
     * @param workflowTool the workflow tool to adapt
     * @throws NullPointerException if workflowTool is null
     */
    public LangChain4jToolAdapter(WorkflowTool workflowTool) {
        this.workflowTool = Objects.requireNonNull(workflowTool, "workflowTool must not be null");
        this.toolSpec = buildToolSpec();
    }

    /**
     * Gets the tool specification describing this tool's name, description, and input schema.
     *
     * @return the tool specification
     */
    public ToolSpec getToolSpec() {
        return toolSpec;
    }

    /**
     * Gets the name of the adapted tool.
     *
     * @return the tool name
     */
    public String getToolName() {
        return workflowTool.getName();
    }

    /**
     * Gets the description of the adapted tool.
     *
     * @return the tool description, or null if not set
     */
    public String getToolDescription() {
        return workflowTool.getDescription();
    }

    /**
     * Gets the underlying workflow tool.
     *
     * @return the workflow tool
     */
    public WorkflowTool getWorkflowTool() {
        return workflowTool;
    }

    /**
     * Executes the tool with the given JSON arguments string.
     *
     * <p>The JSON string is parsed into a Map, passed to the underlying WorkflowTool,
     * and the result is serialized back to a JSON string.</p>
     *
     * @param jsonArguments JSON string containing the tool arguments, or null for empty input
     * @return JSON string containing the execution result
     */
    public String execute(String jsonArguments) {
        try {
            Map<String, Object> input = parseArguments(jsonArguments);

            log.debug("Executing adapted workflow tool '{}' with {} arguments",
                    workflowTool.getName(), input.size());

            WorkflowToolResult result = workflowTool.execute(input);

            return serializeResult(result);

        } catch (JsonProcessingException e) {
            log.error("Failed to parse arguments for tool '{}': {}", workflowTool.getName(), e.getMessage());
            return serializeError("Invalid JSON arguments: " + e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error executing tool '{}': {}", workflowTool.getName(), e.getMessage(), e);
            return serializeError("Execution error: " + e.getMessage());
        }
    }

    private ToolSpec buildToolSpec() {
        Map<String, Object> jsonSchema = WorkflowToolSpecificationFactory.createJsonSchema(workflowTool);
        return new ToolSpec(
                workflowTool.getName(),
                workflowTool.getDescription(),
                jsonSchema
        );
    }

    private Map<String, Object> parseArguments(String jsonArguments) throws JsonProcessingException {
        if (jsonArguments == null || jsonArguments.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(jsonArguments, new TypeReference<>() {});
    }

    private String serializeResult(WorkflowToolResult result) {
        try {
            Map<String, Object> resultMap;
            if (result.success()) {
                resultMap = Map.of(
                        "success", true,
                        "output", result.output() != null ? result.output() : "null",
                        "duration_ms", result.duration().toMillis()
                );
            } else {
                resultMap = Map.of(
                        "success", false,
                        "error", result.error() != null ? result.error() : "Unknown error",
                        "duration_ms", result.duration().toMillis()
                );
            }
            return objectMapper.writeValueAsString(resultMap);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize result for tool '{}': {}", workflowTool.getName(), e.getMessage());
            return serializeError("Failed to serialize result: " + e.getMessage());
        }
    }

    private String serializeError(String message) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "success", false,
                    "error", message
            ));
        } catch (JsonProcessingException e) {
            // Last resort fallback
            return "{\"success\":false,\"error\":\"" + message.replace("\"", "\\\"") + "\"}";
        }
    }

    /**
     * Describes a tool's specification in a LangChain4j-compatible format.
     *
     * <p>This record contains the tool name, description, and a JSON Schema representation
     * of the input parameters. Downstream LangChain4j integration modules can convert this
     * to a native {@code ToolSpecification}.</p>
     *
     * @param name the tool name
     * @param description the tool description
     * @param inputJsonSchema the JSON Schema describing the tool's input parameters
     */
    public record ToolSpec(
            String name,
            String description,
            Map<String, Object> inputJsonSchema
    ) {}
}
