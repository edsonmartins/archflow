package br.com.archflow.template;

import br.com.archflow.template.WorkflowTemplate.ParameterDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WorkflowTemplate.ParameterDefinition}.
 */
@DisplayName("ParameterDefinition")
class ParameterDefinitionTest {

    @Nested
    @DisplayName("required() factory method")
    class RequiredFactoryMethod {

        @Test
        @DisplayName("should create a required parameter with correct name")
        void shouldCreateWithCorrectName() {
            ParameterDefinition param = ParameterDefinition.required("apiKey", "The API key", String.class);

            assertThat(param.name()).isEqualTo("apiKey");
        }

        @Test
        @DisplayName("should create a required parameter with correct description")
        void shouldCreateWithCorrectDescription() {
            ParameterDefinition param = ParameterDefinition.required("apiKey", "The API key", String.class);

            assertThat(param.description()).isEqualTo("The API key");
        }

        @Test
        @DisplayName("should create a required parameter with correct type")
        void shouldCreateWithCorrectType() {
            ParameterDefinition param = ParameterDefinition.required("apiKey", "The API key", String.class);

            assertThat(param.type()).isEqualTo(String.class);
        }

        @Test
        @DisplayName("should set required flag to true")
        void shouldBeRequired() {
            ParameterDefinition param = ParameterDefinition.required("apiKey", "The API key", String.class);

            assertThat(param.required()).isTrue();
        }

        @Test
        @DisplayName("should have null default value")
        void shouldHaveNullDefaultValue() {
            ParameterDefinition param = ParameterDefinition.required("apiKey", "The API key", String.class);

            assertThat(param.defaultValue()).isNull();
        }

        @Test
        @DisplayName("should have null options")
        void shouldHaveNullOptions() {
            ParameterDefinition param = ParameterDefinition.required("apiKey", "The API key", String.class);

            assertThat(param.options()).isNull();
        }

        @Test
        @DisplayName("should work with Integer type")
        void shouldWorkWithIntegerType() {
            ParameterDefinition param = ParameterDefinition.required("count", "Count", Integer.class);

            assertThat(param.type()).isEqualTo(Integer.class);
            assertThat(param.required()).isTrue();
        }

        @Test
        @DisplayName("should work with List type")
        void shouldWorkWithListType() {
            ParameterDefinition param = ParameterDefinition.required("items", "Item list", java.util.List.class);

            assertThat(param.type()).isEqualTo(java.util.List.class);
            assertThat(param.required()).isTrue();
        }
    }

    @Nested
    @DisplayName("optional() factory method")
    class OptionalFactoryMethod {

        @Test
        @DisplayName("should create an optional parameter with correct name and description")
        void shouldCreateWithCorrectNameAndDescription() {
            ParameterDefinition param = ParameterDefinition.optional("timeout", "Timeout in seconds", Integer.class, 30);

            assertThat(param.name()).isEqualTo("timeout");
            assertThat(param.description()).isEqualTo("Timeout in seconds");
        }

        @Test
        @DisplayName("should set required flag to false")
        void shouldNotBeRequired() {
            ParameterDefinition param = ParameterDefinition.optional("timeout", "Timeout", Integer.class, 30);

            assertThat(param.required()).isFalse();
        }

        @Test
        @DisplayName("should store the default value")
        void shouldStoreDefaultValue() {
            ParameterDefinition param = ParameterDefinition.optional("timeout", "Timeout", Integer.class, 30);

            assertThat(param.defaultValue()).isEqualTo(30);
        }

        @Test
        @DisplayName("should have null options")
        void shouldHaveNullOptions() {
            ParameterDefinition param = ParameterDefinition.optional("timeout", "Timeout", Integer.class, 30);

            assertThat(param.options()).isNull();
        }

        @Test
        @DisplayName("should store String default value")
        void shouldStoreStringDefault() {
            ParameterDefinition param = ParameterDefinition.optional("host", "Hostname", String.class, "localhost");

            assertThat(param.defaultValue()).isEqualTo("localhost");
            assertThat(param.type()).isEqualTo(String.class);
        }

        @Test
        @DisplayName("should store Boolean default value")
        void shouldStoreBooleanDefault() {
            ParameterDefinition param = ParameterDefinition.optional("verbose", "Verbose mode", Boolean.class, true);

            assertThat(param.defaultValue()).isEqualTo(true);
            assertThat(param.type()).isEqualTo(Boolean.class);
        }

        @Test
        @DisplayName("should allow null default value")
        void shouldAllowNullDefault() {
            ParameterDefinition param = ParameterDefinition.optional("label", "Label", String.class, null);

            assertThat(param.defaultValue()).isNull();
            assertThat(param.required()).isFalse();
        }
    }

    @Nested
    @DisplayName("enumParameter() factory method")
    class EnumParameterFactoryMethod {

        @Test
        @DisplayName("should create enum parameter with String type")
        void shouldHaveStringType() {
            ParameterDefinition param = ParameterDefinition.enumParameter("mode", "Mode", "fast", "slow", "balanced");

            assertThat(param.type()).isEqualTo(String.class);
        }

        @Test
        @DisplayName("should set required flag to true")
        void shouldBeRequired() {
            ParameterDefinition param = ParameterDefinition.enumParameter("mode", "Mode", "fast", "slow");

            assertThat(param.required()).isTrue();
        }

        @Test
        @DisplayName("should use first option as default value")
        void shouldUseFirstOptionAsDefault() {
            ParameterDefinition param = ParameterDefinition.enumParameter("mode", "Mode", "fast", "slow", "balanced");

            assertThat(param.defaultValue()).isEqualTo("fast");
        }

        @Test
        @DisplayName("should store all options")
        void shouldStoreAllOptions() {
            ParameterDefinition param = ParameterDefinition.enumParameter("mode", "Mode", "fast", "slow", "balanced");

            assertThat(param.options()).containsExactly("fast", "slow", "balanced");
        }

        @Test
        @DisplayName("should work with single option")
        void shouldWorkWithSingleOption() {
            ParameterDefinition param = ParameterDefinition.enumParameter("mode", "Mode", "only");

            assertThat(param.options()).containsExactly("only");
            assertThat(param.defaultValue()).isEqualTo("only");
        }
    }

    @Nested
    @DisplayName("Record equality and identity")
    class RecordEquality {

        @Test
        @DisplayName("should be equal when all fields match")
        void shouldBeEqualWhenFieldsMatch() {
            ParameterDefinition param1 = ParameterDefinition.required("key", "desc", String.class);
            ParameterDefinition param2 = ParameterDefinition.required("key", "desc", String.class);

            assertThat(param1).isEqualTo(param2);
        }

        @Test
        @DisplayName("should have same hashCode when equal")
        void shouldHaveSameHashCodeWhenEqual() {
            ParameterDefinition param1 = ParameterDefinition.required("key", "desc", String.class);
            ParameterDefinition param2 = ParameterDefinition.required("key", "desc", String.class);

            assertThat(param1.hashCode()).isEqualTo(param2.hashCode());
        }

        @Test
        @DisplayName("should not be equal when name differs")
        void shouldNotBeEqualWhenNameDiffers() {
            ParameterDefinition param1 = ParameterDefinition.required("key1", "desc", String.class);
            ParameterDefinition param2 = ParameterDefinition.required("key2", "desc", String.class);

            assertThat(param1).isNotEqualTo(param2);
        }

        @Test
        @DisplayName("should not be equal when type differs")
        void shouldNotBeEqualWhenTypeDiffers() {
            ParameterDefinition param1 = ParameterDefinition.required("key", "desc", String.class);
            ParameterDefinition param2 = ParameterDefinition.required("key", "desc", Integer.class);

            assertThat(param1).isNotEqualTo(param2);
        }

        @Test
        @DisplayName("should not be equal when required flag differs")
        void shouldNotBeEqualWhenRequiredDiffers() {
            ParameterDefinition param1 = ParameterDefinition.required("key", "desc", String.class);
            ParameterDefinition param2 = ParameterDefinition.optional("key", "desc", String.class, null);

            assertThat(param1).isNotEqualTo(param2);
        }

        @Test
        @DisplayName("should have meaningful toString")
        void shouldHaveMeaningfulToString() {
            ParameterDefinition param = ParameterDefinition.required("apiKey", "The API key", String.class);

            assertThat(param.toString()).contains("apiKey");
            assertThat(param.toString()).contains("The API key");
        }
    }
}
