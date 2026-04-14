package br.com.archflow.template;

import br.com.archflow.model.Workflow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AbstractWorkflowTemplate}.
 */
@DisplayName("AbstractWorkflowTemplate")
class AbstractWorkflowTemplateTest {

    /**
     * Concrete subclass used for testing the abstract base class.
     */
    static class TestableTemplate extends AbstractWorkflowTemplate {

        Workflow builtWorkflow;

        TestableTemplate(
                String id,
                String displayName,
                String description,
                String category,
                Set<String> tags,
                Map<String, ParameterDefinition> parameters) {
            super(id, displayName, description, category, tags, parameters);
        }

        @Override
        protected Workflow buildWorkflow(String name, Map<String, Object> parameters) {
            builtWorkflow = Workflow.builder()
                    .id(name)
                    .name(name)
                    .build();
            return builtWorkflow;
        }

        @Override
        public WorkflowTemplateDefinition getDefinition() {
            return new WorkflowTemplateDefinition(
                    getId(), "1.0.0", getDisplayName(), getDescription(),
                    getCategory(), getTags(), Map.of(),
                    new WorkflowTemplateDefinition.TemplateStructure(
                            "start", Map.of(), Map.of()
                    )
            );
        }

        // Expose protected helpers for testing
        public String exposeGetString(Map<String, Object> params, String key, String def) {
            return getString(params, key, def);
        }

        public int exposeGetInt(Map<String, Object> params, String key, int def) {
            return getInt(params, key, def);
        }

        public boolean exposeGetBoolean(Map<String, Object> params, String key, boolean def) {
            return getBoolean(params, key, def);
        }

        public Map<String, Object> exposeResolveParameters(Map<String, Object> params) {
            return resolveParameters(params);
        }
    }

    private static Map<String, WorkflowTemplate.ParameterDefinition> buildParameters() {
        Map<String, WorkflowTemplate.ParameterDefinition> params = new LinkedHashMap<>();
        params.put("requiredParam", WorkflowTemplate.ParameterDefinition.required(
                "requiredParam", "A required string parameter", String.class));
        params.put("optionalParam", WorkflowTemplate.ParameterDefinition.optional(
                "optionalParam", "An optional int parameter", Integer.class, 42));
        return params;
    }

    private TestableTemplate template;

    @BeforeEach
    void setUp() {
        template = new TestableTemplate(
                "test-template",
                "Test Template",
                "A template for testing",
                "test-category",
                Set.of("tag-a", "tag-b"),
                buildParameters()
        );
    }

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("constructor sets id correctly")
        void setsIdCorrectly() {
            assertThat(template.getId()).isEqualTo("test-template");
        }

        @Test
        @DisplayName("constructor sets displayName correctly")
        void setsDisplayNameCorrectly() {
            assertThat(template.getDisplayName()).isEqualTo("Test Template");
        }

        @Test
        @DisplayName("constructor sets description correctly")
        void setsDescriptionCorrectly() {
            assertThat(template.getDescription()).isEqualTo("A template for testing");
        }

        @Test
        @DisplayName("constructor sets category correctly")
        void setsCategoryCorrectly() {
            assertThat(template.getCategory()).isEqualTo("test-category");
        }

        @Test
        @DisplayName("constructor with null tags produces empty set")
        void nullTagsProducesEmptySet() {
            TestableTemplate t = new TestableTemplate(
                    "id", "name", "desc", "cat", null, Map.of());
            assertThat(t.getTags()).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("constructor stores parameter definitions")
        void storesParameterDefinitions() {
            assertThat(template.getParameters()).containsKey("requiredParam");
            assertThat(template.getParameters()).containsKey("optionalParam");
        }
    }

    @Nested
    @DisplayName("getTags()")
    class GetTagsTests {

        @Test
        @DisplayName("getTags returns all provided tags")
        void returnsAllTags() {
            assertThat(template.getTags()).containsExactlyInAnyOrder("tag-a", "tag-b");
        }

        @Test
        @DisplayName("getTags returns unmodifiable set")
        void returnsUnmodifiableSet() {
            Set<String> tags = template.getTags();

            assertThatThrownBy(() -> tags.add("new-tag"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("createInstance()")
    class CreateInstanceTests {

        @Test
        @DisplayName("createInstance calls validateParameters and resolveParameters then buildWorkflow")
        void callsValidateAndResolve() {
            // Arrange
            Map<String, Object> params = new HashMap<>();
            params.put("requiredParam", "hello");

            // Act
            Workflow workflow = template.createInstance("my-workflow", params);

            // Assert
            assertThat(workflow).isNotNull();
            assertThat(workflow.getId()).isEqualTo("my-workflow");
            assertThat(template.builtWorkflow).isSameAs(workflow);
        }

        @Test
        @DisplayName("createInstance throws when required parameter is missing")
        void throwsWhenRequiredParamMissing() {
            // Arrange - omit "requiredParam"
            Map<String, Object> params = Map.of("optionalParam", 10);

            // Act & Assert
            assertThatThrownBy(() -> template.createInstance("my-workflow", params))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("requiredParam");
        }
    }

    @Nested
    @DisplayName("resolveParameters()")
    class ResolveParametersTests {

        @Test
        @DisplayName("resolveParameters merges defaults with user-provided values")
        void mergesDefaultsWithUserValues() {
            // Arrange - provide requiredParam, omit optionalParam
            Map<String, Object> userParams = Map.of("requiredParam", "value");

            // Act
            Map<String, Object> resolved = template.exposeResolveParameters(userParams);

            // Assert
            assertThat(resolved).containsEntry("requiredParam", "value");
            assertThat(resolved).containsEntry("optionalParam", 42); // default applied
        }

        @Test
        @DisplayName("resolveParameters uses user value over default when both present")
        void userValueOverridesDefault() {
            // Arrange
            Map<String, Object> userParams = new HashMap<>();
            userParams.put("requiredParam", "provided");
            userParams.put("optionalParam", 99);

            // Act
            Map<String, Object> resolved = template.exposeResolveParameters(userParams);

            // Assert
            assertThat(resolved).containsEntry("optionalParam", 99);
        }

        @Test
        @DisplayName("resolveParameters adds extra keys not defined in parameter definitions")
        void addsExtraUserKeys() {
            // Arrange
            Map<String, Object> userParams = new HashMap<>();
            userParams.put("requiredParam", "value");
            userParams.put("extraKey", "extraValue");

            // Act
            Map<String, Object> resolved = template.exposeResolveParameters(userParams);

            // Assert
            assertThat(resolved).containsEntry("extraKey", "extraValue");
        }

        @Test
        @DisplayName("resolveParameters with null input applies all defaults")
        void nullInputAppliesAllDefaults() {
            // Act
            Map<String, Object> resolved = template.exposeResolveParameters(null);

            // Assert
            assertThat(resolved).containsEntry("requiredParam", null);
            assertThat(resolved).containsEntry("optionalParam", 42);
        }
    }

    @Nested
    @DisplayName("getString()")
    class GetStringTests {

        @Test
        @DisplayName("getString returns string value when key exists")
        void returnsStringValue() {
            Map<String, Object> params = Map.of("key", "hello");
            assertThat(template.exposeGetString(params, "key", "default")).isEqualTo("hello");
        }

        @Test
        @DisplayName("getString returns defaultValue when key is absent")
        void returnsDefaultWhenKeyAbsent() {
            Map<String, Object> params = Map.of();
            assertThat(template.exposeGetString(params, "missing", "fallback")).isEqualTo("fallback");
        }

        @Test
        @DisplayName("getString converts non-string value via toString()")
        void convertsNonStringViaToString() {
            Map<String, Object> params = Map.of("key", 123);
            assertThat(template.exposeGetString(params, "key", "default")).isEqualTo("123");
        }

        @Test
        @DisplayName("getString returns defaultValue when value is null")
        void returnsDefaultWhenValueIsNull() {
            Map<String, Object> params = new HashMap<>();
            params.put("key", null);
            assertThat(template.exposeGetString(params, "key", "default")).isEqualTo("default");
        }
    }

    @Nested
    @DisplayName("getInt()")
    class GetIntTests {

        @Test
        @DisplayName("getInt returns integer value when key exists and value is a Number")
        void returnsIntValue() {
            Map<String, Object> params = Map.of("count", 7);
            assertThat(template.exposeGetInt(params, "count", 0)).isEqualTo(7);
        }

        @Test
        @DisplayName("getInt returns defaultValue when key is absent")
        void returnsDefaultWhenKeyAbsent() {
            Map<String, Object> params = Map.of();
            assertThat(template.exposeGetInt(params, "missing", 99)).isEqualTo(99);
        }

        @Test
        @DisplayName("getInt returns defaultValue when value is null")
        void returnsDefaultWhenValueIsNull() {
            Map<String, Object> params = new HashMap<>();
            params.put("count", null);
            assertThat(template.exposeGetInt(params, "count", 5)).isEqualTo(5);
        }

        @Test
        @DisplayName("getInt returns defaultValue when value is non-numeric type")
        void returnsDefaultForNonNumericType() {
            Map<String, Object> params = Map.of("count", "not-a-number");
            assertThat(template.exposeGetInt(params, "count", 3)).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("getBoolean()")
    class GetBooleanTests {

        @Test
        @DisplayName("getBoolean returns true when key maps to Boolean true")
        void returnsTrueValue() {
            Map<String, Object> params = Map.of("flag", true);
            assertThat(template.exposeGetBoolean(params, "flag", false)).isTrue();
        }

        @Test
        @DisplayName("getBoolean returns false when key maps to Boolean false")
        void returnsFalseValue() {
            Map<String, Object> params = Map.of("flag", false);
            assertThat(template.exposeGetBoolean(params, "flag", true)).isFalse();
        }

        @Test
        @DisplayName("getBoolean returns defaultValue when key is absent")
        void returnsDefaultWhenKeyAbsent() {
            Map<String, Object> params = Map.of();
            assertThat(template.exposeGetBoolean(params, "missing", true)).isTrue();
        }

        @Test
        @DisplayName("getBoolean returns defaultValue when value is null")
        void returnsDefaultWhenValueIsNull() {
            Map<String, Object> params = new HashMap<>();
            params.put("flag", null);
            assertThat(template.exposeGetBoolean(params, "flag", true)).isTrue();
        }

        @Test
        @DisplayName("getBoolean returns defaultValue when value is non-Boolean type")
        void returnsDefaultForNonBooleanType() {
            Map<String, Object> params = Map.of("flag", "true");
            assertThat(template.exposeGetBoolean(params, "flag", false)).isFalse();
        }
    }

    @Nested
    @DisplayName("validateParameters()")
    class ValidateParametersTests {

        @Test
        @DisplayName("validateParameters throws when required parameter is missing")
        void throwsForMissingRequiredParameter() {
            // Arrange - no "requiredParam" in map
            Map<String, Object> params = Map.of("optionalParam", 10);

            // Act & Assert
            assertThatThrownBy(() -> template.validateParameters(params))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("requiredParam")
                    .hasMessageContaining("test-template");
        }

        @Test
        @DisplayName("validateParameters throws when parameters map is null and required param exists")
        void throwsForNullParametersWhenRequiredExists() {
            assertThatThrownBy(() -> template.validateParameters(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("requiredParam");
        }

        @Test
        @DisplayName("validateParameters accepts valid parameters without throwing")
        void acceptsValidParameters() {
            // Arrange
            Map<String, Object> params = new HashMap<>();
            params.put("requiredParam", "valid-value");
            params.put("optionalParam", 5);

            // Act & Assert - no exception thrown
            template.validateParameters(params);
        }

        @Test
        @DisplayName("validateParameters accepts params with only required fields provided")
        void acceptsOnlyRequiredParams() {
            Map<String, Object> params = Map.of("requiredParam", "value");
            template.validateParameters(params);
        }

        @Test
        @DisplayName("validateParameters throws for wrong parameter type")
        void throwsForWrongParameterType() {
            // Arrange - optionalParam expects Integer but gets String
            Map<String, Object> params = new HashMap<>();
            params.put("requiredParam", "value");
            params.put("optionalParam", "not-an-integer");

            // Act & Assert
            assertThatThrownBy(() -> template.validateParameters(params))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("optionalParam");
        }
    }
}
