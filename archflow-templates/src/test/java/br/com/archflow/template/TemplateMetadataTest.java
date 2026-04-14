package br.com.archflow.template;

import br.com.archflow.model.Workflow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TemplateMetadata}.
 */
@DisplayName("TemplateMetadata")
class TemplateMetadataTest {

    /**
     * Minimal concrete WorkflowTemplate for testing TemplateMetadata.from().
     */
    static class StubTemplate implements WorkflowTemplate {

        private final String id;
        private final String displayName;
        private final String description;
        private final String category;
        private final Set<String> tags;
        private final Map<String, ParameterDefinition> parameters;
        private final WorkflowTemplateDefinition definition;

        StubTemplate(
                String id,
                String displayName,
                String description,
                String category,
                Set<String> tags,
                Map<String, ParameterDefinition> parameters,
                WorkflowTemplateDefinition definition) {
            this.id = id;
            this.displayName = displayName;
            this.description = description;
            this.category = category;
            this.tags = tags;
            this.parameters = parameters;
            this.definition = definition;
        }

        @Override public String getId() { return id; }
        @Override public String getDisplayName() { return displayName; }
        @Override public String getDescription() { return description; }
        @Override public String getCategory() { return category; }
        @Override public Set<String> getTags() { return tags; }
        @Override public WorkflowTemplateDefinition getDefinition() { return definition; }
        @Override public Map<String, ParameterDefinition> getParameters() { return parameters; }

        @Override
        public Workflow createInstance(String name, Map<String, Object> parameters) {
            return Workflow.builder().id(name).name(name).build();
        }
    }

    private static WorkflowTemplateDefinition definitionWithNodes(int nodeCount) {
        Map<String, WorkflowTemplateDefinition.TemplateNode> nodes = new HashMap<>();
        for (int i = 0; i < nodeCount; i++) {
            String nodeId = "node-" + i;
            nodes.put(nodeId, new WorkflowTemplateDefinition.TemplateNode(nodeId, "type", "Node " + i, Map.of()));
        }
        return new WorkflowTemplateDefinition(
                "stub-id", "1.0.0", "Stub", "Stub desc", "test",
                Set.of("t1", "t2"), Map.of(),
                new WorkflowTemplateDefinition.TemplateStructure("node-0", nodes, Map.of())
        );
    }

    private static StubTemplate buildStub(WorkflowTemplateDefinition definition) {
        Map<String, WorkflowTemplate.ParameterDefinition> params = new LinkedHashMap<>();
        params.put("param1", WorkflowTemplate.ParameterDefinition.required("param1", "A param", String.class));
        return new StubTemplate(
                "stub-template",
                "Stub Template",
                "Description of stub",
                "testing",
                Set.of("unit", "stub"),
                params,
                definition
        );
    }

    @Nested
    @DisplayName("Record field accessors")
    class RecordFieldAccessors {

        @Test
        @DisplayName("record fields are accessible via canonical accessors")
        void recordFieldsAreAccessible() {
            // Arrange
            Map<String, WorkflowTemplate.ParameterDefinition> params = Map.of(
                    "p", WorkflowTemplate.ParameterDefinition.optional("p", "d", String.class, "v")
            );
            TemplateMetadata metadata = new TemplateMetadata(
                    "my-id", "My Name", "My Description", "my-category",
                    "2.0.0", "test-author", "icon.png", "medium",
                    Set.of("tag1"), params, 5
            );

            // Assert
            assertThat(metadata.id()).isEqualTo("my-id");
            assertThat(metadata.displayName()).isEqualTo("My Name");
            assertThat(metadata.description()).isEqualTo("My Description");
            assertThat(metadata.category()).isEqualTo("my-category");
            assertThat(metadata.version()).isEqualTo("2.0.0");
            assertThat(metadata.author()).isEqualTo("test-author");
            assertThat(metadata.icon()).isEqualTo("icon.png");
            assertThat(metadata.complexity()).isEqualTo("medium");
            assertThat(metadata.tags()).containsExactly("tag1");
            assertThat(metadata.parameters()).containsKey("p");
            assertThat(metadata.stepCount()).isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("from(WorkflowTemplate)")
    class FromTests {

        @Test
        @DisplayName("from() creates metadata with fields sourced from the template")
        void createsMetadataFromTemplate() {
            // Arrange
            StubTemplate stub = buildStub(definitionWithNodes(3));

            // Act
            TemplateMetadata metadata = TemplateMetadata.from(stub);

            // Assert
            assertThat(metadata.id()).isEqualTo("stub-template");
            assertThat(metadata.displayName()).isEqualTo("Stub Template");
            assertThat(metadata.description()).isEqualTo("Description of stub");
            assertThat(metadata.category()).isEqualTo("testing");
            assertThat(metadata.tags()).containsExactlyInAnyOrder("unit", "stub");
            assertThat(metadata.parameters()).containsKey("param1");
        }

        @Test
        @DisplayName("from() sets version to 1.0.0 and author to archflow")
        void setsVersionAndAuthor() {
            // Arrange
            StubTemplate stub = buildStub(definitionWithNodes(1));

            // Act
            TemplateMetadata metadata = TemplateMetadata.from(stub);

            // Assert
            assertThat(metadata.version()).isEqualTo("1.0.0");
            assertThat(metadata.author()).isEqualTo("archflow");
        }

        @Test
        @DisplayName("from() extracts correct stepCount from definition nodes")
        void extractsCorrectStepCount() {
            // Arrange
            StubTemplate stub = buildStub(definitionWithNodes(4));

            // Act
            TemplateMetadata metadata = TemplateMetadata.from(stub);

            // Assert
            assertThat(metadata.stepCount()).isEqualTo(4);
        }

        @Test
        @DisplayName("from() returns stepCount of zero when definition has no nodes")
        void returnsZeroStepCountForEmptyNodes() {
            // Arrange
            StubTemplate stub = buildStub(definitionWithNodes(0));

            // Act
            TemplateMetadata metadata = TemplateMetadata.from(stub);

            // Assert
            assertThat(metadata.stepCount()).isZero();
        }

        @Test
        @DisplayName("from() returns stepCount of zero when definition is null")
        void returnsZeroStepCountForNullDefinition() {
            // Arrange
            StubTemplate stub = new StubTemplate(
                    "null-def", "Null Def", "desc", "cat",
                    Set.of(), Map.of(), null
            );

            // Act
            TemplateMetadata metadata = TemplateMetadata.from(stub);

            // Assert
            assertThat(metadata.stepCount()).isZero();
        }

        @Test
        @DisplayName("from() returns stepCount of zero when structure is null")
        void returnsZeroStepCountForNullStructure() {
            // Arrange
            WorkflowTemplateDefinition defWithNullStructure = new WorkflowTemplateDefinition(
                    "id", "1.0.0", "name", "desc", "cat",
                    Set.of(), Map.of(), null
            );
            StubTemplate stub = buildStub(defWithNullStructure);

            // Act
            TemplateMetadata metadata = TemplateMetadata.from(stub);

            // Assert
            assertThat(metadata.stepCount()).isZero();
        }

        @Test
        @DisplayName("from() sets icon and complexity to null (not provided by template)")
        void setsIconAndComplexityToNull() {
            // Arrange
            StubTemplate stub = buildStub(definitionWithNodes(2));

            // Act
            TemplateMetadata metadata = TemplateMetadata.from(stub);

            // Assert
            assertThat(metadata.icon()).isNull();
            assertThat(metadata.complexity()).isNull();
        }
    }
}
