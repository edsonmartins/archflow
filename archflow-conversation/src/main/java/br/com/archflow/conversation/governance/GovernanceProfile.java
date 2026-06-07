package br.com.archflow.conversation.governance;

import java.time.Instant;

/**
 * Perfil de governança persistido (um por tenant, ou referenciável por id).
 * A persistência é responsabilidade do produto via {@link GovernanceProfileStore}.
 *
 * @since 1.0.0
 */
public record GovernanceProfile(
        String id,
        String tenantId,
        boolean active,
        GovernanceSettings settings,
        Instant createdAt,
        String createdBy,
        Instant updatedAt,
        String updatedBy
) {
    public GovernanceProfile {
        if (settings == null) {
            settings = GovernanceSettings.defaults();
        }
    }
}
