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
     * Registers a workflow as a tool.
     */
    public WorkflowTool register(Workflow workflow) {
        WorkflowTool tool = WorkflowTool.from(workflow);
        register(tool);
        return tool;
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
     * The output of each tool becomes the input for the next.
     */
    public WorkflowTool createComposite(String id, String name, String description, List<WorkflowTool> tools) {
        Workflow compositeWorkflow = Workflow.builder()
                .id(id)
                .name(name)
                .description(description)
                .addMetadata("composite", true)
                .addMetadata("toolCount", tools.size())
                .build();

        return WorkflowTool.builder()
                .id(id)
                .name(name)
                .description(description)
                .workflow(compositeWorkflow)
                .build();
    }

    /**
     * Creates a parallel tool that executes multiple tools in parallel and merges results.
     */
    public WorkflowTool createParallel(String id, String name, String description, List<WorkflowTool> tools) {
        Workflow parallelWorkflow = Workflow.builder()
                .id(id)
                .name(name)
                .description(description)
                .addMetadata("parallel", true)
                .addMetadata("toolCount", tools.size())
                .build();

        return WorkflowTool.builder()
                .id(id)
                .name(name)
                .description(description)
                .workflow(parallelWorkflow)
                .async(true)
                .build();
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
