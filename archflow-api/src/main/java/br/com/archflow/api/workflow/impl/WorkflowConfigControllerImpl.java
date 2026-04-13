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

    /**
     * Creates an implementation with empty persona, governance and MCP
     * catalogs. Useful for a minimal bootstrap or unit tests.
     */
    public WorkflowConfigControllerImpl() {
        this(Collections::emptyList, Collections::emptyList, Collections::emptyList);
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
