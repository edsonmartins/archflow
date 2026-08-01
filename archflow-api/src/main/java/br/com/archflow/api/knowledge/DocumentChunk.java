package br.com.archflow.api.knowledge;

public record DocumentChunk(
        String id,
        String tenantId,
        String documentId,
        int position,
        String content,
        int startOffset,
        int endOffset) {
}
