package br.com.archflow.workflow.tool;

import br.com.archflow.model.Workflow;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Registry for managing workflow tools.
 *
 * <p>This registry allows workflows to be registered as tools,
 * making them invokable within other workflows or by AI agents.</p>
 *
 * <p>Features:
 * <ul>
 *   <li>Register/unregister workflow tools</li>
 *   <li>Lookup by ID or name</li>
 *   <li>Execute tools by ID</li>
 *   <li>Tool composition (chaining tools together)</li>
 *   <li>Event notifications for tool lifecycle</li>
 * </ul>
 */
public class WorkflowToolRegistry {

    private static volatile WorkflowToolRegistry instance;

    private final Map<String, WorkflowTool> toolsById;
    private final Map<String, WorkflowTool> toolsByName;
    private final List<Consumer<ToolEvent>> eventListeners;

    private WorkflowToolRegistry() {
        this.toolsById = new ConcurrentHashMap<>();
        this.toolsByName = new ConcurrentHashMap<>();
        this.eventListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    }

    /**
     * Gets the singleton instance.
     */
    public static WorkflowToolRegistry getInstance() {
        if (instance == null) {
            synchronized (WorkflowToolRegistry.class) {
                if (instance == null) {
                    instance = new WorkflowToolRegistry();
                }
            }
        }
        return instance;
    }

    /**
     * Resets the singleton (mainly for testing).
     */
    public static synchronized void reset() {
        instance = null;
    }

    /**
     * Registers a workflow tool.
     */
    public boolean register(WorkflowTool tool) {
        if (toolsById.containsKey(tool.getId())) {
            return false;
        }

        toolsById.put(tool.getId(), tool);
        toolsByName.put(tool.getName(), tool);

        publishEvent(new ToolEvent(
                ToolEventType.REGISTERED,
                tool.getId(),
                tool.getName()
        ));

        return true;
    }

    /**
     * Registers a workflow as a tool, using the given executor to run it.
     *
     * <p>This module has no workflow engine, so the caller must supply the function
     * that actually executes the workflow (typically a closure over a flow engine).
     */
    public WorkflowTool register(Workflow workflow,
                                 java.util.function.Function<Map<String, Object>, Object> executor) {
        WorkflowTool tool = WorkflowTool.from(workflow, executor);
        register(tool);
        return tool;
    }

    /**
     * Unsupported: registering a bare {@link Workflow} cannot produce an executable
     * tool because this module has no workflow engine. Failing at registration is
     * deliberate — more honest than registering a tool that only fails on execute.
     *
     * @throws UnsupportedOperationException always
     * @deprecated use {@link #register(Workflow, java.util.function.Function)} with an executor
     */
    @Deprecated
    public WorkflowTool register(Workflow workflow) {
        throw new UnsupportedOperationException(
                "register(Workflow) cannot create an executable tool: module "
                + "archflow-workflow-tool has no workflow engine to run '" + workflow.getId()
                + "'. Use register(workflow, executor) and supply an executor.");
    }

    /**
     * Unregisters a workflow tool.
     */
    public WorkflowTool unregister(String toolId) {
        WorkflowTool tool = toolsById.remove(toolId);
        if (tool != null) {
            toolsByName.remove(tool.getName());

            publishEvent(new ToolEvent(
                    ToolEventType.UNREGISTERED,
                    tool.getId(),
                    tool.getName()
            ));
        }
        return tool;
    }

    /**
     * Gets a tool by ID.
     */
    public Optional<WorkflowTool> getTool(String toolId) {
        return Optional.ofNullable(toolsById.get(toolId));
    }

    /**
     * Gets a tool by name.
     */
    public Optional<WorkflowTool> getToolByName(String name) {
        return Optional.ofNullable(toolsByName.get(name));
    }

    /**
     * Executes a tool by ID.
     */
    public WorkflowToolResult execute(String toolId, Map<String, Object> input) {
        WorkflowTool tool = toolsById.get(toolId);
        if (tool == null) {
            return WorkflowToolResult.failure("Tool not found: " + toolId,
                    java.time.Duration.ZERO, java.util.UUID.randomUUID().toString());
        }
        return tool.execute(input);
    }

    /**
     * Executes a tool by name.
     */
    public WorkflowToolResult executeByName(String name, Map<String, Object> input) {
        WorkflowTool tool = toolsByName.get(name);
        if (tool == null) {
            return WorkflowToolResult.failure("Tool not found: " + name,
                    java.time.Duration.ZERO, java.util.UUID.randomUUID().toString());
        }
        return tool.execute(input);
    }

    /**
     * Gets all registered tools.
     */
    public List<WorkflowTool> getAllTools() {
        return List.copyOf(toolsById.values());
    }

    /**
     * Gets the number of registered tools.
     */
    public int size() {
        return toolsById.size();
    }

    /**
     * Checks if a tool is registered.
     */
    public boolean hasTool(String toolId) {
        return toolsById.containsKey(toolId);
    }

    /**
     * Creates a composite tool that chains multiple tools together.
     *
     * <p>The tools are executed sequentially, in list order. The initial input map is
     * passed to the first tool; each tool's output then becomes the input of the next:
     * a {@code Map} output is passed as-is (keys converted to {@code String}), any other
     * non-null output is wrapped as {@code {"input": output}}. The composite's output is
     * the output of the last tool. If any tool fails, the composite fails immediately
     * with an error identifying the failing tool.
     *
     * @throws IllegalArgumentException if {@code tools} is empty
     */
    public WorkflowTool createComposite(String id, String name, String description, List<WorkflowTool> tools) {
        if (tools == null || tools.isEmpty()) {
            throw new IllegalArgumentException("createComposite requires at least one tool");
        }
        List<WorkflowTool> chain = List.copyOf(tools);

        Workflow compositeWorkflow = Workflow.builder()
                .id(id)
                .name(name)
                .description(description)
                .addMetadata("composite", true)
                .addMetadata("toolCount", chain.size())
                .build();

        return WorkflowTool.builder()
                .id(id)
                .name(name)
                .description(description)
                .workflow(compositeWorkflow)
                .executor(input -> {
                    Object current = input;
                    for (WorkflowTool step : chain) {
                        WorkflowToolResult result = step.execute(toInputMap(current));
                        if (!result.success()) {
                            throw new WorkflowToolResult.WorkflowToolExecutionException(
                                    "Composite tool '" + id + "' failed at step '" + step.getId()
                                    + "': " + result.error());
                        }
                        current = result.output();
                    }
                    return current;
                })
                .build();
    }

    /**
     * Creates a parallel tool that executes multiple tools in parallel and merges results.
     *
     * <p>All tools receive the same input map and run concurrently (via
     * {@link java.util.concurrent.CompletableFuture#supplyAsync(java.util.function.Supplier)}).
     * The output is a {@code Map<String, Object>} indexed by each tool's name (on name
     * collision, the key is suffixed with {@code "#<index>"}). If any tool fails, the
     * parallel tool fails with an error identifying the failing tool.
     *
     * @throws IllegalArgumentException if {@code tools} is empty
     */
    public WorkflowTool createParallel(String id, String name, String description, List<WorkflowTool> tools) {
        if (tools == null || tools.isEmpty()) {
            throw new IllegalArgumentException("createParallel requires at least one tool");
        }
        List<WorkflowTool> branches = List.copyOf(tools);

        Workflow parallelWorkflow = Workflow.builder()
                .id(id)
                .name(name)
                .description(description)
                .addMetadata("parallel", true)
                .addMetadata("toolCount", branches.size())
                .build();

        return WorkflowTool.builder()
                .id(id)
                .name(name)
                .description(description)
                .workflow(parallelWorkflow)
                .async(true)
                .executor(input -> {
                    List<java.util.concurrent.CompletableFuture<WorkflowToolResult>> futures =
                            branches.stream()
                                    .map(tool -> java.util.concurrent.CompletableFuture
                                            .supplyAsync(() -> tool.execute(input)))
                                    .collect(Collectors.toList());

                    Map<String, Object> merged = new LinkedHashMap<>();
                    for (int i = 0; i < branches.size(); i++) {
                        WorkflowTool branch = branches.get(i);
                        WorkflowToolResult result = futures.get(i).join();
                        if (!result.success()) {
                            throw new WorkflowToolResult.WorkflowToolExecutionException(
                                    "Parallel tool '" + id + "' failed at branch '" + branch.getId()
                                    + "': " + result.error());
                        }
                        String key = branch.getName();
                        if (merged.containsKey(key)) {
                            key = key + "#" + i;
                        }
                        merged.put(key, result.output());
                    }
                    return merged;
                })
                .build();
    }

    /**
     * Converts a previous tool's output into the input map of the next tool in a chain.
     */
    private static Map<String, Object> toInputMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> converted = new LinkedHashMap<>();
            map.forEach((k, v) -> converted.put(String.valueOf(k), v));
            return converted;
        }
        return Map.of("input", value);
    }

    /**
     * Adds an event listener.
     */
    public void addListener(Consumer<ToolEvent> listener) {
        eventListeners.add(listener);
    }

    /**
     * Removes an event listener.
     */
    public void removeListener(Consumer<ToolEvent> listener) {
        eventListeners.remove(listener);
    }

    /**
     * Searches tools by keyword in name or description.
     */
    public List<WorkflowTool> search(String query) {
        String lowerQuery = query.toLowerCase();
        return toolsById.values().stream()
                .filter(t -> t.getName().toLowerCase().contains(lowerQuery) ||
                           t.getDescription() != null && t.getDescription().toLowerCase().contains(lowerQuery))
                .collect(Collectors.toList());
    }

    private void publishEvent(ToolEvent event) {
        eventListeners.forEach(listener -> {
            try {
                listener.accept(event);
            } catch (Exception e) {
                // Log error but continue notifying other listeners
                System.err.println("Error notifying tool listener: " + e.getMessage());
            }
        });
    }

    /**
     * Event types for tool lifecycle.
     */
    public enum ToolEventType {
        REGISTERED,
        UNREGISTERED,
        EXECUTED,
        FAILED
    }

    /**
     * Event published when tool lifecycle changes.
     */
    public record ToolEvent(
            ToolEventType type,
            String toolId,
            String toolName,
            java.time.Instant timestamp
    ) {
        public ToolEvent(ToolEventType type, String toolId, String toolName) {
            this(type, toolId, toolName, java.time.Instant.now());
        }
    }

    /**
     * Statistics about the tool registry.
     */
    public record ToolStats(
            int totalTools,
            int asyncTools,
            int toolsWithTimeout
    ) {
        public static ToolStats from(Collection<WorkflowTool> tools) {
            return new ToolStats(
                    tools.size(),
                    (int) tools.stream().filter(WorkflowTool::isAsync).count(),
                    (int) tools.stream().filter(t -> t.getTimeout() != null).count()
            );
        }
    }

    /**
     * Gets statistics about registered tools.
     */
    public ToolStats getStats() {
        return ToolStats.from(toolsById.values());
    }
}
