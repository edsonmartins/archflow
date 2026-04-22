package br.com.archflow.api.admin.impl;

import br.com.archflow.api.admin.GlobalConfigController;
import br.com.archflow.api.admin.dto.GlobalConfigDto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class GlobalConfigControllerImpl implements GlobalConfigController {
    private static final Logger log = LoggerFactory.getLogger(GlobalConfigControllerImpl.class);

    private final List<LLMModelDto> models = new ArrayList<>(List.of(
            new LLMModelDto("gpt-4o", "GPT-4o", "OpenAI", "active", 2.50, 10.00),
            new LLMModelDto("claude-sonnet-4-6", "Claude Sonnet 4.6", "Anthropic", "active", 3.00, 15.00),
            new LLMModelDto("gemma-3-27b", "Gemma 3 27B", "Local", "beta", 0, 0),
            new LLMModelDto("gpt-3.5-turbo", "GPT-3.5 Turbo", "OpenAI", "deprecated", 0.50, 1.50)
    ));

    private final Map<String, PlanDefaultsDto> planDefaults = new ConcurrentHashMap<>(Map.of(
            "enterprise", new PlanDefaultsDto("enterprise", 1000, 10_000_000, 50, 25),
            "professional", new PlanDefaultsDto("professional", 500, 5_000_000, 20, 10),
            "trial", new PlanDefaultsDto("trial", 50, 100_000, 3, 2)
    ));

    private FeatureTogglesDto toggles = new FeatureTogglesDto(true, true, true, false, false, true);

    @Override
    public List<LLMModelDto> getModels() {
        return List.copyOf(models);
    }

    @Override
    public void toggleModel(String modelId, ToggleModelRequest request) {
        log.info("Toggle model {}: active={}", modelId, request.active());
        for (int i = 0; i < models.size(); i++) {
            if (models.get(i).id().equals(modelId)) {
                var m = models.get(i);
                models.set(i, new LLMModelDto(m.id(), m.name(), m.provider(),
                        request.active() ? "active" : "deprecated", m.costInputPer1M(), m.costOutputPer1M()));
                return;
            }
        }
    }

    @Override
    public List<PlanDefaultsDto> getPlanDefaults() {
        return new ArrayList<>(planDefaults.values());
    }

    @Override
    public void updatePlanDefaults(String plan, PlanDefaultsDto defaults) {
        log.info("Update plan defaults: {}", plan);
        planDefaults.put(plan, defaults);
    }

    @Override
    public FeatureTogglesDto getToggles() {
        return toggles;
    }

    @Override
    public void updateToggles(FeatureTogglesDto newToggles) {
        log.info("Update feature toggles");
        this.toggles = newToggles;
    }

    @Override
    public List<AuditEntryDto> getAuditLog(int limit) {
        return List.of(
                new AuditEntryDto(UUID.randomUUID().toString(), Instant.now().toString(),
                        "superadmin", "tenant.created", "Created tenant 'Demo Trial'"),
                new AuditEntryDto(UUID.randomUUID().toString(), Instant.now().minusSeconds(3600).toString(),
                        "superadmin", "model.toggled", "Disabled GPT-3.5 Turbo"),
                new AuditEntryDto(UUID.randomUUID().toString(), Instant.now().minusSeconds(7200).toString(),
                        "tenant_rio_quality/joao", "workflow.executed", "Executed 'Customer Support Flow'")
        );
    }

    @Override
    public List<UsageRowDto> getUsageByTenant(String month) {
        return List.of(
                new UsageRowDto("tenant_rio_quality", "Rio Quality", 8400, 3_200_000, 1_000_000, 48.50, 62, 10_000_000),
                new UsageRowDto("tenant_acme_corp", "Acme Corp", 2100, 800_000, 400_000, 18.20, 24, 5_000_000),
                new UsageRowDto("tenant_demo", "Demo Trial", 350, 40_000, 10_000, 1.10, 14, 100_000)
        );
    }

    @Override
    public String exportUsageCsv(String month) {
        StringBuilder csv = new StringBuilder();
        csv.append("tenantId,tenantName,executions,tokensInput,tokensOutput,estimatedCost,percentOfTotal,planLimit\n");
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
}
