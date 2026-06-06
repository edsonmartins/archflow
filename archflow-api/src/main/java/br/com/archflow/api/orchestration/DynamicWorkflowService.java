package br.com.archflow.api.orchestration;

import br.com.archflow.agent.confidence.ConfidenceScorer;
import br.com.archflow.agent.orchestration.CatalogAgentWorker;
import br.com.archflow.agent.orchestration.ConfidenceVoter;
import br.com.archflow.agent.orchestration.DynamicSupervisor;
import br.com.archflow.agent.orchestration.LlmPlanner;
import br.com.archflow.agent.orchestration.SupervisorConfig;
import br.com.archflow.agent.orchestration.SupervisorResult;
import br.com.archflow.conversation.agent.ConversationalAgent.ChatFunction;
import br.com.archflow.langchain4j.provider.LLMConfigResolver;
import br.com.archflow.langchain4j.provider.LLMResolutionRequest;
import br.com.archflow.model.config.ResolvedLLMConfig;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.orchestration.Budget;
import br.com.archflow.orchestration.BudgetLedger;
import br.com.archflow.orchestration.ConvergePolicy;
import br.com.archflow.orchestration.DefaultOrchestrator;
import br.com.archflow.orchestration.Goal;
import br.com.archflow.orchestration.Planner;
import br.com.archflow.orchestration.VerifyPolicy;
import br.com.archflow.orchestration.Voter;
import br.com.archflow.orchestration.Worker;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import br.com.archflow.plugin.api.catalog.ComponentQueryRouter;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.stereotype.Service;

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

    private static final String DEFAULT_DECOMPOSE_PROMPT =
            "Decompose the goal into independent, concrete subtasks.";

    private final LLMConfigResolver llmConfigResolver;
    private final ResolvedLLMConfig platformDefault;
    private final ComponentCatalog catalog;
    private final ComponentQueryRouter router;
    private final ConfidenceScorer scorer;

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

    /** Entry point that creates its own ExecutionContext (REST path). */
    public DynamicWorkflowResponse run(DynamicWorkflowRequest req) {
        ExecutionContext ctx = new DefaultExecutionContext(
                req.tenantId(), "orchestrator", UUID.randomUUID().toString(),
                MessageWindowChatMemory.builder().maxMessages(20).build());
        SupervisorResult result = runOn(req, ctx);
        return new DynamicWorkflowResponse(result.confirmed(), result.confirmedCount(), result.rounds());
    }

    /**
     * Runs a dynamic workflow on a caller-provided {@link ExecutionContext}, so an
     * embedding flow step ({@code OrchestrateStep}) shares the flow's context with
     * the worker agents. Wires LlmPlanner + CatalogAgentWorker + ConfidenceVoter
     * into a DynamicSupervisor under an optional token budget.
     */
    public SupervisorResult runOn(DynamicWorkflowRequest req, ExecutionContext ctx) {
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
        ChatModel model = llmConfigResolver.resolveModel(
                LLMResolutionRequest.builder(platformDefault).build());
        ChatFunction chat = model::chat;

        Planner<String> planner = new LlmPlanner(chat);
        Worker<String, Object> worker = new CatalogAgentWorker(router, catalog, ctx);
        Voter<String> voter = new ConfidenceVoter(scorer, req.goal());

        BudgetLedger budget = req.budgetTokens() != null
                ? new BudgetLedger(Budget.ofTokens(req.budgetTokens()))
                : BudgetLedger.unlimited();

        DynamicSupervisor supervisor = new DynamicSupervisor(new DefaultOrchestrator(orDefault(req.concurrency(), 4)));
        return supervisor.run(Goal.of(req.goal()), config, planner, worker, voter, budget);
    }

    private static int orDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }
}
