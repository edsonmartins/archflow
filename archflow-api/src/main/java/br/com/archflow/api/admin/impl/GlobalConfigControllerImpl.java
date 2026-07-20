package br.com.archflow.api.admin.impl;

import br.com.archflow.api.admin.GlobalConfigController;
import br.com.archflow.api.admin.dto.GlobalConfigDto.*;
import br.com.archflow.api.admin.store.GlobalConfigStore;
import br.com.archflow.api.admin.store.InMemoryGlobalConfigStore;
import br.com.archflow.api.audit.AuditTrail;
import br.com.archflow.observability.audit.AuditAction;
import br.com.archflow.observability.audit.AuditEvent;
import br.com.archflow.observability.audit.AuditRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private static final String USAGE_CSV_HEADER =
            "tenantId,tenantName,executions,tokensInput,tokensOutput,estimatedCost,percentOfTotal,planLimit";

    private final ObjectMapper mapper = new ObjectMapper();

    private volatile GlobalConfigStore store;
    private volatile Supplier<AuditRepository> auditRepository = () -> null;
    private volatile AuditTrail auditTrail = AuditTrail.noop();

    /** In-memory store (historical behaviour) — dev/tests and the default bean. */
    public GlobalConfigControllerImpl() {
        this(new InMemoryGlobalConfigStore());
    }

    public GlobalConfigControllerImpl(GlobalConfigStore store) {
        this.store = store != null ? store : new InMemoryGlobalConfigStore();
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

    @Override
    public List<LLMModelDto> getModels() {
        return read(KEY_MODELS, new TypeReference<List<LLMModelDto>>() {}, GlobalConfigControllerImpl::defaultModels);
    }

    @Override
    public synchronized void toggleModel(String modelId, ToggleModelRequest request) {
        log.info("Toggle model {}: active={}", modelId, request.active());
        List<LLMModelDto> updated = new ArrayList<>(getModels());
        updated.replaceAll(m -> m.id().equals(modelId)
                ? new LLMModelDto(m.id(), m.name(), m.provider(),
                        request.active() ? "active" : "deprecated",
                        m.costInputPer1M(), m.costOutputPer1M())
                : m);
        write(KEY_MODELS, updated);
        auditTrail.record(AuditAction.CONFIG_CHANGE, "global-config", "model:" + modelId,
                true, null, Map.of("active", String.valueOf(request.active())));
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
     * No real per-tenant metering source exists yet (executions are not
     * tenant-partitioned), so this returns empty instead of fabricated rows.
     */
    @Override
    public List<UsageRowDto> getUsageByTenant(String month) {
        return List.of();
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
                    .append(row.estimatedCost()).append(',')
                    .append(row.percentOfTotal()).append(',')
                    .append(row.planLimit()).append('\n');
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

    private static List<LLMModelDto> defaultModels() {
        return List.of(
                new LLMModelDto("gpt-4o", "GPT-4o", "OpenAI", "active", 2.50, 10.00),
                new LLMModelDto("claude-sonnet-4-6", "Claude Sonnet 4.6", "Anthropic", "active", 3.00, 15.00),
                new LLMModelDto("gemma-3-27b", "Gemma 3 27B", "Local", "beta", 0, 0),
                new LLMModelDto("gpt-3.5-turbo", "GPT-3.5 Turbo", "OpenAI", "deprecated", 0.50, 1.50));
    }

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
