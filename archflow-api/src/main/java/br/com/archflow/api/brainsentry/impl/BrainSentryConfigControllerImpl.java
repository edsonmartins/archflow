package br.com.archflow.api.brainsentry.impl;

import br.com.archflow.api.brainsentry.BrainSentryConfigController;
import br.com.archflow.api.brainsentry.dto.BrainSentryConfigDto;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Default in-memory implementation. Holds the config in an
 * {@link AtomicReference} so reads/writes are race-free across
 * concurrent admin requests.
 *
 * <p>This is intentionally not persistent by itself — production
 * deployments can override the bean with a JDBC/file-backed version.
 * The default suits dev and gives a single source of truth that
 * outlives a single HTTP request.
 */
public class BrainSentryConfigControllerImpl implements BrainSentryConfigController {

    private final AtomicReference<BrainSentryConfigDto> current;

    public BrainSentryConfigControllerImpl(BrainSentryConfigDto initial) {
        this.current = new AtomicReference<>(initial);
    }

    @Override
    public BrainSentryConfigDto get() {
        return current.get().withMaskedKey();
    }

    @Override
    public synchronized BrainSentryConfigDto update(BrainSentryConfigDto incoming) {
        BrainSentryConfigDto previous = current.get();
        String apiKey = (incoming.apiKey() == null || incoming.apiKey().isBlank()
                        || incoming.apiKey().contains("…"))
                ? previous.apiKey()
                : incoming.apiKey();
        BrainSentryConfigDto next = new BrainSentryConfigDto(
                incoming.enabled(),
                incoming.baseUrl(),
                apiKey,
                incoming.tenantId(),
                incoming.maxTokenBudget(),
                incoming.deepAnalysisEnabled(),
                incoming.timeoutSeconds());
        current.set(next);
        return next.withMaskedKey();
    }
}
