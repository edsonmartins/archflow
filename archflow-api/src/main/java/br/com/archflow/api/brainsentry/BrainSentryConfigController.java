package br.com.archflow.api.brainsentry;

import br.com.archflow.api.brainsentry.dto.BrainSentryConfigDto;

/**
 * Admin-facing controller for editing BrainSentry integration settings
 * at runtime. The stored values feed the {@code BrainSentryClient}
 * beans on the next request cycle.
 */
public interface BrainSentryConfigController {

    /** Returns the current config with the API key masked. */
    BrainSentryConfigDto get();

    /** Replaces the config atomically. Empty apiKey keeps the existing key. */
    BrainSentryConfigDto update(BrainSentryConfigDto update);
}
