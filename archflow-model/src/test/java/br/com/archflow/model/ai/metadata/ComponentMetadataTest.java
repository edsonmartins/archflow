package br.com.archflow.model.ai.metadata;

import br.com.archflow.model.ai.ComponentState;
import br.com.archflow.model.ai.type.ComponentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

@DisplayName("ComponentMetadata")
class ComponentMetadataTest {

    @Test
    @DisplayName("should create with all fields")
    void shouldCreateWithAllFields() {
        var operation = new ComponentMetadata.OperationMetadata(
                "op-1", "analyze", "Analyze input",
                List.of(new ComponentMetadata.ParameterMetadata("input", "String", "The input", true)),
                List.of(new ComponentMetadata.ParameterMetadata("result", "String", "The result", true))
        );

        var metadata = new ComponentMetadata(
                "comp-1", "TestComponent", "A test component",
                ComponentType.ASSISTANT, "1.0.0",
                Set.of("chat", "analyze"),
                List.of(operation),
                Map.of("model", "gpt-4"),
                Set.of("ai", "test")
        );

        assertThat(metadata.id()).isEqualTo("comp-1");
        assertThat(metadata.name()).isEqualTo("TestComponent");
        assertThat(metadata.type()).isEqualTo(ComponentType.ASSISTANT);
        assertThat(metadata.version()).isEqualTo("1.0.0");
        assertThat(metadata.capabilities()).containsExactlyInAnyOrder("chat", "analyze");
        assertThat(metadata.operations()).hasSize(1);
        assertThat(metadata.tags()).contains("ai");
    }

    @Test
    @DisplayName("should validate successfully with valid data")
    void shouldValidateWithValidData() {
        var metadata = new ComponentMetadata(
                "comp-1", "Test", null, ComponentType.TOOL, "1.0.0",
                Set.of(), List.of(), Map.of(), Set.of()
        );

        assertThatNoException().isThrownBy(metadata::validate);
    }

    @Test
    @DisplayName("should reject null or blank id")
    void shouldRejectNullId() {
        var metadata = new ComponentMetadata(
                null, "Test", null, ComponentType.TOOL, "1.0.0",
                Set.of(), List.of(), Map.of(), Set.of()
        );

        assertThatThrownBy(metadata::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ID");
    }

    @Test
    @DisplayName("should reject blank id")
    void shouldRejectBlankId() {
        var metadata = new ComponentMetadata(
                "  ", "Test", null, ComponentType.TOOL, "1.0.0",
                Set.of(), List.of(), Map.of(), Set.of()
        );

        assertThatThrownBy(metadata::validate)
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("should reject null type")
    void shouldRejectNullType() {
        var metadata = new ComponentMetadata(
                "comp-1", "Test", null, null, "1.0.0",
                Set.of(), List.of(), Map.of(), Set.of()
        );

        assertThatThrownBy(metadata::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Tipo");
    }

    @Test
    @DisplayName("should reject null version")
    void shouldRejectNullVersion() {
        var metadata = new ComponentMetadata(
                "comp-1", "Test", null, ComponentType.AGENT, null,
                Set.of(), List.of(), Map.of(), Set.of()
        );

        assertThatThrownBy(metadata::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Versão");
    }

    @Test
    @DisplayName("should reject operation with blank id")
    void shouldRejectOperationWithBlankId() {
        var badOp = new ComponentMetadata.OperationMetadata("", "op", null, List.of(), List.of());
        var metadata = new ComponentMetadata(
                "comp-1", "Test", null, ComponentType.TOOL, "1.0.0",
                Set.of(), List.of(badOp), Map.of(), Set.of()
        );

        assertThatThrownBy(metadata::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operação");
    }

    @Test
    @DisplayName("should allow null operations list")
    void shouldAllowNullOperations() {
        var metadata = new ComponentMetadata(
                "comp-1", "Test", null, ComponentType.TOOL, "1.0.0",
                Set.of(), null, Map.of(), Set.of()
        );

        assertThatNoException().isThrownBy(metadata::validate);
    }

    @Test
    @DisplayName("ComponentState should create with factory methods")
    void componentStateShouldCreate() {
        var state1 = ComponentState.of(ComponentState.StateType.READY);
        assertThat(state1.type()).isEqualTo(ComponentState.StateType.READY);
        assertThat(state1.message()).isNull();
        assertThat(state1.lastUpdated()).isPositive();

        var state2 = ComponentState.of(ComponentState.StateType.ERROR, "Something went wrong");
        assertThat(state2.type()).isEqualTo(ComponentState.StateType.ERROR);
        assertThat(state2.message()).isEqualTo("Something went wrong");
    }

    @Test
    @DisplayName("ComponentState.StateType should have all expected values")
    void stateTypeShouldHaveAllValues() {
        assertThat(ComponentState.StateType.values()).contains(
                ComponentState.StateType.UNINITIALIZED,
                ComponentState.StateType.INITIALIZING,
                ComponentState.StateType.READY,
                ComponentState.StateType.BUSY,
                ComponentState.StateType.ERROR,
                ComponentState.StateType.SHUTTING_DOWN,
                ComponentState.StateType.SHUTDOWN
        );
    }
}
