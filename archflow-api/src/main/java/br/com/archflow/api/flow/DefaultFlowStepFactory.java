package br.com.archflow.api.flow;

import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.agent.tool.ToolInterceptorChain;
import br.com.archflow.api.flow.adapter.AdapterNodeTypes;
import br.com.archflow.api.flow.adapter.LangChainAdapterComponent;
import br.com.archflow.langchain4j.provider.TenantKeyResolver;
import br.com.archflow.engine.core.StateManager;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepConnection;
import br.com.archflow.model.flow.StepType;
import br.com.archflow.plugin.api.catalog.ComponentAccessPolicy;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import br.com.archflow.plugin.api.catalog.ScopedComponentCatalog;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Maps a workflow-JSON node to an executable {@link FlowStep}: an
 * {@link OrchestrateStep} for {@code type="ORCHESTRATE"} (design-0004 step 2),
 * a {@link HumanApprovalStep} for {@code type="APPROVAL"} (the human-in-the-loop
 * gate), otherwise a {@link ComponentStep} (ASSISTANT/AGENT/TOOL). The remaining
 * granular orchestration kinds (FAN_OUT/VERIFY/LOOP_UNTIL) are a follow-up.
 */
@Component
public class DefaultFlowStepFactory implements FlowStepFactory {

    private final ComponentCatalog catalog;
    private final DynamicWorkflowService dynamicWorkflowService;
    private final EventStreamRegistry streamRegistry;
    private final StateManager stateManager;
    private final ObjectProvider<FlowEngine> flowEngine;
    private final ToolInterceptorChain interceptors;
    private final TenantKeyResolver tenantKeyResolver;

    public DefaultFlowStepFactory(ComponentCatalog catalog, DynamicWorkflowService dynamicWorkflowService,
                                  EventStreamRegistry streamRegistry, StateManager stateManager,
                                  ObjectProvider<FlowEngine> flowEngine) {
        this(catalog, dynamicWorkflowService, streamRegistry, stateManager, flowEngine, null);
    }

    public DefaultFlowStepFactory(ComponentCatalog catalog, DynamicWorkflowService dynamicWorkflowService,
                                  EventStreamRegistry streamRegistry, StateManager stateManager,
                                  ObjectProvider<FlowEngine> flowEngine,
                                  ToolInterceptorChain interceptors) {
        this(catalog, dynamicWorkflowService, streamRegistry, stateManager, flowEngine,
                interceptors, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DefaultFlowStepFactory(ComponentCatalog catalog, DynamicWorkflowService dynamicWorkflowService,
                                  EventStreamRegistry streamRegistry, StateManager stateManager,
                                  ObjectProvider<FlowEngine> flowEngine,
                                  ToolInterceptorChain interceptors,
                                  TenantKeyResolver tenantKeyResolver) {
        this.catalog = catalog;
        this.dynamicWorkflowService = dynamicWorkflowService;
        this.streamRegistry = streamRegistry;
        this.stateManager = stateManager;
        // ObjectProvider (resolução tardia) porque o grafo de beans é
        // FlowEngine → FlowRepository → WorkflowDeserializer → esta factory:
        // uma injeção direta fecharia o ciclo.
        this.flowEngine = flowEngine;
        this.interceptors = interceptors;
        this.tenantKeyResolver = tenantKeyResolver != null ? tenantKeyResolver : TenantKeyResolver.NOOP;
    }

    @Override
    public FlowStep create(Map<String, Object> node, ComponentAccessPolicy componentPolicy) {
        String id = str(node.get("id"), "step");
        String type = str(node.get("type"), "");
        Map<String, Object> config = config(node);
        List<StepConnection> connections = connections(id, node.get("connections"));
        ComponentAccessPolicy policy =
                componentPolicy != null ? componentPolicy : ComponentAccessPolicy.allowAll();
        // O step só enxerga o recorte que o fluxo declarou: nem resolve nem lista
        // o que está fora dele.
        ComponentCatalog scopedCatalog = ScopedComponentCatalog.of(catalog, policy);

        if (StepType.ORCHESTRATE.name().equalsIgnoreCase(type)) {
            // Os sub-agentes herdam o escopo do fluxo — sem isso, um passo
            // restrito delegaria para um agente irrestrito e a restrição seria
            // contornável por delegação.
            return new OrchestrateStep(id, connections, config, dynamicWorkflowService,
                    streamRegistry, stateManager, policy);
        }

        if (StepType.APPROVAL.name().equalsIgnoreCase(type)) {
            return new HumanApprovalStep(id, connections, config,
                    flowEngine == null ? null : flowEngine::getObject);
        }

        // componentId, falling back to the node "type" (the designer often uses
        // the component type as the node type, e.g. "llm-chat").
        Object componentId = node.get("componentId");
        if (componentId == null) {
            componentId = node.get("type");
        }

        // Nó servido por um adapter LangChain4j (openai/anthropic/redis/...).
        // Só entra aqui quando o registry REALMENTE tem aquele provider para
        // aquele tipo — qualquer outra coisa cai no catálogo, então nenhum
        // workflow existente muda de comportamento.
        String providerId = str(config.get("provider"),
                componentId == null ? null : componentId.toString());
        if (AdapterNodeTypes.isAdapterNode(type, providerId)) {
            if (!policy.isAllowed(providerId)) {
                // Fora do escopo do fluxo: cai no ComponentStep normal, que não
                // resolve e falha com "component not found" — mesmo tratamento
                // dos demais componentes negados.
                return new ComponentStep(id, StepType.TOOL, providerId, operationOf(config, node),
                        connections, scopedCatalog, interceptors);
            }
            LangChainAdapterComponent adapter = new LangChainAdapterComponent(
                    providerId, AdapterNodeTypes.adapterTypeFor(type), config, tenantKeyResolver);
            return new ComponentStep(id, StepType.TOOL, adapter.componentId(),
                    operationOf(config, node), connections, scopedCatalog, interceptors, adapter);
        }
        return new ComponentStep(
                id, StepType.TOOL,
                componentId == null ? "" : componentId.toString(),
                operationOf(config, node), connections, scopedCatalog, interceptors);
    }

    /**
     * Operação do nó. Config explícita vence; o campo do nó é fallback para
     * fluxos escritos em YAML/API. O designer guarda o nome de exibição em
     * {@code label}, então ele nunca chega aqui.
     */
    private static String operationOf(Map<String, Object> config, Map<String, Object> node) {
        return str(config.get("operation"), str(node.get("operation"), "execute"));
    }

    private static String str(Object v, String fallback) {
        return v == null ? fallback : v.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static Map<String, Object> config(Map<String, Object> node) {
        Map<String, Object> config = new LinkedHashMap<>(asMap(node.get("config")));
        config.putAll(asMap(node.get("configuration")));
        return config.isEmpty() ? Map.of() : Collections.unmodifiableMap(config);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object v) {
        return v instanceof List<?> l ? (List<Object>) l : List.of();
    }

    private static List<StepConnection> connections(String stepId, Object value) {
        List<StepConnection> connections = new ArrayList<>();
        for (Object item : asList(value)) {
            Map<String, Object> map = asMap(item);
            String sourceId = str(map.get("sourceId"), stepId);
            String targetId = str(map.get("targetId"), "");
            if (targetId.isBlank()) {
                continue;
            }
            String condition = str(map.get("condition"), null);
            boolean errorPath = bool(map.get("isErrorPath"),
                    bool(map.get("errorPath"), "error".equals(map.get("type"))));
            connections.add(new PersistedStepConnection(sourceId, targetId, condition, errorPath));
        }
        return List.copyOf(connections);
    }

    private static boolean bool(Object v, boolean fallback) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s && !s.isBlank()) {
            return Boolean.parseBoolean(s);
        }
        return fallback;
    }

    private static final class PersistedStepConnection implements StepConnection {
        private final String sourceId;
        private final String targetId;
        private final String condition;
        private final boolean errorPath;

        private PersistedStepConnection(String sourceId, String targetId, String condition, boolean errorPath) {
            this.sourceId = sourceId;
            this.targetId = targetId;
            this.condition = condition;
            this.errorPath = errorPath;
        }

        @Override
        public String getSourceId() {
            return sourceId;
        }

        @Override
        public String getTargetId() {
            return targetId;
        }

        @Override
        public Optional<String> getCondition() {
            return Optional.ofNullable(condition);
        }

        @Override
        public boolean isErrorPath() {
            return errorPath;
        }
    }
}
