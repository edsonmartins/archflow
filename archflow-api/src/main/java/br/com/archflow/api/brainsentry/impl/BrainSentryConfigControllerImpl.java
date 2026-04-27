package br.com.archflow.api.brainsentry.impl;

import br.com.archflow.api.brainsentry.BrainSentryConfigController;
import br.com.archflow.api.brainsentry.dto.BrainSentryConfigDto;
import br.com.archflow.api.config.TenantContext;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default in-memory implementation. Stores one config per tenant so
 * tenant A's update cannot clobber tenant B's settings.
 *
 * <p>Tenant id is resolved from the request attribute populated by
 * {@code ImpersonationFilter}; missing headers map to
 * {@link TenantContext#DEFAULT_TENANT} so single-tenant deployments
 * keep a single config slot. Production deployments can override the
 * bean with a JDBC/file-backed version.
 */
public class BrainSentryConfigControllerImpl implements BrainSentryConfigController {

    private final BrainSentryConfigDto initial;
    private final Map<String, BrainSentryConfigDto> byTenant = new ConcurrentHashMap<>();

    public BrainSentryConfigControllerImpl(BrainSentryConfigDto initial) {
        this.initial = initial;
    }

    @Override
    public BrainSentryConfigDto get() {
        return byTenant.computeIfAbsent(TenantContext.currentTenantId(), k -> initial)
                .withMaskedKey();
    }

    @Override
    public BrainSentryConfigDto update(BrainSentryConfigDto incoming) {
        String tenantId = TenantContext.currentTenantId();
        // compute() runs under the bucket lock so concurrent updates
        // for the same tenant serialize, while different tenants stay
        // independent.
        BrainSentryConfigDto next = byTenant.compute(tenantId, (k, prev) -> {
            BrainSentryConfigDto previous = prev != null ? prev : initial;
            String apiKey = (incoming.apiKey() == null || incoming.apiKey().isBlank()
                            || incoming.apiKey().contains("…"))
                    ? previous.apiKey()
                    : incoming.apiKey();
            return new BrainSentryConfigDto(
                    incoming.enabled(),
                    incoming.baseUrl(),
                    apiKey,
                    incoming.tenantId(),
                    incoming.maxTokenBudget(),
                    incoming.deepAnalysisEnabled(),
                    incoming.timeoutSeconds());
        });
        return next.withMaskedKey();
    }
}
