-- Definições de fluxo persistidas como JSON.
-- Usada por br.com.archflow.engine.persistence.jdbc.JdbcFlowRepository.
-- definition é TEXT (JSON serializado) para portabilidade entre PostgreSQL,
-- H2 e MySQL — o engine não consulta dentro do documento.
CREATE TABLE flows (
    id          VARCHAR(64)  PRIMARY KEY,
    definition  TEXT         NOT NULL,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
