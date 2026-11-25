package br.com.archflow.conversation.form;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Formulários que o {@code formId} de uma suspensão resolve por padrão (RCHFLW-67).
 *
 * <p>O envio vira "Form submitted: campo=valor" no histórico da conversa, e esse histórico vai no
 * prompt do agente: um formulário que pede senha ou cartão não pode estar disponível por padrão.
 */
@DisplayName("InMemoryFormRegistry")
class InMemoryFormRegistryTest {

    @Test
    @DisplayName("por padrão só resolve formulários sem segredo: senha e cartão ficam de fora")
    void padraoSemSegredos() {
        InMemoryFormRegistry registry = InMemoryFormRegistry.withTemplates();

        assertThat(registry.ids()).containsExactly("support-ticket");
        assertThat(registry.find("user-registration")).isEmpty();
        assertThat(registry.find("payment")).isEmpty();
        assertThatThrownBy(() -> registry.require("payment"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payment");
    }

    @Test
    @DisplayName("quem precisa de outro formulário o registra explicitamente")
    void registroExplicito() {
        InMemoryFormRegistry registry = InMemoryFormRegistry.withTemplates()
                .register(FormData.Templates.payment());

        assertThat(registry.find("payment")).isPresent();
    }
}
