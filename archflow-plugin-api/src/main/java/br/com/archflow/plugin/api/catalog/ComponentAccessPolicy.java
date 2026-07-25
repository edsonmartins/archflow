package br.com.archflow.plugin.api.catalog;

import java.util.Collection;
import java.util.Locale;
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
