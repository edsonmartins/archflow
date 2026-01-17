package br.com.archflow.workflow.tool;

import br.com.archflow.model.Workflow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for workflow tool functionality.
 */
class WorkflowToolTest {

    private WorkflowToolRegistry registry;
    private Workflow testWorkflow;

    @BeforeEach
    void setUp() {
        WorkflowToolRegistry.reset();
        registry = WorkflowToolRegistry.getInstance();

        testWorkflow = Workflow.builder()
                .id("test-workflow")
                .name("Test Workflow")
                .description("A test workflow")
                .addMetadata("key1", "value1")
                .build();
    }

    @AfterEach
    void tearDown() {
        WorkflowToolRegistry.reset();
    }

    @Test
    void testCreateWorkflowTool() {
        WorkflowTool tool = WorkflowTool.builder()
                .id("test-tool")
                .name("Test Tool")
                .description("A test tool")
                .workflow(testWorkflow)
                .build();

        assertThat(tool.getId()).isEqualTo("test-tool");
        assertThat(tool.getName()).isEqualTo("Test Tool");
        assertThat(tool.getDescription()).isEqualTo("A test tool");
        assertThat(tool.getWorkflow()).isNotNull();
    }

    @Test
    void testWorkflowToolFromWorkflow() {
        WorkflowTool tool = WorkflowTool.from(testWorkflow);

        assertThat(tool.getId()).isEqualTo("test-workflow");
        assertThat(tool.getName()).isEqualTo("Test Workflow");
        assertThat(tool.getWorkflow()).isEqualTo(testWorkflow);
    }

    @Test
    void testExecuteWorkflowTool() {
        WorkflowTool tool = WorkflowTool.builder()
                .id("test-tool")
                .workflow(testWorkflow)
                .build();

        WorkflowToolResult result = tool.execute(Map.of("input", "value"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isNotNull();
    }

    @Test
    void testExecuteWorkflowToolAsync() {
        WorkflowTool tool = WorkflowTool.builder()
                .id("test-tool")
                .workflow(testWorkflow)
                .async(true)
                .build();

        java.util.concurrent.CompletableFuture<WorkflowToolResult> future =
                tool.executeAsync(Map.of("input", "value"));

        WorkflowToolResult result = future.join();
        assertThat(result.success()).isTrue();
    }

    @Test
    void testWorkflowToolWithInputSchema() {
        WorkflowTool tool = WorkflowTool.builder()
                .id("test-tool")
                .workflow(testWorkflow)
                .inputSchema(Map.of(
                        "data", "string",
                        "count", "integer"
                ))
                .build();

        assertThat(tool.getInputSchema()).hasSize(2);
        assertThat(tool.getInputSchema()).containsEntry("data", "string");
    }

    @Test
    void testWorkflowToolWithTimeout() {
        Duration timeout = Duration.ofSeconds(30);
        WorkflowTool tool = WorkflowTool.builder()
                .id("test-tool")
                .workflow(testWorkflow)
                .timeout(timeout)
                .build();

        assertThat(tool.getTimeout()).isEqualTo(timeout);
    }

    @Test
    void testWorkflowToolResultSuccess() {
        WorkflowToolResult result = WorkflowToolResult.success(
                Map.of("key", "value"),
                Duration.ofMillis(100),
                "exec-123"
        );

        assertThat(result.success()).isTrue();
        assertThat(result.getError()).isEmpty();
        assertThat(result.output()).isNotNull();
    }

    @Test
    void testWorkflowToolResultFailure() {
        WorkflowToolResult result = WorkflowToolResult.failure(
                "Something went wrong",
                Duration.ofMillis(50),
                "exec-456"
        );

        assertThat(result.success()).isFalse();
        assertThat(result.getError()).hasValue("Something went wrong");
        assertThat(result.output()).isNull();
    }

    @Test
    void testOutputOrThrow() {
        WorkflowToolResult successResult = WorkflowToolResult.success(
                "output value",
                Duration.ZERO,
                "exec-1"
        );

        assertThat(successResult.outputOrThrow()).isEqualTo("output value");

        WorkflowToolResult failureResult = WorkflowToolResult.failure(
                "error",
                Duration.ZERO,
                "exec-2"
        );

        Throwable thrown = catchThrowable(() -> failureResult.outputOrThrow());
        assertThat(thrown)
                .isInstanceOf(WorkflowToolResult.WorkflowToolExecutionException.class)
                .hasMessage("error");
    }

    @Test
    void testOutputOrElse() {
        WorkflowToolResult successResult = WorkflowToolResult.success(
                "output",
                Duration.ZERO,
                "exec-1"
        );

        assertThat(successResult.outputOrElse("default")).isEqualTo("output");

        WorkflowToolResult failureResult = WorkflowToolResult.failure(
                "error",
                Duration.ZERO,
                "exec-2"
        );

        assertThat(failureResult.outputOrElse("default")).isEqualTo("default");
    }

    @Test
    void testRegistryRegisterTool() {
        WorkflowTool tool = WorkflowTool.from(testWorkflow);

        boolean registered = registry.register(tool);

        assertThat(registered).isTrue();
        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.hasTool("test-workflow")).isTrue();
    }

    @Test
    void testRegistryGetTool() {
        WorkflowTool tool = WorkflowTool.from(testWorkflow);
        registry.register(tool);

        Optional<WorkflowTool> found = registry.getTool("test-workflow");

        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("Test Workflow");
    }

    @Test
    void testRegistryGetToolByName() {
        WorkflowTool tool = WorkflowTool.from(testWorkflow);
        registry.register(tool);

        Optional<WorkflowTool> found = registry.getToolByName("Test Workflow");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo("test-workflow");
    }

    @Test
    void testRegistryExecute() {
        WorkflowTool tool = WorkflowTool.from(testWorkflow);
        registry.register(tool);

        WorkflowToolResult result = registry.execute("test-workflow", Map.of("input", "value"));

        assertThat(result.success()).isTrue();
    }

    @Test
    void testRegistryExecuteByName() {
        WorkflowTool tool = WorkflowTool.from(testWorkflow);
        registry.register(tool);

        WorkflowToolResult result = registry.executeByName("Test Workflow", Map.of("input", "value"));

        assertThat(result.success()).isTrue();
    }

    @Test
    void testRegistryExecuteInvalidTool() {
        WorkflowToolResult result = registry.execute("invalid-tool", Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.getError()).hasValueSatisfying(e -> e.contains("Tool not found"));
    }

    @Test
    void testRegistryUnregister() {
        WorkflowTool tool = WorkflowTool.from(testWorkflow);
        registry.register(tool);

        WorkflowTool unregistered = registry.unregister("test-workflow");

        assertThat(unregistered).isNotNull();
        assertThat(registry.size()).isEqualTo(0);
    }

    @Test
    void testRegistrySearch() {
        Workflow workflow1 = Workflow.builder()
                .id("workflow-1")
                .name("Data Processor")
                .description("Processes data files")
                .build();
        Workflow workflow2 = Workflow.builder()
                .id("workflow-2")
                .name("Email Sender")
                .description("Sends email notifications")
                .build();

        registry.register(WorkflowTool.from(workflow1));
        registry.register(WorkflowTool.from(workflow2));

        List<WorkflowTool> results = registry.search("data");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getName()).isEqualTo("Data Processor");
    }

    @Test
    void testRegistryEventListeners() {
        StringBuilder eventLog = new StringBuilder();
        registry.addListener(event -> {
            eventLog.append(event.type()).append(":").append(event.toolId()).append(";");
        });

        WorkflowTool tool = WorkflowTool.from(testWorkflow);
        registry.register(tool);

        assertThat(eventLog.toString()).contains("REGISTERED:test-workflow");
    }

    @Test
    void testRegistryStats() {
        WorkflowTool asyncTool = WorkflowTool.builder()
                .id("async-tool")
                .workflow(testWorkflow)
                .async(true)
                .timeout(Duration.ofSeconds(10))
                .build();

        registry.register(WorkflowTool.from(testWorkflow));
        registry.register(asyncTool);

        WorkflowToolRegistry.ToolStats stats = registry.getStats();

        assertThat(stats.totalTools()).isEqualTo(2);
        assertThat(stats.asyncTools()).isEqualTo(1);
        assertThat(stats.toolsWithTimeout()).isEqualTo(1);
    }

    @Test
    void testRegistryRegisterWorkflow() {
        WorkflowTool tool = registry.register(testWorkflow);

        assertThat(tool).isNotNull();
        assertThat(registry.hasTool("test-workflow")).isTrue();
    }

    @Test
    void testRegistryGetAllTools() {
        registry.register(testWorkflow);

        Workflow workflow2 = Workflow.builder()
                .id("workflow-2")
                .name("Workflow 2")
                .build();
        registry.register(workflow2);

        List<WorkflowTool> allTools = registry.getAllTools();

        assertThat(allTools).hasSize(2);
    }

    @Test
    void testWorkflowToolToBuilder() {
        WorkflowTool original = WorkflowTool.builder()
                .id("test-tool")
                .name("Test Tool")
                .description("A description")
                .workflow(testWorkflow)
                .async(true)
                .maxRetries(3)
                .build();

        WorkflowTool copy = original.toBuilder()
                .id("copied-tool")
                .build();

        assertThat(copy.getId()).isEqualTo("copied-tool");
        assertThat(copy.getName()).isEqualTo(original.getName());
        assertThat(copy.isAsync()).isEqualTo(original.isAsync());
        assertThat(copy.getMaxRetries()).isEqualTo(original.getMaxRetries());
    }

    @Test
    void testRegistryCreateComposite() {
        WorkflowTool tool1 = WorkflowTool.from(testWorkflow);
        WorkflowTool tool2 = WorkflowTool.from(
                Workflow.builder().id("wf2").name("WF2").build()
        );

        WorkflowTool composite = registry.createComposite(
                "composite-tool",
                "Composite Tool",
                "Chains multiple tools",
                List.of(tool1, tool2)
        );

        assertThat(composite.getId()).isEqualTo("composite-tool");
        assertThat(composite.getWorkflow().getMetadata()).containsEntry("composite", true);
        assertThat(composite.getWorkflow().getMetadata()).containsEntry("toolCount", 2);
    }

    @Test
    void testRegistryCreateParallel() {
        WorkflowTool tool1 = WorkflowTool.from(testWorkflow);
        WorkflowTool tool2 = WorkflowTool.from(
                Workflow.builder().id("wf2").name("WF2").build()
        );

        WorkflowTool parallel = registry.createParallel(
                "parallel-tool",
                "Parallel Tool",
                "Executes tools in parallel",
                List.of(tool1, tool2)
        );

        assertThat(parallel.getId()).isEqualTo("parallel-tool");
        assertThat(parallel.isAsync()).isTrue();
        assertThat(parallel.getWorkflow().getMetadata()).containsEntry("parallel", true);
    }
}
