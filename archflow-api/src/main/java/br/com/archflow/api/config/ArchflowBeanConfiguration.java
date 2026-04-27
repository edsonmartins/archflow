package br.com.archflow.api.config;

import br.com.archflow.agent.queue.AgentInvocationQueue;
import br.com.archflow.agent.queue.InMemoryAgentInvocationQueue;
import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.agent.streaming.RunningFlowsRegistry;
import br.com.archflow.api.admin.impl.GlobalConfigControllerImpl;
import br.com.archflow.api.admin.impl.TenantControllerImpl;
import br.com.archflow.api.admin.impl.WorkspaceControllerImpl;
import br.com.archflow.api.admin.observability.impl.InMemoryTraceStore;
import br.com.archflow.api.admin.observability.impl.ObservabilityControllerImpl;
import br.com.archflow.api.admin.observability.impl.ObservabilityService;
import br.com.archflow.api.agent.impl.AgentControllerImpl;
import br.com.archflow.api.catalog.CatalogController;
import br.com.archflow.api.catalog.impl.CatalogControllerImpl;
import br.com.archflow.api.apikey.impl.ApiKeyControllerImpl;
import br.com.archflow.api.approval.impl.ApprovalControllerImpl;
import br.com.archflow.api.approval.impl.ApprovalQueueService;
import br.com.archflow.api.auth.impl.AuthControllerImpl;
import br.com.archflow.api.conversation.impl.ConversationControllerImpl;
import br.com.archflow.api.events.impl.EventControllerImpl;
import br.com.archflow.api.events.ingest.impl.EventIngestControllerImpl;
import br.com.archflow.api.marketplace.impl.MarketplaceControllerImpl;
import br.com.archflow.api.realtime.DevRealtimeAdapter;
import br.com.archflow.api.realtime.SpringRealtimeController;
import br.com.archflow.api.template.impl.TemplateControllerImpl;
import br.com.archflow.api.workflow.WorkflowYamlBridge;
import br.com.archflow.api.workflow.impl.WorkflowConfigControllerImpl;
import br.com.archflow.conversation.ConversationManager;
import br.com.archflow.conversation.service.ConversationService;
import br.com.archflow.conversation.service.DefaultConversationService;
import br.com.archflow.marketplace.installer.ExtensionInstaller;
import br.com.archflow.marketplace.registry.ExtensionRegistry;
import br.com.archflow.observability.audit.AuditRepository;
import br.com.archflow.security.apikey.ApiKeyService;
import br.com.archflow.security.auth.AuthService;
import br.com.archflow.security.auth.InMemoryUserRepository;
import br.com.archflow.security.auth.UserRepository;
import br.com.archflow.security.jwt.JwtService;
import br.com.archflow.security.password.PasswordService;
import br.com.archflow.langchain4j.realtime.spi.RealtimeAdapter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Central bean factory for archflow services and controller implementations.
 *
 * <p>All beans use {@link ConditionalOnMissingBean} so downstream users can
 * override any service (e.g., replace InMemoryUserRepository with JDBC).
 */
@Configuration
public class ArchflowBeanConfiguration {

    // =========================================================================
    // Security / Auth
    // =========================================================================

    @Bean
    @ConditionalOnMissingBean
    public PasswordService passwordService() {
        return new PasswordService();
    }

    @Bean
    @ConditionalOnMissingBean
    public JwtService jwtService(
            @Value("${archflow.security.jwt.secret:change-me-in-production-this-must-be-very-long}") String secret) {
        return new JwtService(secret);
    }

    @Bean
    @ConditionalOnMissingBean
    public UserRepository userRepository(PasswordService passwordService) {
        var repo = new InMemoryUserRepository();
        repo.findByUsername("admin").ifPresent(admin -> {
            admin.setPasswordHash(passwordService.hash("admin123"));
            repo.save(admin);
        });
        return repo;
    }

    @Bean
    @ConditionalOnMissingBean
    public AuthService authService(JwtService jwtService, PasswordService passwordService, UserRepository userRepository) {
        return new AuthService(jwtService, passwordService, userRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public ApiKeyService apiKeyService() {
        // In-memory API key repository for dev; override for production
        ApiKeyService.ApiKeyRepository repo = new InMemoryApiKeyRepository();
        return new ApiKeyService(repo);
    }

    /** Simple in-memory implementation of ApiKeyRepository for dev/testing. */
    private static class InMemoryApiKeyRepository implements ApiKeyService.ApiKeyRepository {
        private final java.util.concurrent.ConcurrentHashMap<String, br.com.archflow.model.security.ApiKey> store = new java.util.concurrent.ConcurrentHashMap<>();

        @Override public br.com.archflow.model.security.ApiKey save(br.com.archflow.model.security.ApiKey key) { store.put(key.getId(), key); return key; }
        @Override public java.util.Optional<br.com.archflow.model.security.ApiKey> findById(String id) { return java.util.Optional.ofNullable(store.get(id)); }
        @Override public java.util.Optional<br.com.archflow.model.security.ApiKey> findByKeyId(String keyId) { return store.values().stream().filter(k -> keyId.equals(k.getKeyId())).findFirst(); }
        @Override public java.util.List<br.com.archflow.model.security.ApiKey> findByOwnerId(String ownerId) { return store.values().stream().filter(k -> ownerId.equals(k.getOwnerId())).toList(); }
        @Override public void delete(br.com.archflow.model.security.ApiKey key) { store.remove(key.getId()); }
    }

    // =========================================================================
    // Infrastructure services
    // =========================================================================

    @Bean
    @ConditionalOnMissingBean
    public EventStreamRegistry eventStreamRegistry() {
        return new EventStreamRegistry(60_000, 300_000);
    }

    @Bean
    @ConditionalOnMissingBean
    public RunningFlowsRegistry runningFlowsRegistry() {
        return new RunningFlowsRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public InMemoryTraceStore inMemoryTraceStore() {
        return new InMemoryTraceStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public ObservabilityService observabilityService(
            InMemoryTraceStore traceStore,
            EventStreamRegistry eventStreamRegistry,
            RunningFlowsRegistry runningFlowsRegistry) {
        return new ObservabilityService(null, traceStore, null, eventStreamRegistry, runningFlowsRegistry);
    }

    // =========================================================================
    // Controller implementations — Auth
    // =========================================================================

    @Bean
    public AuthControllerImpl authControllerImpl(
            AuthService authService,
            @Value("${archflow.security.jwt.access-token-expiration-seconds:900}") long ttl) {
        return new AuthControllerImpl(authService, ttl);
    }

    // =========================================================================
    // Controller implementations — Admin (self-contained, no deps)
    // =========================================================================

    @Bean
    @ConditionalOnMissingBean
    public GlobalConfigControllerImpl globalConfigControllerImpl() {
        return new GlobalConfigControllerImpl();
    }

    @Bean
    @ConditionalOnMissingBean
    public TenantControllerImpl tenantControllerImpl() {
        return new TenantControllerImpl();
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkspaceControllerImpl workspaceControllerImpl(
            @Value("${archflow.admin.tenantFallback:}") String tenantFallback) {
        // The property is intentionally empty by default so production
        // deployments fail loud when the X-Tenant-Id header / security
        // filter is not wired. Dev profiles should set it to a demo id.
        String fallback = (tenantFallback == null || tenantFallback.isBlank()) ? null : tenantFallback;
        return new WorkspaceControllerImpl(fallback);
    }

    @Bean
    @ConditionalOnMissingBean
    public ObservabilityControllerImpl observabilityControllerImpl(ObservabilityService service) {
        return new ObservabilityControllerImpl(service);
    }

    // =========================================================================
    // Controller implementations — Realtime
    // =========================================================================

    /**
     * The mock {@link DevRealtimeAdapter} is wired only when the explicit
     * {@code archflow.realtime.adapter=dev} property is set, OR when the
     * active profile list contains the token {@code dev} exactly
     * (matches regex {@code \bdev\b} against the comma-separated list).
     *
     * <p>We avoid substring matching ({@code contains('dev')}) because it
     * silently activates the mock for {@code devops}, {@code development},
     * or any profile name that happens to contain {@code dev}. Better to
     * fail to start than to serve mocked realtime responses in production.
     */
    @Bean
    @ConditionalOnMissingBean(RealtimeAdapter.class)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnExpression(
            "'${archflow.realtime.adapter:}' == 'dev' "
                    + "or ',${spring.profiles.active:},'.contains(',dev,')")
    public RealtimeAdapter devRealtimeAdapter() {
        return new DevRealtimeAdapter();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(RealtimeAdapter.class)
    public SpringRealtimeController springRealtimeController(RealtimeAdapter realtimeAdapter) {
        return new SpringRealtimeController(realtimeAdapter);
    }

    // =========================================================================
    // Controller implementations — API Key
    // =========================================================================

    @Bean
    @ConditionalOnMissingBean
    public ApiKeyControllerImpl apiKeyControllerImpl(ApiKeyService apiKeyService) {
        return new ApiKeyControllerImpl(apiKeyService);
    }

    // =========================================================================
    // Controller implementations — Approval (HITL)
    // =========================================================================

    @Bean
    @ConditionalOnMissingBean
    public ApprovalQueueService approvalQueueService() {
        return new ApprovalQueueService(new br.com.archflow.conversation.approval.ApprovalRegistry());
    }

    @Bean
    @ConditionalOnMissingBean
    public ApprovalControllerImpl approvalControllerImpl(ApprovalQueueService approvalQueueService) {
        return new ApprovalControllerImpl(approvalQueueService);
    }

    // =========================================================================
    // Controller implementations — Conversation
    // =========================================================================

    @Bean
    @ConditionalOnMissingBean
    public ConversationControllerImpl conversationControllerImpl(ConversationService conversationService) {
        return new ConversationControllerImpl(conversationService);
    }

    @Bean
    @ConditionalOnMissingBean
    public ConversationService conversationService() {
        return new DefaultConversationService(ConversationManager.getInstance());
    }

    // =========================================================================
    // Controller implementations — Events
    // =========================================================================

    @Bean
    @ConditionalOnMissingBean
    public AgentInvocationQueue agentInvocationQueue() {
        return new InMemoryAgentInvocationQueue();
    }

    @Bean
    @ConditionalOnMissingBean
    public EventControllerImpl eventControllerImpl(AgentInvocationQueue agentInvocationQueue) {
        return new EventControllerImpl(agentInvocationQueue);
    }

    @Bean
    @ConditionalOnMissingBean
    public EventIngestControllerImpl eventIngestControllerImpl(EventStreamRegistry registry) {
        return new EventIngestControllerImpl(registry);
    }

    // =========================================================================
    // Controller implementations — Marketplace
    // =========================================================================

    @Bean
    @ConditionalOnMissingBean
    public MarketplaceControllerImpl marketplaceControllerImpl() {
        return new MarketplaceControllerImpl(
                ExtensionRegistry.getInstance(),
                new ExtensionInstaller(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "archflow-extensions")));
    }

    // =========================================================================
    // Controller implementations — Templates
    // =========================================================================

    @Bean
    @ConditionalOnMissingBean
    public TemplateControllerImpl templateControllerImpl() {
        return new TemplateControllerImpl();
    }

    // =========================================================================
    // Controller implementations — Workflow
    // =========================================================================

    @Bean
    @ConditionalOnMissingBean
    public WorkflowConfigControllerImpl workflowConfigControllerImpl(
            br.com.archflow.api.linktor.LinktorConfigController linktorConfigController) {
        // Linktor appears in the MCP dropdown as soon as the admin
        // enables it in /admin/linktor — no restart needed. We resolve
        // the list lazily so each /workflow/mcp-servers call sees the
        // latest config state.
        return new WorkflowConfigControllerImpl(() -> {
            var cfg = linktorConfigController.get();
            if (!cfg.enabled() || cfg.mcpCommand() == null || cfg.mcpCommand().isBlank()) {
                return java.util.List.of();
            }
            return java.util.List.of(new br.com.archflow.api.workflow.dto.McpServerDto(
                    br.com.archflow.api.linktor.impl.LinktorConfigControllerImpl.SERVER_NAME,
                    "stdio",
                    cfg.mcpCommand(),
                    cfg.apiBaseUrl(),
                    0));
        });
    }

    @Bean
    @ConditionalOnMissingBean
    public br.com.archflow.engine.persistence.FlowRepository flowRepository() {
        return new br.com.archflow.agent.persistence.InMemoryFlowRepository();
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowYamlBridge workflowYamlBridge() {
        return new WorkflowYamlBridge();
    }

    // =========================================================================
    // Controller implementations — Agent
    // =========================================================================

    @Bean
    @ConditionalOnMissingBean
    public AgentControllerImpl agentControllerImpl(AgentInvocationQueue agentInvocationQueue) {
        return new AgentControllerImpl(agentInvocationQueue);
    }

    // =========================================================================
    // Scheduled triggers (Quartz)
    // =========================================================================

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean
    public org.quartz.Scheduler quartzScheduler() throws org.quartz.SchedulerException {
        // In-memory Quartz scheduler — sufficient for single-instance dev
        // deployments. Production clusters should override this bean with
        // a JDBC-backed scheduler for job persistence across restarts.
        org.quartz.impl.StdSchedulerFactory factory = new org.quartz.impl.StdSchedulerFactory();
        java.util.Properties props = new java.util.Properties();
        props.setProperty("org.quartz.scheduler.instanceName", "archflow-triggers");
        props.setProperty("org.quartz.threadPool.class", "org.quartz.simpl.SimpleThreadPool");
        props.setProperty("org.quartz.threadPool.threadCount", "4");
        props.setProperty("org.quartz.jobStore.class", "org.quartz.simpl.RAMJobStore");
        factory.initialize(props);
        org.quartz.Scheduler scheduler = factory.getScheduler();
        scheduler.start();
        return scheduler;
    }

    @Bean
    @ConditionalOnMissingBean
    public br.com.archflow.api.triggers.ScheduledTriggerController scheduledTriggerController(
            org.quartz.Scheduler scheduler,
            AgentInvocationQueue agentInvocationQueue) {
        return new br.com.archflow.api.triggers.impl.ScheduledTriggerControllerImpl(
                scheduler, agentInvocationQueue);
    }

    // =========================================================================
    // Catalog (agents/assistants/tools + langchain4j adapters)
    // =========================================================================

    @Bean
    @ConditionalOnMissingBean
    public br.com.archflow.plugin.api.catalog.ComponentCatalog componentCatalog() {
        // Dev-friendly default so the catalog is never null. Seeds built-in
        // plugins via reflection so they show up in the UI without
        // forcing a hard dependency loop — if a plugin jar is missing
        // from the deployment classpath it is silently skipped.
        br.com.archflow.plugin.api.catalog.ComponentCatalog catalog =
                new br.com.archflow.plugin.api.catalog.DefaultComponentCatalog();
        String[] builtIns = {
                "br.com.archflow.plugins.agents.ConversationalAgent",
                "br.com.archflow.plugins.agents.ResearchAgent",
                "br.com.archflow.plugins.agents.DataAnalysisAgent",
                "br.com.archflow.plugins.agents.MonitoringAgent",
                "br.com.archflow.plugins.assistants.TechSupportAssistant",
                "br.com.archflow.plugins.tools.TextTransformTool"
        };
        for (String className : builtIns) {
            try {
                Class<?> cls = Class.forName(className);
                Object instance = cls.getDeclaredConstructor().newInstance();
                if (instance instanceof br.com.archflow.model.ai.AIComponent aic) {
                    catalog.register(aic);
                }
            } catch (Throwable ignored) {
                // Plugin jar not on classpath or construction failed — fine.
            }
        }
        return catalog;
    }

    @Bean
    @ConditionalOnMissingBean
    public CatalogController catalogController(
            br.com.archflow.plugin.api.catalog.ComponentCatalog componentCatalog) {
        return new CatalogControllerImpl(componentCatalog);
    }

    // =========================================================================
    // Skills (read + activate/deactivate adapter)
    // =========================================================================

    @Bean
    @ConditionalOnMissingBean
    public br.com.archflow.langchain4j.skills.SkillsManager skillsManager(
            @Value("${archflow.skills.directory:}") String skillsDirectory) {
        br.com.archflow.langchain4j.skills.SkillsManager manager =
                new br.com.archflow.langchain4j.skills.SkillsManager();
        if (skillsDirectory != null && !skillsDirectory.isBlank()) {
            try {
                br.com.archflow.langchain4j.skills.FileSystemSkillLoader loader =
                        new br.com.archflow.langchain4j.skills.FileSystemSkillLoader(
                                java.nio.file.Path.of(skillsDirectory));
                manager.loadFrom(loader);
            } catch (Exception ignored) {
                // Directory missing / unreadable — leave manager empty.
            }
        }
        return manager;
    }

    @Bean
    @ConditionalOnMissingBean
    public br.com.archflow.api.skills.SkillsController skillsController(
            br.com.archflow.langchain4j.skills.SkillsManager skillsManager) {
        return new br.com.archflow.api.skills.impl.SkillsControllerImpl(skillsManager);
    }

    // =========================================================================
    // MCP inspection (read-only)
    // =========================================================================

    @Bean
    @ConditionalOnMissingBean
    public br.com.archflow.api.mcp.McpInspectionController mcpInspectionController(
            br.com.archflow.api.linktor.LinktorConfigController linktorConfigController) {
        // Built-in integrations register their suppliers here. Other
        // deployments are free to override this bean entirely.
        java.util.Map<String, java.util.function.Supplier<br.com.archflow.langchain4j.mcp.McpClient>> registry =
                new java.util.LinkedHashMap<>();
        registry.put(
                br.com.archflow.api.linktor.impl.LinktorConfigControllerImpl.SERVER_NAME,
                () -> {
                    var supplier = linktorConfigController.clientSupplier();
                    var client = supplier.get();
                    if (client == null) {
                        throw new IllegalStateException(
                                "Linktor integration is disabled or mcpCommand is empty");
                    }
                    return client;
                });
        return new br.com.archflow.api.mcp.impl.McpInspectionControllerImpl(registry);
    }

    // =========================================================================
    // Linktor runtime config
    // =========================================================================

    @Bean
    @ConditionalOnMissingBean
    public br.com.archflow.api.linktor.LinktorHttpClient linktorHttpClient(
            br.com.archflow.api.linktor.LinktorConfigController linktorConfigController) {
        return new br.com.archflow.api.linktor.LinktorHttpClient(linktorConfigController);
    }

    /**
     * Registers a Linktor-backed {@link br.com.archflow.model.escalation.EscalationChannel}
     * both as a Spring bean and as the process-wide default consumed by
     * reflection-loaded plugin agents via
     * {@link br.com.archflow.model.escalation.EscalationChannels#getDefault()}.
     */
    @Bean
    @ConditionalOnMissingBean(br.com.archflow.model.escalation.EscalationChannel.class)
    public br.com.archflow.model.escalation.EscalationChannel linktorEscalationChannel(
            br.com.archflow.api.linktor.LinktorHttpClient linktorHttpClient) {
        br.com.archflow.model.escalation.EscalationChannel ch =
                new br.com.archflow.api.linktor.escalation.LinktorEscalationChannel(linktorHttpClient);
        br.com.archflow.model.escalation.EscalationChannels.setDefault(ch);
        return ch;
    }

    /**
     * Registers the Linktor flow publisher so any flow whose
     * {@link br.com.archflow.model.engine.ExecutionContext} carries a
     * {@code conversationId} has its final output automatically posted
     * as a Linktor message when the flow completes.
     *
     * <p>Wire-up uses the process-wide
     * {@link br.com.archflow.engine.lifecycle.FlowLifecycleListeners}
     * registry: when {@link br.com.archflow.agent.ArchFlowAgent} builds
     * its engine it snapshots this registry into a composite listener.</p>
     */
    @Bean
    @ConditionalOnMissingBean(br.com.archflow.api.linktor.publisher.LinktorFlowPublisher.class)
    public br.com.archflow.api.linktor.publisher.LinktorFlowPublisher linktorFlowPublisher(
            br.com.archflow.api.linktor.LinktorHttpClient linktorHttpClient) {
        br.com.archflow.api.linktor.publisher.LinktorFlowPublisher publisher =
                new br.com.archflow.api.linktor.publisher.LinktorFlowPublisher(linktorHttpClient);
        br.com.archflow.engine.lifecycle.FlowLifecycleListeners.register(publisher);
        return publisher;
    }

    @Bean
    @ConditionalOnMissingBean
    public br.com.archflow.api.linktor.LinktorConfigController linktorConfigController(
            @Value("${archflow.linktor.enabled:false}") boolean enabled,
            @Value("${archflow.linktor.apiBaseUrl:http://localhost:8081/api/v1}") String apiBaseUrl,
            @Value("${archflow.linktor.apiKey:}") String apiKey,
            @Value("${archflow.linktor.accessToken:}") String accessToken,
            @Value("${archflow.linktor.mcpCommand:}") String mcpCommand,
            @Value("${archflow.linktor.timeoutSeconds:30}") long timeoutSeconds) {
        return new br.com.archflow.api.linktor.impl.LinktorConfigControllerImpl(
                new br.com.archflow.api.linktor.dto.LinktorConfigDto(
                        enabled, apiBaseUrl, apiKey, accessToken, mcpCommand, timeoutSeconds));
    }

    // =========================================================================
    // BrainSentry runtime config
    // =========================================================================

    @Bean
    @ConditionalOnMissingBean
    public br.com.archflow.api.brainsentry.BrainSentryConfigController brainSentryConfigController(
            @Value("${archflow.brainsentry.baseUrl:}") String baseUrl,
            @Value("${archflow.brainsentry.apiKey:}") String apiKey,
            @Value("${archflow.brainsentry.tenantId:}") String tenantId,
            @Value("${archflow.brainsentry.maxTokenBudget:2000}") int budget,
            @Value("${archflow.brainsentry.deepAnalysis:false}") boolean deep,
            @Value("${archflow.brainsentry.timeoutSeconds:10}") long timeoutSeconds,
            @Value("${archflow.brainsentry.enabled:false}") boolean enabled) {
        br.com.archflow.api.brainsentry.dto.BrainSentryConfigDto initial =
                new br.com.archflow.api.brainsentry.dto.BrainSentryConfigDto(
                        enabled, baseUrl, apiKey, tenantId,
                        budget, deep, timeoutSeconds);
        return new br.com.archflow.api.brainsentry.impl.BrainSentryConfigControllerImpl(initial);
    }
}
