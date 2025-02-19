package br.com.archflow.plugin.loader;

import br.com.archflow.model.ai.type.ComponentType;

public class ComponentLoadException extends PluginLoadException {
    private final ComponentType type;
    private final String componentId;

    public ComponentLoadException(String message, ComponentType type, String componentId) {
        super(message);
        this.type = type;
        this.componentId = componentId;
    }

    public ComponentLoadException(String message, ComponentType type, String componentId, Throwable cause) {
        super(message, cause);
        this.type = type;
        this.componentId = componentId;
    }

    public ComponentType getType() {
        return type;
    }

    public String getComponentId() {
        return componentId;
    }
}