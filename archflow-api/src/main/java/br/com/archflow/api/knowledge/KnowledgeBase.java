package br.com.archflow.api.knowledge;

import java.time.Instant;

public record KnowledgeBase(
        String id, String tenantId, String workspaceId, String name, Instant createdAt) {
}
