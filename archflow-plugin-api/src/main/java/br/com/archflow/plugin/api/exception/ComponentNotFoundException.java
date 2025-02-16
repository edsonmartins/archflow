package br.com.archflow.plugin.api.exception;

/**
 * Exceção lançada quando um componente não é encontrado.
 */
public class ComponentNotFoundException extends ComponentException {
    public ComponentNotFoundException(String componentId) {
        super("Componente não encontrado: " + componentId);
    }
}