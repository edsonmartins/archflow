package br.com.archflow.conversation.form;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Form definition for user interaction during suspended conversations.
 *
 * <p>When an AI workflow needs human input, it can present a form
 * that the user fills out to resume the conversation.</p>
 *
 * <p>Example usage:
 * <pre>{@code
 * FormData form = FormData.builder()
 *     .title("Create Account")
 *     .description("Please provide the following information")
 *     .addField(FormField.text("name", "Full Name").required(true))
 *     .addField(FormField.email("email", "Email Address").required(true))
 *     .addField(FormField.select("plan", "Plan", List.of("free", "pro", "enterprise")))
 *     .build();
 * }</pre>
 *
 * @see FormField
 * @see FormOption
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FormData {

    private final String id;
    private final String title;
    private final String description;
    private final List<FormField> fields;
    private final String submitLabel;
    private final String cancelLabel;
    private final Map<String, Object> metadata;

    private FormData(Builder builder) {
        this.id = builder.id;
        this.title = builder.title;
        this.description = builder.description;
        this.fields = List.copyOf(builder.fields);
        this.submitLabel = builder.submitLabel != null ? builder.submitLabel : "Submit";
        this.cancelLabel = builder.cancelLabel;
        this.metadata = builder.metadata != null ? Map.copyOf(builder.metadata) : null;
    }

    /**
     * Creates a new builder for constructing forms.
     */
    public static Builder builder() {
        return new Builder();
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public List<FormField> getFields() {
        return fields;
    }

    public String getSubmitLabel() {
        return submitLabel;
    }

    public String getCancelLabel() {
        return cancelLabel;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * Builder for constructing FormData instances.
     */
    public static class Builder {
        private String id;
        private String title;
        private String description;
        private final List<FormField> fields = new ArrayList<>();
        private String submitLabel;
        private String cancelLabel;
        private Map<String, Object> metadata;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder addField(FormField field) {
            this.fields.add(field);
            return this;
        }

        public Builder fields(List<FormField> fields) {
            this.fields.addAll(fields);
            return this;
        }

        public Builder submitLabel(String submitLabel) {
            this.submitLabel = submitLabel;
            return this;
        }

        public Builder cancelLabel(String cancelLabel) {
            this.cancelLabel = cancelLabel;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public FormData build() {
            if (title == null) {
                throw new IllegalStateException("title is required");
            }
            if (fields.isEmpty()) {
                throw new IllegalStateException("at least one field is required");
            }
            return new FormData(this);
        }
    }

    /**
     * A single field in a form.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FormField {

        private final String name;
        private final String type;
        private final String label;
        private final String description;
        private final boolean required;
        private final Object defaultValue;
        private final List<FormOption> options;
        private final ValidationRule validation;
        private final String placeholder;
        private final Map<String, Object> metadata;

        private FormField(Builder builder) {
            this.name = builder.name;
            this.type = builder.type;
            this.label = builder.label;
            this.description = builder.description;
            this.required = builder.required;
            this.defaultValue = builder.defaultValue;
            this.options = builder.options != null ? List.copyOf(builder.options) : null;
            this.validation = builder.validation;
            this.placeholder = builder.placeholder;
            this.metadata = builder.metadata != null ? Map.copyOf(builder.metadata) : null;
        }

        /**
         * Creates a text input field.
         */
        public static Builder text(String name, String label) {
            return new Builder(name, "text", label);
        }

        /**
         * Creates an email input field.
         */
        public static Builder email(String name, String label) {
            return new Builder(name, "email", label);
        }

        /**
         * Creates a password input field.
         */
        public static Builder password(String name, String label) {
            return new Builder(name, "password", label);
        }

        /**
         * Creates a number input field.
         */
        public static Builder number(String name, String label) {
            return new Builder(name, "number", label);
        }

        /**
         * Creates a textarea field.
         */
        public static Builder textarea(String name, String label) {
            return new Builder(name, "textarea", label);
        }

        /**
         * Creates a select dropdown field.
         */
        public static Builder select(String name, String label, List<String> values) {
            List<FormOption> options = values.stream()
                    .map(v -> new FormOption(v, v))
                    .toList();
            return new Builder(name, "select", label).options(options);
        }

        /**
         * Creates a checkbox field.
         */
        public static Builder checkbox(String name, String label) {
            return new Builder(name, "checkbox", label);
        }

        /**
         * Creates a multi-select field.
         */
        public static Builder multiSelect(String name, String label, List<String> values) {
            List<FormOption> options = values.stream()
                    .map(v -> new FormOption(v, v))
                    .toList();
            return new Builder(name, "multi-select", label).options(options);
        }

        /**
         * Creates a date field.
         */
        public static Builder date(String name, String label) {
            return new Builder(name, "date", label);
        }

        /**
         * Creates a file upload field.
         */
        public static Builder file(String name, String label) {
            return new Builder(name, "file", label);
        }

        public String getName() {
            return name;
        }

        public String getType() {
            return type;
        }

        public String getLabel() {
            return label;
        }

        public String getDescription() {
            return description;
        }

        public boolean isRequired() {
            return required;
        }

        public Object getDefaultValue() {
            return defaultValue;
        }

        public List<FormOption> getOptions() {
            return options;
        }

        public ValidationRule getValidation() {
            return validation;
        }

        public String getPlaceholder() {
            return placeholder;
        }

        public Map<String, Object> getMetadata() {
            return metadata;
        }

        /**
         * Builder for FormField.
         */
        public static class Builder {
            private final String name;
            private final String type;
            private final String label;
            private String description;
            private boolean required;
            private Object defaultValue;
            private List<FormOption> options;
            private ValidationRule validation;
            private String placeholder;
            private Map<String, Object> metadata;

            private Builder(String name, String type, String label) {
                this.name = name;
                this.type = type;
                this.label = label;
            }

            public Builder description(String description) {
                this.description = description;
                return this;
            }

            public Builder required(boolean required) {
                this.required = required;
                return this;
            }

            public Builder defaultValue(Object defaultValue) {
                this.defaultValue = defaultValue;
                return this;
            }

            public Builder options(List<FormOption> options) {
                this.options = options;
                return this;
            }

            public Builder validation(ValidationRule validation) {
                this.validation = validation;
                return this;
            }

            public Builder placeholder(String placeholder) {
                this.placeholder = placeholder;
                return this;
            }

            public Builder metadata(Map<String, Object> metadata) {
                this.metadata = metadata;
                return this;
            }

            public FormField build() {
                return new FormField(this);
            }
        }
    }

    /**
     * An option for select/multi-select fields.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FormOption(
            @JsonProperty("value") String value,
            @JsonProperty("label") String label,
            @JsonProperty("disabled") boolean disabled
    ) {
        public FormOption(String value, String label) {
            this(value, label, false);
        }
    }

    /**
     * Validation rules for a field.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ValidationRule(
            @JsonProperty("minLength") Integer minLength,
            @JsonProperty("maxLength") Integer maxLength,
            @JsonProperty("min") Double min,
            @JsonProperty("max") Double max,
            @JsonProperty("pattern") String pattern,
            @JsonProperty("message") String message
    ) {
        /**
         * Creates a length validation rule.
         */
        public static ValidationRule length(int min, int max, String message) {
            return new ValidationRule(min, max, null, null, null, message);
        }

        /**
         * Creates a range validation rule for numbers.
         */
        public static ValidationRule range(double min, double max, String message) {
            return new ValidationRule(null, null, min, max, null, message);
        }

        /**
         * Creates a pattern (regex) validation rule.
         */
        public static ValidationRule pattern(String regex, String message) {
            return new ValidationRule(null, null, null, null, regex, message);
        }
    }

    /**
     * Pre-defined form templates for common use cases.
     */
    public static class Templates {

        /**
         * Creates a user registration form.
         */
        public static FormData userRegistration() {
            return FormData.builder()
                    .id("user-registration")
                    .title("Create Account")
                    .description("Please provide your information to create an account")
                    .addField(FormField.text("name", "Full Name")
                            .required(true)
                            .placeholder("Enter your full name")
                            .build())
                    .addField(FormField.email("email", "Email Address")
                            .required(true)
                            .placeholder("you@example.com")
                            .build())
                    .addField(FormField.password("password", "Password")
                            .required(true)
                            .validation(ValidationRule.length(8, 128, "Password must be 8-128 characters"))
                            .build())
                    .addField(FormField.checkbox("terms", "I agree to the terms and conditions")
                            .required(true)
                            .build())
                    .submitLabel("Create Account")
                    .build();
        }

        /**
         * Creates a customer support form.
         */
        public static FormData customerSupport() {
            return FormData.builder()
                    .id("support-ticket")
                    .title("Contact Support")
                    .description("How can we help you today?")
                    .addField(FormField.select("category", "Category",
                                    List.of("Billing", "Technical", "Sales", "Other"))
                            .required(true)
                            .build())
                    .addField(FormField.text("subject", "Subject")
                            .required(true)
                            .placeholder("Brief description of your issue")
                            .build())
                    .addField(FormField.textarea("message", "Message")
                            .required(true)
                            .placeholder("Please provide details...")
                            .build())
                    .addField(FormField.email("email", "Email")
                            .required(true)
                            .description("We'll reply to this address")
                            .build())
                    .submitLabel("Submit Ticket")
                    .build();
        }

        /**
         * Creates a payment form.
         */
        public static FormData payment() {
            return FormData.builder()
                    .id("payment")
                    .title("Payment Information")
                    .description("Enter your payment details")
                    .addField(FormField.text("cardNumber", "Card Number")
                            .required(true)
                            .placeholder("1234 5678 9012 3456")
                            .validation(ValidationRule.pattern("^\\d{16}$", "Invalid card number"))
                            .build())
                    .addField(FormField.text("expiry", "Expiry Date")
                            .required(true)
                            .placeholder("MM/YY")
                            .build())
                    .addField(FormField.number("cvv", "CVV")
                            .required(true)
                            .validation(ValidationRule.range(100, 9999, "Invalid CVV"))
                            .build())
                    .submitLabel("Pay Now")
                    .build();
        }
    }
}
