package br.com.archflow.plugin.api.catalog;

import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decide quais componentes do catálogo um fluxo (ou um agente) pode resolver.
 *
 * <p>O {@link ComponentCatalog} é um mapa plano por processo: qualquer nó de
 * workflow que nomeie um {@code componentId} alcança o componente
 * correspondente. Isso vale para o designer, onde a liberdade é o ponto, e não
 * vale para um fluxo que precisa garantir que jamais tocará um componente de
 * escrita — nem por engano de configuração.
 *
 * <p>Combine com {@link ScopedComponentCatalog} para aplicar a decisão no ponto
 * de resolução, em vez de espalhar checagens pelos chamadores.
 *
 * @see br.com.archflow.plugin.api.catalog.ScopedComponentCatalog
 */
@FunctionalInterface
public interface ComponentAccessPolicy {

    boolean isAllowed(String componentId);

    /**
     * Por que {@code componentId} foi negado, quando a política sabe dizer.
     *
     * <p>Existe para o caso em que a negação não é do componente, e sim do fluxo inteiro: com
     * {@code archflow.components.require-allowlist=true}, um fluxo sem lista nega tudo, e o passo
     * falhava com um "component not found: decision" seco — o motivo só ia para o log do servidor
     * (RCHFLW-105). Vazio = sem explicação além da negação.</p>
     */
    default Optional<String> denialReason(String componentId) {
        return Optional.empty();
    }

    /**
     * Instância única do "sem restrição". É singleton de propósito: permite que
     * {@link ScopedComponentCatalog#of} reconheça o caso irrestrito por
     * identidade e devolva o catálogo original sem envolvê-lo.
     */
    ComponentAccessPolicy ALLOW_ALL = componentId -> true;

    /**
     * Sem restrição — o comportamento histórico do catálogo global. É o default
     * de fluxos que não declaram allowlist, para não quebrar o que já existe.
     */
    static ComponentAccessPolicy allowAll() {
        return ALLOW_ALL;
    }

    /**
     * Nega tudo, dizendo por quê. É a política de um fluxo que não declarou a lista num servidor que
     * a exige: a falha do passo carrega {@code reason}, em vez de parecer um componente inexistente.
     */
    static ComponentAccessPolicy denyAll(String reason) {
        Objects.requireNonNull(reason, "reason");
        return new ComponentAccessPolicy() {
            @Override
            public boolean isAllowed(String componentId) {
                return false;
            }

            @Override
            public Optional<String> denialReason(String componentId) {
                return Optional.of(reason);
            }
        };
    }

    /** Allowlist fechada, case-insensitive. Lista vazia bloqueia tudo. */
    static ComponentAccessPolicy allowOnly(Collection<String> componentIds) {
        Set<String> allowed = componentIds == null ? Set.of()
                : componentIds.stream()
                        .filter(id -> id != null && !id.isBlank())
                        .map(id -> id.trim().toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet());
        return componentId -> componentId != null
                && allowed.contains(componentId.trim().toLowerCase(Locale.ROOT));
    }
}
