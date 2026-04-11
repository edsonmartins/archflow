package br.com.archflow.conversation.prompt;

import java.util.List;
import java.util.Optional;

/**
 * Registry of versioned system prompts with tenant isolation.
 *
 * <p>Each prompt has a logical {@code promptId} and multiple versions.
 * Only one version is active at a time per (tenantId, promptId).
 *
 * <p>Use case: SAC agents want to evolve their system prompt without
 * losing the ability to roll back. The product owns the persistence —
 * this interface defines the contract; in-memory implementation provided
 * for tests.
 */
public interface PromptRegistry {

    /**
     * Register a new prompt version. Becomes active automatically if it's
     * the first version for this (tenantId, promptId), otherwise inactive.
     *
     * @return The persisted version with its number assigned
     */
    PromptVersion register(String tenantId, String promptId, String template);

    /**
     * Returns the active version for a (tenantId, promptId), if any.
     */
    Optional<PromptVersion> getActive(String tenantId, String promptId);

    /**
     * Returns a specific version of a prompt.
     */
    Optional<PromptVersion> getVersion(String tenantId, String promptId, int version);

    /**
     * Lists all versions for a prompt, newest first.
     */
    List<PromptVersion> listVersions(String tenantId, String promptId);

    /**
     * Activate a specific version, deactivating any previously active version
     * of the same (tenantId, promptId).
     */
    void activateVersion(String tenantId, String promptId, int version);

    /**
     * Lists all prompt IDs registered for a tenant.
     */
    List<String> listPromptIds(String tenantId);
}
