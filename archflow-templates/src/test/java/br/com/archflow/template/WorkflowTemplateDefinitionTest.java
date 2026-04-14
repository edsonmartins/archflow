package br.com.archflow.template;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link WorkflowTemplateDefinition} and its inner classes.
 */
@DisplayName("WorkflowTemplateDefinition")
class WorkflowTemplateDefinitionTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static WorkflowTemplateDefinition.TemplateNode node(String id) {
        return new WorkflowTemplateDefinition.TemplateNode(id, "agent", "Node " + id, Map.of("k", "v"));
    }

    private static WorkflowTemplateDefinition.TemplateConnection connection(String id, String src, String tgt) {
        return new WorkflowTemplateDefinition.TemplateConnection(id, src, tgt, null, Map.of());
    }

    // -------------------------------------------------------------------------
    // WorkflowTemplateDefinition
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Constructor and field accessors")
    class ConstructorTests {

        @Test
        @DisplayName("constructor sets all fields correctly")
        void setsAllFieldsCorrectly() {
            // Arrange
            Set<String> tags = Set.of("tag1", "tag2");
            Map<String, Object> defaultParams = Map.of("timeout", 30);
            WorkflowTemplateDefinition.TemplateStructure structure =
                    new WorkflowTemplateDefinition.TemplateStructure("start", Map.of(), Map.of());

            // Act
            Instant before = Instant.now();
            WorkflowTemplateDefinition def = new WorkflowTemplateDefinition(
                    "wf-001", "2.0.0", "My Workflow", "A description",
                    "automation", tags, defaultParams, structure
            );
            Instant after = Instant.now();

            // Assert
            assertThat(def.getId()).isEqualTo("wf-001");
            assertThat(def.getVersion()).isEqualTo("2.0.0");
            assertThat(def.getName()).isEqualTo("My Workflow");
            assertThat(def.getDescription()).isEqualTo("A description");
            assertThat(def.getCategory()).isEqualTo("automation");
            assertThat(def.getTags()).containsExactlyInAnyOrder("tag1", "tag2");
            assertThat(def.getDefaultParameters()).containsEntry("timeout", 30);
            assertThat(def.getStructure()).isSameAs(structure);
            assertThat(def.getCreatedAt()).isAfterOrEqualTo(before).isBeforeOrEqualTo(after);
        }

        @Test
        @DisplayName("constructor with null tags produces empty set")
        void nullTagsProducesEmptySet() {
            WorkflowTemplateDefinition def = new WorkflowTemplateDefinition(
                    "id", "1.0", "name", "desc", "cat",
                    null, Map.of(), null
            );

            assertThat(def.getTags()).isNotNull().isEmpty();
        }
    }

    @Nested
    @DisplayName("Immutability of tags")
    class TagsImmutabilityTests {

        @Test
        @DisplayName("tags returned by getTags() are unmodifiable (Set.copyOf)")
        void tagsAreUnmodifiable() {
            Set<String> originalTags = new java.util.HashSet<>();
            originalTags.add("alpha");
            WorkflowTemplateDefinition def = new WorkflowTemplateDefinition(
                    "id", "1.0", "name", "desc", "cat",
                    originalTags, Map.of(), null
            );

            Set<String> tags = def.getTags();

            assertThatThrownBy(() -> tags.add("beta"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("modifying the original tag set after construction does not affect getTags()")
        void originalTagMutationDoesNotAffectDefinition() {
            Set<String> originalTags = new java.util.HashSet<>();
            originalTags.add("alpha");
            WorkflowTemplateDefinition def = new WorkflowTemplateDefinition(
                    "id", "1.0", "name", "desc", "cat",
                    originalTags, Map.of(), null
            );

            originalTags.add("beta"); // mutate source

            assertThat(def.getTags()).containsOnly("alpha");
        }
    }

    @Nested
    @DisplayName("Immutability of defaultParameters")
    class DefaultParametersImmutabilityTests {

        @Test
        @DisplayName("defaultParameters returned by getDefaultParameters() are unmodifiable (Map.copyOf)")
        void defaultParametersAreUnmodifiable() {
            Map<String, Object> params = new HashMap<>();
            params.put("key", "value");
            WorkflowTemplateDefinition def = new WorkflowTemplateDefinition(
                    "id", "1.0", "name", "desc", "cat",
                    Set.of(), params, null
            );

            Map<String, Object> defaultParams = def.getDefaultParameters();

            assertThatThrownBy(() -> defaultParams.put("newKey", "newValue"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("modifying original parameters map does not affect getDefaultParameters()")
        void originalMapMutationDoesNotAffectDefinition() {
            Map<String, Object> params = new HashMap<>();
            params.put("key", "original");
            WorkflowTemplateDefinition def = new WorkflowTemplateDefinition(
                    "id", "1.0", "name", "desc", "cat",
                    Set.of(), params, null
            );

            params.put("key", "mutated");

            assertThat(def.getDefaultParameters()).containsEntry("key", "original");
        }
    }

    // -------------------------------------------------------------------------
    // TemplateNode
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("TemplateNode")
    class TemplateNodeTests {

        @Test
        @DisplayName("TemplateNode fields are accessible via getters")
        void fieldsAreAccessible() {
            // Arrange
            Map<String, Object> config = Map.of("model", "gpt-4", "temperature", 0.7);
            WorkflowTemplateDefinition.TemplateNode templateNode =
                    new WorkflowTemplateDefinition.TemplateNode("n1", "llm", "LLM Node", config);

            // Assert
            assertThat(templateNode.getId()).isEqualTo("n1");
            assertThat(templateNode.getType()).isEqualTo("llm");
            assertThat(templateNode.getName()).isEqualTo("LLM Node");
            assertThat(templateNode.getConfiguration()).containsEntry("model", "gpt-4");
            assertThat(templateNode.getConfiguration()).containsEntry("temperature", 0.7);
        }

        @Test
        @DisplayName("configuration returned by getConfiguration() is unmodifiable (Map.copyOf)")
        void configurationIsUnmodifiable() {
            WorkflowTemplateDefinition.TemplateNode templateNode =
                    new WorkflowTemplateDefinition.TemplateNode("n1", "type", "name", Map.of("k", "v"));

            assertThatThrownBy(() -> templateNode.getConfiguration().put("newKey", "newValue"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("TemplateNode with null configuration produces empty map")
        void nullConfigurationProducesEmptyMap() {
            WorkflowTemplateDefinition.TemplateNode templateNode =
                    new WorkflowTemplateDefinition.TemplateNode("n1", "type", "name", null);

            assertThat(templateNode.getConfiguration()).isNotNull().isEmpty();
        }
    }

    // -------------------------------------------------------------------------
    // TemplateConnection
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("TemplateConnection")
    class TemplateConnectionTests {

        @Test
        @DisplayName("TemplateConnection fields are accessible via getters")
        void fieldsAreAccessible() {
            // Arrange
            Map<String, Object> config = Map.of("priority", 1);
            WorkflowTemplateDefinition.TemplateConnection conn =
                    new WorkflowTemplateDefinition.TemplateConnection(
                            "c1", "nodeA", "nodeB", "success", config);

            // Assert
            assertThat(conn.getId()).isEqualTo("c1");
            assertThat(conn.getSource()).isEqualTo("nodeA");
            assertThat(conn.getTarget()).isEqualTo("nodeB");
            assertThat(conn.getCondition()).isEqualTo("success");
            assertThat(conn.getConfiguration()).containsEntry("priority", 1);
        }

        @Test
        @DisplayName("TemplateConnection with null condition stores null")
        void nullConditionStoredAsNull() {
            WorkflowTemplateDefinition.TemplateConnection conn =
                    new WorkflowTemplateDefinition.TemplateConnection("c1", "a", "b", null, Map.of());

            assertThat(conn.getCondition()).isNull();
        }

        @Test
        @DisplayName("configuration returned by getConfiguration() is unmodifiable (Map.copyOf)")
        void configurationIsUnmodifiable() {
            WorkflowTemplateDefinition.TemplateConnection conn =
                    new WorkflowTemplateDefinition.TemplateConnection("c1", "a", "b", null, Map.of("k", "v"));

            assertThatThrownBy(() -> conn.getConfiguration().put("x", "y"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("TemplateConnection with null configuration produces empty map")
        void nullConfigurationProducesEmptyMap() {
            WorkflowTemplateDefinition.TemplateConnection conn =
                    new WorkflowTemplateDefinition.TemplateConnection("c1", "a", "b", null, null);

            assertThat(conn.getConfiguration()).isNotNull().isEmpty();
        }
    }

    // -------------------------------------------------------------------------
    // TemplateStructure
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("TemplateStructure")
    class TemplateStructureTests {

        @Test
        @DisplayName("TemplateStructure with nodes and connections stores all values")
        void storesNodesAndConnections() {
            // Arrange
            Map<String, WorkflowTemplateDefinition.TemplateNode> nodes = new HashMap<>();
            nodes.put("n1", node("n1"));
            nodes.put("n2", node("n2"));

            Map<String, WorkflowTemplateDefinition.TemplateConnection> connections = new HashMap<>();
            connections.put("c1", connection("c1", "n1", "n2"));

            // Act
            WorkflowTemplateDefinition.TemplateStructure structure =
                    new WorkflowTemplateDefinition.TemplateStructure("n1", nodes, connections);

            // Assert
            assertThat(structure.getEntryPoint()).isEqualTo("n1");
            assertThat(structure.getNodes()).containsKey("n1").containsKey("n2");
            assertThat(structure.getConnections()).containsKey("c1");
        }

        @Test
        @DisplayName("nodes map returned by getNodes() is unmodifiable (Map.copyOf)")
        void nodesMapIsUnmodifiable() {
            Map<String, WorkflowTemplateDefinition.TemplateNode> nodes = new HashMap<>();
            nodes.put("n1", node("n1"));
            WorkflowTemplateDefinition.TemplateStructure structure =
                    new WorkflowTemplateDefinition.TemplateStructure("n1", nodes, Map.of());

            assertThatThrownBy(() -> structure.getNodes().put("extra", node("extra")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("connections map returned by getConnections() is unmodifiable (Map.copyOf)")
        void connectionsMapIsUnmodifiable() {
            Map<String, WorkflowTemplateDefinition.TemplateConnection> connections = new HashMap<>();
            connections.put("c1", connection("c1", "n1", "n2"));
            WorkflowTemplateDefinition.TemplateStructure structure =
                    new WorkflowTemplateDefinition.TemplateStructure("n1", Map.of(), connections);

            assertThatThrownBy(() -> structure.getConnections().put("extra", connection("extra", "a", "b")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("TemplateStructure with empty nodes and connections is valid")
        void emptyNodesAndConnections() {
            WorkflowTemplateDefinition.TemplateStructure structure =
                    new WorkflowTemplateDefinition.TemplateStructure("start", Map.of(), Map.of());

            assertThat(structure.getEntryPoint()).isEqualTo("start");
            assertThat(structure.getNodes()).isEmpty();
            assertThat(structure.getConnections()).isEmpty();
        }
    }

    // -------------------------------------------------------------------------
    // Integration: full definition with structure
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Full definition assembly")
    class FullDefinitionTests {

        @Test
        @DisplayName("assembling a full definition with nodes and connections produces consistent object graph")
        void fullDefinitionObjectGraph() {
            // Arrange
            Map<String, WorkflowTemplateDefinition.TemplateNode> nodes = new HashMap<>();
            nodes.put("input", node("input"));
            nodes.put("processor", node("processor"));
            nodes.put("output", node("output"));

            Map<String, WorkflowTemplateDefinition.TemplateConnection> connections = new HashMap<>();
            connections.put("edge1", connection("edge1", "input", "processor"));
            connections.put("edge2", connection("edge2", "processor", "output"));

            WorkflowTemplateDefinition.TemplateStructure structure =
                    new WorkflowTemplateDefinition.TemplateStructure("input", nodes, connections);

            // Act
            WorkflowTemplateDefinition def = new WorkflowTemplateDefinition(
                    "full-wf", "1.0.0", "Full Workflow", "End-to-end test",
                    "integration",
                    Set.of("full", "test"),
                    Map.of("maxRetries", 3),
                    structure
            );

            // Assert — outer definition
            assertThat(def.getId()).isEqualTo("full-wf");
            assertThat(def.getDefaultParameters()).containsEntry("maxRetries", 3);

            // Assert — structure
            assertThat(def.getStructure().getEntryPoint()).isEqualTo("input");
            assertThat(def.getStructure().getNodes()).hasSize(3);
            assertThat(def.getStructure().getConnections()).hasSize(2);

            // Assert — node detail
            WorkflowTemplateDefinition.TemplateNode processorNode = def.getStructure().getNodes().get("processor");
            assertThat(processorNode.getId()).isEqualTo("processor");
            assertThat(processorNode.getType()).isEqualTo("agent");
        }
    }
}
