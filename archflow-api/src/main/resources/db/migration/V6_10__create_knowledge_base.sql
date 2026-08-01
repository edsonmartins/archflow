CREATE TABLE knowledge_bases (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    workspace_id VARCHAR(128),
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE knowledge_documents (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    knowledge_base_id VARCHAR(64) NOT NULL,
    file_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    chunk_count INTEGER NOT NULL DEFAULT 0,
    error TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_knowledge_document_base FOREIGN KEY (knowledge_base_id)
        REFERENCES knowledge_bases (id),
    CONSTRAINT fk_knowledge_document_file FOREIGN KEY (file_id)
        REFERENCES stored_files (id)
);

CREATE TABLE document_chunks (
    id VARCHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    document_id VARCHAR(64) NOT NULL,
    position INTEGER NOT NULL,
    content TEXT NOT NULL,
    start_offset INTEGER NOT NULL,
    end_offset INTEGER NOT NULL,
    CONSTRAINT fk_document_chunk_document FOREIGN KEY (document_id)
        REFERENCES knowledge_documents (id),
    CONSTRAINT uq_document_chunk_position UNIQUE (document_id, position)
);

CREATE INDEX idx_knowledge_bases_tenant ON knowledge_bases (tenant_id, created_at);
CREATE INDEX idx_knowledge_documents_base ON knowledge_documents (tenant_id, knowledge_base_id);
CREATE INDEX idx_document_chunks_document ON document_chunks (tenant_id, document_id, position);
