package br.com.archflow.api.workflow.impl;

import br.com.archflow.agent.governance.GovernanceProfile;
import br.com.archflow.api.workflow.WorkflowConfigController;
import br.com.archflow.api.workflow.dto.AgentPatternDto;
import br.com.archflow.api.workflow.dto.GovernanceProfileDto;
import br.com.archflow.api.workflow.dto.McpServerDto;
import br.com.archflow.api.workflow.dto.PersonaDto;
import br.com.archflow.api.workflow.dto.ProviderDto;
import br.com.archflow.conversation.persona.Persona;
import br.com.archflow.langchain4j.provider.LLMProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Framework-agnostic implementation of {@link WorkflowConfigController}.
 *
 * <p>Providers and agent patterns are platform-wide and known at compile
 * time — they are read directly from {@link LLMProvider} and a static
 * pattern catalog. Personas, governance profiles and MCP servers are
 * product-specific, so the caller injects a {@link Supplier} for each.
 * Suppliers that return {@code null} are treated as empty lists,
 * allowing a minimal setup to omit features it doesn't use.
 */
public class WorkflowConfigControllerImpl implements WorkflowConfigController {

    private static final List<AgentPatternDto> AGENT_PATTERNS = List.of(
            new AgentPatternDto(
                    "react",
                    "ReAct (Reason + Act)",
                    "Iterative thought-action-observation loop. Best for multi-step tool use."),
            new AgentPatternDto(
                    "plan-execute",
                    "Plan and Execute",
                    "Separates planning from execution. Cost-efficient for complex tasks."),
            new AgentPatternDto(
                    "rewoo",
                    "ReWOO (Reasoning Without Observation)",
                    "Plans all tool calls upfront. ~82% fewer tokens than ReAct."),
            new AgentPatternDto(
                    "chain-of-thought",
                    "Chain of Thought",
                    "Multiple reasoning paths with majority vote. Best for analytical tasks.")
    );

    private final Supplier<List<Persona>> personaSupplier;
    private final Supplier<List<GovernanceProfile>> governanceSupplier;
    private final Supplier<List<McpServerDto>> mcpServerSupplier;

    private static final List<Persona> DEFAULT_PERSONAS = List.of(
            Persona.of("order_tracking", "Order Tracking",
                    "prompts/order_tracking", List.of("crm_lookup", "order_status"),
                    "order", "tracking", "delivery"),
            Persona.of("customer_support", "Customer Support",
                    "prompts/customer_support", List.of("crm_lookup", "ticket_create"),
                    "help", "support", "issue", "problem"),
            Persona.of("product_recommendation", "Product Recommendation",
                    "prompts/product_recommendation", List.of("search_products", "recommend"),
                    "recommend", "suggest", "product"),
            Persona.of("code_review", "Code Review",
                    "prompts/code_review", List.of("lint_diff", "sast_diff"),
                    "review", "pull.?request", "merge.?request"),
            Persona.of("data_analysis", "Data Analysis",
                    "prompts/data_analysis", List.of("query_db", "chart_generate"),
                    "data", "analysis", "report", "metric")
    );

    private static final List<GovernanceProfile> DEFAULT_GOVERNANCE = List.of(
            GovernanceProfile.builder()
                    .id("default")
                    .name("Default")
                    .systemPrompt("You are a helpful assistant.")
                    .escalationThreshold(0.4)
                    .maxToolExecutions(10)
                    .build(),
            GovernanceProfile.builder()
                    .id("strict")
                    .name("Strict")
                    .systemPrompt("You are a careful assistant that always verifies before acting.")
                    .escalationThreshold(0.7)
                    .maxToolExecutions(5)
                    .customInstructions("Never disclose PII. Always confirm destructive actions.")
                    .build(),
            GovernanceProfile.builder()
                    .id("autonomous")
                    .name("Autonomous")
                    .systemPrompt("You are an autonomous agent. Execute tasks efficiently.")
                    .escalationThreshold(0.2)
                    .maxToolExecutions(50)
                    .build()
    );

    /**
     * Creates an implementation with built-in default personas and
     * governance profiles. MCP servers default to empty.
     */
    public WorkflowConfigControllerImpl() {
        this(() -> DEFAULT_PERSONAS, () -> DEFAULT_GOVERNANCE, Collections::emptyList);
    }

    public WorkflowConfigControllerImpl(
            Supplier<List<Persona>> personaSupplier,
            Supplier<List<GovernanceProfile>> governanceSupplier,
            Supplier<List<McpServerDto>> mcpServerSupplier) {
        this.personaSupplier = Objects.requireNonNull(personaSupplier, "personaSupplier");
        this.governanceSupplier = Objects.requireNonNull(governanceSupplier, "governanceSupplier");
        this.mcpServerSupplier = Objects.requireNonNull(mcpServerSupplier, "mcpServerSupplier");
    }

    @Override
    public List<ProviderDto> listProviders() {
        List<ProviderDto> result = new ArrayList<>(LLMProvider.values().length);
        for (LLMProvider p : LLMProvider.values()) {
            List<ProviderDto.ModelDto> models = p.getModels().stream()
                    .map(m -> new ProviderDto.ModelDto(
                            m.id(),
                            m.name(),
                            m.contextWindow(),
                            m.maxTemperature()))
                    .toList();
            result.add(new ProviderDto(
                    p.getId(),
                    p.getDisplayName(),
                    p.requiresApiKey(),
                    p.supportsStreaming(),
                    p == LLMProvider.OLLAMA ? "Local" : "Cloud",
                    models));
        }
        return result;
    }

    @Override
    public List<AgentPatternDto> listAgentPatterns() {
        return AGENT_PATTERNS;
    }

    @Override
    public List<PersonaDto> listPersonas() {
        List<Persona> personas = personaSupplier.get();
        if (personas == null || personas.isEmpty()) return List.of();
        return personas.stream()
                .map(p -> new PersonaDto(p.id(), p.label(), p.description(), p.promptId()))
                .toList();
    }

    @Override
    public List<GovernanceProfileDto> listGovernanceProfiles() {
        List<GovernanceProfile> profiles = governanceSupplier.get();
        if (profiles == null || profiles.isEmpty()) return List.of();
        return profiles.stream()
                .map(g -> new GovernanceProfileDto(
                        g.id(),
                        g.name(),
                        g.systemPrompt(),
                        List.copyOf(g.enabledTools()),
                        List.copyOf(g.disabledTools()),
                        g.escalationThreshold(),
                        g.maxToolExecutions(),
                        g.customInstructions()))
                .toList();
    }

    @Override
    public List<McpServerDto> listMcpServers() {
        List<McpServerDto> servers = mcpServerSupplier.get();
        return servers != null ? servers : List.of();
    }
}
