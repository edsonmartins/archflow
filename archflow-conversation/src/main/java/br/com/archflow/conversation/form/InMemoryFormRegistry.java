package br.com.archflow.conversation.form;

import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link FormRegistry} em memória, indexado pelo {@link FormData#getId()} de cada formulário.
 *
 * <p>{@link #withTemplates()} traz, dos formulários que o módulo já define em {@link FormData.Templates},
 * os que não pedem segredo. Não há cadastro de formulários pela API ou pela tela: quem precisa de outro
 * registra o seu ({@link #register}) ou fornece outro {@link FormRegistry}.
 */
public class InMemoryFormRegistry implements FormRegistry {

    private final ConcurrentHashMap<String, FormData> byId = new ConcurrentHashMap<>();

    /**
     * Registro com o formulário de suporte de {@link FormData.Templates}.
     *
     * <p>{@code user-registration} (senha) e {@code payment} (cartão e CVV) ficam de fora de propósito:
     * o envio vira a fala "Form submitted: campo=valor" gravada no histórico da conversa, e sem
     * execução associada esse histórico vai no prompt do agente. Nada nesse caminho separa um campo
     * secreto, então oferecê-los por padrão gravaria e mandaria ao provider senha e cartão em texto.
     */
    public static InMemoryFormRegistry withTemplates() {
        InMemoryFormRegistry registry = new InMemoryFormRegistry();
        registry.register(FormData.Templates.customerSupport());
        return registry;
    }

    /**
     * Registra o formulário pelo id dele. Um formulário sem id não teria como ser pedido, e dois com o
     * mesmo id fariam o mesmo {@code formId} abrir formulários diferentes conforme a ordem de carga.
     */
    public InMemoryFormRegistry register(FormData form) {
        Objects.requireNonNull(form, "form");
        String id = form.getId();
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Formulário sem id não pode ser registrado");
        }
        if (byId.putIfAbsent(id, form) != null) {
            throw new IllegalStateException("Já existe um formulário registrado com o id '" + id + "'");
        }
        return this;
    }

    @Override
    public Optional<FormData> find(String formId) {
        return formId == null ? Optional.empty() : Optional.ofNullable(byId.get(formId));
    }

    @Override
    public Set<String> ids() {
        return Collections.unmodifiableSet(new TreeSet<>(byId.keySet()));
    }
}
