package br.com.archflow.api.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Fails fast quando um deployment que NÃO está em profile dev/test sobe com
 * stores em memória — o modo de falha silencioso mais perigoso do archflow:
 * tudo funciona até o primeiro restart, quando fluxos, usuários, jobs
 * agendados e trilha de auditoria desaparecem.
 *
 * <p>Comportamento:
 * <ul>
 *   <li>profiles {@code dev}/{@code test} ativos → verificação ignorada;</li>
 *   <li>{@code archflow.allow-in-memory=true} → violações apenas logadas
 *       (escape hatch consciente para POCs single-instance);</li>
 *   <li>caso contrário → o startup falha com a lista de beans violando e o
 *       que configurar em cada um.</li>
 * </ul>
 *
 * <p>Antes dos stores, duas recusas sem escape hatch: autenticação desligada e segredo JWT público
 * ({@link #PUBLIC_DEFAULT_JWT_SECRETS}) — as duas deixam a API aberta, não só sujeita a perder dados.
 *
 * <p>Roda via {@link SmartInitializingSingleton}, depois que todos os
 * singletons foram instanciados — inclusive beans condicionais que possam
 * ter substituído os defaults em memória.
 */
public class ProductionReadinessGuard implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(ProductionReadinessGuard.class);

    /** Property que rebaixa violações de erro fatal para warning. */
    public static final String ALLOW_IN_MEMORY_PROPERTY = "archflow.allow-in-memory";

    /** Segredo que assina e confere os JWT ({@code ARCHFLOW_JWT_SECRET} no {@code application.yml}). */
    static final String JWT_SECRET_PROPERTY = "archflow.security.jwt.secret";

    /**
     * Os segredos JWT que vêm escritos no repositório: o default do {@code application.yml} e o do
     * {@code @Value} do bean {@code JwtService} (quando a property nem existe). São públicos — quem
     * leu o código assina um access token com {@code roles: ["ADMIN"]} e o filtro o aceita.
     */
    static final Set<String> PUBLIC_DEFAULT_JWT_SECRETS = Set.of(
            "change-me-in-production-this-must-be-very-long-at-least-256-bits",
            "change-me-in-production-this-must-be-very-long");

    private final Environment environment;
    private final ListableBeanFactory beanFactory;

    public ProductionReadinessGuard(Environment environment, ListableBeanFactory beanFactory) {
        this.environment = environment;
        this.beanFactory = beanFactory;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (Profiles.isDevLike(environment)) {
            log.debug("Production readiness guard skipped (dev/test profile active)");
            return;
        }

        // Auth desligada fora de dev/test deixa TODOS os endpoints abertos —
        // sem escape hatch (archflow.allow-in-memory cobre perda de dados,
        // não ausência de autenticação).
        if (!environment.getProperty("archflow.security.auth.enabled", Boolean.class, false)) {
            throw new IllegalStateException(
                    "Refusing to start: archflow.security.auth.enabled is false (or unset) outside "
                    + "the dev/test profiles — every endpoint would be reachable without "
                    + "authentication. Enable it (the prod profile already does) or activate "
                    + "the dev profile.");
        }

        // Segredo JWT público fora de dev/test: a autenticação está ligada, mas qualquer um forja
        // um token ADMIN. Só o profile prod exigia ARCHFLOW_JWT_SECRET (o placeholder dele não tem
        // default); "staging", "homolog" ou nenhum profile subiam com o default. Sem escape hatch,
        // como a auth desligada: archflow.allow-in-memory aceita perda de dados, não credencial
        // forjável.
        String jwtSecret = environment.getProperty(JWT_SECRET_PROPERTY);
        if (jwtSecret == null || PUBLIC_DEFAULT_JWT_SECRETS.contains(jwtSecret.trim())) {
            throw new IllegalStateException(
                    "Refusing to start: " + JWT_SECRET_PROPERTY + " is the public default secret "
                    + "(or unset) outside the dev/test profiles — anyone who has read the "
                    + "repository can sign an ADMIN access token. Set ARCHFLOW_JWT_SECRET (or "
                    + JWT_SECRET_PROPERTY + ") to a random secret of at least 32 bytes, e.g. "
                    + "`openssl rand -base64 48`, or activate the dev profile.");
        }

        Object embeddingModel = beanFactory.getBeanProvider(
                br.com.archflow.api.knowledge.KnowledgeEmbeddingModel.class).getIfAvailable();
        if (embeddingModel instanceof br.com.archflow.api.knowledge.HashEmbeddingModel) {
            throw new IllegalStateException(
                    "Refusing to start: HashEmbeddingModel is active outside the dev/test "
                    + "profiles. It is deterministic test scaffolding, not a semantic embedding "
                    + "model. Configure archflow.knowledge.embedding.provider=openai and provide "
                    + "OPENAI_API_KEY, or supply a production KnowledgeEmbeddingModel bean.");
        }

        List<String> violations = collectViolations();
        if (violations.isEmpty()) {
            log.info("Production readiness guard: no in-memory stores detected");
            return;
        }

        String report = String.join("\n  - ", violations);
        if (environment.getProperty(ALLOW_IN_MEMORY_PROPERTY, Boolean.class, false)) {
            log.warn("In-memory stores active in a non-dev profile ({}=true):\n  - {}",
                    ALLOW_IN_MEMORY_PROPERTY, report);
            return;
        }

        throw new IllegalStateException(
                "Refusing to start: in-memory stores are active outside the dev/test profiles. "
                + "All data they hold is lost on restart.\n  - " + report
                + "\nFix: provide durable beans (e.g. JdbcStateRepository/JdbcFlowRepository with a "
                + "PostgreSQL DataSource, JDBC-backed Quartz), activate the dev profile, or set "
                + ALLOW_IN_MEMORY_PROPERTY + "=true to accept the data loss explicitly.");
    }

    private List<String> collectViolations() {
        List<String> violations = new ArrayList<>();

        checkBean(br.com.archflow.engine.persistence.FlowRepository.class,
                br.com.archflow.agent.persistence.InMemoryFlowRepository.class,
                "FlowRepository — definições de fluxo", violations);
        checkBean(br.com.archflow.engine.core.StateManager.class,
                br.com.archflow.api.flow.InMemoryStateManager.class,
                "StateManager — estado de execução dos fluxos", violations);
        checkBean(br.com.archflow.security.auth.UserRepository.class,
                br.com.archflow.security.auth.InMemoryUserRepository.class,
                "UserRepository — usuários e credenciais", violations);
        checkBean(br.com.archflow.agent.queue.AgentInvocationQueue.class,
                br.com.archflow.agent.queue.InMemoryAgentInvocationQueue.class,
                "AgentInvocationQueue — invocações assíncronas de agentes", violations);
        checkBean(br.com.archflow.api.agent.invocation.AgentInvocationResultStore.class,
                br.com.archflow.api.agent.invocation.InMemoryAgentInvocationResultStore.class,
                "AgentInvocationResultStore — resultados das invocações de agentes", violations);
        checkBean(br.com.archflow.observability.audit.AuditRepository.class,
                br.com.archflow.observability.audit.InMemoryAuditRepository.class,
                "AuditRepository — trilha de auditoria", violations);
        checkBean(br.com.archflow.security.auth.TokenBlacklist.class,
                br.com.archflow.security.auth.InMemoryTokenBlacklist.class,
                "TokenBlacklist — logout e refresh tokens consumidos (revogação por réplica)", violations);
        checkBean(br.com.archflow.security.apikey.ApiKeyService.ApiKeyRepository.class,
                br.com.archflow.api.config.InMemoryApiKeyRepository.class,
                "ApiKeyRepository — chaves de API", violations);
        checkBean(br.com.archflow.conversation.state.SuspendedConversationStore.class,
                br.com.archflow.conversation.state.InMemorySuspendedConversationStore.class,
                "SuspendedConversationStore — conversas suspensas (suspend/resume)", violations);
        checkBean(br.com.archflow.api.web.workflow.WorkflowRuntimeStore.class,
                br.com.archflow.api.web.workflow.InMemoryWorkflowRuntimeStore.class,
                "WorkflowRuntimeStore — workflows e execuções do designer", violations);
        checkBean(br.com.archflow.api.storage.FileMetadataRepository.class,
                br.com.archflow.api.storage.InMemoryFileMetadataRepository.class,
                "FileMetadataRepository — metadados de arquivos", violations);
        checkBean(br.com.archflow.api.jobs.JobRepository.class,
                br.com.archflow.api.jobs.InMemoryJobRepository.class,
                "JobRepository — jobs assíncronos", violations);
        checkBean(br.com.archflow.api.knowledge.KnowledgeRepository.class,
                br.com.archflow.api.knowledge.InMemoryKnowledgeRepository.class,
                "KnowledgeRepository — bases e documentos", violations);
        checkBean(br.com.archflow.api.usage.RepositorioDeUso.class,
                br.com.archflow.api.usage.InMemoryRepositorioDeUso.class,
                "RepositorioDeUso — consumo de modelo medido por tenant (uso e faturamento)", violations);
        checkQuartz(violations);

        // Stores de observabilidade/rascunho: a perda degrada visibilidade,
        // não corretude — WARN em vez de falhar o boot (não têm alternativa
        // durável embutida hoje).
        warnIfPresent(br.com.archflow.api.admin.observability.impl.InMemoryTraceStore.class,
                "InMemoryTraceStore — traces de execução serão perdidos no restart");
        // Config admin (modelos/planos/toggles) é recuperável — o controller
        // recai nos defaults embutidos — então WARN, não violação fatal.
        warnIfPresent(br.com.archflow.api.admin.store.InMemoryGlobalConfigStore.class,
                "InMemoryGlobalConfigStore — configuração admin (modelos/planos/toggles) "
                        + "será perdida no restart");

        return violations;
    }

    private void warnIfPresent(Class<?> beanType, String message) {
        if (beanFactory.getBeanProvider(beanType).getIfAvailable() != null) {
            log.warn("Production readiness: {}", message);
        }
    }

    /**
     * Marca violação quando o bean ativo é (ou estende) a implementação em
     * memória. Usa referência {@code Class} — um rename/move da classe vira
     * erro de compilação aqui, não uma falha silenciosa do guard.
     */
    private void checkBean(Class<?> beanType, Class<?> inMemoryClass, String description,
                           List<String> violations) {
        Object bean = beanFactory.getBeanProvider(beanType).getIfAvailable();
        if (bean != null && inMemoryClass.isInstance(bean)) {
            violations.add(description + " (" + inMemoryClass.getSimpleName() + ")");
        }
    }

    private void checkQuartz(List<String> violations) {
        org.quartz.Scheduler scheduler =
                beanFactory.getBeanProvider(org.quartz.Scheduler.class).getIfAvailable();
        if (scheduler == null) {
            return;
        }
        try {
            Class<?> jobStore = scheduler.getMetaData().getJobStoreClass();
            if (jobStore != null && jobStore.getName().contains("RAMJobStore")) {
                violations.add("Quartz Scheduler — jobs agendados (RAMJobStore; use JDBCJobStore)");
            }
        } catch (org.quartz.SchedulerException e) {
            log.warn("Could not inspect Quartz job store for the readiness guard", e);
        }
    }
}
