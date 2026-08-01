package br.com.archflow.api.knowledge;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryKnowledgeVectorIndex implements KnowledgeVectorIndex {
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Map<String, IndexVersion> versions = new ConcurrentHashMap<>();
    private final Map<String, String> versionScopes = new ConcurrentHashMap<>();
    private final Map<String, String> activeVersions = new ConcurrentHashMap<>();

    public synchronized IndexVersion createCandidate(String tenantId, String baseId) {
        String basedOn = activeVersions.get(key(tenantId, baseId));
        String id = "idx-" + java.util.UUID.randomUUID();
        var version = new IndexVersion(id, baseId, basedOn, "BUILDING",
                java.time.Instant.now(), null);
        versions.put(id, version);
        versionScopes.put(id, key(tenantId, baseId));
        if (basedOn != null) {
            entries.entrySet().stream()
                    .filter(entry -> entry.getValue().tenantId.equals(tenantId))
                    .filter(entry -> entry.getValue().baseId.equals(baseId))
                    .filter(entry -> entry.getValue().versionId.equals(basedOn))
                    .toList().forEach(entry -> entries.put(
                            id + ":" + entry.getValue().chunkId,
                            entry.getValue().withVersion(id)));
        }
        return version;
    }

    public void replaceDocument(String tenantId, String baseId, String versionId,
                                String documentId,
                                List<EmbeddedChunk> chunks) {
        entries.entrySet().removeIf(entry -> entry.getValue().tenantId.equals(tenantId)
                && entry.getValue().versionId.equals(versionId)
                && entry.getValue().documentId.equals(documentId));
        for (EmbeddedChunk item : chunks) {
            entries.put(versionId + ":" + item.chunk().id(),
                    new Entry(item.chunk().id(), tenantId, baseId, versionId, documentId,
                            item.chunk().content(), item.embedding().clone()));
        }
    }

    public synchronized boolean activate(String tenantId, String baseId, String versionId) {
        IndexVersion candidate = versions.get(versionId);
        String key = key(tenantId, baseId);
        if (candidate == null || !key.equals(versionScopes.get(versionId))
                || !"BUILDING".equals(candidate.status()) || !java.util.Objects.equals(
                candidate.basedOnVersionId(), activeVersions.get(key))) return false;
        String previous = activeVersions.put(key, versionId);
        if (previous != null) {
            IndexVersion old = versions.get(previous);
            versions.put(previous, new IndexVersion(old.id(), old.baseId(),
                    old.basedOnVersionId(), "INACTIVE", old.createdAt(), old.activatedAt()));
        }
        versions.put(versionId, new IndexVersion(candidate.id(), candidate.baseId(),
                candidate.basedOnVersionId(), "ACTIVE", candidate.createdAt(),
                java.time.Instant.now()));
        return true;
    }

    public synchronized boolean rollback(String tenantId, String baseId, String versionId) {
        String key = key(tenantId, baseId);
        IndexVersion target = versions.get(versionId);
        if (target == null || !key.equals(versionScopes.get(versionId))
                || "BUILDING".equals(target.status())) return false;
        String previous = activeVersions.put(key, versionId);
        if (previous != null && !previous.equals(versionId)) {
            IndexVersion old = versions.get(previous);
            versions.put(previous, new IndexVersion(old.id(), old.baseId(),
                    old.basedOnVersionId(), "INACTIVE", old.createdAt(), old.activatedAt()));
        }
        versions.put(versionId, new IndexVersion(target.id(), target.baseId(),
                target.basedOnVersionId(), "ACTIVE", target.createdAt(),
                java.time.Instant.now()));
        return true;
    }

    public IndexVersion activeVersion(String tenantId, String baseId) {
        return versions.get(activeVersions.get(key(tenantId, baseId)));
    }

    public List<IndexVersion> versions(String tenantId, String baseId) {
        String scope = key(tenantId, baseId);
        return versions.values().stream()
                .filter(version -> scope.equals(versionScopes.get(version.id())))
                .sorted(java.util.Comparator.comparing(IndexVersion::createdAt).reversed())
                .toList();
    }

    public List<SearchHit> search(String tenantId, String baseId, String versionId,
                                  float[] query,
                                  int limit, double minScore) {
        String selected = versionId != null ? versionId : activeVersions.get(key(tenantId, baseId));
        if (selected == null) return List.of();
        return entries.entrySet().stream()
                .filter(entry -> entry.getValue().tenantId.equals(tenantId))
                .filter(entry -> entry.getValue().baseId.equals(baseId))
                .filter(entry -> entry.getValue().versionId.equals(selected))
                .map(entry -> new SearchHit(entry.getValue().chunkId, entry.getValue().documentId,
                        entry.getValue().content, cosine(query, entry.getValue().embedding)))
                .filter(hit -> hit.score() >= minScore)
                .sorted(java.util.Comparator.comparingDouble(SearchHit::score).reversed())
                .limit(limit).toList();
    }

    private static double cosine(float[] left, float[] right) {
        if (left.length != right.length) throw new IllegalArgumentException("Dimension mismatch");
        double dot = 0, a = 0, b = 0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            a += left[i] * left[i];
            b += right[i] * right[i];
        }
        return a == 0 || b == 0 ? 0 : dot / Math.sqrt(a * b);
    }

    private static String key(String tenantId, String baseId) {
        return tenantId + ":" + baseId;
    }

    private record Entry(String chunkId, String tenantId, String baseId,
                         String versionId, String documentId,
                         String content, float[] embedding) {
        Entry withVersion(String version) {
            return new Entry(chunkId, tenantId, baseId, version, documentId,
                    content, embedding.clone());
        }
    }
}
