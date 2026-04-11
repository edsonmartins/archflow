package br.com.archflow.agent.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ExecutionId")
class ExecutionIdTest {

    @Test
    void rootCreation() {
        ExecutionId root = ExecutionId.createRoot(ExecutionId.ExecutionType.FLOW);
        assertThat(root.isRoot()).isTrue();
        assertThat(root.getDepth()).isZero();
        assertThat(root.getSequence()).isZero();
        assertThat(root.getType()).isEqualTo(ExecutionId.ExecutionType.FLOW);
        assertThat(root.getParentId()).isNull();
        assertThat(root.getId()).startsWith("FLOW_");
        assertThat(root.getId()).endsWith("_000");
    }

    @Test
    void childCreation() {
        ExecutionId root = ExecutionId.createRoot(ExecutionId.ExecutionType.FLOW);
        ExecutionId child = root.createChild(ExecutionId.ExecutionType.TOOL);
        assertThat(child.isRoot()).isFalse();
        assertThat(child.getDepth()).isEqualTo(1);
        assertThat(child.getParentId()).isEqualTo(root.getId());
        assertThat(child.getRootId()).isEqualTo(root.getRootId());
        assertThat(child.getType()).isEqualTo(ExecutionId.ExecutionType.TOOL);
        assertThat(child.getId()).startsWith("TOOL_");
    }

    @Test
    void siblingCreation() {
        ExecutionId root = ExecutionId.createRoot(ExecutionId.ExecutionType.AGENT);
        ExecutionId sibling = root.createSibling(ExecutionId.ExecutionType.CHAIN, 2);
        assertThat(sibling.getSequence()).isEqualTo(2);
        assertThat(sibling.getDepth()).isEqualTo(root.getDepth());
        assertThat(sibling.getRootId()).isEqualTo(root.getRootId());
    }

    @Test
    void getPathForRoot() {
        ExecutionId root = ExecutionId.createRoot(ExecutionId.ExecutionType.FLOW);
        assertThat(root.getPath()).isEqualTo(root.getId());
    }

    @Test
    void getPathForChild() {
        ExecutionId root = ExecutionId.createRoot(ExecutionId.ExecutionType.FLOW);
        ExecutionId child = root.createChild(ExecutionId.ExecutionType.TOOL);
        assertThat(child.getPath()).contains(" -> ");
        assertThat(child.getPath()).startsWith(root.getId());
    }

    @Test
    void shortId() {
        ExecutionId root = ExecutionId.createRoot(ExecutionId.ExecutionType.FLOW);
        assertThat(root.getShortId()).hasSize(8);
    }

    @Test
    void fromStringRoundTrip() {
        ExecutionId original = ExecutionId.createRoot(ExecutionId.ExecutionType.FLOW);
        ExecutionId parsed = ExecutionId.fromString(original.getId());
        assertThat(parsed.getId()).isEqualTo(original.getId());
        assertThat(parsed.getType()).isEqualTo(original.getType());
    }

    @Test
    void fromStringRejectsInvalid() {
        assertThatThrownBy(() -> ExecutionId.fromString("bad"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equalsAndHashCode() {
        ExecutionId a = ExecutionId.createRoot(ExecutionId.ExecutionType.FLOW);
        ExecutionId b = ExecutionId.fromString(a.getId());
        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void toStringReturnsId() {
        ExecutionId root = ExecutionId.createRoot(ExecutionId.ExecutionType.FLOW);
        assertThat(root.toString()).isEqualTo(root.getId());
    }

    @Test
    void executionTypeFromPrefix() {
        assertThat(ExecutionId.ExecutionType.fromPrefix("FLOW")).isEqualTo(ExecutionId.ExecutionType.FLOW);
        assertThat(ExecutionId.ExecutionType.fromPrefix("TOOL")).isEqualTo(ExecutionId.ExecutionType.TOOL);
        assertThat(ExecutionId.ExecutionType.fromPrefix("AGENT")).isEqualTo(ExecutionId.ExecutionType.AGENT);
        assertThat(ExecutionId.ExecutionType.fromPrefix("CHAIN")).isEqualTo(ExecutionId.ExecutionType.CHAIN);
    }

    @Test
    void executionTypeFromPrefixUnknownThrows() {
        assertThatThrownBy(() -> ExecutionId.ExecutionType.fromPrefix("UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void builderRejectsNullType() {
        assertThatThrownBy(() -> ExecutionId.builder().build())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
