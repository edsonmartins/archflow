package br.com.archflow.model.flow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("FlowMetadata")
class FlowMetadataTest {

    @Test
    @DisplayName("should create with all fields via constructor")
    void shouldCreateWithAllFields() {
        var tags = List.of("ai", "support");
        var metadata = new FlowMetadata("Test Flow", "A test", "1.0.0", "author", "support", tags);

        assertThat(metadata.name()).isEqualTo("Test Flow");
        assertThat(metadata.description()).isEqualTo("A test");
        assertThat(metadata.version()).isEqualTo("1.0.0");
        assertThat(metadata.author()).isEqualTo("author");
        assertThat(metadata.category()).isEqualTo("support");
        assertThat(metadata.tags()).containsExactly("ai", "support");
    }

    @Test
    @DisplayName("should create with builder")
    void shouldCreateWithBuilder() {
        var metadata = FlowMetadata.builder()
                .name("Builder Flow")
                .description("Built with builder")
                .version("2.0.0")
                .author("builder-author")
                .category("test")
                .tags(List.of("tag1"))
                .build();

        assertThat(metadata.name()).isEqualTo("Builder Flow");
        assertThat(metadata.version()).isEqualTo("2.0.0");
        assertThat(metadata.tags()).containsExactly("tag1");
    }

    @Test
    @DisplayName("should default tags to empty list when null")
    void shouldDefaultTagsToEmptyList() {
        var metadata = new FlowMetadata("Flow", null, "1.0.0", null, null, null);

        assertThat(metadata.tags()).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "\t"})
    @DisplayName("should reject null/blank name")
    void shouldRejectInvalidName(String name) {
        assertThatThrownBy(() -> new FlowMetadata(name, null, "1.0.0", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Name is required");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "\t"})
    @DisplayName("should reject null/blank version")
    void shouldRejectInvalidVersion(String version) {
        assertThatThrownBy(() -> new FlowMetadata("Flow", null, version, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Version is required");
    }

    @Test
    @DisplayName("should support record equality")
    void shouldSupportEquality() {
        var tags = List.of("tag");
        var m1 = new FlowMetadata("Flow", "desc", "1.0.0", "auth", "cat", tags);
        var m2 = new FlowMetadata("Flow", "desc", "1.0.0", "auth", "cat", tags);

        assertThat(m1).isEqualTo(m2);
        assertThat(m1.hashCode()).isEqualTo(m2.hashCode());
    }

    @Test
    @DisplayName("should allow null description, author, and category")
    void shouldAllowNullOptionalFields() {
        var metadata = new FlowMetadata("Flow", null, "1.0.0", null, null, null);

        assertThat(metadata.description()).isNull();
        assertThat(metadata.author()).isNull();
        assertThat(metadata.category()).isNull();
    }
}
