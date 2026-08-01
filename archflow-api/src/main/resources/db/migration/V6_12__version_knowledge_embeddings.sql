CREATE TABLE knowledge_index_versions (
    id                   VARCHAR(64)  PRIMARY KEY,
    tenant_id            VARCHAR(128) NOT NULL,
    knowledge_base_id    VARCHAR(64)  NOT NULL,
    based_on_version_id  VARCHAR(64),
    status               VARCHAR(16)  NOT NULL,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    activated_at         TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_knowledge_index_base FOREIGN KEY (knowledge_base_id)
        REFERENCES knowledge_bases (id) ON DELETE CASCADE,
    CONSTRAINT fk_knowledge_index_parent FOREIGN KEY (based_on_version_id)
        REFERENCES knowledge_index_versions (id)
);

CREATE INDEX idx_knowledge_index_versions_scope
    ON knowledge_index_versions (tenant_id, knowledge_base_id, status, created_at DESC);

ALTER TABLE knowledge_embeddings ADD COLUMN index_version_id VARCHAR(64);

INSERT INTO knowledge_index_versions
    (id, tenant_id, knowledge_base_id, status, created_at, activated_at)
SELECT 'idx-legacy-' || md5(tenant_id || ':' || knowledge_base_id),
       tenant_id, knowledge_base_id, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
  FROM knowledge_embeddings
 GROUP BY tenant_id, knowledge_base_id;

UPDATE knowledge_embeddings
   SET index_version_id =
       'idx-legacy-' || md5(tenant_id || ':' || knowledge_base_id);

ALTER TABLE knowledge_embeddings
    ALTER COLUMN index_version_id SET NOT NULL,
    DROP CONSTRAINT knowledge_embeddings_pkey,
    DROP CONSTRAINT fk_knowledge_embedding_chunk,
    ADD PRIMARY KEY (index_version_id, chunk_id),
    ADD CONSTRAINT fk_knowledge_embedding_version
        FOREIGN KEY (index_version_id)
        REFERENCES knowledge_index_versions (id) ON DELETE CASCADE;

DROP INDEX idx_knowledge_embeddings_scope;

CREATE INDEX idx_knowledge_embeddings_scope
    ON knowledge_embeddings (tenant_id, knowledge_base_id, index_version_id, document_id);
