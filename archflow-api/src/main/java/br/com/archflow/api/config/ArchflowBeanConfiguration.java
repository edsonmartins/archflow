package br.com.archflow.api.config;

import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.agent.streaming.RunningFlowsRegistry;
import br.com.archflow.api.admin.impl.GlobalConfigControllerImpl;
import br.com.archflow.api.admin.impl.TenantControllerImpl;
import br.com.archflow.api.admin.impl.WorkspaceControllerImpl;
import br.com.archflow.api.admin.observability.impl.InMemoryTraceStore;
import br.com.archflow.api.admin.observability.impl.ObservabilityControllerImpl;
import br.com.archflow.api.admin.observability.impl.ObservabilityService;
import br.com.archflow.api.agent.impl.AgentControllerImpl;
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
import br.com.archflow.api.workflow.impl.WorkflowConfigControllerImpl;
import br.com.archflow.api.workflow.impl.WorkflowYamlControllerImpl;
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
    public WorkspaceControllerImpl workspaceControllerImpl() {
        return new WorkspaceControllerImpl();
    }

    @Bean
    @ConditionalOnMissingBean
    public ObservabilityControllerImpl observabilityControllerImpl(ObservabilityService service) {
        return new ObservabilityControllerImpl(service);
    }

    // =========================================================================
    // Controller implementations — Realtime
    // =========================================================================

    @Bean
    @ConditionalOnMissingBean
    public RealtimeAdapter realtimeAdapter() {
        return new DevRealtimeAdapter();
    }

    @Bean
    @ConditionalOnMissingBean
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
    public EventControllerImpl eventControllerImpl() {
        return new EventControllerImpl();
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
    public WorkflowConfigControllerImpl workflowConfigControllerImpl() {
        return new WorkflowConfigControllerImpl();
    }

    @Bean
    @ConditionalOnMissingBean
    public br.com.archflow.engine.persistence.FlowRepository flowRepository() {
        return new br.com.archflow.agent.persistence.InMemoryFlowRepository();
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowYamlControllerImpl workflowYamlControllerImpl(br.com.archflow.engine.persistence.FlowRepository flowRepository) {
        return new WorkflowYamlControllerImpl(flowRepository);
    }

    // =========================================================================
    // Controller implementations — Agent
    // =========================================================================

    @Bean
    @ConditionalOnMissingBean
    public AgentControllerImpl agentControllerImpl() {
        return new AgentControllerImpl();
    }
}
