package br.com.archflow.api.admin.impl;

import br.com.archflow.api.admin.TenantController;
import br.com.archflow.api.admin.dto.GlobalConfigDto.PlanDefaultsDto;
import br.com.archflow.api.admin.dto.TenantDto;
import br.com.archflow.api.admin.dto.TenantDto.*;
import br.com.archflow.api.audit.AuditTrail;
import br.com.archflow.model.tenant.DefaultTenant;
import br.com.archflow.observability.audit.AuditAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

public class TenantControllerImpl implements TenantController {
    private static final Logger log = LoggerFactory.getLogger(TenantControllerImpl.class);
    private final Map<String, TenantDto> tenants = new ConcurrentHashMap<>();
    private volatile AuditTrail auditTrail = AuditTrail.noop();
    /**
     * De onde vem o uso de cada tenant — medido (RCHFLW-75). Sem fonte, o uso é o guardado no
     * cadastro, que nasce zerado: nunca um número de demonstração.
     */
    private volatile Function<String, TenantUsageDto> usoMedido;
    /** {@code archflow.admin.impersonation.enabled}, só repassado nos totais (RCHFLW-198). */
    private volatile boolean impersonationEnabled;

    /** No-arg constructor seeds demo tenants — used by tests / dev profile. */
    public TenantControllerImpl() {
        this(true);
    }

    /**
     * @param seedDemoData when {@code true}, pre-populates a demo tenant so
     *                     the dev workspace flows have something to render.
     *                     Production deployments MUST pass {@code false}
     *                     so real tenants are not shadowed by fixture data.
     */
    public TenantControllerImpl(boolean seedDemoData) {
        if (seedDemoData) {
            // Ids do catálogo da plataforma (lista do código, RCHFLW-79): "claude-sonnet-4-6" não existia nele.
            var limits = new TenantLimitsDto(500, 5_000_000, 20, 10,
                    List.of("gpt-4o", "claude-sonnet-4-20250514"), List.of("hitl", "brainSentry"));
            // Uso zerado: o que aparece na lista é o medido (setUsoMedido), não um número inventado.
            var usage = new TenantUsageDto(0, 0, 0, 0);
            tenants.put("tenant_rio_quality", new TenantDto("tenant_rio_quality", "Rio Quality", "enterprise", "active",
                    "admin@rioquality.com.br", "Food Distribution", limits, usage, limits.allowedModels(), "2025-06"));
        }
    }

    /**
     * Os padrões de plano do {@code /admin/global} ({@code plano → padrões}). A página promete
     * "Limites iniciais dos novos tenants de cada plano", mas a criação sem limites usava um conjunto
     * fixo (100 / 1.000.000 / 5 / 3) para qualquer plano (RCHFLW-178). Sem fonte, ou para um plano sem
     * padrão gravado, o conjunto fixo continua valendo.
     */
    private volatile Function<String, Optional<PlanDefaultsDto>> planDefaults = plan -> Optional.empty();

    public void setPlanDefaults(Function<String, Optional<PlanDefaultsDto>> planDefaults) {
        if (planDefaults != null) {
            this.planDefaults = planDefaults;
        }
    }

    /** Audit event producer (tenant lifecycle) — optional, no-op default. */
    public void setAuditTrail(AuditTrail auditTrail) {
        if (auditTrail != null) {
            this.auditTrail = auditTrail;
        }
    }

    /**
     * Liga o uso medido: a lista, o detalhe e os totais passam a mostrar o que o tenant de fato
     * consumiu (execuções de hoje, tokens do mês, workflows, usuários).
     */
    public void setUsoMedido(Function<String, TenantUsageDto> usoMedido) {
        this.usoMedido = usoMedido;
    }

    /**
     * Se este servidor aceita a personificação ({@code archflow.admin.impersonation.enabled}). Quem
     * decide é o {@code ImpersonationFilter}; aqui o valor só segue nos totais, para a lista de tenants
     * não oferecer um "Entrar" que o servidor vai recusar (RCHFLW-198).
     */
    public void setImpersonationEnabled(boolean impersonationEnabled) {
        this.impersonationEnabled = impersonationEnabled;
    }

    /** O cadastro com o uso medido no lugar do guardado. */
    private TenantDto comUsoMedido(TenantDto tenant) {
        Function<String, TenantUsageDto> fonte = usoMedido;
        if (fonte == null || tenant == null) {
            return tenant;
        }
        return new TenantDto(tenant.id(), tenant.name(), tenant.plan(), tenant.status(), tenant.adminEmail(),
                tenant.sector(), tenant.limits(), fonte.apply(tenant.id()), tenant.allowedModels(), tenant.createdAt());
    }

    /** O cadastro do tenant, se existir — sem lançar, para quem só precisa saber o plano/limites. */
    public Optional<TenantDto> findTenant(String tenantId) {
        return tenantId == null ? Optional.empty() : Optional.ofNullable(tenants.get(tenantId));
    }

    /**
     * {@code true} quando o tenant está no cadastro com o status {@code suspended} — o que o
     * {@code TenantSuspensionInterceptor} confere em cada requisição autenticada (QA2 P6). Tenant fora do
     * cadastro (o padrão, um tenant que só existe no produto) não está suspenso.
     */
    public boolean isSuspended(String tenantId) {
        return findTenant(tenantId).map(tenant -> "suspended".equals(tenant.status())).orElse(false);
    }

    @Override
    public List<TenantDto> listTenants() {
        return tenants.values().stream().map(this::comUsoMedido).collect(Collectors.toCollection(ArrayList::new));
    }

    @Override
    public TenantStatsDto getStats() {
        List<TenantDto> all = listTenants();
        long active = all.stream().filter(t -> "active".equals(t.status())).count();
        long trial = all.stream().filter(t -> "trial".equals(t.status())).count();
        List<TenantUsageDto> usos = all.stream().map(TenantDto::usage).collect(Collectors.toCollection(ArrayList::new));
        // O tenant padrão não está no cadastro, mas é onde cai todo login sem claim de tenant: sem ele, os
        // totais "de todos os tenants" ficavam em zero enquanto Uso e cobrança mostrava o consumo dele.
        Function<String, TenantUsageDto> fonte = usoMedido;
        if (fonte != null && !tenants.containsKey(DefaultTenant.ID)) {
            usos.add(fonte.apply(DefaultTenant.ID));
        }
        int execToday = usos.stream().mapToInt(u -> u != null ? u.executionsToday() : 0).sum();
        long tokensMonth = usos.stream().mapToLong(u -> u != null ? u.tokensThisMonth() : 0).sum();
        return new TenantStatsDto((int) active, (int) trial, execToday, tokensMonth, impersonationEnabled);
    }

    @Override
    public TenantDto createTenant(CreateTenantRequest request) {
        // Sem id não há chave no cadastro: o put no ConcurrentHashMap lançava NPE e a resposta era um 500
        // sem explicação (RCHFLW-193). IllegalArgumentException é o 400 do GlobalExceptionHandler.
        if (request == null || request.tenantId() == null || request.tenantId().isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        log.info("Creating tenant: {} ({})", request.name(), request.tenantId());
        var limits = request.limits() != null ? request.limits() : planDefaults.apply(request.plan())
                .map(p -> new TenantLimitsDto(p.executionsPerDay(), p.tokensPerMonth(), p.maxWorkflows(),
                        p.maxUsers(), List.of(), List.of()))
                .orElseGet(() -> new TenantLimitsDto(100, 1_000_000, 5, 3, List.of(), List.of()));
        var usage = new TenantUsageDto(0, 0, 0, 0);
        // A lista que vale é a de cima (allowedModels); a de limits é aceita só quando a de cima não veio,
        // para um cliente da API que só preencheu os limites não perder a restrição.
        List<String> allowed = request.allowedModels() != null ? request.allowedModels()
                : request.limits() != null ? request.limits().allowedModels() : null;
        var tenant = new TenantDto(request.tenantId(), request.name(), request.plan(),
                "trial".equals(request.plan()) ? "trial" : "active",
                request.adminEmail(), request.sector(), limits, usage,
                normalizeModels(allowed),
                Instant.now().toString().substring(0, 7));
        tenants.put(request.tenantId(), tenant);
        auditTrail.record(AuditAction.CREATE, "tenant", request.tenantId(), true, null,
                Map.of("name", String.valueOf(request.name()), "plan", String.valueOf(request.plan())));
        return comUsoMedido(tenant);
    }

    @Override
    public TenantDto getTenant(String tenantId) {
        TenantDto tenant = tenants.get(tenantId);
        if (tenant == null) throw new IllegalArgumentException("Tenant not found: " + tenantId);
        return comUsoMedido(tenant);
    }

    @Override
    public TenantDto updateTenant(String tenantId, TenantDto update) {
        // Fail fast when the body tries to rename a tenant via the URL
        // path. Previously the path won silently, which masked client
        // bugs and could let a caller target the wrong tenant.
        if (update != null && update.id() != null
                && !update.id().isBlank() && !update.id().equals(tenantId)) {
            throw new IllegalArgumentException(
                    "tenantId in body (" + update.id()
                            + ") does not match path variable (" + tenantId + ")");
        }
        // compute() guarantees the read-modify-write runs under the map's
        // bucket lock so concurrent suspend/activate/updateTenant on the
        // same tenantId serialize instead of racing.
        if (update != null && "suspended".equals(update.status())) {
            recusaSuspenderOPadrao(tenantId);
        }
        TenantDto updated = tenants.compute(tenantId, (id, existing) -> {
            if (existing == null) {
                throw new IllegalArgumentException("Tenant not found: " + tenantId);
            }
            return new TenantDto(tenantId,
                    update.name() != null ? update.name() : existing.name(),
                    update.plan() != null ? update.plan() : existing.plan(),
                    update.status() != null ? update.status() : existing.status(),
                    update.adminEmail() != null ? update.adminEmail() : existing.adminEmail(),
                    update.sector() != null ? update.sector() : existing.sector(),
                    update.limits() != null ? update.limits() : existing.limits(),
                    existing.usage(),
                    update.allowedModels() != null
                            ? normalizeModels(update.allowedModels()) : existing.allowedModels(),
                    existing.createdAt());
        });
        auditTrail.record(AuditAction.UPDATE, "tenant", tenantId, true, null, null);
        return comUsoMedido(updated);
    }

    /**
     * Os modelos que o tenant pode usar (RCHFLW-79) — o recorte do catálogo da plataforma que o
     * seletor dos nós mostra e que a execução confere ({@code TenantModelPolicy}).
     *
     * @return vazio quando o tenant não tem restrição: lista vazia, ou tenant sem cadastro aqui (um
     *         tenant que só existe no produto — VendaX — ou o SYSTEM das execuções sem tenant). Vazio
     *         é "o catálogo inteiro", nunca "nada".
     */
    public Optional<List<String>> allowedModels(String tenantId) {
        if (tenantId == null) {
            return Optional.empty();
        }
        TenantDto tenant = tenants.get(tenantId);
        if (tenant == null || tenant.allowedModels() == null || tenant.allowedModels().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(List.copyOf(tenant.allowedModels()));
    }

    /** Aparado, sem vazios nem repetidos: um {@code " gpt-4o"} vindo do formulário não pode virar outro id. */
    static List<String> normalizeModels(List<String> models) {
        if (models == null) {
            return List.of();
        }
        return models.stream()
                .filter(m -> m != null && !m.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    /**
     * O tenant padrão não se suspende. É onde cai todo token sem a claim de tenant — inclusive o do
     * admin da plataforma —, e desde o QA2 P6 a suspensão tranca de fato: suspendê-lo (o cadastro
     * aceita criar um {@code __default__}) deixaria a plataforma inteira com 403, e só um ADMIN de outro
     * tenant ou um restart desfaria. Recusa, em vez de ignorar, para o status nunca mentir na tela.
     */
    private static void recusaSuspenderOPadrao(String tenantId) {
        if (DefaultTenant.ID.equals(tenantId)) {
            throw new IllegalArgumentException("The default tenant (" + tenantId + ") cannot be suspended");
        }
    }

    @Override
    public void suspendTenant(String tenantId) {
        recusaSuspenderOPadrao(tenantId);
        log.info("Suspending tenant: {}", tenantId);
        tenants.compute(tenantId, (id, t) -> {
            if (t == null) throw new IllegalArgumentException("Tenant not found: " + tenantId);
            return new TenantDto(t.id(), t.name(), t.plan(), "suspended",
                    t.adminEmail(), t.sector(), t.limits(), t.usage(), t.allowedModels(), t.createdAt());
        });
        auditTrail.record(AuditAction.UPDATE, "tenant", tenantId, true, null,
                Map.of("status", "suspended"));
    }

    @Override
    public void activateTenant(String tenantId) {
        log.info("Activating tenant: {}", tenantId);
        tenants.compute(tenantId, (id, t) -> {
            if (t == null) throw new IllegalArgumentException("Tenant not found: " + tenantId);
            return new TenantDto(t.id(), t.name(), t.plan(), "active",
                    t.adminEmail(), t.sector(), t.limits(), t.usage(), t.allowedModels(), t.createdAt());
        });
        auditTrail.record(AuditAction.UPDATE, "tenant", tenantId, true, null,
                Map.of("status", "active"));
    }

    @Override
    public void deleteTenant(String tenantId) {
        log.info("Deleting tenant: {}", tenantId);
        tenants.remove(tenantId);
        auditTrail.record(AuditAction.DELETE, "tenant", tenantId, true, null, null);
    }
}
