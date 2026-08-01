CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE knowledge_embeddings (
    chunk_id         VARCHAR(64)  PRIMARY KEY,
    tenant_id        VARCHAR(128) NOT NULL,
    knowledge_base_id VARCHAR(64) NOT NULL,
    document_id      VARCHAR(64)  NOT NULL,
    content          TEXT         NOT NULL,
    embedding        vector(128)  NOT NULL,
    CONSTRAINT fk_knowledge_embedding_chunk FOREIGN KEY (chunk_id)
        REFERENCES document_chunks (id) ON DELETE CASCADE
);

CREATE INDEX idx_knowledge_embeddings_scope
    ON knowledge_embeddings (tenant_id, knowledge_base_id, document_id);
