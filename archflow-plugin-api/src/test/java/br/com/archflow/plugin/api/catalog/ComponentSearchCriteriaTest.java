package br.com.archflow.plugin.api.catalog;

import br.com.archflow.model.ai.type.ComponentType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ComponentSearchCriteria}.
 */
@DisplayName("ComponentSearchCriteria")
class ComponentSearchCriteriaTest {

    @Nested
    @DisplayName("Builder defaults")
    class BuilderDefaults {

        @Test
        @DisplayName("builder with no fields set produces null type, empty capabilities, and null textSearch")
        void defaultBuilderProducesNullsAndEmptySet() {
            // Act
            ComponentSearchCriteria criteria = ComponentSearchCriteria.builder().build();

            // Assert
            assertThat(criteria.type()).isNull();
            assertThat(criteria.capabilities()).isNotNull().isEmpty();
            assertThat(criteria.textSearch()).isNull();
        }
    }

    @Nested
    @DisplayName("Builder with type only")
    class BuilderWithTypeOnly {

        @Test
        @DisplayName("builder with type set only returns correct type and defaults for other fields")
        void builderWithTypeOnlyHasCorrectType() {
            // Act
            ComponentSearchCriteria criteria = ComponentSearchCriteria.builder()
                    .type(ComponentType.AGENT)
                    .build();

            // Assert
            assertThat(criteria.type()).isEqualTo(ComponentType.AGENT);
            assertThat(criteria.capabilities()).isEmpty();
            assertThat(criteria.textSearch()).isNull();
        }
    }

    @Nested
    @DisplayName("Builder with capabilities")
    class BuilderWithCapabilities {

        @Test
        @DisplayName("builder with capabilities set returns correct capabilities")
        void builderWithCapabilitiesReturnsCorrectSet() {
            // Arrange
            Set<String> caps = Set.of("search", "summarize");

            // Act
            ComponentSearchCriteria criteria = ComponentSearchCriteria.builder()
                    .capabilities(caps)
                    .build();

            // Assert
            assertThat(criteria.capabilities()).containsExactlyInAnyOrder("search", "summarize");
            assertThat(criteria.type()).isNull();
            assertThat(criteria.textSearch()).isNull();
        }
    }

    @Nested
    @DisplayName("Builder with textSearch")
    class BuilderWithTextSearch {

        @Test
        @DisplayName("builder with textSearch set returns correct textSearch")
        void builderWithTextSearchReturnsCorrectValue() {
            // Act
            ComponentSearchCriteria criteria = ComponentSearchCriteria.builder()
                    .textSearch("customer support")
                    .build();

            // Assert
            assertThat(criteria.textSearch()).isEqualTo("customer support");
            assertThat(criteria.type()).isNull();
            assertThat(criteria.capabilities()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Builder with all fields")
    class BuilderWithAllFields {

        @Test
        @DisplayName("builder with all fields set produces record with all values correctly populated")
        void builderWithAllFieldsPopulatesAllValues() {
            // Arrange
            Set<String> caps = Set.of("qa", "retrieval");

            // Act
            ComponentSearchCriteria criteria = ComponentSearchCriteria.builder()
                    .type(ComponentType.ASSISTANT)
                    .capabilities(caps)
                    .textSearch("knowledge base")
                    .build();

            // Assert
            assertThat(criteria.type()).isEqualTo(ComponentType.ASSISTANT);
            assertThat(criteria.capabilities()).containsExactlyInAnyOrder("qa", "retrieval");
            assertThat(criteria.textSearch()).isEqualTo("knowledge base");
        }

        @Test
        @DisplayName("builder supports all ComponentType enum values")
        void builderSupportsAllComponentTypes() {
            for (ComponentType componentType : ComponentType.values()) {
                ComponentSearchCriteria criteria = ComponentSearchCriteria.builder()
                        .type(componentType)
                        .build();

                assertThat(criteria.type())
                        .as("Expected type %s to be set correctly", componentType)
                        .isEqualTo(componentType);
            }
        }
    }

    @Nested
    @DisplayName("Record accessors")
    class RecordAccessors {

        @Test
        @DisplayName("record accessors return the values passed at construction time")
        void recordAccessorsReturnCorrectValues() {
            // Arrange
            Set<String> caps = Set.of("routing", "sentiment");
            ComponentSearchCriteria criteria = new ComponentSearchCriteria(
                    ComponentType.TOOL,
                    caps,
                    "email tool"
            );

            // Assert
            assertThat(criteria.type()).isEqualTo(ComponentType.TOOL);
            assertThat(criteria.capabilities()).containsExactlyInAnyOrder("routing", "sentiment");
            assertThat(criteria.textSearch()).isEqualTo("email tool");
        }

        @Test
        @DisplayName("two criteria records with same values are equal")
        void equalityBasedOnValues() {
            // Arrange
            Set<String> caps = Set.of("search");
            ComponentSearchCriteria a = new ComponentSearchCriteria(ComponentType.PLUGIN, caps, "search plugin");
            ComponentSearchCriteria b = new ComponentSearchCriteria(ComponentType.PLUGIN, caps, "search plugin");

            // Assert
            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("toString includes field values")
        void toStringIncludesFieldValues() {
            ComponentSearchCriteria criteria = ComponentSearchCriteria.builder()
                    .type(ComponentType.AGENT)
                    .textSearch("my agent")
                    .build();

            assertThat(criteria.toString()).contains("AGENT").contains("my agent");
        }
    }
}
