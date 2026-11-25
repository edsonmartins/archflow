package br.com.archflow.api.flow;

import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.agent.tool.ToolInterceptorChain;
import br.com.archflow.api.flow.adapter.AdapterNodeTypes;
import br.com.archflow.api.flow.adapter.ChatNodeDefaults;
import br.com.archflow.api.flow.adapter.LangChainAdapterComponent;
import br.com.archflow.langchain4j.provider.TenantKeyResolver;
import br.com.archflow.engine.core.StateManager;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.config.LLMConfigPatch;
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
    // Sem o bean do host, o nó `decision` ainda funciona com os provedores embutidos — só não tem
    // chave por tenant, e um provedor que exige chave escala a decisão em vez de chamar sem ela.
    private br.com.archflow.decision.DecisionProviders decisionProviders =
            br.com.archflow.decision.DecisionProviders.withDefaults();
    private br.com.archflow.decision.DecisionKeys decisionKeys =
            br.com.archflow.decision.DecisionKeys.NONE;

    /** Provedores e chaves do nó de decisão, entregues pelo host (ver {@code ArchflowBeanConfiguration}). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setDecisionSupport(br.com.archflow.decision.DecisionProviders providers,
                                   br.com.archflow.decision.DecisionKeys keys) {
        if (providers != null) {
            this.decisionProviders = providers;
        }
        if (keys != null) {
            this.decisionKeys = keys;
        }
    }

    // Sem o bean do host, só os endereços oficiais recebem a chave injetada — o default seguro.
    private br.com.archflow.langchain4j.provider.BaseUrlPolicy baseUrlPolicy =
            br.com.archflow.langchain4j.provider.BaseUrlPolicy.officialOnly();

    /**
     * Para onde a chave injetada num nó adapter pode ir ({@code archflow.llm.allowed-base-urls}).
     * O mesmo bean guarda o caminho dos agentes ({@code DefaultLLMConfigResolver}), para os dois
     * caminhos recusarem o mesmo host.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setBaseUrlPolicy(br.com.archflow.langchain4j.provider.BaseUrlPolicy policy) {
        if (policy != null) {
            this.baseUrlPolicy = policy;
        }
    }

    // Preferência de provedor do OpenRouter (ZDR) — do deployment; sem o bean, nada configurado.
    private br.com.archflow.model.config.ProviderPreferences providerPreferences =
            br.com.archflow.model.config.ProviderPreferences.NONE;
    private boolean providerPreferencesRequired;

    /**
     * {@code archflow.llm.provider-preferences}: o nó adapter do OpenRouter as envia no corpo
     * ({@code provider: {zdr, data_collection}}, RCHFLW-41) e, com {@code required=true} sem nenhuma,
     * falha antes de chamar — ver {@link LangChainAdapterComponent}.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setProviderPreferences(
            br.com.archflow.model.config.ProviderPreferences preferences,
            @org.springframework.beans.factory.annotation.Value(
                    "${archflow.llm.provider-preferences.required:false}") boolean required) {
        if (preferences != null) {
            this.providerPreferences = preferences;
        }
        this.providerPreferencesRequired = required;
    }

    // Modelos permitidos por tenant (RCHFLW-79). Sem o bean do host, ninguém é restringido.
    private br.com.archflow.langchain4j.provider.TenantModelPolicy modelPolicy =
            br.com.archflow.langchain4j.provider.TenantModelPolicy.UNRESTRICTED;

    /**
     * {@code allowedModels} do tenant: o nó de chat confere o modelo antes de cada chamada — o mesmo
     * bean que o caminho dos agentes ({@code DefaultLLMConfigResolver}) usa, para os dois recusarem o
     * mesmo modelo. Ver {@link LangChainAdapterComponent}.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setModelPolicy(br.com.archflow.langchain4j.provider.TenantModelPolicy policy) {
        if (policy != null) {
            this.modelPolicy = policy;
        }
    }

    // Padrão de LLM da plataforma (archflow.llm.provider/model). Nulo sem o host (testes, standalone):
    // aí um nó de chat sem provider só herda do fluxo, e sem isso segue o caminho antigo.
    private br.com.archflow.model.config.ResolvedLLMConfig platformDefaultLLM;

    /**
     * O nível mais baixo da herança dos nós de chat sem provider (RCHFLW-16) — o mesmo bean que o
     * caminho dos agentes usa, para {@code llm-chat} e {@code mcp-agent} herdarem igual. Ver
     * {@link ChatNodeDefaults}.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPlatformDefaultLLM(
            @org.springframework.beans.factory.annotation.Qualifier("platformDefaultLLMConfig")
            br.com.archflow.model.config.ResolvedLLMConfig platformDefaultLLMConfig) {
        this.platformDefaultLLM = platformDefaultLLMConfig;
    }

    /**
     * Endereços que o deployment declarou serem o OpenRouter: o {@code archflow.llm.base-url} quando
     * {@code archflow.llm.provider=openrouter} (um gateway do operador à frente do OpenRouter). O hub
     * dos agentes manda a preferência de provedor por provider, então o agente nesse gateway já leva
     * ZDR; sem isto o nó de chat, que decide pelo host, ia ao mesmo gateway sem ela e com
     * {@code required=true} passava. Só o padrão da plataforma entra — um {@code base.url} escrito no
     * nó não se declara OpenRouter (um Ollama receberia o campo e devolveria 400).
     */
    private List<String> openRouterUrls() {
        if (platformDefaultLLM == null || !"openrouter".equalsIgnoreCase(platformDefaultLLM.provider())) {
            return List.of();
        }
        Object baseUrl = platformDefaultLLM.additionalConfig().get("baseUrl");
        return baseUrl instanceof String url && !url.isBlank() ? List.of(url.trim()) : List.of();
    }

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
    public FlowStep create(Map<String, Object> node, ComponentAccessPolicy componentPolicy,
                           LLMConfigPatch flowPatch) {
        String id = str(node.get("id"), "step");
        String type = str(node.get("type"), "");
        Map<String, Object> config = config(node);
        String kind = kindOf(node);
        List<StepConnection> connections = connections(id, kind, node.get("connections"));
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

        // Nós de controle do designer: passos de verdade, que calculam o ramo; quem ramifica é a aresta.
        if ("condition".equals(kind) || "switch".equals(kind)) {
            return new ControlStep(id, "condition".equals(kind)
                    ? ControlStep.Kind.CONDITION : ControlStep.Kind.SWITCH, connections, config);
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

        // Nó de chat (llm-chat / llm-streaming / chat): o provider é o do nó ou o herdado do fluxo e
        // da plataforma (RCHFLW-16). Sem adapter para ele, segue para o catálogo como sempre — mas a
        // falha passa a dizer por quê, em vez de um "component not found: llm-chat" seco.
        String notFoundHint = null;
        if ("chat".equals(AdapterNodeTypes.adapterTypeFor(type))) {
            String explicitProvider = explicitChatProvider(config,
                    componentId == null ? null : componentId.toString());
            Optional<ChatNodeDefaults.Resolution> chat =
                    ChatNodeDefaults.resolve(config, explicitProvider, flowPatch, platformDefaultLLM);
            if (chat.isPresent() && AdapterNodeTypes.isAdapterNode(type, chat.get().provider())) {
                String chatProvider = chat.get().provider();
                if (!policy.isAllowed(chatProvider)) {
                    // O escopo do fluxo vale também para o provider herdado: um fluxo restrito não
                    // alcança um adapter fora da lista só por não ter escrito o provider.
                    return new ComponentStep(id, StepType.TOOL, chatProvider, operationOf(config, node),
                            connections, scopedCatalog, interceptors, null, config,
                            "o provider '" + chatProvider + "' não está em configuration.allowedComponents");
                }
                LangChainAdapterComponent adapter = new LangChainAdapterComponent(
                        chatProvider, "chat", chat.get().config(), tenantKeyResolver,
                        baseUrlPolicy, providerPreferences, providerPreferencesRequired, openRouterUrls(),
                        modelPolicy);
                return new ComponentStep(id, StepType.TOOL, adapter.componentId(),
                        operationOf(config, node), connections, scopedCatalog, interceptors, adapter,
                        config);
            }
            notFoundHint = semAdapterDeChat(chat.orElse(null));
        }

        // Nó servido por um adapter LangChain4j (openai/anthropic/redis/...).
        // Só entra aqui quando o registry REALMENTE tem aquele provider para
        // aquele tipo — qualquer outra coisa cai no catálogo, então nenhum
        // workflow existente muda de comportamento.
        String providerId = str(config.get("provider"),
                componentId == null ? null : componentId.toString());
        if (notFoundHint == null && AdapterNodeTypes.isAdapterNode(type, providerId)) {
            if (!policy.isAllowed(providerId)) {
                // Fora do escopo do fluxo: cai no ComponentStep normal, que não
                // resolve e falha com "component not found" — mesmo tratamento
                // dos demais componentes negados.
                return new ComponentStep(id, StepType.TOOL, providerId, operationOf(config, node),
                        connections, scopedCatalog, interceptors, null, config);
            }
            LangChainAdapterComponent adapter = new LangChainAdapterComponent(
                    providerId, AdapterNodeTypes.adapterTypeFor(type), config, tenantKeyResolver,
                    baseUrlPolicy, providerPreferences, providerPreferencesRequired, openRouterUrls(),
                    modelPolicy);
            return new ComponentStep(id, StepType.TOOL, adapter.componentId(),
                    operationOf(config, node), connections, scopedCatalog, interceptors, adapter,
                    config);
        }

        // Componentes do catálogo são singletons. O mcp-agent carrega config
        // específica do nó, portanto precisa de uma instância isolada por
        // passo; inicializar o singleton vazaria modelo/prompt entre fluxos e
        // tenants concorrentes.
        if (br.com.archflow.api.agent.mcp.McpAgentComponent.COMPONENT_ID
                .equals(componentId == null ? null : componentId.toString())) {
            if (!policy.isAllowed(br.com.archflow.api.agent.mcp.McpAgentComponent.COMPONENT_ID)) {
                return new ComponentStep(id, StepType.TOOL,
                        br.com.archflow.api.agent.mcp.McpAgentComponent.COMPONENT_ID,
                        operationOf(config, node), connections, scopedCatalog, interceptors, null,
                        config);
            }
            var mcpAgent = new br.com.archflow.api.agent.mcp.McpAgentComponent(flowPatch);
            mcpAgent.initialize(config);
            return new ComponentStep(id, StepType.TOOL, mcpAgent.getMetadata().id(),
                    operationOf(config, node), connections, scopedCatalog, interceptors, mcpAgent,
                    config);
        }
        // O nó de decisão também é uma instância por passo: a configuração (perguntas, modelo,
        // limiares) é do nó, e um singleton do catálogo vazaria isso entre fluxos e tenants.
        if (br.com.archflow.decision.DecisionComponent.COMPONENT_ID
                .equals(componentId == null ? null : componentId.toString())) {
            if (!policy.isAllowed(br.com.archflow.decision.DecisionComponent.COMPONENT_ID)) {
                return new ComponentStep(id, StepType.TOOL,
                        br.com.archflow.decision.DecisionComponent.COMPONENT_ID,
                        operationOf(config, node), connections, scopedCatalog, interceptors, null,
                        config);
            }
            var decision = new br.com.archflow.decision.DecisionComponent(
                    decisionProviders, decisionKeys);
            decision.initialize(config);
            return new ComponentStep(id, StepType.TOOL, decision.getMetadata().id(),
                    operationOf(config, node), connections, scopedCatalog, interceptors, decision,
                    config);
        }
        return new ComponentStep(
                id, StepType.TOOL,
                componentId == null ? "" : componentId.toString(),
                operationOf(config, node), connections, scopedCatalog, interceptors, null, config,
                notFoundHint);
    }

    /**
     * O provider que um nó de chat declarou: {@code config.provider}, ou o {@code componentId}
     * legado ({@code {type: "llm-chat", componentId: "openai"}}) quando ele não é só o próprio tipo
     * do nó — o designer grava {@code componentId: "llm-chat"}, que não é escolha de provider.
     */
    private static String explicitChatProvider(Map<String, Object> config, String componentId) {
        if (config.get("provider") instanceof String provider && !provider.isBlank()) {
            return provider.trim();
        }
        if (componentId != null && !componentId.isBlank()
                && AdapterNodeTypes.adapterTypeFor(componentId) == null) {
            return componentId.trim();
        }
        return null;
    }

    /** Por que um nó de chat não foi para um adapter — o que a falha diz depois de "component not found". */
    private static String semAdapterDeChat(ChatNodeDefaults.Resolution chat) {
        String disponiveis = String.join(", ", new java.util.TreeSet<>(
                br.com.archflow.langchain4j.core.spi.LangChainRegistry.getProvidersOfType("chat")));
        if (chat == null) {
            return "o nó não escolheu provider e não há padrão do fluxo (configuration.llmConfig.provider) "
                    + "nem da plataforma (archflow.llm.provider). Escolha um provider no nó (com adapter de "
                    + "chat: " + disponiveis + ")";
        }
        String deOnde = switch (chat.origin()) {
            case NODE -> "o provider '" + chat.provider() + "'";
            case FLOW -> "o nó não escolheu provider e o padrão do fluxo (configuration.llmConfig.provider) é '"
                    + chat.provider() + "', que";
            case PLATFORM -> "o nó não escolheu provider e o padrão da plataforma (archflow.llm.provider) é '"
                    + chat.provider() + "', que";
        };
        return deOnde + " não tem adapter de chat (disponíveis: " + disponiveis + "). Escolha um destes no "
                + "nó, ou use um nó de agente (mcp-agent), que fala com todos os providers";
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

    /** O tipo do nó em minúsculas: o componentId, ou o type quando o designer não grava um. */
    private static String kindOf(Map<String, Object> node) {
        Object componentId = node.get("componentId");
        Object raw = componentId != null ? componentId : node.get("type");
        return raw == null ? "" : raw.toString().trim().toLowerCase();
    }

    /**
     * A condição que o ramo declarado numa aresta quer dizer, ou {@code null} quando o nó de origem
     * não tem ramos. É o que deixa o designer, a DSL ({@code route(...)}) e quem escreve YAML dizer
     * "esta aresta é o ramo {@code true}" em vez de montar a expressão à mão.
     */
    static String conditionForBranch(String stepId, String kind, String branch) {
        // A regra mora no modelo: o transporte do archflow-standalone converte o mesmo `branch`.
        return br.com.archflow.model.flow.BranchCondition.of(stepId, kind, branch);
    }

    private static List<StepConnection> connections(String stepId, String kind, Object value) {
        List<StepConnection> connections = new ArrayList<>();
        for (Object item : asList(value)) {
            Map<String, Object> map = asMap(item);
            String sourceId = str(map.get("sourceId"), stepId);
            String targetId = str(map.get("targetId"), "");
            if (targetId.isBlank()) {
                continue;
            }
            // Uma condição escrita à mão vence o ramo: quem a escreveu sabia o que queria.
            String condition = str(map.get("condition"), null);
            if (condition == null) {
                condition = conditionForBranch(stepId, kind, str(map.get("branch"), null));
            }
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
