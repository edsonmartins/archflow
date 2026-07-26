package br.com.archflow.plugin.api.catalog;

import br.com.archflow.model.ai.AIComponent;
import br.com.archflow.model.ai.metadata.ComponentMetadata;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Vista somente-leitura de um {@link ComponentCatalog} restrita por uma
 * {@link ComponentAccessPolicy}.
 *
 * <p>Aplica a restrição no <b>ponto de resolução</b>, e não em cada chamador.
 * Isso importa porque há mais de um caminho até o catálogo — o step de workflow,
 * o roteador semântico e o worker de orquestração multi-agente. Uma checagem
 * espalhada por eles vira uma checagem esquecida em um deles; um decorador que
 * simplesmente não devolve o componente não tem como ser esquecido.
 *
 * <p>Os métodos de listagem/busca também filtram: um componente que o fluxo não
 * pode invocar não deve nem aparecer para o roteador, senão ele rota para algo
 * que vai falhar depois — e com mensagem confusa.
 *
 * <p>Escrever através de uma vista restrita não faz sentido (registraria no
 * catálogo de baixo, fora do escopo), então {@code register}/{@code unregister}
 * falham alto em vez de enganar quem chamou.
 */
public final class ScopedComponentCatalog implements ComponentCatalog {

    private final ComponentCatalog delegate;
    private final ComponentAccessPolicy policy;

    public ScopedComponentCatalog(ComponentCatalog delegate, ComponentAccessPolicy policy) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /**
     * Devolve o próprio catálogo quando a política não restringe nada — evita
     * uma camada de indireção inútil no caminho comum.
     */
    public static ComponentCatalog of(ComponentCatalog delegate, ComponentAccessPolicy policy) {
        if (delegate == null || policy == null || policy == ComponentAccessPolicy.ALLOW_ALL) {
            return delegate;
        }
        return new ScopedComponentCatalog(delegate, policy);
    }

    @Override
    public Optional<AIComponent> getComponent(String componentId) {
        return policy.isAllowed(componentId) ? delegate.getComponent(componentId) : Optional.empty();
    }

    @Override
    public Optional<ComponentMetadata> getMetadata(String componentId) {
        return policy.isAllowed(componentId) ? delegate.getMetadata(componentId) : Optional.empty();
    }

    @Override
    public List<ComponentMetadata> listComponents() {
        return delegate.listComponents().stream()
                .filter(meta -> policy.isAllowed(meta.id()))
                .toList();
    }

    @Override
    public List<ComponentMetadata> searchComponents(ComponentSearchCriteria criteria) {
        return delegate.searchComponents(criteria).stream()
                .filter(meta -> policy.isAllowed(meta.id()))
                .toList();
    }

    @Override
    public ComponentVersionManager getVersionManager() {
        return delegate.getVersionManager();
    }

    @Override
    public void register(AIComponent component) {
        throw new UnsupportedOperationException(
                "ScopedComponentCatalog é somente leitura — registre no catálogo de origem");
    }

    @Override
    public void unregister(String componentId) {
        throw new UnsupportedOperationException(
                "ScopedComponentCatalog é somente leitura — remova no catálogo de origem");
    }
}
