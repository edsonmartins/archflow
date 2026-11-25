package br.com.archflow.api.workflow.impl;

import br.com.archflow.agent.governance.GovernanceProfile;
import br.com.archflow.api.admin.dto.GlobalConfigDto.LLMModelDto;
import br.com.archflow.api.workflow.WorkflowConfigController;
import br.com.archflow.api.workflow.dto.AgentPatternDto;
import br.com.archflow.api.workflow.dto.GovernanceProfileDto;
import br.com.archflow.api.workflow.dto.McpServerDto;
import br.com.archflow.api.workflow.dto.PersonaDto;
import br.com.archflow.api.workflow.dto.ProviderDto;
import br.com.archflow.conversation.persona.Persona;
import br.com.archflow.langchain4j.provider.LLMProvider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Framework-agnostic implementation of {@link WorkflowConfigController}.
 *
 * <p>Providers come from {@link LLMProvider}; their models come from the
 * platform catalog cut by the request tenant's allowed models once
 * {@link #setModelCatalog} is wired (RCHFLW-79), and from the enum otherwise.
 * Agent patterns are a static catalog. Personas, governance profiles and MCP servers are
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

    // Catálogo de modelos (RCHFLW-79). Nulo = sem catálogo ligado: a lista do enum, como antes.
    private volatile Supplier<List<LLMModelDto>> activeCatalog;
    private volatile Supplier<Optional<? extends Collection<String>>> tenantAllowed = Optional::empty;
    private volatile String platformProvider;
    private volatile String platformModel;

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

    /**
     * Convenience overload that keeps the default personas / governance
     * but replaces the MCP server supplier — used by the bean config to
     * inject dynamic MCP registrations (e.g. the Linktor integration).
     */
    public WorkflowConfigControllerImpl(Supplier<List<McpServerDto>> mcpServerSupplier) {
        this(() -> DEFAULT_PERSONAS, () -> DEFAULT_GOVERNANCE, mcpServerSupplier);
    }

    public WorkflowConfigControllerImpl(
            Supplier<List<Persona>> personaSupplier,
            Supplier<List<GovernanceProfile>> governanceSupplier,
            Supplier<List<McpServerDto>> mcpServerSupplier) {
        this.personaSupplier = Objects.requireNonNull(personaSupplier, "personaSupplier");
        this.governanceSupplier = Objects.requireNonNull(governanceSupplier, "governanceSupplier");
        this.mcpServerSupplier = Objects.requireNonNull(mcpServerSupplier, "mcpServerSupplier");
    }

    /**
     * Liga o seletor de modelos ao catálogo da plataforma e ao tenant da requisição (RCHFLW-79).
     * Sem isto (testes, hosts mínimos) a lista é a do enum {@link LLMProvider} e o id livre vale.
     *
     * @param activeCatalog    modelos ligados do catálogo global ({@code GlobalConfigControllerImpl})
     * @param tenantAllowed    modelos permitidos ao tenant da requisição; vazio = sem restrição
     * @param platformProvider {@code archflow.llm.provider} — marcado como o que um nó sem provider herda
     */
    public void setModelCatalog(Supplier<List<LLMModelDto>> activeCatalog,
                                Supplier<Optional<? extends Collection<String>>> tenantAllowed,
                                String platformProvider) {
        setModelCatalog(activeCatalog, tenantAllowed, platformProvider, null);
    }

    /**
     * Como {@link #setModelCatalog(Supplier, Supplier, String)}, dizendo também o modelo da plataforma.
     *
     * @param platformModel {@code archflow.llm.model} — vai no provider da plataforma
     *                      ({@link ProviderDto#defaultModel()}) para o painel saber o que um nó sem
     *                      modelo herda e avisar quando o tenant não o permite
     */
    public void setModelCatalog(Supplier<List<LLMModelDto>> activeCatalog,
                                Supplier<Optional<? extends Collection<String>>> tenantAllowed,
                                String platformProvider, String platformModel) {
        this.activeCatalog = activeCatalog;
        this.tenantAllowed = tenantAllowed != null ? tenantAllowed : Optional::empty;
        this.platformProvider = platformProvider == null ? null : platformProvider.trim();
        this.platformModel = platformModel == null || platformModel.isBlank() ? null : platformModel.trim();
    }

    /**
     * Providers com os modelos que um nó deste tenant pode escolher: catálogo ativo ∩ permitidos do
     * tenant. Antes era a lista fixa do enum, que não conversava com o catálogo global (o superadmin
     * desligava um modelo e ele seguia no seletor) nem com a restrição do tenant (que nada aplicava).
     *
     * <p>Os providers aparecem todos, mesmo sem modelo: quem tira do dropdown um provider sem modelo
     * permitido é o painel, que sabe se o nó é de chat ou de agente. Um provider do catálogo que o
     * enum não conhece (adapter de plugin) entra no fim.
     */
    @Override
    public List<ProviderDto> listProviders() {
        List<LLMModelDto> catalog = activeCatalog == null ? null : activeCatalog.get();
        Set<String> allowed = permitidos();
        boolean allowCustom = allowed == null;
        List<ProviderDto> result = new ArrayList<>(LLMProvider.values().length);
        Set<String> listed = new HashSet<>();
        for (LLMProvider p : LLMProvider.values()) {
            List<ProviderDto.ModelDto> models = catalog == null
                    ? p.getModels().stream().map(WorkflowConfigControllerImpl::toDto).toList()
                    : catalog.stream()
                            .filter(m -> p.getId().equalsIgnoreCase(m.provider()))
                            .map(m -> toDto(p, m))
                            .toList();
            listed.add(p.getId());
            result.add(new ProviderDto(
                    p.getId(),
                    p.getDisplayName(),
                    p.requiresApiKey(),
                    p.supportsStreaming(),
                    p == LLMProvider.OLLAMA ? "Local" : "Cloud",
                    recortar(models, allowed),
                    allowCustom,
                    p.getId().equalsIgnoreCase(platformProvider),
                    modeloDaPlataforma(p.getId())));
        }
        if (catalog != null) {
            Map<String, List<ProviderDto.ModelDto>> extras = new LinkedHashMap<>();
            for (LLMModelDto m : catalog) {
                String provider = m.provider() == null ? "" : m.provider().toLowerCase(Locale.ROOT);
                if (!provider.isBlank() && !listed.contains(provider)) {
                    extras.computeIfAbsent(provider, k -> new ArrayList<>())
                            .add(new ProviderDto.ModelDto(m.id(), nome(m), 0, 0));
                }
            }
            extras.forEach((provider, models) -> result.add(new ProviderDto(provider, provider, true, false,
                    "Cloud", recortar(models, allowed), allowCustom, provider.equalsIgnoreCase(platformProvider),
                    modeloDaPlataforma(provider))));
        }
        return result;
    }

    /** O {@code archflow.llm.model}, só no provider da plataforma — nos outros ninguém o herda. */
    private String modeloDaPlataforma(String provider) {
        return provider.equalsIgnoreCase(platformProvider) ? platformModel : null;
    }

    /** Os permitidos do tenant da requisição, ou {@code null} quando ele não tem restrição. */
    private Set<String> permitidos() {
        Optional<? extends Collection<String>> lista = tenantAllowed.get();
        if (lista == null || lista.isEmpty()) {
            return null;
        }
        Set<String> ids = new HashSet<>();
        lista.get().stream().filter(m -> m != null && !m.isBlank()).map(String::trim).forEach(ids::add);
        return ids.isEmpty() ? null : ids;
    }

    private static List<ProviderDto.ModelDto> recortar(List<ProviderDto.ModelDto> models, Set<String> allowed) {
        return allowed == null ? models : models.stream().filter(m -> allowed.contains(m.id())).toList();
    }

    private static ProviderDto.ModelDto toDto(LLMProvider.ModelInfo m) {
        return new ProviderDto.ModelDto(m.id(), m.name(), m.contextWindow(), m.maxTemperature());
    }

    /**
     * Um modelo do catálogo, com janela e temperatura do enum quando o código conhece o id. Um id que
     * só a variável {@code ARCHFLOW_LLM_MODELS} conhece vai com {@code 0} nos dois — "não sei", em vez
     * de um número inventado.
     */
    private static ProviderDto.ModelDto toDto(LLMProvider provider, LLMModelDto m) {
        return provider.getModel(m.id())
                .map(info -> new ProviderDto.ModelDto(m.id(), nome(m), info.contextWindow(), info.maxTemperature()))
                .orElseGet(() -> new ProviderDto.ModelDto(m.id(), nome(m), 0, 0));
    }

    private static String nome(LLMModelDto m) {
        return m.name() == null || m.name().isBlank() ? m.id() : m.name();
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
