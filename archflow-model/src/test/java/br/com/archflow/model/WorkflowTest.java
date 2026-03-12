package br.com.archflow.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Workflow")
class WorkflowTest {

    @Test
    @DisplayName("should create with builder")
    void shouldCreateWithBuilder() {
        var workflow = Workflow.builder()
                .id("wf-1")
                .name("Test Workflow")
                .description("A test workflow")
                .metadata(Map.of("version", "1.0"))
                .build();

        assertThat(workflow.getId()).isEqualTo("wf-1");
        assertThat(workflow.getName()).isEqualTo("Test Workflow");
        assertThat(workflow.getDescription()).isEqualTo("A test workflow");
        assertThat(workflow.getMetadata()).containsEntry("version", "1.0");
    }

    @Test
    @DisplayName("should default name to id when not set")
    void shouldDefaultNameToId() {
        var workflow = Workflow.builder()
                .id("wf-1")
                .build();

        assertThat(workflow.getName()).isEqualTo("wf-1");
    }

    @Test
    @DisplayName("should default description to empty string")
    void shouldDefaultDescriptionToEmpty() {
        var workflow = Workflow.builder()
                .id("wf-1")
                .build();

        assertThat(workflow.getDescription()).isEmpty();
    }

    @Test
    @DisplayName("should default metadata to empty map")
    void shouldDefaultMetadataToEmpty() {
        var workflow = Workflow.builder()
                .id("wf-1")
                .build();

        assertThat(workflow.getMetadata()).isEmpty();
    }

    @Test
    @DisplayName("should add individual metadata entries")
    void shouldAddIndividualMetadata() {
        var workflow = Workflow.builder()
                .id("wf-1")
                .addMetadata("key1", "value1")
                .addMetadata("key2", 42)
                .build();

        assertThat(workflow.getMetadata()).hasSize(2);
        assertThat(workflow.getMetadata()).containsEntry("key1", "value1");
        assertThat(workflow.getMetadata()).containsEntry("key2", 42);
    }

    @Test
    @DisplayName("should return immutable metadata map")
    void shouldReturnImmutableMetadata() {
        var workflow = Workflow.builder()
                .id("wf-1")
                .metadata(Map.of("key", "value"))
                .build();

        assertThatThrownBy(() -> workflow.getMetadata().put("new", "entry"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
