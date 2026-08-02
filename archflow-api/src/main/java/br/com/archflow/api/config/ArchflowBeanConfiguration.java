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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Central bean factory for archflow services and controller implementations.
 *
 * <p>All beans use {@link ConditionalOnMissingBean} so downstream users can
 * override any service (e.g., replace InMemoryUserRepository with JDBC).
 */
@Configuration
@org.springframework.context.annotation.Import(JdbcPersistenceConfiguration.class)
public class ArchflowBeanConfiguration {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ArchflowBeanConfiguration.class);

    /**
     * Propriedade que liga a persistência durável ({@link JdbcPersistenceConfiguration}).
     *
     * <p>Os defaults em memória que o {@code JdbcPersistenceConfiguration} também
     * define ({@code userRepository}, {@code apiKeyRepository}, {@code quartzScheduler})
     * recuam por esta propriedade, e não apenas por {@link ConditionalOnMissingBean}.
     *
     * <p>Motivo: {@code @ConditionalOnMissingBean} só recua se o outro bean <em>já
     * tiver sido registrado</em>. Como esta classe e a JDBC são ambas
     * component-scanned e esta vem primeiro na ordem de varredura, o bean em
     * memória era registrado antes e o da JDBC falhava com
     * {@code BeanDefinitionOverrideException} — o perfil {@code prod} não subia.
     * A condição por propriedade é determinística e não depende de ordem.
     * ({@code @ConditionalOnMissingBean} foi mantido para que integradores ainda
     * consigam sobrescrever os beans por conta própria.)
     */
    static final String JDBC_ENABLED = "archflow.persistence.jdbc.enabled";

    @Bean
    @ConditionalOnMissingBean
    public br.com.archflow.api.storage.ObjectStorage objectStorage(
            @Value("${archflow.storage.local.root:${java.io.tmpdir}/archflow-storage}") String root) {
        return new br.com.archflow.api.storage.LocalFileObjectStorage(
                java.nio.file.Path.of(root));
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = JDBC_ENABLED, havingValue = "false", matchIfMissing = true)
    public br.com.archflow.api.storage.FileMetadataRepository fileMetadataRepository() {
        return new br.com.archflow.api.storage.InMemoryFileMetadataRepository();
    }

    @Bean
    @ConditionalOnMissingBean
    public br.com.archflow.api.storage.FileStorageService fileStorageService(
            br.com.archflow.api.storage.ObjectStorage storage,
            br.com.archflow.api.storage.FileMetadataRepository repository,
            @Value("${archflow.storage.max-file-size:26214400}") long maxFileSize) {
        return new br.com.archflow.api.storage.FileStorageService(
                storage, repository, maxFileSize);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = JDBC_ENABLED, havingValue = "false", matchIfMissing = true)
    public br.com.archflow.api.jobs.JobRepository jobRepository() {
        return new br.com.archflow.api.jobs.InMemoryJobRepository();
    }

    @Bean
    @ConditionalOnMissingBean
    public br.com.archflow.api.jobs.JobService jobService(
            br.com.archflow.api.jobs.JobRepository repository) {
        return new br.com.archflow.api.jobs.JobService(repository);
    }

    @Bean
    @ConditionalOnMissingBean
    public br.com.archflow.api.jobs.JobHandlerRegistry jobHandlerRegistry(
            java.util.List<br.com.archflow.api.jobs.JobHandler> handlers) {
        return new br.com.archflow.api.jobs.JobHandlerRegistry(handlers);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = JDBC_ENABLED, havingValue = "false", matchIfMissing = true)
    public br.com.archflow.api.knowledge.KnowledgeRepository knowledgeRepository() {
        return new br.com.archflow.api.knowledge.InMemoryKnowledgeRepository();
    }

    @Bean
    @ConditionalOnMissingBean
    public br.com.archflow.api.knowledge.KnowledgeService knowledgeService(
            br.com.archflow.api.knowledge.KnowledgeRepository repository,
            br.com.archflow.api.storage.FileStorageService files,
            br.com.archflow.api.jobs.JobService jobs,
            br.com.archflow.api.knowledge.KnowledgeEmbeddingModel embeddings,
            br.com.archflow.api.knowledge.KnowledgeVectorIndex vectorIndex) {
        return new br.com.archflow.api.knowledge.KnowledgeService(
                repository, files, jobs, embeddings, vectorIndex);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "archflow.knowledge.embedding.provider",
            havingValue = "hash", matchIfMissing = true)
    public br.com.archflow.api.knowledge.KnowledgeEmbeddingModel knowledgeEmbeddingModel() {
        return new br.com.archflow.api.knowledge.HashEmbeddingModel(128);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "archflow.knowledge.embedding.provider", havingValue = "openai")
    public br.com.archflow.api.knowledge.KnowledgeEmbeddingModel openAiKnowledgeEmbeddingModel(
            @Value("${archflow.knowledge.embedding.openai.api-key:${OPENAI_API_KEY:}}")
            String apiKey,
            @Value("${archflow.knowledge.embedding.openai.model:text-embedding-3-small}")
            String model,
            @Value("${archflow.knowledge.embedding.openai.base-url:}") String baseUrl,
            @Value("${archflow.knowledge.embedding.openai.timeout:PT30S}")
            java.time.Duration timeout,
            @Value("${archflow.knowledge.embedding.openai.max-retries:3}") int maxRetries) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "OpenAI embedding provider requires "
                            + "archflow.knowledge.embedding.openai.api-key or OPENAI_API_KEY");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("OpenAI embedding model cannot be blank");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalStateException("OpenAI embedding timeout must be positive");
        }
        if (maxRetries < 0 || maxRetries > 10) {
            throw new IllegalStateException(
                    "OpenAI embedding max-retries must be between 0 and 10");
        }
        var builder = dev.langchain4j.model.openai.OpenAiEmbeddingModel.builder()
                .apiKey(apiKey)
                .modelName(model)
                .dimensions(128)
                .timeout(timeout)
                .maxRetries(maxRetries);
        if (baseUrl != null && !baseUrl.isBlank()) builder.baseUrl(baseUrl);
        return new br.com.archflow.api.knowledge.LangChainKnowledgeEmbeddingModel(
                builder.build(), 128);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = JDBC_ENABLED, havingValue = "false", matchIfMissing = true)
    public br.com.archflow.api.knowledge.KnowledgeVectorIndex knowledgeVectorIndex() {
        return new br.com.archflow.api.knowledge.InMemoryKnowledgeVectorIndex();
    }

    @Bean
    @ConditionalOnMissingBean
    public br.com.archflow.api.knowledge.DocumentTextExtractorRegistry
            documentTextExtractorRegistry(
            @Value("${archflow.knowledge.extraction.max-characters:5000000}")
            int maxCharacters,
            @Value("${archflow.knowledge.extraction.pdf.max-pages:1000}") int maxPdfPages) {
        return new br.com.archflow.api.knowledge.DocumentTextExtractorRegistry(
                java.util.List.of(
                        new br.com.archflow.api.knowledge.PlainTextDocumentExtractor(),
                        new br.com.archflow.api.knowledge.PdfDocumentExtractor(maxPdfPages),
                        new br.com.archflow.api.knowledge.DocxDocumentExtractor()),
                maxCharacters);
    }

    @Bean
    @ConditionalOnMissingBean
    public br.com.archflow.api.knowledge.DocumentIngestionHandler documentIngestionHandler(
            br.com.archflow.api.knowledge.KnowledgeRepository repository,
            br.com.archflow.api.storage.FileStorageService files,
            br.com.archflow.api.knowledge.KnowledgeEmbeddingModel embeddings,
            br.com.archflow.api.knowledge.KnowledgeVectorIndex vectorIndex,
            br.com.archflow.api.knowledge.DocumentTextExtractorRegistry extractors,
            @Value("${archflow.knowledge.chunk-size:1200}") int chunkSize,
            @Value("${archflow.knowledge.chunk-overlap:200}") int overlap) {
        return new br.com.archflow.api.knowledge.DocumentIngestionHandler(
                repository, files, new br.com.archflow.api.knowledge.RecursiveTextChunker(),
                chunkSize, overlap, embeddings, vectorIndex, extractors);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "archflow.jobs.worker.enabled", havingValue = "true")
    public br.com.archflow.api.jobs.JobWorkerLoop jobWorkerLoop(
            br.com.archflow.api.jobs.JobService service,
            br.com.archflow.api.jobs.JobHandlerRegistry registry,
            @Value("${archflow.jobs.worker.id:${HOSTNAME:local}}") String workerId,
            @Value("${archflow.jobs.worker.lease:PT30S}") java.time.Duration lease,
            @Value("${archflow.jobs.worker.poll-interval:PT1S}") java.time.Duration pollInterval) {
        var worker = new br.com.archflow.api.jobs.JobWorker(
                workerId, service, registry, lease);
        return new br.com.archflow.api.jobs.JobWorkerLoop(worker, pollInterval);
    }

    /**
     * Falha o startup fora de dev/test quando stores em memória estão ativos
     * (perda de dados no restart). Escape hatch: archflow.allow-in-memory=true.
     */
    @Bean
    public ProductionReadinessGuard productionReadinessGuard(
            org.springframework.core.env.Environment environment,
            org.springframework.beans.factory.ListableBeanFactory beanFactory) {
        return new ProductionReadinessGuard(environment, beanFactory);
    }

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

    /**
     * Default in-memory user repository seeded with a bootstrap admin.
     *
     * <p>The admin password is resolved from {@code archflow.security.admin-password}
     * (or the {@code ARCHFLOW_ADMIN_PASSWORD} environment variable). When absent:
     * under the {@code dev}/{@code test} profiles a fixed development password is
     * used; otherwise a random password is generated and logged once at WARN so
     * the deployment is never reachable with a publicly known credential.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = JDBC_ENABLED, havingValue = "false", matchIfMissing = true)
    public UserRepository userRepository(
            PasswordService passwordService,
            org.springframework.core.env.Environment environment,
            @Value("${archflow.security.admin-password:${ARCHFLOW_ADMIN_PASSWORD:}}") String adminPassword) {
        String resolved = AdminBootstrap.resolvePassword(environment, adminPassword, log);
        return new InMemoryUserRepository(passwordService.hash(resolved));
    }

    @Bean
    @ConditionalOnMissingBean
    public AuthService authService(JwtService jwtService, PasswordService passwordService, UserRepository userRepository) {
        return new AuthService(jwtService, passwordService, userRepository);
    }

    /**
     * Default in-memory API key repository — bean próprio (não mais embutido
     * no apiKeyService) para que deployments possam sobrescrevê-lo com uma
     * implementação durável e o ProductionReadinessGuard consiga detectá-lo.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = JDBC_ENABLED, havingValue = "false", matchIfMissing = true)
    public ApiKeyService.ApiKeyRepository apiKeyRepository() {
        return new InMemoryApiKeyRepository();
    }

    @Bean
    @ConditionalOnMissingBean
    public ApiKeyService apiKeyService(ApiKeyService.ApiKeyRepository apiKeyRepository) {
        return new ApiKeyService(apiKeyRepository);
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

    /**
     * Escritor do {@link InMemoryTraceStore}. É passado ao engine em
     * {@link #flowEngine} — sem esta ligação o store nunca recebe um trace e
     * toda a tela de observabilidade do admin fica vazia.
     */
    @Bean
    @ConditionalOnMissingBean
    public br.com.archflow.api.admin.observability.impl.TraceStoreRecorder traceStoreRecorder(
            InMemoryTraceStore traceStore) {
        return new br.com.archflow.api.admin.observability.impl.TraceStoreRecorder(traceStore);
    }

    /**
     * Coletor compartilhado entre o engine (que grava) e o
     * {@link ObservabilityService} (que lê). Antes cada lado tinha o seu:
     * o engine criava um interno na factory e o serviço recebia {@code null},
     * então nenhuma métrica de fluxo chegava à API.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public br.com.archflow.agent.metrics.MetricsCollector metricsCollector() {
        return new br.com.archflow.agent.metrics.MetricsCollector(
                br.com.archflow.agent.config.AgentConfig.builder().build());
    }

    @Bean
    @ConditionalOnMissingBean
    public ObservabilityService observabilityService(
            br.com.archflow.agent.metrics.MetricsCollector metricsCollector,
            InMemoryTraceStore traceStore,
            EventStreamRegistry eventStreamRegistry,
            RunningFlowsRegistry runningFlowsRegistry,
            org.springframework.beans.factory.ObjectProvider<AuditRepository> auditRepository) {
        // AuditRepository é opcional: presente quando archflow.persistence.jdbc.enabled=true
        // (JdbcAuditRepository) — aí as consultas de auditoria da observabilidade passam a
        // ler do banco em vez de ficarem vazias. ObjectProvider evita exigir o bean.
        return new ObservabilityService(metricsCollector, traceStore, auditRepository.getIfAvailable(),
                eventStreamRegistry, runningFlowsRegistry);
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
    public TenantControllerImpl tenantControllerImpl(
            @Value("${archflow.admin.seedDemoData:false}") boolean seedDemoData) {
        // Default false so production never returns the fixture tenant in
        // listTenants(). Dev profile (application-dev.yml) overrides to
        // true so the local UI has something to render.
        return new TenantControllerImpl(seedDemoData);
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkspaceControllerImpl workspaceControllerImpl(
            @Value("${archflow.admin.tenantFallback:}") String tenantFallback,
            @Value("${archflow.admin.seedDemoData:false}") boolean seedDemoData) {
        // The fallback is intentionally empty by default so production
        // deployments fail loud when the X-Tenant-Id header / security
        // filter is not wired. Dev profiles should set both props.
        String fallback = (tenantFallback == null || tenantFallback.isBlank()) ? null : tenantFallback;
        return new WorkspaceControllerImpl(fallback, seedDemoData);
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

    /**
     * A fila de aprovações lê o estado durável dos fluxos e decide pelo motor.
     * Antes recebia um {@code ApprovalRegistry} em memória recém-criado cujo
     * {@code register()} não tinha produtor algum: a fila nunca saía de vazia e
     * não sobreviveria a restart, enquanto o gate durável do engine ficava
     * inalcançável do lado de fora.
     */
    @Bean
    @ConditionalOnMissingBean
    public ApprovalQueueService approvalQueueService(
            br.com.archflow.engine.core.StateManager stateManager,
            br.com.archflow.engine.api.FlowEngine flowEngine,
            @Value("${archflow.approval.timeout:PT24H}") java.time.Duration approvalTimeout) {
        return new ApprovalQueueService(stateManager, flowEngine, approvalTimeout);
    }

    /**
     * Aplica o prazo das aprovações pendentes.
     *
     * <p>Antes nada expirava {@code AWAITING_APPROVAL}: uma remediação proposta
     * ficava pendurada para sempre, segurando estado e poluindo uma fila que
     * nunca esvaziava. O prazo default é generoso (24h) e a decisão de timeout é
     * sempre REJECTED — "ninguém olhou" não pode virar "pode executar".
     * {@code archflow.approval.timeout=PT0S} restaura o comportamento anterior.
     */
    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnMissingBean
    public br.com.archflow.api.approval.impl.ApprovalTimeoutSweeper approvalTimeoutSweeper(
            ApprovalQueueService approvalQueueService,
            @Value("${archflow.approval.sweep-interval:PT5M}") java.time.Duration sweepInterval) {
        return new br.com.archflow.api.approval.impl.ApprovalTimeoutSweeper(
                approvalQueueService, sweepInterval);
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

    /**
     * Store de conversas suspensas — default em memória (perde suspend/resume no
     * restart). Sob {@code archflow.persistence.jdbc.enabled=true},
     * {@code JdbcPersistenceConfiguration} fornece a versão durável, que vence
     * este {@code @ConditionalOnMissingBean}.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "archflow.persistence.jdbc.enabled", havingValue = "false", matchIfMissing = true)
    public br.com.archflow.conversation.state.SuspendedConversationStore suspendedConversationStore() {
        return new br.com.archflow.conversation.state.InMemorySuspendedConversationStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public ConversationManager conversationManager(
            br.com.archflow.conversation.state.SuspendedConversationStore suspendedConversationStore) {
        return new ConversationManager(java.time.Duration.ofMinutes(30), suspendedConversationStore);
    }

    @Bean
    @ConditionalOnMissingBean
    public ConversationService conversationService(ConversationManager conversationManager) {
        return new DefaultConversationService(conversationManager);
    }

    // =========================================================================
    // Controller implementations — Events
    // =========================================================================

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "archflow.persistence.jdbc.enabled", havingValue = "false", matchIfMissing = true)
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
    @ConditionalOnProperty(name = "archflow.persistence.jdbc.enabled", havingValue = "false", matchIfMissing = true)
    public br.com.archflow.engine.persistence.FlowRepository flowRepository() {
        return new br.com.archflow.agent.persistence.InMemoryFlowRepository();
    }

    /**
     * Shared flow state store (design-0005 step 4): one {@link br.com.archflow.engine.core.StateManager}
     * used by the engine, the OrchestrateStep (to materialize the dynamic tree)
     * and the execution controller (to read it back). In-memory for dev.
     *
     * <p>Desligado quando {@code archflow.persistence.jdbc.enabled=true} — aí o
     * {@link JdbcPersistenceConfiguration} fornece o StateManager durável
     * (mutuamente exclusivo pela mesma propriedade, sem corrida de ordenação).
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "archflow.persistence.jdbc.enabled", havingValue = "false", matchIfMissing = true)
    public br.com.archflow.engine.core.StateManager stateManager() {
        return new br.com.archflow.api.flow.InMemoryStateManager();
    }

    /**
     * Runtime store dos workflows do designer — default em memória (workflows e
     * execuções se perdem no restart). Sob {@code archflow.persistence.jdbc.enabled=true},
     * {@code JdbcPersistenceConfiguration} fornece o {@code JdbcWorkflowRuntimeStore}
     * durável (mutuamente exclusivo pela mesma propriedade).
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "archflow.persistence.jdbc.enabled", havingValue = "false", matchIfMissing = true)
    public br.com.archflow.api.web.workflow.WorkflowRuntimeStore workflowRuntimeStore() {
        return new br.com.archflow.api.web.workflow.InMemoryWorkflowRuntimeStore();
    }

    /**
     * The real, async {@link br.com.archflow.engine.api.FlowEngine} (design-0005
     * step 1): virtual-thread execution with backpressure and pause/resume/cancel,
     * wired from its collaborators (in-memory state for dev). Turns the previously
     * dormant engine into a usable async executor for every workflow.
     */
    @Bean
    @ConditionalOnMissingBean
    public br.com.archflow.engine.api.FlowEngine flowEngine(
            br.com.archflow.engine.persistence.FlowRepository flowRepository,
            EventStreamRegistry eventStreamRegistry,
            RunningFlowsRegistry runningFlowsRegistry,
            br.com.archflow.engine.core.StateManager stateManager,
            br.com.archflow.api.web.workflow.WorkflowRuntimeStore runtimeStore,
            br.com.archflow.agent.metrics.MetricsCollector metricsCollector,
            br.com.archflow.api.admin.observability.impl.TraceStoreRecorder traceStoreRecorder,
            @Value("${archflow.flow.strict-conditions:false}") boolean strictConditions) {
        // Registered before create(): the factory snapshots process-wide
        // listeners into the engine's composite lifecycle listener.
        br.com.archflow.engine.lifecycle.FlowLifecycleListeners.register(
                new br.com.archflow.api.flow.StepRecordingListener(runtimeStore));
        return br.com.archflow.api.flow.FlowEngineFactory.create(
                flowRepository, eventStreamRegistry, runningFlowsRegistry, stateManager,
                metricsCollector, traceStoreRecorder, 16, 3_600_000L, 8, strictConditions);
    }

    /**
     * Cadeia de interceptores aplicada a cada invocação de componente pelo
     * {@code ComponentStep}. A cadeia existia completa em archflow-agent —
     * inclusive com {@code beforeExecute} capaz de abortar a execução — e não
     * tinha nenhum chamador em produção; era um ponto de extensão inalcançável.
     *
     * <p>O default traz apenas interceptores aditivos (log e métricas). Cache e
     * guardrails ficam de fora de propósito: ligá-los por default mudaria o
     * comportamento de fluxos existentes. Um deployment que os queira declara
     * o próprio bean.
     */
    @Bean
    @ConditionalOnMissingBean
    public br.com.archflow.agent.tool.ToolInterceptorChain toolInterceptorChain() {
        return br.com.archflow.agent.tool.ToolInterceptorChain.builder()
                .addInterceptor(new br.com.archflow.agent.tool.interceptor.LoggingInterceptor())
                .addInterceptor(new br.com.archflow.agent.tool.interceptor.MetricsInterceptor())
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowYamlBridge workflowYamlBridge() {
        return new WorkflowYamlBridge();
    }

    /**
     * MCP server exposing the stored workflows as tools (Onda E): the
     * counterpart of the platform's MCP-client support, served by
     * {@code SpringMcpServerController} at {@code POST /mcp}.
     */
    @Bean
    @ConditionalOnMissingBean
    public br.com.archflow.api.mcp.server.WorkflowMcpServer workflowMcpServer(
            br.com.archflow.api.web.workflow.WorkflowRuntimeStore runtimeStore,
            br.com.archflow.api.flow.WorkflowDeserializer workflowDeserializer,
            br.com.archflow.engine.api.FlowEngine flowEngine,
            br.com.archflow.engine.persistence.FlowRepository flowRepository,
            com.fasterxml.jackson.databind.ObjectMapper jackson2ObjectMapper) {
        return new br.com.archflow.api.mcp.server.WorkflowMcpServer(
                runtimeStore, workflowDeserializer, flowEngine, flowRepository, jackson2ObjectMapper);
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
    @ConditionalOnProperty(name = JDBC_ENABLED, havingValue = "false", matchIfMissing = true)
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

    /**
     * Carrega plugins de um diretório de fat-jars, quando
     * {@code archflow.plugins.directory} aponta para um.
     *
     * <p><b>Opt-in de propósito, e não por acaso.</b> Carregar um jar executa
     * {@code ComponentPlugin.onLoad} e os inicializadores estáticos das suas
     * classes — <b>código arbitrário, sem sandbox, com os privilégios da JVM</b>
     * (filesystem, rede, variáveis de ambiente, segredos). Ligar isso por
     * default transformaria um diretório numa porta de execução remota. Só
     * aponte para um diretório cujos jars você produziu ou audita.
     *
     * <p>Diretório ausente ou vazio não é erro — carrega nada. Um jar que falha
     * ao carregar É erro, e falha alto: um plugin quebrado sumindo em silêncio
     * faria o workflow que depende dele falhar depois, de forma misteriosa.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "archflow.plugins.directory")
    public br.com.archflow.plugin.loader.ArchflowPluginManager archflowPluginManager(
            @Value("${archflow.plugins.directory:}") String pluginsDirectory) {
        var manager = new br.com.archflow.plugin.loader.ArchflowPluginManager();
        if (pluginsDirectory == null || pluginsDirectory.isBlank()) {
            log.info("archflow.plugins.directory vazio — nenhum plugin externo carregado");
            return manager;
        }
        log.warn("Carregando plugins de {} — jars de plugin executam código SEM SANDBOX "
                + "com os privilégios desta JVM; só use jars confiáveis", pluginsDirectory);
        java.util.List<String> loaded =
                manager.loadFromDirectory(java.nio.file.Path.of(pluginsDirectory));
        log.info("{} plugin(s) externo(s) carregado(s): {}", loaded.size(), loaded);
        return manager;
    }

    @Bean
    @ConditionalOnMissingBean
    public br.com.archflow.plugin.api.catalog.ComponentCatalog componentCatalog(
            org.springframework.beans.factory.ObjectProvider<
                    br.com.archflow.plugin.loader.ArchflowPluginManager> pluginManager) {
        br.com.archflow.plugin.api.catalog.ComponentCatalog catalog = seedBuiltIns();
        // O manager mantém catálogo próprio; os componentes dele são copiados
        // para o do app, que é o que o ComponentStep resolve.
        var manager = pluginManager.getIfAvailable();
        if (manager != null) {
            int copied = 0;
            for (var meta : manager.getCatalog().listComponents()) {
                var component = manager.getCatalog().getComponent(meta.id()).orElse(null);
                if (component != null) {
                    catalog.register(component);
                    copied++;
                }
            }
            if (copied > 0) {
                log.info("Component catalog: {} componente(s) de plugin externo registrado(s)", copied);
            }
        }
        return catalog;
    }

    private br.com.archflow.plugin.api.catalog.ComponentCatalog seedBuiltIns() {
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
                "br.com.archflow.plugins.tools.TextTransformTool",
                // Laço agente↔tools MCP como passo de fluxo. Mora aqui, e não num jar de plugin,
                // porque o McpAgentRunner que ele embrulha é deste módulo — o que também significa
                // que levá-lo ao agente local exige extrair os dois para um módulo compartilhado.
                "br.com.archflow.api.agent.mcp.McpAgentComponent"
        };
        java.util.List<String> registered = new java.util.ArrayList<>();
        java.util.List<String> failed = new java.util.ArrayList<>();
        for (String className : builtIns) {
            try {
                Class<?> cls = Class.forName(className);
                Object instance = cls.getDeclaredConstructor().newInstance();
                if (instance instanceof br.com.archflow.model.ai.AIComponent aic) {
                    catalog.register(aic);
                    registered.add(className);
                }
            } catch (ClassNotFoundException e) {
                // Jar do plugin fora do classpath é uma configuração válida,
                // mas precisa ficar visível — workflows que dependem dele
                // falhariam de forma misteriosa.
                failed.add(className + " (not on classpath)");
            } catch (Throwable e) {
                failed.add(className + " (failed to construct: " + e.getMessage() + ")");
                log.warn("Built-in plugin {} failed to construct", className, e);
            }
        }
        log.info("Component catalog: {} built-in plugin(s) registered", registered.size());
        if (!failed.isEmpty()) {
            log.warn("Component catalog: {} built-in plugin(s) NOT available: {}",
                    failed.size(), failed);
        }
        return catalog;
    }

    @Bean
    @ConditionalOnMissingBean
    public CatalogController catalogController(
            br.com.archflow.plugin.api.catalog.ComponentCatalog componentCatalog) {
        return new CatalogControllerImpl(componentCatalog);
    }

    /**
     * Roteador descriptor-driven que escolhe o melhor agente/componente para uma
     * query (keywords {@literal >} capabilities {@literal >} tags {@literal >} texto).
     */
    @Bean
    @ConditionalOnMissingBean
    public br.com.archflow.plugin.api.catalog.ComponentQueryRouter componentQueryRouter(
            br.com.archflow.plugin.api.catalog.ComponentCatalog componentCatalog) {
        return new br.com.archflow.plugin.api.catalog.DefaultComponentQueryRouter(componentCatalog);
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

    // =========================================================================
    // LLM config resolution (per-step/agent/flow/tenant model overrides)
    // =========================================================================

    /**
     * Resolução de chave por tenant. NOOP por padrão (sem chaves por tenant);
     * produtos sobrepõem este bean para resolver chaves do seu storage.
     */
    @Bean
    @ConditionalOnMissingBean
    public br.com.archflow.langchain4j.provider.TenantKeyResolver tenantKeyResolver() {
        return br.com.archflow.langchain4j.provider.TenantKeyResolver.NOOP;
    }

    /**
     * Default de LLM da plataforma — o tier mais baixo da cadeia de herança.
     * Configurável via {@code archflow.llm.*}.
     */
    @Bean
    @ConditionalOnMissingBean(name = "platformDefaultLLMConfig")
    public br.com.archflow.model.config.ResolvedLLMConfig platformDefaultLLMConfig(
            @Value("${archflow.llm.provider:openai}") String provider,
            @Value("${archflow.llm.model:gpt-4o-mini}") String model,
            @Value("${archflow.llm.temperature:0.2}") double temperature,
            @Value("${archflow.llm.max-tokens:1024}") int maxTokens,
            @Value("${archflow.llm.timeout-ms:30000}") long timeoutMs,
            @Value("${archflow.llm.api-key:}") String apiKey,
            @Value("${archflow.llm.base-url:}") String baseUrl) {
        // Inline key/baseUrl go into additionalConfig — the resolver reads the key
        // from additionalConfig.apiKey (tenant key takes precedence). Keep secrets
        // out of source: set via ARCHFLOW_LLM_API_KEY / ARCHFLOW_LLM_BASE_URL.
        java.util.Map<String, Object> additional = new java.util.HashMap<>();
        if (apiKey != null && !apiKey.isBlank()) {
            additional.put("apiKey", apiKey);
        }
        if (baseUrl != null && !baseUrl.isBlank()) {
            additional.put("baseUrl", baseUrl);
        }
        return br.com.archflow.model.config.ResolvedLLMConfig.builder()
                .provider(provider)
                .model(model)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .timeout(timeoutMs)
                .additionalConfig(additional)
                .build();
    }

    /**
     * Resolver de config de LLM com herança step {@literal >} agent {@literal >}
     * flow {@literal >} tenant {@literal >} platform e chave por tenant.
     */
    @Bean
    @ConditionalOnMissingBean
    public br.com.archflow.langchain4j.provider.LLMConfigResolver llmConfigResolver(
            br.com.archflow.langchain4j.provider.TenantKeyResolver tenantKeyResolver) {
        return new br.com.archflow.langchain4j.provider.DefaultLLMConfigResolver(
                br.com.archflow.langchain4j.provider.LLMProviderHub.getInstance(),
                tenantKeyResolver);
    }

    // =========================================================================
    // Assist (IA síncrona — família /archflow/assist/*, ADR-0004)
    // =========================================================================

    /**
     * Jackson 2 {@link com.fasterxml.jackson.databind.ObjectMapper} bean.
     *
     * <p>Spring Boot 4 auto-configures only a Jackson 3 ({@code tools.jackson})
     * mapper, so the classic {@code com.fasterxml.jackson.databind.ObjectMapper}
     * is no longer available for injection. The codebase still serializes via
     * Jackson 2 in several places, so expose a single shared bean here instead of
     * each consumer building its own — that fixes the missing-bean error once and
     * gives every consumer one consistent configuration to evolve.
     */
    @Bean
    @ConditionalOnMissingBean
    public com.fasterxml.jackson.databind.ObjectMapper jackson2ObjectMapper() {
        return new com.fasterxml.jackson.databind.ObjectMapper();
    }

    /**
     * Confidence scorer used by the dynamic-orchestration verification path
     * (ConfidenceVoter) and available to the agent layer for escalation.
     */
    @Bean
    @ConditionalOnMissingBean
    public br.com.archflow.agent.confidence.ConfidenceScorer confidenceScorer() {
        return new br.com.archflow.agent.confidence.DefaultConfidenceScorer();
    }

    /**
     * Serviço de assistência por IA. Usa o {@code LLMConfigResolver} e o
     * default da plataforma para resolver o modelo padrão e diagnosticar erros.
     */
    @Bean
    @ConditionalOnMissingBean
    public br.com.archflow.api.assist.AssistService assistService(
            br.com.archflow.langchain4j.provider.LLMConfigResolver llmConfigResolver,
            br.com.archflow.model.config.ResolvedLLMConfig platformDefaultLLMConfig,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        return new br.com.archflow.api.assist.impl.AssistServiceImpl(
                llmConfigResolver, platformDefaultLLMConfig, objectMapper);
    }

    // =========================================================================
    // Integração VendaX Core (MCP) — agente QP
    // =========================================================================

    /**
     * Provider do MCP client HTTP do VendaX Core, por tenant. Config default via
     * properties; overrides por tenant via {@code configure()}.
     */
    @Bean
    @ConditionalOnMissingBean
    public br.com.archflow.api.mcp.vendax.VendaxMcpClientProvider vendaxMcpClientProvider(
            @Value("${archflow.vendax.mcp.base-url:}") String baseUrl,
            @Value("${archflow.vendax.mcp.service-token:}") String serviceToken) {
        return new br.com.archflow.api.mcp.vendax.VendaxMcpClientProvider(baseUrl, serviceToken);
    }

    /** Loop de tool-calling nativo server-side (motor reutilizável). */
    @Bean
    @ConditionalOnMissingBean
    public br.com.archflow.api.agent.mcp.McpAgentRunner mcpAgentRunner(
            br.com.archflow.langchain4j.provider.LLMConfigResolver llmConfigResolver,
            br.com.archflow.model.config.ResolvedLLMConfig platformDefaultLLMConfig,
            br.com.archflow.agent.metrics.MetricsCollector metricsCollector,
            br.com.archflow.api.agent.mcp.McpAgentStateStore mcpAgentStateStore,
            @Value("${archflow.agent.tool-catalog.warn-tokens:4000}") int catalogWarnTokens) {
        // Mesmo coletor do engine e do ObservabilityService: latência e taxa de
        // falha por tool aparecem junto das métricas de fluxo, não num silo.
        return new br.com.archflow.api.agent.mcp.McpAgentRunner(
                llmConfigResolver, platformDefaultLLMConfig, metricsCollector, catalogWarnTokens,
                mcpAgentStateStore);
    }

    /** Referência sob a qual a configuração legada do VendaX é semeada. */
    private static final String VENDAX_SERVER_REF = "vendax";

    /**
     * O host de MCP que os nós de fluxo encontram no contexto de execução.
     *
     * <p>Sem este bean o {@code McpAgentComponent} existe no catálogo, aparece no designer e
     * <b>falha ao executar</b> dizendo que não há host — foi exatamente o estado em que ficou
     * enquanto cada agente era construído por caminho próprio em vez de virar fluxo.</p>
     *
     * <p>Os servidores vêm de {@code archflow.mcp.servers.<ref>.base-url|service-token}, e o nó os
     * escolhe por referência ({@code server: "vendax"}). Endereço e credencial ficam fora do
     * documento do fluxo — que é versionado e visível no designer — e é o que permite o mesmo
     * documento rodar aqui e num agente local: cada lado fornece o seu host.</p>
     *
     * <p>A chave antiga {@code archflow.vendax.mcp.*} continua valendo, semeada sob
     * {@code ref = "vendax"}: instalação existente ganha o host sem editar configuração.</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public br.com.archflow.api.agent.mcp.McpAgentHost mcpAgentHost(
            br.com.archflow.api.agent.mcp.McpAgentRunner mcpAgentRunner,
            org.springframework.core.env.Environment environment,
            @Value("${archflow.mcp.default-server:}") String defaultServer,
            @Value("${archflow.vendax.mcp.base-url:}") String legadoBaseUrl,
            @Value("${archflow.vendax.mcp.service-token:}") String legadoToken) {

        java.util.Map<String, br.com.archflow.api.agent.mcp.ConfiguredMcpAgentHost.Server> servers =
                new java.util.LinkedHashMap<>();

        org.springframework.boot.context.properties.bind.Bindable<
                java.util.Map<String, java.util.Map<String, String>>> tipo =
                org.springframework.boot.context.properties.bind.Bindable.of(
                        org.springframework.core.ResolvableType.forClassWithGenerics(
                                java.util.Map.class,
                                org.springframework.core.ResolvableType.forClass(String.class),
                                org.springframework.core.ResolvableType.forClassWithGenerics(
                                        java.util.Map.class, String.class, String.class)));

        java.util.Map<String, java.util.Map<String, String>> declarados =
                org.springframework.boot.context.properties.bind.Binder.get(environment)
                        .bind("archflow.mcp.servers", tipo)
                        .orElseGet(java.util.Map::of);

        declarados.forEach((ref, props) -> {
            String baseUrl = props.get("base-url");
            if (baseUrl != null && !baseUrl.isBlank()) {
                servers.put(ref, new br.com.archflow.api.agent.mcp.ConfiguredMcpAgentHost.Server(
                        baseUrl, props.get("service-token")));
            }
        });

        if (!servers.containsKey(VENDAX_SERVER_REF)
                && legadoBaseUrl != null && !legadoBaseUrl.isBlank()) {
            servers.put(VENDAX_SERVER_REF,
                    new br.com.archflow.api.agent.mcp.ConfiguredMcpAgentHost.Server(
                            legadoBaseUrl, legadoToken));
        }

        if (servers.isEmpty()) {
            // Não é erro de inicialização: uma instalação pode não usar MCP. Mas precisa ficar
            // visível, porque o sintoma chega tarde — na execução do primeiro fluxo com o nó.
            log.warn("MCP host: nenhum servidor configurado. Fluxos com nó 'mcp-agent' vão falhar "
                    + "na execução. Configure archflow.mcp.servers.<ref>.base-url");
        } else {
            log.info("MCP host: {} servidor(es) configurado(s): {}", servers.size(), servers.keySet());
        }

        return new br.com.archflow.api.agent.mcp.ConfiguredMcpAgentHost(
                mcpAgentRunner, servers,
                defaultServer == null || defaultServer.isBlank() ? null : defaultServer);
    }

    /**
     * Store dos laços de tool-calling suspensos aguardando decisão humana.
     *
     * <p>Em memória por default (dev). O durável vive em
     * {@link JdbcPersistenceConfiguration} — e importa que seja o durável:
     * suspender um laço num store volátil dá uma pausa que não sobrevive a
     * restart, que é justamente o único motivo de suspender.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = JDBC_ENABLED, havingValue = "false", matchIfMissing = true)
    public br.com.archflow.api.agent.mcp.McpAgentStateStore mcpAgentStateStore() {
        return new br.com.archflow.api.agent.mcp.InMemoryMcpAgentStateStore();
    }

    /** Agente QP: orquestra as tools do VendaX Core sobre o loop nativo. */
    @Bean
    @ConditionalOnMissingBean
    public br.com.archflow.api.agent.qp.QpAgentService qpAgentService(
            br.com.archflow.api.agent.mcp.McpAgentRunner mcpAgentRunner,
            br.com.archflow.api.mcp.vendax.VendaxMcpClientProvider vendaxMcpClientProvider) {
        return new br.com.archflow.api.agent.qp.QpAgentService(mcpAgentRunner, vendaxMcpClientProvider);
    }

    /** Caminho de volta ao VendaX Core: resultado assinado do agente. */
    @Bean
    @ConditionalOnMissingBean
    public br.com.archflow.api.agent.vendax.VendaxResultSender vendaxResultSender(
            @Value("${archflow.vendax.core.base-url:}") String coreBaseUrl,
            @Value("${archflow.vendax.core.result-secret:}") String resultSecret) {
        return new br.com.archflow.api.agent.vendax.VendaxResultSender(coreBaseUrl, resultSecret);
    }

    /** Executor I/O-bound com virtual threads e fila limitada: saturação é explícita, não vazamento. */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "vendaxAgentExecutor")
    public java.util.concurrent.ThreadPoolExecutor vendaxAgentExecutor(
            @Value("${archflow.vendax.agent.max-concurrency:32}") int maxConcurrency,
            @Value("${archflow.vendax.agent.queue-capacity:256}") int queueCapacity) {
        if (maxConcurrency <= 0 || queueCapacity <= 0) {
            throw new IllegalArgumentException("max-concurrency e queue-capacity devem ser positivos");
        }
        return new java.util.concurrent.ThreadPoolExecutor(
                maxConcurrency, maxConcurrency, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(queueCapacity),
                Thread.ofVirtual().name("vendax-agent-", 0).factory(),
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    @ConditionalOnMissingBean
    public br.com.archflow.api.agent.vendax.VendaxAgentMetrics vendaxAgentMetrics(
            io.micrometer.core.instrument.MeterRegistry meterRegistry,
            java.util.concurrent.ThreadPoolExecutor vendaxAgentExecutor) {
        return new br.com.archflow.api.agent.vendax.VendaxAgentMetrics(
                meterRegistry, vendaxAgentExecutor);
    }

    @Bean(name = "vendaxAgent")
    @ConditionalOnMissingBean(name = "vendaxAgent")
    public org.springframework.boot.health.contributor.HealthIndicator vendaxAgentHealthIndicator(
            java.util.concurrent.ThreadPoolExecutor vendaxAgentExecutor) {
        return new br.com.archflow.api.agent.vendax.VendaxAgentHealthIndicator(vendaxAgentExecutor);
    }

    /** Roteia o invoke do Core para o agente certo e devolve o resultado. */
    @Bean
    @ConditionalOnMissingBean
    public br.com.archflow.api.agent.vendax.VendaxAgentDispatcher vendaxAgentDispatcher(
            br.com.archflow.api.agent.qp.QpAgentService qpAgentService,
            br.com.archflow.api.agent.mcp.McpAgentRunner mcpAgentRunner,
            br.com.archflow.api.mcp.vendax.VendaxMcpClientProvider vendaxMcpClientProvider,
            br.com.archflow.api.agent.vendax.VendaxResultSender vendaxResultSender,
            java.util.concurrent.ExecutorService vendaxAgentExecutor,
            br.com.archflow.api.agent.vendax.VendaxAgentMetrics vendaxAgentMetrics) {
        return new br.com.archflow.api.agent.vendax.VendaxAgentDispatcher(
                qpAgentService, mcpAgentRunner, vendaxMcpClientProvider,
                vendaxResultSender, vendaxAgentExecutor, vendaxAgentMetrics);
    }
}
