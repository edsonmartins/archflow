package br.com.archflow.plugins.agents;

import br.com.archflow.model.ai.AIAgent;
import br.com.archflow.model.ai.domain.*;
import br.com.archflow.model.ai.metadata.ComponentMetadata;
import br.com.archflow.model.ai.type.ComponentType;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.plugin.api.spi.ComponentPlugin;

import java.util.*;

/**
 * Reference agent plugin for research tasks.
 *
 * <p>Decomposes research tasks into actionable steps, makes decisions
 * based on available information, and plans actions to achieve research goals.
 */
public class ResearchAgent implements AIAgent, ComponentPlugin {

    private static final String COMPONENT_ID = "research-agent";
    private static final String VERSION = "1.0.0";

    private static final Set<String> SUPPORTED_TASK_TYPES = Set.of(
            "research", "summarize", "compare", "analyze"
    );

    private Map<String, Object> config;
    private boolean initialized = false;

    @Override
    public void initialize(Map<String, Object> config) {
        validateConfig(config);
        this.config = config;
        this.initialized = true;
    }

    @Override
    public ComponentMetadata getMetadata() {
        return new ComponentMetadata(
                COMPONENT_ID,
                "Research Agent",
                "Autonomous research agent that decomposes tasks, plans actions, and makes decisions",
                ComponentType.AGENT,
                VERSION,
                Set.of("research", "analysis", "planning"),
                List.of(
                        new ComponentMetadata.OperationMetadata(
                                "executeTask", "Execute Task", "Execute a research task",
                                List.of(new ComponentMetadata.ParameterMetadata("task", "object", "Task to execute", true)),
                                List.of(new ComponentMetadata.ParameterMetadata("result", "object", "Task result", true))
                        ),
                        new ComponentMetadata.OperationMetadata(
                                "planActions", "Plan Actions", "Plan research actions for a goal",
                                List.of(new ComponentMetadata.ParameterMetadata("goal", "object", "Research goal", true)),
                                List.of(new ComponentMetadata.ParameterMetadata("actions", "array", "Planned actions", true))
                        )
                ),
                Map.of(),
                Set.of("agent", "research", "reference")
        );
    }

    @Override
    public Object execute(String operation, Object input, ExecutionContext context) throws Exception {
        if (!initialized) {
            throw new IllegalStateException("Agent not initialized. Call initialize() first.");
        }

        return switch (operation) {
            case "executeTask" -> executeTask((Task) input, context);
            case "makeDecision" -> makeDecision(context);
            case "planActions" -> planActions((Goal) input, context);
            default -> throw new IllegalArgumentException("Unsupported operation: " + operation);
        };
    }

    @Override
    public Result executeTask(Task task, ExecutionContext context) {
        if (task == null) {
            return Result.failure("Task cannot be null");
        }

        if (!SUPPORTED_TASK_TYPES.contains(task.type())) {
            return Result.failure("Unsupported task type: " + task.type()
                    + ". Supported: " + SUPPORTED_TASK_TYPES);
        }

        // Decompose into steps and simulate execution
        List<String> steps = decomposeTask(task);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("task_id", task.id());
        output.put("task_type", task.type());
        output.put("steps_executed", steps.size());
        output.put("steps", steps);
        output.put("status", "completed");

        return new Result(true, output, Map.of("agent", COMPONENT_ID), List.of("Task completed successfully"));
    }

    @Override
    public Decision makeDecision(ExecutionContext context) {
        // Analyze context to determine next action
        boolean hasData = context != null && context.getVariable("research_data") != null;

        if (hasData) {
            return new Decision(
                    "analyze",
                    "Research data is available; proceed with analysis",
                    0.85,
                    List.of("gather_more_data", "summarize")
            );
        }

        return new Decision(
                "gather_data",
                "No research data available; need to gather information first",
                0.90,
                List.of("wait", "ask_user")
        );
    }

    @Override
    public List<Action> planActions(Goal goal, ExecutionContext context) {
        if (goal == null) {
            return List.of(Action.of("error", "No goal provided"));
        }

        List<Action> actions = new ArrayList<>();

        // Step 1: Define scope
        actions.add(new Action("define_scope", "Define Research Scope",
                Map.of("goal", goal.description()), true));

        // Step 2: Gather information
        actions.add(new Action("gather_info", "Gather Information",
                Map.of("sources", List.of("internal_docs", "knowledge_base")), true));

        // Step 3: Analyze findings
        actions.add(new Action("analyze", "Analyze Findings",
                Map.of("criteria", goal.successCriteria()), false));

        // Step 4: Synthesize results
        actions.add(new Action("synthesize", "Synthesize Results",
                Map.of("format", "structured_report"), false));

        // Step 5: Validate against success criteria
        actions.add(new Action("validate", "Validate Results",
                Map.of("success_criteria", goal.successCriteria()), false));

        return actions;
    }

    @Override
    public void validateConfig(Map<String, Object> config) {
        // No required configuration for the reference implementation
    }

    @Override
    public void shutdown() {
        this.config = null;
        this.initialized = false;
    }

    private List<String> decomposeTask(Task task) {
        return switch (task.type()) {
            case "research" -> List.of(
                    "Define research scope",
                    "Identify information sources",
                    "Gather relevant data",
                    "Analyze findings",
                    "Compile results"
            );
            case "summarize" -> List.of(
                    "Read input material",
                    "Extract key points",
                    "Generate summary"
            );
            case "compare" -> List.of(
                    "Identify comparison criteria",
                    "Collect data for each item",
                    "Perform comparison",
                    "Generate comparison matrix"
            );
            case "analyze" -> List.of(
                    "Define analysis framework",
                    "Collect data points",
                    "Apply analysis methods",
                    "Draw conclusions"
            );
            default -> List.of("Process task");
        };
    }
}
