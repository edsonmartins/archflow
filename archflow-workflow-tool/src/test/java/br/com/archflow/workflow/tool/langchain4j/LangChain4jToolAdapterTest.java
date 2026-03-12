package br.com.archflow.workflow.tool.langchain4j;

import br.com.archflow.model.Workflow;
import br.com.archflow.workflow.tool.WorkflowTool;
import br.com.archflow.workflow.tool.WorkflowToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link LangChain4jToolAdapter}.
 */
class LangChain4jToolAdapterTest {

    private Workflow testWorkflow;
    private WorkflowTool workflowTool;
    private LangChain4jToolAdapter adapter;

    @BeforeEach
    void setUp() {
        testWorkflow = Workflow.builder()
                .id("test-workflow")
                .name("Test Workflow")
                .description("A test workflow for unit testing")
                .build();

        workflowTool = WorkflowTool.builder()
                .id("test-tool")
                .name("test-summarizer")
                .description("Summarizes input text")
                .workflow(testWorkflow)
                .executor(input -> "Summary of: " + input.getOrDefault("text", ""))
                .inputSchema(Map.of(
                        "text", "string",
                        "maxLength", "integer"
                ))
                .build();

        adapter = new LangChain4jToolAdapter(workflowTool);
    }

    @Test
    void shouldAdaptWorkflowTool() {
        assertThat(adapter).isNotNull();
        assertThat(adapter.getWorkflowTool()).isSameAs(workflowTool);
        assertThat(adapter.getToolSpec()).isNotNull();
    }

    @Test
    void shouldExecuteWithStringInput() {
        String result = adapter.execute("{\"text\": \"Hello World\"}");

        assertThat(result).isNotNull();
        assertThat(result).contains("\"success\":true");
        assertThat(result).contains("Summary of: Hello World");
    }

    @Test
    void shouldHandleExecutionError() {
        WorkflowTool failingTool = WorkflowTool.builder()
                .id("failing-tool")
                .name("failing-tool")
                .workflow(testWorkflow)
                .executor(input -> {
                    throw new RuntimeException("Something went wrong");
                })
                .build();

        LangChain4jToolAdapter failingAdapter = new LangChain4jToolAdapter(failingTool);
        String result = failingAdapter.execute("{\"input\": \"data\"}");

        assertThat(result).isNotNull();
        assertThat(result).contains("\"success\":false");
        assertThat(result).contains("Something went wrong");
    }

    @Test
    void shouldGetToolSpec() {
        LangChain4jToolAdapter.ToolSpec spec = adapter.getToolSpec();

        assertThat(spec).isNotNull();
        assertThat(spec.name()).isEqualTo("test-summarizer");
        assertThat(spec.description()).isEqualTo("Summarizes input text");
        assertThat(spec.inputJsonSchema()).isNotNull();
        assertThat(spec.inputJsonSchema()).containsKey("type");
        assertThat(spec.inputJsonSchema().get("type")).isEqualTo("object");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldConvertInputSchema() {
        LangChain4jToolAdapter.ToolSpec spec = adapter.getToolSpec();
        Map<String, Object> schema = spec.inputJsonSchema();

        assertThat(schema.get("type")).isEqualTo("object");

        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertThat(properties).containsKey("text");
        assertThat(properties).containsKey("maxLength");

        Map<String, Object> textProp = (Map<String, Object>) properties.get("text");
        assertThat(textProp.get("type")).isEqualTo("string");

        Map<String, Object> maxLengthProp = (Map<String, Object>) properties.get("maxLength");
        assertThat(maxLengthProp.get("type")).isEqualTo("integer");
    }

    @Test
    void shouldReturnToolName() {
        assertThat(adapter.getToolName()).isEqualTo("test-summarizer");
    }

    @Test
    void shouldReturnToolDescription() {
        assertThat(adapter.getToolDescription()).isEqualTo("Summarizes input text");
    }

    @Test
    void shouldHandleNullInput() {
        String result = adapter.execute(null);

        assertThat(result).isNotNull();
        // Should execute with empty map and not throw
        assertThat(result).contains("\"success\"");
    }
}
