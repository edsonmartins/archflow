package br.com.archflow.conversation.form;

import br.com.archflow.conversation.form.FormData.FormField;
import br.com.archflow.conversation.form.FormData.FormOption;
import br.com.archflow.conversation.form.FormData.ValidationRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("FormData")
class FormDataTest {

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("should build form with required fields")
        void shouldBuildWithRequiredFields() {
            // Arrange & Act
            FormData form = FormData.builder()
                    .title("Test Form")
                    .addField(FormField.text("name", "Name").build())
                    .build();

            // Assert
            assertThat(form.getTitle()).isEqualTo("Test Form");
            assertThat(form.getFields()).hasSize(1);
            assertThat(form.getSubmitLabel()).isEqualTo("Submit");
        }

        @Test
        @DisplayName("should build form with all optional fields")
        void shouldBuildWithAllOptionalFields() {
            // Act
            FormData form = FormData.builder()
                    .id("form-1")
                    .title("Full Form")
                    .description("A complete form")
                    .addField(FormField.text("name", "Name").build())
                    .submitLabel("Send")
                    .cancelLabel("Back")
                    .metadata(Map.of("version", "1.0"))
                    .build();

            // Assert
            assertThat(form.getId()).isEqualTo("form-1");
            assertThat(form.getDescription()).isEqualTo("A complete form");
            assertThat(form.getSubmitLabel()).isEqualTo("Send");
            assertThat(form.getCancelLabel()).isEqualTo("Back");
            assertThat(form.getMetadata()).containsEntry("version", "1.0");
        }

        @Test
        @DisplayName("should throw when title is missing")
        void shouldThrowWhenTitleMissing() {
            assertThatThrownBy(() ->
                    FormData.builder()
                            .addField(FormField.text("name", "Name").build())
                            .build()
            ).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("title is required");
        }

        @Test
        @DisplayName("should throw when no fields are added")
        void shouldThrowWhenNoFields() {
            assertThatThrownBy(() ->
                    FormData.builder()
                            .title("Empty Form")
                            .build()
            ).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("at least one field is required");
        }

        @Test
        @DisplayName("should support adding multiple fields via list")
        void shouldSupportFieldsList() {
            // Arrange
            List<FormField> fields = List.of(
                    FormField.text("name", "Name").build(),
                    FormField.email("email", "Email").build()
            );

            // Act
            FormData form = FormData.builder()
                    .title("Multi Fields")
                    .fields(fields)
                    .build();

            // Assert
            assertThat(form.getFields()).hasSize(2);
        }

        @Test
        @DisplayName("fields list should be immutable")
        void fieldsListShouldBeImmutable() {
            FormData form = FormData.builder()
                    .title("Form")
                    .addField(FormField.text("name", "Name").build())
                    .build();

            assertThatThrownBy(() -> form.getFields().add(FormField.text("x", "X").build()))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("metadata should be immutable")
        void metadataShouldBeImmutable() {
            FormData form = FormData.builder()
                    .title("Form")
                    .addField(FormField.text("name", "Name").build())
                    .metadata(Map.of("key", "value"))
                    .build();

            assertThatThrownBy(() -> form.getMetadata().put("new", "entry"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("FormField Types")
    class FormFieldTypeTests {

        @Test
        @DisplayName("text field should have correct type")
        void textFieldType() {
            FormField field = FormField.text("name", "Name").build();
            assertThat(field.getType()).isEqualTo("text");
            assertThat(field.getName()).isEqualTo("name");
            assertThat(field.getLabel()).isEqualTo("Name");
        }

        @Test
        @DisplayName("email field should have correct type")
        void emailFieldType() {
            FormField field = FormField.email("email", "Email").build();
            assertThat(field.getType()).isEqualTo("email");
        }

        @Test
        @DisplayName("password field should have correct type")
        void passwordFieldType() {
            FormField field = FormField.password("pwd", "Password").build();
            assertThat(field.getType()).isEqualTo("password");
        }

        @Test
        @DisplayName("number field should have correct type")
        void numberFieldType() {
            FormField field = FormField.number("age", "Age").build();
            assertThat(field.getType()).isEqualTo("number");
        }

        @Test
        @DisplayName("textarea field should have correct type")
        void textareaFieldType() {
            FormField field = FormField.textarea("msg", "Message").build();
            assertThat(field.getType()).isEqualTo("textarea");
        }

        @Test
        @DisplayName("select field should have correct type and options")
        void selectFieldType() {
            FormField field = FormField.select("plan", "Plan", List.of("free", "pro")).build();
            assertThat(field.getType()).isEqualTo("select");
            assertThat(field.getOptions()).hasSize(2);
            assertThat(field.getOptions().get(0).value()).isEqualTo("free");
            assertThat(field.getOptions().get(0).label()).isEqualTo("free");
        }

        @Test
        @DisplayName("checkbox field should have correct type")
        void checkboxFieldType() {
            FormField field = FormField.checkbox("agree", "I agree").build();
            assertThat(field.getType()).isEqualTo("checkbox");
        }

        @Test
        @DisplayName("multiSelect field should have correct type and options")
        void multiSelectFieldType() {
            FormField field = FormField.multiSelect("tags", "Tags", List.of("a", "b", "c")).build();
            assertThat(field.getType()).isEqualTo("multi-select");
            assertThat(field.getOptions()).hasSize(3);
        }

        @Test
        @DisplayName("date field should have correct type")
        void dateFieldType() {
            FormField field = FormField.date("dob", "Date of Birth").build();
            assertThat(field.getType()).isEqualTo("date");
        }

        @Test
        @DisplayName("file field should have correct type")
        void fileFieldType() {
            FormField field = FormField.file("attachment", "Attachment").build();
            assertThat(field.getType()).isEqualTo("file");
        }

        @Test
        @DisplayName("field builder should set all optional properties")
        void fieldBuilderOptionalProperties() {
            FormField field = FormField.text("name", "Name")
                    .description("Enter your name")
                    .required(true)
                    .defaultValue("John")
                    .placeholder("Your name here")
                    .metadata(Map.of("hint", "first and last"))
                    .build();

            assertThat(field.getDescription()).isEqualTo("Enter your name");
            assertThat(field.isRequired()).isTrue();
            assertThat(field.getDefaultValue()).isEqualTo("John");
            assertThat(field.getPlaceholder()).isEqualTo("Your name here");
            assertThat(field.getMetadata()).containsEntry("hint", "first and last");
        }

        @Test
        @DisplayName("required should default to false")
        void requiredDefaultsFalse() {
            FormField field = FormField.text("name", "Name").build();
            assertThat(field.isRequired()).isFalse();
        }
    }

    @Nested
    @DisplayName("FormOption")
    class FormOptionTests {

        @Test
        @DisplayName("should create option with value and label")
        void shouldCreateWithValueAndLabel() {
            FormOption option = new FormOption("val", "Label");
            assertThat(option.value()).isEqualTo("val");
            assertThat(option.label()).isEqualTo("Label");
            assertThat(option.disabled()).isFalse();
        }

        @Test
        @DisplayName("should create disabled option")
        void shouldCreateDisabledOption() {
            FormOption option = new FormOption("val", "Label", true);
            assertThat(option.disabled()).isTrue();
        }
    }

    @Nested
    @DisplayName("ValidationRule")
    class ValidationRuleTests {

        @Test
        @DisplayName("length rule should set minLength and maxLength")
        void lengthRuleShouldSetCorrectFields() {
            ValidationRule rule = ValidationRule.length(3, 50, "Must be 3-50 chars");

            assertThat(rule.minLength()).isEqualTo(3);
            assertThat(rule.maxLength()).isEqualTo(50);
            assertThat(rule.min()).isNull();
            assertThat(rule.max()).isNull();
            assertThat(rule.pattern()).isNull();
            assertThat(rule.message()).isEqualTo("Must be 3-50 chars");
        }

        @Test
        @DisplayName("range rule should set min and max")
        void rangeRuleShouldSetCorrectFields() {
            ValidationRule rule = ValidationRule.range(1.0, 100.0, "Must be 1-100");

            assertThat(rule.min()).isEqualTo(1.0);
            assertThat(rule.max()).isEqualTo(100.0);
            assertThat(rule.minLength()).isNull();
            assertThat(rule.maxLength()).isNull();
            assertThat(rule.pattern()).isNull();
            assertThat(rule.message()).isEqualTo("Must be 1-100");
        }

        @Test
        @DisplayName("pattern rule should set regex pattern")
        void patternRuleShouldSetCorrectFields() {
            ValidationRule rule = ValidationRule.pattern("^\\d+$", "Must be digits");

            assertThat(rule.pattern()).isEqualTo("^\\d+$");
            assertThat(rule.minLength()).isNull();
            assertThat(rule.min()).isNull();
            assertThat(rule.message()).isEqualTo("Must be digits");
        }
    }

    @Nested
    @DisplayName("Templates")
    class TemplateTests {

        @Test
        @DisplayName("userRegistration template should have expected fields")
        void userRegistrationTemplate() {
            FormData form = FormData.Templates.userRegistration();

            assertThat(form.getId()).isEqualTo("user-registration");
            assertThat(form.getTitle()).isEqualTo("Create Account");
            assertThat(form.getDescription()).isNotNull();
            assertThat(form.getSubmitLabel()).isEqualTo("Create Account");
            assertThat(form.getFields()).hasSize(4);

            assertThat(form.getFields().get(0).getName()).isEqualTo("name");
            assertThat(form.getFields().get(0).getType()).isEqualTo("text");
            assertThat(form.getFields().get(0).isRequired()).isTrue();

            assertThat(form.getFields().get(1).getName()).isEqualTo("email");
            assertThat(form.getFields().get(1).getType()).isEqualTo("email");

            assertThat(form.getFields().get(2).getName()).isEqualTo("password");
            assertThat(form.getFields().get(2).getType()).isEqualTo("password");
            assertThat(form.getFields().get(2).getValidation()).isNotNull();
            assertThat(form.getFields().get(2).getValidation().minLength()).isEqualTo(8);

            assertThat(form.getFields().get(3).getName()).isEqualTo("terms");
            assertThat(form.getFields().get(3).getType()).isEqualTo("checkbox");
        }

        @Test
        @DisplayName("customerSupport template should have expected fields")
        void customerSupportTemplate() {
            FormData form = FormData.Templates.customerSupport();

            assertThat(form.getId()).isEqualTo("support-ticket");
            assertThat(form.getTitle()).isEqualTo("Contact Support");
            assertThat(form.getSubmitLabel()).isEqualTo("Submit Ticket");
            assertThat(form.getFields()).hasSize(4);

            assertThat(form.getFields().get(0).getName()).isEqualTo("category");
            assertThat(form.getFields().get(0).getType()).isEqualTo("select");
            assertThat(form.getFields().get(0).getOptions()).hasSize(4);

            assertThat(form.getFields().get(1).getName()).isEqualTo("subject");
            assertThat(form.getFields().get(2).getName()).isEqualTo("message");
            assertThat(form.getFields().get(2).getType()).isEqualTo("textarea");
            assertThat(form.getFields().get(3).getName()).isEqualTo("email");
        }

        @Test
        @DisplayName("payment template should have expected fields")
        void paymentTemplate() {
            FormData form = FormData.Templates.payment();

            assertThat(form.getId()).isEqualTo("payment");
            assertThat(form.getTitle()).isEqualTo("Payment Information");
            assertThat(form.getSubmitLabel()).isEqualTo("Pay Now");
            assertThat(form.getFields()).hasSize(3);

            assertThat(form.getFields().get(0).getName()).isEqualTo("cardNumber");
            assertThat(form.getFields().get(0).getValidation()).isNotNull();
            assertThat(form.getFields().get(0).getValidation().pattern()).isNotNull();

            assertThat(form.getFields().get(1).getName()).isEqualTo("expiry");

            assertThat(form.getFields().get(2).getName()).isEqualTo("cvv");
            assertThat(form.getFields().get(2).getType()).isEqualTo("number");
            assertThat(form.getFields().get(2).getValidation()).isNotNull();
            assertThat(form.getFields().get(2).getValidation().min()).isEqualTo(100.0);
            assertThat(form.getFields().get(2).getValidation().max()).isEqualTo(9999.0);
        }
    }
}
