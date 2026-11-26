package br.com.archflow.api.flow;

import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.agent.tool.ToolInterceptorChain;
import br.com.archflow.langchain4j.provider.TenantKeyResolver;
import br.com.archflow.engine.core.StateManager;
import br.com.archflow.model.ai.AIComponent;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepType;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * A fábrica de passos do servidor: a {@link PortableFlowStepFactory} (archflow-flow-runtime) com as
 * configurações do deployment e os passos que só o servidor constrói.
 *
 * <p>Maps a workflow-JSON node to an executable {@link FlowStep}: an {@link OrchestrateStep} for
 * {@code type="ORCHESTRATE"} (design-0004 step 2), a {@link HumanApprovalStep} for {@code type="APPROVAL"}
 * (the human-in-the-loop gate), an isolated {@code McpAgentComponent} per {@code mcp-agent} node and the
 * Knowledge-base search for {@code vector-search}/{@code rag} with a base; everything else is decided by
 * the portable factory, in the same order as before.</p>
 *
 * <p><b>Por que a divisão (RCHFLW-37).</b> O runner standalone e o SDK embarcado executam o mesmo
 * documento, e o archflow-api depende do archflow-standalone: a fábrica foi para o módulo comum, e o que
 * depende de serviço do servidor entra por {@link ServerNodes}, implementado aqui. Os construtores e os
 * setters continuam os mesmos — quem montava esta fábrica não muda.</p>
 */
@Component
public class DefaultFlowStepFactory extends PortableFlowStepFactory {

    private final ServerSteps server;

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
        super(catalog, interceptors, tenantKeyResolver);
        // ObjectProvider (resolução tardia) porque o grafo de beans é
        // FlowEngine → FlowRepository → WorkflowDeserializer → esta factory:
        // uma injeção direta fecharia o ciclo.
        this.server = new ServerSteps(dynamicWorkflowService, streamRegistry, stateManager, flowEngine);
        setServerNodes(server);
    }

    /** O serviço da tela de Conhecimento, para os nós {@code vector-search}/{@code rag} com uma base escolhida. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setKnowledgeService(
            ObjectProvider<br.com.archflow.api.knowledge.KnowledgeService> knowledgeService) {
        if (knowledgeService != null) {
            server.knowledgeService = knowledgeService::getIfAvailable;
        }
    }

    /** O mesmo, com o serviço já em mãos (testes). */
    public void useKnowledgeService(br.com.archflow.api.knowledge.KnowledgeService service) {
        server.knowledgeService = () -> service;
    }

    /**
     * As conexões vêm das propriedades do servidor — nunca do JSON do nó. A memória JDBC recebe o banco da
     * persistência JDBC, e só com ela ligada ({@code archflow.persistence.jdbc.enabled=true}, RCHFLW-119): é o
     * banco onde o Flyway aplicou a migração da tabela {@code chat_messages}. Um DataSource que exista por outro
     * motivo, sem a persistência ligada, não vira o banco da memória dos fluxos.
     *
     * <p>O {@link org.springframework.beans.factory.ObjectProvider} é lido na execução, quando o adapter nasce:
     * a factory é montada no meio do grafo de beans (ver o construtor) e o banco não precisa existir antes.</p>
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setServerConnections(org.springframework.core.env.Environment environment,
                                     ObjectProvider<javax.sql.DataSource> dataSource) {
        if (environment == null) {
            return;
        }
        boolean jdbc = environment.getProperty(
                br.com.archflow.api.flow.adapter.AdapterConnections.JDBC_PERSISTENCE_PROPERTY, Boolean.class, false);
        setAdapterConnections(br.com.archflow.api.flow.adapter.AdapterConnections.of(environment::getProperty,
                jdbc && dataSource != null ? dataSource::getIfAvailable : null));
    }

    /** Provedores e chaves do nó de decisão, entregues pelo host (ver {@code ArchflowBeanConfiguration}). */
    @Override
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setDecisionSupport(br.com.archflow.decision.DecisionProviders providers,
                                   br.com.archflow.decision.DecisionKeys keys) {
        super.setDecisionSupport(providers, keys);
    }

    /** {@code archflow.llm.allowed-base-urls} — o mesmo bean do caminho dos agentes. */
    @Override
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setBaseUrlPolicy(br.com.archflow.langchain4j.provider.BaseUrlPolicy policy) {
        super.setBaseUrlPolicy(policy);
    }

    /** {@code archflow.llm.provider-preferences} (RCHFLW-41). */
    @Override
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setProviderPreferences(
            br.com.archflow.model.config.ProviderPreferences preferences,
            @org.springframework.beans.factory.annotation.Value(
                    "${archflow.llm.provider-preferences.required:false}") boolean required) {
        super.setProviderPreferences(preferences, required);
    }

    /** {@code allowedModels} do tenant (RCHFLW-79). */
    @Override
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setModelPolicy(br.com.archflow.langchain4j.provider.TenantModelPolicy policy) {
        super.setModelPolicy(policy);
    }

    /** {@code archflow.llm.provider/model} — o nível mais baixo da herança dos nós de chat (RCHFLW-16). */
    @Override
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPlatformDefaultLLM(
            @org.springframework.beans.factory.annotation.Qualifier("platformDefaultLLMConfig")
            br.com.archflow.model.config.ResolvedLLMConfig platformDefaultLLMConfig) {
        super.setPlatformDefaultLLM(platformDefaultLLMConfig);
    }

    /** Os passos que dependem de serviço do servidor — os mesmos de antes da extração. */
    private static final class ServerSteps implements ServerNodes {

        private final DynamicWorkflowService dynamicWorkflowService;
        private final EventStreamRegistry streamRegistry;
        private final StateManager stateManager;
        private final ObjectProvider<FlowEngine> flowEngine;

        // Bases de conhecimento (RCHFLW-55). Resolvido na execução: sem o bean (testes) o nó com base
        // falha dizendo que as bases não estão disponíveis, em vez de cair no adapter.
        private Supplier<br.com.archflow.api.knowledge.KnowledgeService> knowledgeService = () -> null;

        private ServerSteps(DynamicWorkflowService dynamicWorkflowService, EventStreamRegistry streamRegistry,
                            StateManager stateManager, ObjectProvider<FlowEngine> flowEngine) {
            this.dynamicWorkflowService = dynamicWorkflowService;
            this.streamRegistry = streamRegistry;
            this.stateManager = stateManager;
            this.flowEngine = flowEngine;
        }

        @Override
        public FlowStep orchestrate(Request r) {
            // Os sub-agentes herdam o escopo do fluxo — sem isso, um passo
            // restrito delegaria para um agente irrestrito e a restrição seria
            // contornável por delegação.
            return new OrchestrateStep(r.id(), r.connections(), r.config(), dynamicWorkflowService,
                    streamRegistry, stateManager, r.policy());
        }

        @Override
        public FlowStep approval(Request r) {
            return new HumanApprovalStep(r.id(), r.connections(), r.config(),
                    flowEngine == null ? null : flowEngine::getObject);
        }

        @Override
        public FlowStep mcpAgent(Request r) {
            if (!r.policy().isAllowed(br.com.archflow.api.agent.mcp.McpAgentComponent.COMPONENT_ID)) {
                return new ComponentStep(r.id(), StepType.TOOL,
                        br.com.archflow.api.agent.mcp.McpAgentComponent.COMPONENT_ID,
                        r.operation(), r.connections(), r.scopedCatalog(), r.interceptors(), null,
                        r.config());
            }
            var mcpAgent = new br.com.archflow.api.agent.mcp.McpAgentComponent(r.flowPatch());
            mcpAgent.initialize(r.config());
            return new ComponentStep(r.id(), StepType.TOOL, mcpAgent.getMetadata().id(),
                    r.operation(), r.connections(), r.scopedCatalog(), r.interceptors(), mcpAgent,
                    r.config());
        }

        @Override
        public FlowStep knowledgeBase(Request r, boolean rag, AIComponent generator, String generatorProblem) {
            KnowledgeNodeComponent component = new KnowledgeNodeComponent(r.id(),
                    rag ? KnowledgeNodeComponent.Mode.RAG : KnowledgeNodeComponent.Mode.SEARCH,
                    r.config(), knowledgeService, generator, generatorProblem);
            return new ComponentStep(r.id(), StepType.TOOL, KnowledgeNodeComponent.COMPONENT_ID,
                    r.operation(), r.connections(), r.scopedCatalog(), r.interceptors(), component, r.config());
        }
    }
}
