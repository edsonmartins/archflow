package br.com.archflow.workflow.tool.langchain4j;

import br.com.archflow.workflow.tool.WorkflowTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages multiple {@link WorkflowTool} adapters and provides centralized tool execution.
 *
 * <p>This class acts as a coordinator between the archflow workflow tool system and
 * LangChain4j's tool execution model. It maintains a registry of adapted tools,
 * provides their specifications, and routes execution requests.</p>
 *
 * <p>Example usage:
 * <pre>{@code
 * LangChain4jToolExecutor executor = new LangChain4jToolExecutor();
 *
 * executor.registerWorkflowTool(summarizerTool);
 * executor.registerWorkflowTool(translatorTool);
 *
 * // Get all tool specs for LangChain4j agent configuration
 * List<LangChain4jToolAdapter.ToolSpec> specs = executor.getToolSpecifications();
 *
 * // Execute a tool by name (as LangChain4j would)
 * String result = executor.execute("text-summarizer", "{\"text\": \"...\"}");
 * }</pre>
 *
 * @see LangChain4jToolAdapter
 * @see WorkflowTool
 */
public class LangChain4jToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jToolExecutor.class);

    private final Map<String, LangChain4jToolAdapter> adaptersByName;
    private final Map<String, LangChain4jToolAdapter> adaptersById;

    /**
     * Creates a new empty executor.
     */
    public LangChain4jToolExecutor() {
        this.adaptersByName = new ConcurrentHashMap<>();
        this.adaptersById = new ConcurrentHashMap<>();
    }

    /**
     * Registers a workflow tool, making it available for execution.
     *
     * @param tool the workflow tool to register
     * @throws NullPointerException if tool is null
     * @throws IllegalArgumentException if a tool with the same name is already registered
     */
    public void registerWorkflowTool(WorkflowTool tool) {
        Objects.requireNonNull(tool, "tool must not be null");

        LangChain4jToolAdapter adapter = new LangChain4jToolAdapter(tool);
        String toolName = tool.getName();
        String toolId = tool.getId();

        if (adaptersByName.containsKey(toolName)) {
            throw new IllegalArgumentException("Tool with name '" + toolName + "' is already registered");
        }

        adaptersByName.put(toolName, adapter);
        adaptersById.put(toolId, adapter);

        log.info("Registered workflow tool '{}' (id: {}) for LangChain4j execution", toolName, toolId);
    }

    /**
     * Gets the tool specifications for all registered tools.
     *
     * <p>These specifications can be used to configure a LangChain4j agent with
     * the available tools.</p>
     *
     * @return an unmodifiable list of tool specifications
     */
    public List<LangChain4jToolAdapter.ToolSpec> getToolSpecifications() {
        List<LangChain4jToolAdapter.ToolSpec> specs = new ArrayList<>();
        for (LangChain4jToolAdapter adapter : adaptersByName.values()) {
            specs.add(adapter.getToolSpec());
        }
        return Collections.unmodifiableList(specs);
    }

    /**
     * Executes a tool by name with the given JSON arguments.
     *
     * @param toolName the name of the tool to execute
     * @param jsonArguments JSON string containing the tool arguments
     * @return JSON string containing the execution result
     * @throws IllegalArgumentException if no tool with the given name is registered
     */
    public String execute(String toolName, String jsonArguments) {
        LangChain4jToolAdapter adapter = adaptersByName.get(toolName);
        if (adapter == null) {
            throw new IllegalArgumentException("No tool registered with name: " + toolName);
        }

        log.debug("Executing tool '{}' via LangChain4j executor", toolName);
        return adapter.execute(jsonArguments);
    }

    /**
     * Unregisters a tool by its ID.
     *
     * @param toolId the ID of the tool to unregister
     * @return true if the tool was found and unregistered, false otherwise
     */
    public boolean unregister(String toolId) {
        LangChain4jToolAdapter adapter = adaptersById.remove(toolId);
        if (adapter == null) {
            return false;
        }

        adaptersByName.remove(adapter.getToolName());

        log.info("Unregistered workflow tool '{}' (id: {}) from LangChain4j executor",
                adapter.getToolName(), toolId);
        return true;
    }

    /**
     * Gets an adapter by tool name.
     *
     * @param toolName the tool name
     * @return an Optional containing the adapter if found
     */
    public Optional<LangChain4jToolAdapter> getAdapter(String toolName) {
        return Optional.ofNullable(adaptersByName.get(toolName));
    }

    /**
     * Gets the number of registered tools.
     *
     * @return the number of registered tools
     */
    public int size() {
        return adaptersByName.size();
    }

    /**
     * Checks if a tool with the given name is registered.
     *
     * @param toolName the tool name
     * @return true if the tool is registered
     */
    public boolean hasToolByName(String toolName) {
        return adaptersByName.containsKey(toolName);
    }

    /**
     * Checks if a tool with the given ID is registered.
     *
     * @param toolId the tool ID
     * @return true if the tool is registered
     */
    public boolean hasToolById(String toolId) {
        return adaptersById.containsKey(toolId);
    }
}
