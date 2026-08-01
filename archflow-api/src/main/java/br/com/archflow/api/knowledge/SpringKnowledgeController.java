package br.com.archflow.api.knowledge;

import br.com.archflow.api.config.TenantContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/knowledge-bases")
public class SpringKnowledgeController {
    private final KnowledgeService service;

    public SpringKnowledgeController(KnowledgeService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<KnowledgeBase> create(@RequestBody CreateBaseRequest request) {
        return ResponseEntity.status(201).body(service.createBase(
                TenantContext.currentTenantId(), request.workspaceId(), request.name()));
    }

    @GetMapping
    public List<KnowledgeBase> list() {
        return service.bases(TenantContext.currentTenantId());
    }

    @PostMapping("/{id}/documents")
    public ResponseEntity<KnowledgeService.IngestionSubmission> ingest(
            @PathVariable String id, @RequestBody IngestRequest request) {
        var result = service.ingest(TenantContext.currentTenantId(), id, request.fileId());
        return result == null ? ResponseEntity.notFound().build()
                : ResponseEntity.accepted().body(result);
    }

    @GetMapping("/{id}/documents")
    public ResponseEntity<List<KnowledgeDocument>> documents(@PathVariable String id) {
        var result = service.documents(TenantContext.currentTenantId(), id);
        return result == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(result);
    }

    @GetMapping("/documents/{id}/chunks")
    public ResponseEntity<List<DocumentChunk>> chunks(@PathVariable String id) {
        var result = service.chunks(TenantContext.currentTenantId(), id);
        return result == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(result);
    }

    @PostMapping("/{id}/search")
    public ResponseEntity<List<KnowledgeVectorIndex.SearchHit>> search(
            @PathVariable String id, @RequestBody SearchRequest request) {
        var result = service.search(TenantContext.currentTenantId(), id, request.versionId(),
                request.query(),
                request.limit() == null ? 5 : request.limit(),
                request.minScore() == null ? 0.0 : request.minScore());
        return result == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(result);
    }

    @GetMapping("/{id}/index/versions")
    public ResponseEntity<List<KnowledgeVectorIndex.IndexVersion>> indexVersions(
            @PathVariable String id) {
        var result = service.indexVersions(TenantContext.currentTenantId(), id);
        return result == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(result);
    }

    @PutMapping("/{id}/index/active")
    public ResponseEntity<KnowledgeVectorIndex.IndexVersion> rollbackIndex(
            @PathVariable String id, @RequestBody ActivateIndexRequest request) {
        var result = service.rollbackIndex(
                TenantContext.currentTenantId(), id, request.versionId());
        return result == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(result);
    }

    public record CreateBaseRequest(String name, String workspaceId) {}
    public record IngestRequest(String fileId) {}
    public record SearchRequest(String query, String versionId, Integer limit, Double minScore) {}
    public record ActivateIndexRequest(String versionId) {}
}
