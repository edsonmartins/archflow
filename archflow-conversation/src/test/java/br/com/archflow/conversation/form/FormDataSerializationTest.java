package br.com.archflow.conversation.form;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Garante que {@link FormData} (e aninhados) faz round-trip em JSON — pré-requisito
 * para persistir conversas suspensas de forma durável.
 */
@DisplayName("FormData — round-trip JSON")
class FormDataSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("template de registro sobrevive a serialize→deserialize")
    void userRegistrationRoundTrip() throws Exception {
        FormData original = FormData.Templates.userRegistration();

        String json = mapper.writeValueAsString(original);
        FormData back = mapper.readValue(json, FormData.class);

        assertThat(back.getId()).isEqualTo("user-registration");
        assertThat(back.getTitle()).isEqualTo(original.getTitle());
        assertThat(back.getSubmitLabel()).isEqualTo("Create Account");
        assertThat(back.getFields()).hasSize(original.getFields().size());
        // valida um campo com validação
        FormData.FormField password = back.getFields().stream()
                .filter(f -> f.getName().equals("password")).findFirst().orElseThrow();
        assertThat(password.getType()).isEqualTo("password");
        assertThat(password.isRequired()).isTrue();
        assertThat(password.getValidation()).isNotNull();
        assertThat(password.getValidation().minLength()).isEqualTo(8);
        assertThat(password.getValidation().maxLength()).isEqualTo(128);
    }

    @Test
    @DisplayName("campos com options, defaultValue e metadata round-trip")
    void richFieldsRoundTrip() throws Exception {
        FormData original = FormData.builder()
                .id("rich")
                .title("Rich Form")
                .addField(FormData.FormField.select("plan", "Plan",
                                List.of("free", "pro", "enterprise"))
                        .required(true)
                        .defaultValue("free")
                        .metadata(Map.of("group", "billing"))
                        .build())
                .addField(FormData.FormField.number("age", "Age")
                        .validation(FormData.ValidationRule.range(0, 120, "0-120"))
                        .build())
                .build();

        String json = mapper.writeValueAsString(original);
        FormData back = mapper.readValue(json, FormData.class);

        FormData.FormField plan = back.getFields().get(0);
        assertThat(plan.getType()).isEqualTo("select");
        assertThat(plan.getOptions()).extracting(FormData.FormOption::value)
                .containsExactly("free", "pro", "enterprise");
        assertThat(plan.getDefaultValue()).isEqualTo("free");
        assertThat(plan.getMetadata()).containsEntry("group", "billing");

        FormData.FormField age = back.getFields().get(1);
        assertThat(age.getValidation().min()).isEqualTo(0.0);
        assertThat(age.getValidation().max()).isEqualTo(120.0);
    }
}
