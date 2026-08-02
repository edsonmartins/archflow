package br.com.archflow.api.flow;

import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.config.LLMConfigPatch;
import br.com.archflow.plugin.api.catalog.ComponentAccessPolicy;

import java.util.Map;

/** Builds an executable {@link FlowStep} from a workflow-JSON node (design-0004). */
public interface FlowStepFactory {

    /** Materializa o nó sem restrição de catálogo (comportamento histórico). */
    default FlowStep create(Map<String, Object> node) {
        return create(node, ComponentAccessPolicy.allowAll(), LLMConfigPatch.empty());
    }

    default FlowStep create(Map<String, Object> node, ComponentAccessPolicy componentPolicy) {
        return create(node, componentPolicy, LLMConfigPatch.empty());
    }

    /**
     * Materializa o nó restringindo quais componentes ele pode resolver.
     *
     * <p>A política vem do fluxo, não do nó: é o fluxo que declara o conjunto de
     * componentes que pode tocar. Um nó já nomeia um componente — restringir por
     * nó não protegeria de nada.
     */
    FlowStep create(Map<String, Object> node, ComponentAccessPolicy componentPolicy,
                    LLMConfigPatch flowPatch);
}
