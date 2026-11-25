package br.com.archflow.api.orchestration;

import br.com.archflow.agent.confidence.ConfidenceScorer;
import br.com.archflow.agent.orchestration.CatalogAgentWorker;
import br.com.archflow.agent.orchestration.ConfidenceVoter;
import br.com.archflow.agent.orchestration.DynamicSupervisor;
import br.com.archflow.agent.orchestration.LlmPlanner;
import br.com.archflow.agent.orchestration.OrchestrationListener;
import br.com.archflow.agent.orchestration.OrchestrationTrace;
import br.com.archflow.agent.orchestration.SupervisorConfig;
import br.com.archflow.agent.orchestration.SupervisorResult;
import br.com.archflow.api.agent.mcp.ContadorDeUso;
import br.com.archflow.api.usage.LancamentoDeUso;
import br.com.archflow.api.usage.RegistroDeUso;
import br.com.archflow.conversation.agent.ConversationalAgent.ChatFunction;
import br.com.archflow.langchain4j.provider.LLMConfigResolver;
import br.com.archflow.langchain4j.provider.LLMResolutionRequest;
import br.com.archflow.model.config.ResolvedLLMConfig;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.engine.ModelUsageListener;
import br.com.archflow.orchestration.Budget;
import br.com.archflow.orchestration.BudgetLedger;
import br.com.archflow.orchestration.ConvergePolicy;
import br.com.archflow.orchestration.DefaultOrchestrator;
import br.com.archflow.orchestration.Goal;
import br.com.archflow.orchestration.Planner;
import br.com.archflow.orchestration.VerifyPolicy;
import br.com.archflow.orchestration.Voter;
import br.com.archflow.orchestration.Worker;
import br.com.archflow.plugin.api.catalog.ComponentAccessPolicy;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import br.com.archflow.plugin.api.catalog.ComponentQueryRouter;
import br.com.archflow.plugin.api.catalog.DefaultComponentQueryRouter;
import br.com.archflow.plugin.api.catalog.ScopedComponentCatalog;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Fires a real dynamic workflow end-to-end (ADR-0002 P1): builds an LLM
 * {@link ChatFunction} from the {@link LLMConfigResolver}, wires the catalog
 * routing worker, confidence voter and LLM planner into a {@link DynamicSupervisor}
 * and runs the decompose → fan-out → verify → loop-until-dry pattern under a
 * token {@link Budget}. The generic machinery lives in the substrate; this
 * service is just the (tenant-aware, governable) entry point.
 */
@Service
public class DynamicWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(DynamicWorkflowService.class);

    private static final String DEFAULT_DECOMPOSE_PROMPT =
            "Decompose the goal into independent, concrete subtasks.";

    private final LLMConfigResolver llmConfigResolver;
    private final ResolvedLLMConfig platformDefault;
    private final ComponentCatalog catalog;
    private final ComponentQueryRouter router;
    private final ConfidenceScorer scorer;

    /** Onde o consumo da rota REST fica medido, por tenant (RCHFLW-75); {@code null} não registra. */
    private volatile RegistroDeUso registroDeUso;

    public DynamicWorkflowService(LLMConfigResolver llmConfigResolver,
                                  ResolvedLLMConfig platformDefaultLLMConfig,
                                  ComponentCatalog catalog,
                                  ComponentQueryRouter router,
                                  ConfidenceScorer scorer) {
        this.llmConfigResolver = llmConfigResolver;
        this.platformDefault = platformDefaultLLMConfig;
        this.catalog = catalog;
        this.router = router;
        this.scorer = scorer;
    }

    /**
     * Liga o registro do consumo da rota REST. No passo {@code ORCHESTRATE} quem registra é o motor
     * (o contador já vem no contexto do fluxo); fora de fluxo não havia contador nenhum, e o gasto do
     * planejador e dos agentes-trabalhadores não entrava no uso do tenant.
     */
    @Autowired(required = false)
    public void setRegistroDeUso(RegistroDeUso registroDeUso) {
        this.registroDeUso = registroDeUso;
    }

    /**
     * Entry point that creates its own ExecutionContext (REST path), with a trace.
     *
     * <p>O contador vai no contexto, como o motor faz num fluxo: o planejador e os agentes do catálogo
     * relatam a ele. O registro fica no {@code finally} — uma orquestração que falha no meio já gastou.
     */
    public DynamicWorkflowResponse run(DynamicWorkflowRequest req) {
        ExecutionContext ctx = new DefaultExecutionContext(
                req.tenantId(), "orchestrator", UUID.randomUUID().toString(),
                MessageWindowChatMemory.builder().maxMessages(20).build());
        ContadorDeUso uso = new ContadorDeUso();
        ContadorDeUso.injetar(ctx, uso);
        OrchestrationTrace trace = new OrchestrationTrace();
        try {
            SupervisorResult result = runOn(req, ctx, trace);
            return new DynamicWorkflowResponse(
                    result.confirmed(), result.confirmedCount(), result.rounds(), trace.entries());
        } finally {
            RegistroDeUso registro = registroDeUso;
            if (registro != null) {
                registro.registrar(ctx.getTenantId(), null, LancamentoDeUso.ORIGEM_ORQUESTRACAO, uso);
            }
        }
    }

    /** As {@link #runOn(DynamicWorkflowRequest, ExecutionContext, OrchestrationListener)} with no listener. */
    public SupervisorResult runOn(DynamicWorkflowRequest req, ExecutionContext ctx) {
        return runOn(req, ctx, OrchestrationListener.NOOP);
    }

    /**
     * Runs a dynamic workflow on a caller-provided {@link ExecutionContext}, so an
     * embedding flow step ({@code OrchestrateStep}) shares the flow's context with
     * the worker agents. Wires LlmPlanner + CatalogAgentWorker + ConfidenceVoter
     * into a DynamicSupervisor under an optional token budget, reporting progress
     * to {@code listener}.
     */
    public SupervisorResult runOn(DynamicWorkflowRequest req, ExecutionContext ctx, OrchestrationListener listener) {
        return runOn(req, ctx, listener, ComponentAccessPolicy.allowAll());
    }

    /**
     * Como {@link #runOn(DynamicWorkflowRequest, ExecutionContext, OrchestrationListener)},
     * restringindo quais componentes os agentes-trabalhadores podem ser.
     *
     * <p>A restrição precisa alcançar TAMBÉM o roteador: se o worker enxergasse
     * um catálogo restrito mas o roteador o irrestrito, o roteador escolheria um
     * componente que o worker depois não resolve — falharia fechado, mas com
     * mensagem enganosa e desperdiçando a rodada.
     */
    public SupervisorResult runOn(DynamicWorkflowRequest req, ExecutionContext ctx,
                                  OrchestrationListener listener,
                                  ComponentAccessPolicy componentPolicy) {
        if (req.goal() == null || req.goal().isBlank()) {
            throw new IllegalArgumentException("goal is required");
        }

        int voters = orDefault(req.voters(), 1);
        VerifyPolicy verifyPolicy = req.minAgree() != null
                ? new VerifyPolicy(voters, req.minAgree(), java.util.List.of())
                : VerifyPolicy.majority(voters);

        SupervisorConfig config = new SupervisorConfig(
                req.decomposePrompt() == null ? DEFAULT_DECOMPOSE_PROMPT : req.decomposePrompt(),
                orDefault(req.maxSubtasks(), 8),
                verifyPolicy,
                ConvergePolicy.untilDry(orDefault(req.maxRounds(), 5)));

        // Resolve the model once and reuse it as the planner's chat seam.
        // O tenant é o do contexto de execução (o do fluxo, no passo ORCHESTRATE; o da requisição,
        // conferido com o token, na rota REST): escolhe a chave dele e aplica os modelos permitidos
        // (RCHFLW-79). Sem ele o planejador rodava sempre como "nenhum tenant".
        LLMResolutionRequest llmRequest = LLMResolutionRequest.builder(platformDefault)
                .tenantId(ctx.getTenantId())
                .build();
        ChatModel model = llmConfigResolver.resolveModel(llmRequest);
        ChatFunction chat = plannerChat(model, ctx, configParaUso(llmRequest));

        ComponentAccessPolicy policy = componentPolicy != null
                ? componentPolicy : ComponentAccessPolicy.allowAll();
        ComponentCatalog scopedCatalog = ScopedComponentCatalog.of(catalog, policy);
        ComponentQueryRouter scopedRouter = scopedCatalog == catalog
                ? router
                : new DefaultComponentQueryRouter(scopedCatalog);

        Planner<String> planner = new LlmPlanner(chat);
        Worker<String, Object> worker = new CatalogAgentWorker(scopedRouter, scopedCatalog, ctx);
        Voter<String> voter = new ConfidenceVoter(scorer, req.goal());

        BudgetLedger budget = req.budgetTokens() != null
                ? new BudgetLedger(Budget.ofTokens(req.budgetTokens()))
                : BudgetLedger.unlimited();

        DynamicSupervisor supervisor = new DynamicSupervisor(new DefaultOrchestrator(orDefault(req.concurrency(), 4)));
        return supervisor.run(Goal.of(req.goal()), config, planner, worker, voter, budget, listener);
    }

    /**
     * O canal de chat do planejador, relatando cada chamada ao {@link ModelUsageListener} do contexto
     * (RCHFLW-75). Antes era {@code model::chat} — texto entra, texto sai — e o uso que o provedor
     * devolve se perdia: o planejamento gastava a chave do tenant sem aparecer no detalhe da execução,
     * nas telas de uso nem no teto de custo.
     *
     * <p>Sem ouvinte a chamada é a de antes ({@code chat(String)}). Com ele, vai como lista de uma
     * {@link UserMessage} — a mesma requisição, com a resposta inteira, de onde sai o uso (a mesma regra
     * de {@code ChatTurn}, dos nós {@code llm-chat}). O ouvinte é lido a cada chamada, não na montagem.
     */
    static ChatFunction plannerChat(ChatModel model, ExecutionContext ctx, ResolvedLLMConfig config) {
        String provedor = config == null || config.provider() == null || config.provider().isBlank()
                ? null : config.provider();
        String rotulo = config == null || config.model() == null || config.model().isBlank() ? null
                : provedor == null ? config.model() : provedor + "/" + config.model();
        return prompt -> {
            var listener = ModelUsageListener.from(ctx);
            if (listener.isEmpty()) {
                return model.chat(prompt);
            }
            ChatResponse response = model.chat(List.of(UserMessage.from(prompt)));
            TokenUsage tokens = response.tokenUsage();
            listener.get().onModelCall(provedor, rotulo,
                    tokens == null || tokens.inputTokenCount() == null ? null : tokens.inputTokenCount().longValue(),
                    tokens == null || tokens.outputTokenCount() == null ? null
                            : tokens.outputTokenCount().longValue());
            return response.aiMessage().text();
        };
    }

    /**
     * A configuração resolvida, só para o rótulo {@code provider/modelo} do uso — a chave com que o
     * operador declara preço, a mesma regra do laço de agente. Falhar aqui não derruba o planejador:
     * sem rótulo, o uso sai sem modelo e sem custo, que é honesto.
     */
    private ResolvedLLMConfig configParaUso(LLMResolutionRequest llmRequest) {
        try {
            return llmConfigResolver.resolve(llmRequest);
        } catch (RuntimeException e) {
            log.debug("Sem configuração resolvida para o uso do planejador: {}", e.getMessage());
            return null;
        }
    }

    private static int orDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }
}
