package br.com.archflow.api.flow;

import br.com.archflow.agent.tool.ToolInterceptorChain;
import br.com.archflow.api.flow.adapter.AdapterNodeTypes;
import br.com.archflow.api.flow.adapter.ChatNodeDefaults;
import br.com.archflow.api.flow.adapter.LangChainAdapterComponent;
import br.com.archflow.langchain4j.provider.TenantKeyResolver;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.config.LLMConfigPatch;
import br.com.archflow.model.flow.StepConnection;
import br.com.archflow.model.flow.StepType;
import br.com.archflow.plugin.api.catalog.ComponentAccessPolicy;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import br.com.archflow.plugin.api.catalog.ScopedComponentCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Maps a workflow-JSON node to an executable {@link FlowStep}: control nodes ({@link ControlStep}),
 * Entrada/Saída ({@link PassThroughStep}), LangChain4j adapter nodes ({@link LangChainAdapterComponent}),
 * the {@code decision} node, the palette types without execution ({@link UnsupportedNodeStep}) and,
 * for everything else, a {@link ComponentStep} over the {@link ComponentCatalog}.
 *
 * <p><b>Uma fábrica para os três runtimes (RCHFLW-37).</b> É a lógica que morava no
 * {@code DefaultFlowStepFactory} do archflow-api, sem Spring: o servidor, o runner standalone e o SDK
 * embarcado materializam o documento por aqui, então um nó vira o mesmo passo nos três. O que só o
 * servidor sabe construir — aprovação com fila, orquestração, {@code mcp-agent}, base de conhecimento —
 * é reconhecido aqui, na mesma ordem de sempre, e construído pelo {@link ServerNodes} que o host
 * entrega ({@link #setServerNodes}). Sem ele, o nó vira {@link ServerOnlyNodeStep} e o fluxo é recusado
 * antes do primeiro passo; não há substituto que rode "quase igual". Sem ele também são recusados o nó
 * {@code decision} que cita um provedor não registrado aqui e o nó de dados sem adapter (nem componente de
 * plugin com o tipo dele) — no servidor esses dois seguem falhando na execução, como sempre.</p>
 *
 * <p>As configurações do deployment (chaves, destinos, preferência de provedor, modelos do tenant,
 * padrão de LLM) entram por setters simples: o servidor as lê das propriedades
 * ({@code DefaultFlowStepFactory}), o standalone do ambiente.</p>
 */
public class PortableFlowStepFactory implements FlowStepFactory {

    private static final Logger log = LoggerFactory.getLogger(PortableFlowStepFactory.class);

    private final ComponentCatalog catalog;
    private final ToolInterceptorChain interceptors;
    private final TenantKeyResolver tenantKeyResolver;

    // Sem host, os nós do servidor são recusados antes do primeiro passo (ServerOnlyNodeStep).
    private ServerNodes serverNodes;

    // Sem o bean do host, o nó `decision` ainda funciona com os provedores embutidos — só não tem
    // chave por tenant, e um provedor que exige chave escala a decisão em vez de chamar sem ela.
    private br.com.archflow.decision.DecisionProviders decisionProviders =
            br.com.archflow.decision.DecisionProviders.withDefaults();
    private br.com.archflow.decision.DecisionKeys decisionKeys =
            br.com.archflow.decision.DecisionKeys.NONE;

    // Conexões dos adapters de dados por nome (RCHFLW-56): archflow.adapters.<provider>.<nome>.<campo>.
    private br.com.archflow.api.flow.adapter.AdapterConnections adapterConnections =
            br.com.archflow.api.flow.adapter.AdapterConnections.NONE;

    // Sem o bean do host, só os endereços oficiais recebem a chave injetada — o default seguro.
    private br.com.archflow.langchain4j.provider.BaseUrlPolicy baseUrlPolicy =
            br.com.archflow.langchain4j.provider.BaseUrlPolicy.officialOnly();

    // Preferência de provedor do OpenRouter (ZDR) — do deployment; sem o bean, nada configurado.
    private br.com.archflow.model.config.ProviderPreferences providerPreferences =
            br.com.archflow.model.config.ProviderPreferences.NONE;
    private boolean providerPreferencesRequired;

    // Modelos permitidos por tenant (RCHFLW-79). Sem o bean do host, ninguém é restringido.
    private br.com.archflow.langchain4j.provider.TenantModelPolicy modelPolicy =
            br.com.archflow.langchain4j.provider.TenantModelPolicy.UNRESTRICTED;

    // Padrão de LLM da plataforma (archflow.llm.provider/model). Nulo sem o host (testes): aí um nó de
    // chat sem provider só herda do fluxo, e sem isso segue o caminho antigo.
    private br.com.archflow.model.config.ResolvedLLMConfig platformDefaultLLM;

    // O servidor aceita a chave escrita no nó, com aviso (o resolver vence). O standalone e o SDK não:
    // lá a chave vem só do ambiente (RCHFLW-37).
    private boolean inlineApiKeys = true;

    // archflow.flow.lenient-conditions (RCHFLW-123): o padrão é falhar fechado — referência sem valor numa
    // condition/switch falha o passo, e o switch sem aresta para o ramo também. true devolve o ramo padrão.
    private boolean lenientConditions;

    // archflow.flow.strict-conditions, o mesmo flag com que o motor percorre as arestas: o switch confere "sem
    // saída" com a regra que o motor vai aplicar (ver ControlStep). Default false, como no motor.
    private boolean strictConditions;

    /**
     * @param catalog           catálogo dos componentes ({@link ComponentStep})
     * @param interceptors      cadeia de interceptors das invocações, ou {@code null}
     * @param tenantKeyResolver de onde vem a chave dos nós de adapter; {@code null} é nenhuma
     */
    public PortableFlowStepFactory(ComponentCatalog catalog, ToolInterceptorChain interceptors,
                                   TenantKeyResolver tenantKeyResolver) {
        this.catalog = catalog;
        this.interceptors = interceptors;
        this.tenantKeyResolver = tenantKeyResolver != null ? tenantKeyResolver : TenantKeyResolver.NOOP;
    }

    /** Quem constrói os nós do servidor; {@code null} os recusa antes do primeiro passo. */
    public final void setServerNodes(ServerNodes serverNodes) {
        this.serverNodes = serverNodes;
    }

    /**
     * {@code false} descarta a chave escrita no JSON do nó ({@code apiKey}/{@code api.key}) antes de
     * criar o adapter — a chave passa a vir só do {@link TenantKeyResolver}. Default {@code true}, o
     * comportamento do servidor.
     */
    public void setInlineApiKeys(boolean allowed) {
        this.inlineApiKeys = allowed;
    }

    /**
     * {@code archflow.flow.lenient-conditions}: {@code true} devolve o comportamento anterior à RCHFLW-123 —
     * numa {@code condition}/{@code switch}, a referência sem valor segue {@code false}/{@code default} (com o
     * {@code warning} na saída) e o switch sem aresta para o ramo conclui. Default {@code false}: falha o passo.
     */
    public void setLenientConditions(boolean lenient) {
        this.lenientConditions = lenient;
    }

    /**
     * {@code archflow.flow.strict-conditions}: o mesmo valor entregue ao motor. Não muda o ramo que um
     * {@code switch} escolhe — só a regra com que ele confere se alguma aresta de saída seria seguida.
     */
    public void setStrictConditions(boolean strict) {
        this.strictConditions = strict;
    }

    public void setAdapterConnections(br.com.archflow.api.flow.adapter.AdapterConnections connections) {
        this.adapterConnections = connections != null
                ? connections : br.com.archflow.api.flow.adapter.AdapterConnections.NONE;
    }

    /** Provedores e chaves do nó de decisão, entregues pelo host. */
    public void setDecisionSupport(br.com.archflow.decision.DecisionProviders providers,
                                   br.com.archflow.decision.DecisionKeys keys) {
        if (providers != null) {
            this.decisionProviders = providers;
        }
        if (keys != null) {
            this.decisionKeys = keys;
        }
    }

    /**
     * Para onde a chave injetada num nó adapter pode ir ({@code archflow.llm.allowed-base-urls}).
     * O mesmo bean guarda o caminho dos agentes ({@code DefaultLLMConfigResolver}), para os dois
     * caminhos recusarem o mesmo host.
     */
    public void setBaseUrlPolicy(br.com.archflow.langchain4j.provider.BaseUrlPolicy policy) {
        if (policy != null) {
            this.baseUrlPolicy = policy;
        }
    }

    /**
     * {@code archflow.llm.provider-preferences}: o nó adapter do OpenRouter as envia no corpo
     * ({@code provider: {zdr, data_collection}}, RCHFLW-41) e, com {@code required=true} sem nenhuma,
     * falha antes de chamar — ver {@link LangChainAdapterComponent}.
     */
    public void setProviderPreferences(br.com.archflow.model.config.ProviderPreferences preferences,
                                       boolean required) {
        if (preferences != null) {
            this.providerPreferences = preferences;
        }
        this.providerPreferencesRequired = required;
    }

    /**
     * {@code allowedModels} do tenant: o nó de chat confere o modelo antes de cada chamada — o mesmo
     * bean que o caminho dos agentes ({@code DefaultLLMConfigResolver}) usa, para os dois recusarem o
     * mesmo modelo. Ver {@link LangChainAdapterComponent}.
     */
    public void setModelPolicy(br.com.archflow.langchain4j.provider.TenantModelPolicy policy) {
        if (policy != null) {
            this.modelPolicy = policy;
        }
    }

    /**
     * O nível mais baixo da herança dos nós de chat sem provider (RCHFLW-16) — o mesmo bean que o
     * caminho dos agentes usa, para {@code llm-chat} e {@code mcp-agent} herdarem igual. Ver
     * {@link ChatNodeDefaults}.
     */
    public void setPlatformDefaultLLM(br.com.archflow.model.config.ResolvedLLMConfig platformDefaultLLMConfig) {
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
            // Os sub-agentes herdam o escopo do fluxo (o servidor passa a política ao OrchestrateStep):
            // sem isso, um passo restrito delegaria para um agente irrestrito.
            return serverNodes == null
                    ? serverOnly(id, node, ServerCapability.ORCHESTRATE, connections)
                    : serverNodes.orchestrate(
                            request(id, node, config, connections, policy, scopedCatalog, flowPatch));
        }

        // Nós de controle do designer: passos de verdade, que calculam o ramo; quem ramifica é a aresta.
        if ("condition".equals(kind) || "switch".equals(kind)) {
            return new ControlStep(id, "condition".equals(kind)
                    ? ControlStep.Kind.CONDITION : ControlStep.Kind.SWITCH, connections, config,
                    str(node.get("label"), null), lenientConditions, strictConditions);
        }

        if (StepType.APPROVAL.name().equalsIgnoreCase(type)) {
            return serverNodes == null
                    ? serverOnly(id, node, ServerCapability.APPROVAL, connections)
                    : serverNodes.approval(
                            request(id, node, config, connections, policy, scopedCatalog, flowPatch));
        }

        // Entrada e Saída do designer repassam a entrada (RCHFLW-11): antes caíam no catálogo e o
        // fluxo Entrada → Saída falhava com "component not found: input".
        if ("input".equals(kind) || "output".equals(kind)) {
            return new PassThroughStep(id, connections);
        }

        // componentId, falling back to the node "type" (the designer often uses
        // the component type as the node type, e.g. "llm-chat").
        Object componentId = node.get("componentId");
        if (componentId == null) {
            componentId = node.get("type");
        }

        // Busca vetorial / RAG com uma Base de conhecimento escolhida (RCHFLW-55): busca pelo serviço da tela
        // de Conhecimento, no tenant da execução — o adapter lia uma tabela que a ingestão nunca preenche.
        if (KnowledgeNodes.NODE_TYPES.contains(kind) && KnowledgeNodes.baseIdOf(config) != null) {
            return knowledgeStep(id, kind, config, node, connections, policy, scopedCatalog, flowPatch);
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
                // O id do nó vai junto para a falha da "Mensagem do usuário" (RCHFLW-120) dizer qual nó; o
                // chat não usa conexões do servidor nem o tipo de nó (só os nós de dados).
                LangChainAdapterComponent adapter = new LangChainAdapterComponent(
                        chatProvider, "chat", adapterConfig(id, chat.get().config()), tenantKeyResolver,
                        baseUrlPolicy, providerPreferences, providerPreferencesRequired, openRouterUrls(),
                        modelPolicy, null, null, id);
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
        // Nos nós de dados (embedding, memória, vector store, RAG) o painel escolhe o provider em outro campo
        // (vectorBackend, embeddingProvider, memoryBackend) e grava chaves que o adapter não lê: a tradução é
        // da DesignerAdapterKeys (RCHFLW-56). O documento salvo não muda.
        String adapterType = AdapterNodeTypes.adapterTypeFor(type);
        boolean dataNode = adapterType != null && !"chat".equals(adapterType);
        String legacyProvider = componentId == null ? null : componentId.toString();
        String providerId = dataNode
                ? br.com.archflow.api.flow.adapter.DesignerAdapterKeys.providerOf(adapterType, config, legacyProvider)
                : str(config.get("provider"), legacyProvider);
        if (notFoundHint == null && AdapterNodeTypes.isAdapterNode(type, providerId)) {
            if (!policy.isAllowed(providerId)) {
                // Fora do escopo do fluxo: cai no ComponentStep normal, que não
                // resolve e falha com "component not found" — mesmo tratamento
                // dos demais componentes negados.
                return new ComponentStep(id, StepType.TOOL, providerId, operationOf(config, node),
                        connections, scopedCatalog, interceptors, null, config);
            }
            Map<String, Object> adapterConfig = dataNode
                    ? br.com.archflow.api.flow.adapter.DesignerAdapterKeys.translate(adapterType, providerId, config)
                    : config;
            LangChainAdapterComponent adapter = new LangChainAdapterComponent(
                    providerId, adapterType, adapterConfig(id, adapterConfig), tenantKeyResolver,
                    baseUrlPolicy, providerPreferences, providerPreferencesRequired, openRouterUrls(),
                    modelPolicy, adapterConnections, type);
            return new ComponentStep(id, StepType.TOOL, adapter.componentId(),
                    operationOf(config, node), connections, scopedCatalog, interceptors, adapter,
                    config);
        }
        if (notFoundHint == null && dataNode) {
            String componentKey = componentId == null ? "" : componentId.toString();
            if (serverNodes == null && scopedCatalog.getComponent(componentKey).isEmpty()) {
                // Fora do servidor não há adapter de dados nem conexão por nome: o nó só falharia ao ser
                // alcançado, depois dos passos anteriores (chamadas pagas ao modelo). Recusa antes do primeiro
                // passo. Um plugin que registre o próprio tipo ainda é servido pelo catálogo.
                return serverOnly(id, node, ServerCapability.DATA_ADAPTER,
                        semAdapterDeDados(kind, adapterType, providerId, legacyProvider, false), connections);
            }
            notFoundHint = semAdapterDeDados(kind, adapterType, providerId, legacyProvider, true);
        }

        // Componentes do catálogo são singletons. O mcp-agent carrega config
        // específica do nó, portanto precisa de uma instância isolada por
        // passo — e o laço dele é do servidor (ServerNodes).
        if (ServerNodes.MCP_AGENT_COMPONENT_ID.equals(componentId == null ? null : componentId.toString())) {
            return serverNodes == null
                    ? serverOnly(id, node, ServerCapability.MCP_AGENT, connections)
                    : serverNodes.mcpAgent(
                            request(id, node, config, connections, policy, scopedCatalog, flowPatch));
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
            if (serverNodes == null) {
                // Fora do servidor os provedores `llm` e `langchain4j-decisions` não existem. Com
                // onError: ESCALATE (o padrão) o ausente virava rota ESCALATE e o fluxo terminava COMPLETED pelo
                // ramo de escalada — recusa antes do primeiro passo, dizendo quais faltam. No servidor nada muda.
                List<String> ausentes = br.com.archflow.decision.DecisionComponent.providersNamedBy(config)
                        .stream().filter(p -> decisionProviders.find(p).isEmpty()).toList();
                if (!ausentes.isEmpty()) {
                    return serverOnly(id, node, ServerCapability.DECISION_PROVIDER,
                            "provedor " + String.join(", ", ausentes.stream().map(p -> "'" + p + "'").toList())
                                    + " não existe fora do servidor (disponíveis aqui: "
                                    + String.join(", ", decisionProviders.ids()) + ")",
                            connections);
                }
            }
            var decision = new br.com.archflow.decision.DecisionComponent(
                    decisionProviders, decisionKeys);
            decision.initialize(config);
            return new ComponentStep(id, StepType.TOOL, decision.getMetadata().id(),
                    operationOf(config, node), connections, scopedCatalog, interceptors, decision,
                    config);
        }
        // Tipo da paleta que o motor ainda não executa (RCHFLW-11): o passo carrega o tipo para a
        // validação do início da execução recusar o fluxo nomeando o nó, em vez de rodar até ele e
        // falhar com "component not found". Decide só a lista, sem perguntar ao catálogo: o designer
        // não conhece os plugins carregados e marca o nó (e bloqueia o Executar) pela mesma lista — se
        // um componente com id "custom" isentasse o nó aqui, o editor recusaria um fluxo que o servidor
        // roda. Os tipos da paleta são, por isso, ids reservados: um plugin registra com outro id.
        if (NodesWithoutExecution.contains(kind)) {
            return new UnsupportedNodeStep(id, kind, str(node.get("label"), null), connections);
        }
        // Componente do catálogo: sem operação no nó, passa null e o passo usa a operação padrão do
        // componente (CatalogComponents) — nenhum embutido declara o "execute" que valia antes.
        return new ComponentStep(
                id, StepType.TOOL,
                componentId == null ? "" : componentId.toString(),
                explicitOperationOf(config, node), connections, scopedCatalog, interceptors, null, config,
                notFoundHint);
    }

    private ServerNodes.Request request(String id, Map<String, Object> node, Map<String, Object> config,
                                        List<StepConnection> connections, ComponentAccessPolicy policy,
                                        ComponentCatalog scopedCatalog, LLMConfigPatch flowPatch) {
        return new ServerNodes.Request(id, node, config, connections, policy, scopedCatalog, flowPatch,
                interceptors, operationOf(config, node));
    }

    private static FlowStep serverOnly(String id, Map<String, Object> node, ServerCapability capability,
                                       List<StepConnection> connections) {
        return serverOnly(id, node, capability, null, connections);
    }

    private static FlowStep serverOnly(String id, Map<String, Object> node, ServerCapability capability,
                                       String detail, List<StepConnection> connections) {
        return new ServerOnlyNodeStep(id, str(node.get("label"), null), capability, detail, connections);
    }

    /** A config que vai ao adapter: sem a chave escrita no nó quando o host não a aceita. */
    private Map<String, Object> adapterConfig(String nodeId, Map<String, Object> config) {
        if (inlineApiKeys) {
            return config;
        }
        Map<String, Object> semChave = LangChainAdapterComponent.withoutInlineKeys(config);
        if (semChave != config) {
            // Só o id do nó, nunca o valor: a chave do arquivo é descartada, a do ambiente vale.
            log.warn("Nó {} traz uma chave de API no JSON do fluxo; ela foi descartada — fora do servidor a "
                    + "chave vem só do ambiente", nodeId);
        }
        return semChave;
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

    /**
     * Um nó {@code vector-search}/{@code rag} com base de conhecimento. O RAG resolve o chat como um nó de
     * chat ({@code llmProvider}, senão o do fluxo e o da plataforma) e o executa pela mesma ponte governada;
     * sem chat possível, o motivo vai para a execução — abrir o fluxo não falha. A busca é do servidor
     * ({@link ServerNodes#knowledgeBase}).
     */
    private FlowStep knowledgeStep(String id, String kind, Map<String, Object> config, Map<String, Object> node,
                                   List<StepConnection> connections, ComponentAccessPolicy policy,
                                   ComponentCatalog scopedCatalog, LLMConfigPatch flowPatch) {
        if (!policy.isAllowed(KnowledgeNodes.COMPONENT_ID)) {
            return new ComponentStep(id, StepType.TOOL, KnowledgeNodes.COMPONENT_ID,
                    operationOf(config, node), connections, scopedCatalog, interceptors, null, config,
                    "a base de conhecimento ('" + KnowledgeNodes.COMPONENT_ID
                            + "') não está em configuration.allowedComponents");
        }
        if (serverNodes == null) {
            return serverOnly(id, node, ServerCapability.KNOWLEDGE_BASE, connections);
        }
        br.com.archflow.model.ai.AIComponent generator = null;
        String problem = null;
        boolean rag = "rag".equals(kind);
        if (rag) {
            String llm = config.get("llmProvider") instanceof String p && !p.isBlank() ? p.trim() : null;
            Optional<ChatNodeDefaults.Resolution> chat =
                    ChatNodeDefaults.resolve(Map.of(), llm, flowPatch, platformDefaultLLM);
            if (chat.isPresent() && AdapterNodeTypes.isAdapterNode("llm-chat", chat.get().provider())) {
                String chatProvider = chat.get().provider();
                if (policy.isAllowed(chatProvider)) {
                    generator = new LangChainAdapterComponent(chatProvider, "chat",
                            adapterConfig(id, chat.get().config()),
                            tenantKeyResolver, baseUrlPolicy, providerPreferences, providerPreferencesRequired,
                            openRouterUrls(), modelPolicy);
                } else {
                    problem = "o provider '" + chatProvider + "' não está em configuration.allowedComponents";
                }
            } else {
                problem = semAdapterDeChat(chat.orElse(null));
            }
        }
        return serverNodes.knowledgeBase(
                request(id, node, config, connections, policy, scopedCatalog, flowPatch), rag, generator, problem);
    }

    /**
     * Por que um nó de dados não foi para um adapter: o backend não foi escolhido, ou o escolhido não tem
     * adapter deste tipo. O prefixo "component not found" fica, como no chat.
     *
     * @param noServidor {@code false} fora do servidor: diz "fora do servidor" e não sugere a Base de
     *                   conhecimento, que também é do servidor
     */
    private static String semAdapterDeDados(String kind, String adapterType, String providerId,
                                            String legacyProvider, boolean noServidor) {
        String field = br.com.archflow.api.flow.adapter.DesignerAdapterKeys.providerField(adapterType);
        String disponiveis = String.join(", ", new java.util.TreeSet<>(
                br.com.archflow.langchain4j.core.spi.LangChainRegistry.getProvidersOfType(adapterType)));
        boolean escolheu = providerId != null && !providerId.isBlank() && !providerId.equals(legacyProvider);
        String base = noServidor && KnowledgeNodes.NODE_TYPES.contains(kind)
                ? " Ou escolha uma Base de conhecimento no nó." : "";
        String fim = noServidor ? "." : "";
        if (!escolheu) {
            return "o nó não escolheu o backend" + (field == null ? "" : " (campo '" + field + "')")
                    + " (disponíveis: " + (disponiveis.isEmpty() ? "nenhum" : disponiveis) + ")" + fim + base;
        }
        return "o backend '" + providerId + "' não tem adapter de " + adapterType
                + (noServidor ? " neste servidor" : " fora do servidor") + " (disponíveis: "
                + (disponiveis.isEmpty() ? "nenhum" : disponiveis) + ")" + fim + base;
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
        String explicit = explicitOperationOf(config, node);
        return explicit == null ? CatalogComponents.LEGACY_OPERATION : explicit;
    }

    /** A operação que o nó declara, ou {@code null} quando não declara (em branco conta como ausente). */
    private static String explicitOperationOf(Map<String, Object> config, Map<String, Object> node) {
        String op = str(config.get("operation"), str(node.get("operation"), null));
        return op == null || op.isBlank() ? null : op;
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
