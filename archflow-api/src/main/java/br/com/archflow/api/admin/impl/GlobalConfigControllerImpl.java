package br.com.archflow.api.admin.impl;

import br.com.archflow.api.admin.GlobalConfigController;
import br.com.archflow.api.admin.dto.GlobalConfigDto.*;
import br.com.archflow.api.admin.dto.TenantDto;
import br.com.archflow.api.admin.store.GlobalConfigStore;
import br.com.archflow.api.admin.store.InMemoryGlobalConfigStore;
import br.com.archflow.api.audit.AuditTrail;
import br.com.archflow.api.usage.RegistroDeUso;
import br.com.archflow.api.usage.UsoSomado;
import br.com.archflow.langchain4j.provider.LLMProvider;
import br.com.archflow.observability.audit.AuditAction;
import br.com.archflow.observability.audit.AuditEvent;
import br.com.archflow.observability.audit.AuditRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Platform-wide configuration: LLM model catalog, plan defaults, feature
 * toggles, cross-tenant usage reports. Intentionally NOT tenant-scoped —
 * these are deployment-level settings managed by superadmins.
 *
 * <p>The HTTP exposure of this controller is gated by
 * {@code AdminRoleInterceptor} (role ADMIN/SUPERADMIN) registered for
 * {@code /api/admin/global/**}. Without that, any authenticated caller could
 * mutate models/toggles for the whole deployment.
 *
 * <p>State lives in a {@link GlobalConfigStore} (JSON key-value): in-memory by
 * default, durable ({@code JdbcGlobalConfigStore}) when
 * {@code archflow.persistence.jdbc.enabled=true}. Built-in defaults are used
 * until the first write of each key.
 *
 * <p>The audit log endpoint reads from the real {@link AuditRepository} when
 * one is configured; usage-by-tenant has no real per-tenant metering source
 * yet, so it returns empty (never fabricated data).
 */
public class GlobalConfigControllerImpl implements GlobalConfigController {
    private static final Logger log = LoggerFactory.getLogger(GlobalConfigControllerImpl.class);

    static final String KEY_MODELS = "models";
    static final String KEY_PLAN_DEFAULTS = "planDefaults";
    static final String KEY_TOGGLES = "featureToggles";

    static final String STATUS_ACTIVE = "active";
    static final String STATUS_DEPRECATED = "deprecated";

    private static final String USAGE_CSV_HEADER =
            "tenantId,tenantName,executions,tokensInput,tokensOutput,costReais,declaredCostUsd,"
                    + "executionsWithoutPrice,percentOfTotal,planLimit";

    private final ObjectMapper mapper = new ObjectMapper();

    private volatile GlobalConfigStore store;
    private volatile Supplier<AuditRepository> auditRepository = () -> null;
    private volatile AuditTrail auditTrail = AuditTrail.noop();
    /** O consumo medido (RCHFLW-75); sem ele, o relatório de uso sai vazio — nada medido. */
    private volatile RegistroDeUso registroDeUso;
    /** O cadastro de tenants, para nome e limite do plano; vazio quando não há cadastro. */
    private volatile Function<String, Optional<TenantDto>> cadastroDeTenants = id -> Optional.empty();

    /** Provider da plataforma ({@code archflow.llm.provider}): dono dos ids de {@link #platformModels}. */
    private final String platformProvider;
    /** {@code ARCHFLOW_LLM_MODELS}; vazio = a lista do código ({@link LLMProvider}). */
    private final List<String> platformModels;

    /** In-memory store (historical behaviour) — dev/tests and the default bean. */
    public GlobalConfigControllerImpl() {
        this(new InMemoryGlobalConfigStore());
    }

    public GlobalConfigControllerImpl(GlobalConfigStore store) {
        this(store, null, null);
    }

    /**
     * @param platformProvider {@code archflow.llm.provider}
     * @param platformModels   {@code archflow.llm.models} / {@code ARCHFLOW_LLM_MODELS}: ids separados
     *                         por vírgula, do provider da plataforma; vazio ou nulo usa a lista do código
     */
    public GlobalConfigControllerImpl(String platformProvider, String platformModels) {
        this(new InMemoryGlobalConfigStore(), platformProvider, platformModels);
    }

    public GlobalConfigControllerImpl(GlobalConfigStore store, String platformProvider, String platformModels) {
        this.store = store != null ? store : new InMemoryGlobalConfigStore();
        this.platformProvider = platformProvider == null || platformProvider.isBlank()
                ? LLMProvider.OPENAI.getId() : platformProvider.trim().toLowerCase(Locale.ROOT);
        this.platformModels = parseModelList(platformModels);
    }

    /** Swaps the backing store (wired after construction via configuration). */
    public void setStore(GlobalConfigStore store) {
        if (store != null) {
            this.store = store;
        }
    }

    /** Audit log reader — optional, absent means empty audit endpoint. */
    public void setAuditRepository(Supplier<AuditRepository> auditRepository) {
        if (auditRepository != null) {
            this.auditRepository = auditRepository;
        }
    }

    /** Audit event producer for config mutations — optional, no-op default. */
    public void setAuditTrail(AuditTrail auditTrail) {
        if (auditTrail != null) {
            this.auditTrail = auditTrail;
        }
    }

    // ------------------------------------------------------------------ models

    /**
     * O catálogo da plataforma: a semente ({@code ARCHFLOW_LLM_MODELS} ou a lista do código) com o
     * status que o superadmin gravou por cima.
     *
     * <p>O store guarda o catálogo inteiro desde sempre, mas aqui ele só empresta o <b>status</b>: os
     * ids vêm da semente. Assim um modelo novo na variável aparece sem limpar o banco, um que saiu da
     * variável some, e um catálogo gravado antes desta mudança (ids de exemplo como {@code gpt-4o} com
     * provider "OpenAI") não ressuscita ids que o provider da plataforma não aceita.
     */
    @Override
    public List<LLMModelDto> getModels() {
        List<LLMModelDto> stored = read(KEY_MODELS, new TypeReference<List<LLMModelDto>>() {}, List::of);
        Map<String, String> statusByKey = new HashMap<>();
        Map<String, String> statusById = new HashMap<>();
        for (LLMModelDto m : stored) {
            if (m == null || m.id() == null || m.status() == null) {
                continue;
            }
            statusByKey.put(key(m.provider(), m.id()), m.status());
            statusById.putIfAbsent(m.id(), m.status());
        }
        List<LLMModelDto> catalog = new ArrayList<>();
        for (LLMModelDto seed : seedModels()) {
            String status = statusByKey.getOrDefault(key(seed.provider(), seed.id()),
                    statusById.getOrDefault(seed.id(), seed.status()));
            catalog.add(new LLMModelDto(seed.id(), seed.name(), seed.provider(), status,
                    seed.costInputPer1M(), seed.costOutputPer1M()));
        }
        return catalog;
    }

    /**
     * Os modelos que os nós podem escolher: tudo que não foi desligado ({@code beta} conta como
     * ligado). É o que o seletor do designer lista ({@code GET /api/workflow/providers}).
     */
    public List<LLMModelDto> activeModels() {
        return getModels().stream().filter(m -> !STATUS_DEPRECATED.equals(m.status())).toList();
    }

    @Override
    public synchronized void toggleModel(String modelId, ToggleModelRequest request) {
        log.info("Toggle model {}: active={}", modelId, request.active());
        List<LLMModelDto> updated = new ArrayList<>(getModels());
        // Por id: o mesmo id em dois providers (gpt-4o na OpenAI e na Azure) liga/desliga junto — a
        // rota é /models/{id}, e desligar "o gpt-4o" para metade da plataforma não é o que se pede.
        updated.replaceAll(m -> m.id().equals(modelId)
                ? new LLMModelDto(m.id(), m.name(), m.provider(),
                        request.active() ? STATUS_ACTIVE : STATUS_DEPRECATED,
                        m.costInputPer1M(), m.costOutputPer1M())
                : m);
        write(KEY_MODELS, updated);
        auditTrail.record(AuditAction.CONFIG_CHANGE, "global-config", "model:" + modelId,
                true, null, Map.of("active", String.valueOf(request.active())));
    }

    /**
     * A semente do catálogo.
     *
     * <p>Com {@code ARCHFLOW_LLM_MODELS} ({@code archflow.llm.models}): exatamente aqueles ids, todos do
     * provider da plataforma ({@code archflow.llm.provider}) — o deployment diz o que oferece, no
     * formato do provider ({@code openai/gpt-6-luna}, {@code google/gemma-4-31b-it:free}). Sem ela, os
     * modelos do enum {@link LLMProvider}, que era a lista que o seletor já mostrava. Eram três listas
     * que não conversavam (seletor, catálogo global com ids de exemplo e permitidos por tenant); agora
     * o catálogo é um só e os outros dois são recortes dele.
     */
    List<LLMModelDto> seedModels() {
        if (!platformModels.isEmpty()) {
            Optional<LLMProvider> known = LLMProvider.fromId(platformProvider);
            return platformModels.stream()
                    .map(id -> new LLMModelDto(id,
                            known.flatMap(p -> p.getModel(id)).map(LLMProvider.ModelInfo::name).orElse(id),
                            platformProvider, STATUS_ACTIVE, null, null))
                    .toList();
        }
        List<LLMModelDto> seed = new ArrayList<>();
        for (LLMProvider provider : LLMProvider.values()) {
            for (LLMProvider.ModelInfo m : provider.getModels()) {
                seed.add(new LLMModelDto(m.id(), m.name(), provider.getId(), STATUS_ACTIVE, null, null));
            }
        }
        return seed;
    }

    /** {@code "a, b,,a "} → {@code [a, b]}: aparado, sem vazios e sem repetidos, na ordem dada. */
    static List<String> parseModelList(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (String part : csv.split(",")) {
            if (!part.isBlank()) {
                ids.add(part.trim());
            }
        }
        return List.copyOf(ids);
    }

    private static String key(String provider, String id) {
        return (provider == null ? "" : provider.toLowerCase(Locale.ROOT)) + "|" + id;
    }

    // ------------------------------------------------------------ plan defaults

    @Override
    public List<PlanDefaultsDto> getPlanDefaults() {
        return new ArrayList<>(planDefaultsByPlan().values());
    }

    @Override
    public synchronized void updatePlanDefaults(String plan, PlanDefaultsDto defaults) {
        log.info("Update plan defaults: {}", plan);
        Map<String, PlanDefaultsDto> byPlan = new LinkedHashMap<>(planDefaultsByPlan());
        byPlan.put(plan, defaults);
        write(KEY_PLAN_DEFAULTS, byPlan);
        auditTrail.record(AuditAction.CONFIG_CHANGE, "global-config", "plan:" + plan,
                true, null, null);
    }

    private Map<String, PlanDefaultsDto> planDefaultsByPlan() {
        return read(KEY_PLAN_DEFAULTS, new TypeReference<LinkedHashMap<String, PlanDefaultsDto>>() {},
                GlobalConfigControllerImpl::defaultPlanDefaults);
    }

    // ----------------------------------------------------------------- toggles

    @Override
    public FeatureTogglesDto getToggles() {
        return read(KEY_TOGGLES, new TypeReference<FeatureTogglesDto>() {},
                GlobalConfigControllerImpl::defaultToggles);
    }

    @Override
    public synchronized void updateToggles(FeatureTogglesDto newToggles) {
        log.info("Update feature toggles");
        write(KEY_TOGGLES, newToggles);
        auditTrail.record(AuditAction.CONFIG_CHANGE, "global-config", "featureToggles",
                true, null, null);
    }

    // --------------------------------------------------------------- audit log

    @Override
    public List<AuditEntryDto> getAuditLog(int limit) {
        AuditRepository repo = auditRepository.get();
        if (repo == null) {
            return List.of();
        }
        try {
            List<AuditEvent> events = repo.query(AuditRepository.AuditQuery.builder()
                    .limit(Math.max(1, limit))
                    .sortDescending(true));
            return events.stream().map(GlobalConfigControllerImpl::toEntry).toList();
        } catch (RuntimeException e) {
            log.warn("Failed to query audit repository", e);
            return List.of();
        }
    }

    private static AuditEntryDto toEntry(AuditEvent event) {
        String actor = event.getUsername() != null ? event.getUsername()
                : event.getUserId() != null ? event.getUserId() : "unknown";
        String action = event.getAction() != null ? event.getAction().getCode() : "unknown";
        StringBuilder details = new StringBuilder();
        if (event.getResourceType() != null) {
            details.append(event.getResourceType());
            if (event.getResourceId() != null) {
                details.append(':').append(event.getResourceId());
            }
        }
        if (!event.isSuccess()) {
            appendDetail(details, "failed");
        }
        if (event.getErrorMessage() != null && !event.getErrorMessage().isBlank()) {
            appendDetail(details, "error=" + event.getErrorMessage());
        }
        event.getContext().forEach((k, v) -> appendDetail(details, k + "=" + v));
        return new AuditEntryDto(
                event.getId(),
                event.getTimestamp() != null ? event.getTimestamp().toString() : "",
                actor,
                action,
                details.toString());
    }

    private static void appendDetail(StringBuilder sb, String part) {
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(part);
    }

    // -------------------------------------------------------------------- usage

    /**
     * Liga o relatório de uso ao consumo medido e ao cadastro de tenants (nome e limite do plano).
     */
    public void setUso(RegistroDeUso registroDeUso,
                       Function<String, Optional<TenantDto>> tenants) {
        this.registroDeUso = registroDeUso;
        if (tenants != null) {
            this.cadastroDeTenants = tenants;
        }
    }

    /**
     * O consumo medido de cada tenant no mês ({@code yyyy-MM}; em branco, o mês corrente). Tenant
     * sem medição não aparece — vazio é "nada medido", nunca uma linha de demonstração.
     *
     * @throws IllegalArgumentException mês fora do formato {@code yyyy-MM} (400)
     */
    @Override
    public List<UsageRowDto> getUsageByTenant(String month) {
        RegistroDeUso registro = registroDeUso;
        if (registro == null) {
            return List.of();
        }
        YearMonth mes;
        try {
            mes = month == null || month.isBlank() ? registro.mesAtual() : YearMonth.parse(month.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid month: " + month + " (expected yyyy-MM)");
        }
        Map<String, UsoSomado> porTenant = registro.porTenantNoMes(mes);
        long totalTokens = porTenant.values().stream().mapToLong(UsoSomado::tokens).sum();
        List<UsageRowDto> rows = new ArrayList<>();
        porTenant.forEach((tenantId, uso) -> {
            Optional<TenantDto> cadastro = cadastroDeTenants.apply(tenantId);
            double percent = totalTokens == 0 ? 0.0 : Math.round(uso.tokens() * 1000.0 / totalTokens) / 10.0;
            rows.add(new UsageRowDto(tenantId,
                    cadastro.map(TenantDto::name).filter(n -> !n.isBlank()).orElse(tenantId),
                    uso.execucoes(), uso.tokensEntrada(), uso.tokensSaida(), uso.custoCentavos(), uso.custoReais(),
                    uso.custoDeclaradoUsd(), uso.execucoesSemPreco(), percent,
                    cadastro.map(TenantDto::limits).map(TenantDto.TenantLimitsDto::tokensPerMonth).orElse(null)));
        });
        rows.sort(Comparator.comparingLong((UsageRowDto r) -> r.tokensInput() + r.tokensOutput()).reversed());
        return rows;
    }

    @Override
    public String exportUsageCsv(String month) {
        StringBuilder csv = new StringBuilder();
        csv.append(USAGE_CSV_HEADER).append('\n');
        for (UsageRowDto row : getUsageByTenant(month)) {
            csv.append(csvValue(row.tenantId())).append(',')
                    .append(csvValue(row.tenantName())).append(',')
                    .append(row.executions()).append(',')
                    .append(row.tokensInput()).append(',')
                    .append(row.tokensOutput()).append(',')
                    // Sem preço fica vazio, não "0": zero seria "de graça".
                    .append(row.costReais() == null ? "" : row.costReais()).append(',')
                    .append(row.declaredCostUsd() == null ? "" : row.declaredCostUsd()).append(',')
                    .append(row.executionsWithoutPrice()).append(',')
                    .append(row.percentOfTotal()).append(',')
                    .append(row.planLimit() == null ? "" : row.planLimit()).append('\n');
        }
        return csv.toString();
    }

    private static String csvValue(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    // ------------------------------------------------------------ store helpers

    private <T> T read(String key, TypeReference<T> type, Supplier<T> defaults) {
        try {
            return store.get(key)
                    .map(json -> fromJson(key, json, type))
                    .orElseGet(defaults);
        } catch (RuntimeException e) {
            log.warn("Failed to read global config key '{}'; falling back to defaults", key, e);
            return defaults.get();
        }
    }

    private void write(String key, Object value) {
        try {
            store.put(key, mapper.writeValueAsString(value));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist global config key: " + key, e);
        }
    }

    private <T> T fromJson(String key, String json, TypeReference<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Corrupt global config value for key: " + key, e);
        }
    }

    // ---------------------------------------------------------------- defaults

    private static LinkedHashMap<String, PlanDefaultsDto> defaultPlanDefaults() {
        LinkedHashMap<String, PlanDefaultsDto> defaults = new LinkedHashMap<>();
        defaults.put("enterprise", new PlanDefaultsDto("enterprise", 1000, 10_000_000, 50, 25));
        defaults.put("professional", new PlanDefaultsDto("professional", 500, 5_000_000, 20, 10));
        defaults.put("trial", new PlanDefaultsDto("trial", 50, 100_000, 3, 2));
        return defaults;
    }

    private static FeatureTogglesDto defaultToggles() {
        return new FeatureTogglesDto(true, true, true, false, false, true);
    }
}
