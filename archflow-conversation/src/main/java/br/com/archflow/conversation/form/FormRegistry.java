package br.com.archflow.conversation.form;

import java.util.Optional;
import java.util.Set;

/**
 * Formulários que uma conversa suspensa pode apresentar, pelo {@code formId}.
 *
 * <p>Existe porque o {@code POST /api/conversations/suspend} recebe só o {@code formId}, e não havia
 * onde resolvê-lo: o id era descartado, a conversa ficava SUSPENDED com {@code form=null}, o chat não
 * tinha o que mostrar e o campo de mensagem continuava liberado (RCHFLW-67).
 *
 * <p>Um id desconhecido é erro de quem chama ({@link #require}), nunca um formulário vazio: uma
 * suspensão sem formulário deixa a conversa parada sem nenhum caminho de volta pela tela.
 */
public interface FormRegistry {

    /** O formulário registrado com esse id, ou vazio. */
    Optional<FormData> find(String formId);

    /** Ids registrados — a mensagem de erro os lista, para quem errou o id saber o que existe. */
    Set<String> ids();

    /**
     * O formulário do id, ou {@link IllegalArgumentException} com uma mensagem que diz o que falta:
     * o id (quando não veio) ou os ids registrados (quando não existe). O servidor a traduz em 400.
     */
    default FormData require(String formId) {
        if (formId == null || formId.isBlank()) {
            throw new IllegalArgumentException("formId é obrigatório para suspender uma conversa: é o "
                    + "formulário que o chat mostra e cujo envio a retoma. Registrados: " + ids());
        }
        return find(formId).orElseThrow(() -> new IllegalArgumentException(
                "Formulário desconhecido: '" + formId + "'. Registrados: " + ids()));
    }
}
