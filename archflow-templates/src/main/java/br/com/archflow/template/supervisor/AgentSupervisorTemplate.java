package br.com.archflow.template.supervisor;

import br.com.archflow.model.Workflow;
import br.com.archflow.template.AbstractWorkflowTemplate;
import br.com.archflow.template.WorkflowTemplateDefinition;

import java.util.*;

/**
 * Template for agent supervisor workflows with multi-agent orchestration.
 *
 * <p>This template provides a supervisor pattern for coordinating multiple AI agents:
 * <ul>
 *   <li><b>Agent Selection:</b> Choose the right agent for each task</li>
 *   <li><b>Task Decomposition:</b> Break complex tasks into sub-tasks</li>
 *   <li><b>Result Aggregation:</b> Combine results from multiple agents</li>
 *   <li><b>Quality Check:</b> Validate and refine outputs</li>
 *   <li><b>Iteration:</b> Retry or refine if quality is insufficient</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * AgentSupervisorTemplate template = new AgentSupervisorTemplate();
 *
 * Map<String, Object> params = Map.of(
 *     "agents", List.of("researcher", "writer", "reviewer"),
 *     "maxIterations", 3,
 *     "qualityThreshold", 0.8
 * );
 *
 * Workflow workflow = template.createInstance("supervisor-bot", params);
 * }</pre>
 */
public class AgentSupervisorTemplate extends AbstractWorkflowTemplate {

    private static final String ID = "agent-supervisor";
    private static final String VERSION = "1.0.0";

    public AgentSupervisorTemplate() {
        super(
                ID,
                "Agent Supervisor with Orchestration",
                "Multi-agent supervisor pattern for task decomposition and agent coordination",
                "automation",
                Set.of("multi-agent", "supervisor", "orchestration", "coordination"),
                buildParameters()
        );
    }

    private static Map<String, ParameterDefinition> buildParameters() {
        Map<String, ParameterDefinition> params = new LinkedHashMap<>();

        params.put("agents", ParameterDefinition.required(
                "agents",
                "List of agent IDs to coordinate",
                List.class
        ));

        params.put("supervisorModel", ParameterDefinition.optional(
                "supervisorModel",
                "Model to use for supervision decisions",
                String.class,
                "gpt-4o"
        ));

        params.put("maxIterations", ParameterDefinition.optional(
                "maxIterations",
                "Maximum number of refinement iterations",
                Integer.class,
                3
        ));

        params.put("qualityThreshold", ParameterDefinition.optional(
                "qualityThreshold",
                "Quality threshold for accepting outputs (0.0-1.0)",
                Double.class,
                0.8
        ));

        params.put("enableDecomposition", ParameterDefinition.optional(
                "enableDecomposition",
                "Enable automatic task decomposition",
                Boolean.class,
                true
        ));

        params.put("maxSubTasks", ParameterDefinition.optional(
                "maxSubTasks",
                "Maximum number of sub-tasks to create",
                Integer.class,
                5
        ));

        params.put("parallelExecution", ParameterDefinition.optional(
                "parallelExecution",
                "Execute agents in parallel when possible",
                Boolean.class,
                true
        ));

        params.put("aggregationMethod", ParameterDefinition.enumParameter(
                "aggregationMethod",
                "Method for aggregating agent results",
                "concatenate", "synthesis", "voting"
        ));

        params.put("enableLogging", ParameterDefinition.optional(
                "enableLogging",
                "Enable detailed execution logging",
                Boolean.class,
                true
        ));

        return params;
    }

    @Override
    public WorkflowTemplateDefinition getDefinition() {
        Map<String, WorkflowTemplateDefinition.TemplateNode> nodes = new LinkedHashMap<>();

        // Task input node
        nodes.put("input", new WorkflowTemplateDefinition.TemplateNode(
                "input",
                "task-input",
                "Task Input",
                Map.of(
                        "schema", Map.of(
                                "task", "string",
                                "context", "string",
                                "requirements", "string"
                        )
                )
        ));

        // Supervisor node
        nodes.put("supervisor", new WorkflowTemplateDefinition.TemplateNode(
                "supervisor",
                "supervisor",
                "Agent Supervisor",
                Map.of(
                        "model", "{{supervisorModel}}",
                        "agents", "{{agents}}",
                        "mode", "coordinate"
                )
        ));

        // Task decomposer node
        nodes.put("decomposer", new WorkflowTemplateDefinition.TemplateNode(
                "decomposer",
                "task-decomposer",
                "Task Decomposer",
                Map.of(
                        "model", "{{supervisorModel}}",
                        "maxSubTasks", "{{maxSubTasks}}"
                )
        ));

        // Agent dispatcher node
        nodes.put("dispatcher", new WorkflowTemplateDefinition.TemplateNode(
                "dispatcher",
                "agent-dispatcher",
                "Agent Dispatcher",
                Map.of(
                        "parallel", "{{parallelExecution}}",
                        "timeout", 300
                )
        ));

        // Result aggregator node
        nodes.put("aggregator", new WorkflowTemplateDefinition.TemplateNode(
                "aggregator",
                "result-aggregator",
                "Result Aggregator",
                Map.of(
                        "method", "{{aggregationMethod}}"
                )
        ));

        // Quality checker node
        nodes.put("quality-checker", new WorkflowTemplateDefinition.TemplateNode(
                "quality-checker",
                "quality-checker",
                "Quality Checker",
                Map.of(
                        "model", "{{supervisorModel}}",
                        "threshold", "{{qualityThreshold}}"
                )
        ));

        // Refiner node
        nodes.put("refiner", new WorkflowTemplateDefinition.TemplateNode(
                "refiner",
                "llm",
                "Output Refiner",
                Map.of(
                        "promptTemplate", """
                                Review and refine the following agent output:

                                Original Task: {{task}}
                                Agent Output: {{output}}
                                Quality Issues: {{quality_issues}}

                                Provide an improved version that addresses the issues.
                                """,
                        "model", "{{supervisorModel}}"
                )
        ));

        // Logger node
        nodes.put("logger", new WorkflowTemplateDefinition.TemplateNode(
                "logger",
                "execution-logger",
                "Execution Logger",
                Map.of(
                        "enabled", "{{enableLogging}}"
                )
        ));

        // Output node
        nodes.put("output", new WorkflowTemplateDefinition.TemplateNode(
                "output",
                "output",
                "Final Output",
                Map.of(
                        "includeMetadata", true
                )
        ));

        // Connections
        Map<String, WorkflowTemplateDefinition.TemplateConnection> connections = new LinkedHashMap<>();

        connections.put("c1", new WorkflowTemplateDefinition.TemplateConnection(
                "c1", "input", "supervisor", null, null
        ));

        connections.put("c2", new WorkflowTemplateDefinition.TemplateConnection(
                "c2", "supervisor", "decomposer",
                "{{enableDecomposition}} == true", null
        ));

        connections.put("c3", new WorkflowTemplateDefinition.TemplateConnection(
                "c3", "supervisor", "dispatcher",
                "{{enableDecomposition}} == false", null
        ));

        connections.put("c4", new WorkflowTemplateDefinition.TemplateConnection(
                "c4", "decomposer", "dispatcher", null, null
        ));

        connections.put("c5", new WorkflowTemplateDefinition.TemplateConnection(
                "c5", "dispatcher", "aggregator", null, null
        ));

        connections.put("c6", new WorkflowTemplateDefinition.TemplateConnection(
                "c6", "aggregator", "quality-checker", null, null
        ));

        connections.put("c7", new WorkflowTemplateDefinition.TemplateConnection(
                "c7", "quality-checker", "logger",
                "{{quality}} >= {{qualityThreshold}}", null
        ));

        connections.put("c8", new WorkflowTemplateDefinition.TemplateConnection(
                "c8", "quality-checker", "refiner",
                "{{quality}} < {{qualityThreshold}} && {{iteration}} < {{maxIterations}}", null
        ));

        connections.put("c9", new WorkflowTemplateDefinition.TemplateConnection(
                "c9", "refiner", "quality-checker", null, null
        ));

        connections.put("c10", new WorkflowTemplateDefinition.TemplateConnection(
                "c10", "quality-checker", "logger",
                "{{quality}} < {{qualityThreshold}} && {{iteration}} >= {{maxIterations}}", null
        ));

        connections.put("c11", new WorkflowTemplateDefinition.TemplateConnection(
                "c11", "logger", "output", null, null
        ));

        WorkflowTemplateDefinition.TemplateStructure structure =
                new WorkflowTemplateDefinition.TemplateStructure("input", nodes, connections);

        return new WorkflowTemplateDefinition(
                ID,
                VERSION,
                getDisplayName(),
                getDescription(),
                getCategory(),
                getTags(),
                Map.of(
                        "agents", List.of("researcher", "writer", "reviewer"),
                        "supervisorModel", "gpt-4o",
                        "maxIterations", 3,
                        "qualityThreshold", 0.8,
                        "enableDecomposition", true,
                        "maxSubTasks", 5,
                        "parallelExecution", true,
                        "aggregationMethod", "synthesis",
                        "enableLogging", true
                ),
                structure
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    protected Workflow buildWorkflow(String name, Map<String, Object> parameters) {
        List<String> agents = (List<String>) parameters.getOrDefault("agents",
                List.of("researcher", "writer", "reviewer"));
        String supervisorModel = getString(parameters, "supervisorModel", "gpt-4o");
        int maxIterations = getInt(parameters, "maxIterations", 3);
        double qualityThreshold = ((Number) parameters.getOrDefault("qualityThreshold", 0.8)).doubleValue();
        boolean enableDecomposition = getBoolean(parameters, "enableDecomposition", true);
        int maxSubTasks = getInt(parameters, "maxSubTasks", 5);
        boolean parallelExecution = getBoolean(parameters, "parallelExecution", true);
        String aggregationMethod = getString(parameters, "aggregationMethod", "synthesis");
        boolean enableLogging = getBoolean(parameters, "enableLogging", true);

        Map<String, Object> config = new HashMap<>();
        config.put("name", name);
        config.put("template", ID);
        config.put("version", VERSION);
        config.put("parameters", Map.of(
                "agents", agents,
                "supervisorModel", supervisorModel,
                "maxIterations", maxIterations,
                "qualityThreshold", qualityThreshold,
                "enableDecomposition", enableDecomposition,
                "maxSubTasks", maxSubTasks,
                "parallelExecution", parallelExecution,
                "aggregationMethod", aggregationMethod,
                "enableLogging", enableLogging
        ));

        return createWorkflowFromConfig(name, config);
    }

    private Workflow createWorkflowFromConfig(String name, Map<String, Object> config) {
        return new Workflow() {
            private final String workflowName = name;

            @Override
            public String getId() {
                return workflowName;
            }

            @Override
            public String getName() {
                return workflowName;
            }

            @Override
            public String getDescription() {
                return "Agent supervisor workflow created from template";
            }

            @Override
            public Map<String, Object> getMetadata() {
                return Map.of(
                        "template", ID,
                        "templateVersion", VERSION,
                        "createdAt", java.time.Instant.now()
                );
            }
        };
    }
}
