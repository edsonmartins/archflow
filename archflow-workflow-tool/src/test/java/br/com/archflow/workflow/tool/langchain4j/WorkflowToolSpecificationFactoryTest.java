package br.com.archflow.workflow.tool.langchain4j;

import br.com.archflow.model.Workflow;
import br.com.archflow.workflow.tool.WorkflowTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link WorkflowToolSpecificationFactory}.
 */
class WorkflowToolSpecificationFactoryTest {

    private Workflow testWorkflow;

    @BeforeEach
    void setUp() {
        testWorkflow = Workflow.builder()
                .id("test-workflow")
                .name("Test Workflow")
                .description("A test workflow")
                .build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldCreateSpecFromWorkflowTool() {
        WorkflowTool tool = WorkflowTool.builder()
                .id("my-tool")
                .name("my-tool")
                .description("Does something useful")
                .workflow(testWorkflow)
                .inputSchema(Map.of("query", "string"))
                .build();

        Map<String, Object> spec = WorkflowToolSpecificationFactory.createSpecification(tool);

        assertThat(spec).containsEntry("name", "my-tool");
        assertThat(spec).containsEntry("description", "Does something useful");
        assertThat(spec).containsKey("inputSchema");

        Map<String, Object> inputSchema = (Map<String, Object>) spec.get("inputSchema");
        assertThat(inputSchema.get("type")).isEqualTo("object");

        Map<String, Object> properties = (Map<String, Object>) inputSchema.get("properties");
        assertThat(properties).containsKey("query");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldMapStringParameter() {
        WorkflowTool tool = WorkflowTool.builder()
                .id("tool")
                .name("tool")
                .workflow(testWorkflow)
                .inputSchema(Map.of("text", "string"))
                .build();

        Map<String, Object> schema = WorkflowToolSpecificationFactory.createJsonSchema(tool);
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        Map<String, Object> textProp = (Map<String, Object>) properties.get("text");

        assertThat(textProp.get("type")).isEqualTo("string");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldMapNumberParameter() {
        WorkflowTool tool = WorkflowTool.builder()
                .id("tool")
                .name("tool")
                .workflow(testWorkflow)
                .inputSchema(Map.of(
                        "count", "integer",
                        "score", "number"
                ))
                .build();

        Map<String, Object> schema = WorkflowToolSpecificationFactory.createJsonSchema(tool);
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

        Map<String, Object> countProp = (Map<String, Object>) properties.get("count");
        assertThat(countProp.get("type")).isEqualTo("integer");

        Map<String, Object> scoreProp = (Map<String, Object>) properties.get("score");
        assertThat(scoreProp.get("type")).isEqualTo("number");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldMapBooleanParameter() {
        WorkflowTool tool = WorkflowTool.builder()
                .id("tool")
                .name("tool")
                .workflow(testWorkflow)
                .inputSchema(Map.of("verbose", "boolean"))
                .build();

        Map<String, Object> schema = WorkflowToolSpecificationFactory.createJsonSchema(tool);
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        Map<String, Object> verboseProp = (Map<String, Object>) properties.get("verbose");

        assertThat(verboseProp.get("type")).isEqualTo("boolean");
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldHandleRequiredParameters() {
        WorkflowTool tool = WorkflowTool.builder()
                .id("tool")
                .name("tool")
                .workflow(testWorkflow)
                .inputSchema(Map.of(
                        "query", Map.of(
                                "type", "string",
                                "description", "The search query",
                                "required", true
                        ),
                        "limit", Map.of(
                                "type", "integer",
                                "description", "Max results"
                        )
                ))
                .build();

        Map<String, Object> schema = WorkflowToolSpecificationFactory.createJsonSchema(tool);

        assertThat(schema).containsKey("required");
        List<String> required = (List<String>) schema.get("required");
        assertThat(required).contains("query");
        assertThat(required).doesNotContain("limit");

        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        Map<String, Object> queryProp = (Map<String, Object>) properties.get("query");
        assertThat(queryProp.get("type")).isEqualTo("string");
        assertThat(queryProp.get("description")).isEqualTo("The search query");
    }

    @Test
    void shouldHandleEmptySchema() {
        WorkflowTool tool = WorkflowTool.builder()
                .id("tool")
                .name("tool")
                .workflow(testWorkflow)
                .build();

        Map<String, Object> schema = WorkflowToolSpecificationFactory.createJsonSchema(tool);

        assertThat(schema).containsEntry("type", "object");
        assertThat(schema).containsKey("properties");
        assertThat(schema).doesNotContainKey("required");
    }
}
