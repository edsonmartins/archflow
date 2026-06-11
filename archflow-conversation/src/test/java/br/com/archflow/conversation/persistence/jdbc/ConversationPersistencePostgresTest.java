package br.com.archflow.conversation.persistence.jdbc;

import br.com.archflow.conversation.domain.Conversation;
import br.com.archflow.conversation.domain.Message;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integração com PostgreSQL real: aplica a migration
 * V001__create_conversations.sql como está no repositório (valida que o DDL
 * roda no Postgres) e exercita os repositórios JDBC de conversação/prompt.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Persistência de conversação (PostgreSQL real)")
class ConversationPersistencePostgresTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static DataSource dataSource;
    private JdbcConversationRepository conversations;
    private JdbcPromptRegistry prompts;

    @BeforeAll
    static void applyMigration() throws Exception {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        dataSource = ds;

        String ddl;
        try (var in = ConversationPersistencePostgresTest.class.getResourceAsStream(
                "/db/migration/V001__create_conversations.sql")) {
            ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute(ddl);
        }
    }

    @BeforeEach
    void cleanTables() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute("DELETE FROM conversation_messages");
            conn.createStatement().execute("DELETE FROM conversations");
            conn.createStatement().execute("DELETE FROM prompt_versions");
        }
        conversations = new JdbcConversationRepository(dataSource);
        prompts = new JdbcPromptRegistry(dataSource);
    }

    @Test
    @DisplayName("conversa + mensagens sobrevivem a uma nova instância (restart)")
    void conversationSurvivesRestart() {
        Conversation conv = Conversation.start("acme", "alice", "WHATSAPP");
        conversations.save(conv);
        conversations.addMessage(Message.userText(conv.id(), "acme", "olá"));
        conversations.addMessage(Message.agentText(conv.id(), "acme", "oi! como ajudo?"));

        JdbcConversationRepository fresh = new JdbcConversationRepository(dataSource);
        assertThat(fresh.findById("acme", conv.id())).isPresent();
        assertThat(fresh.listMessages("acme", conv.id())).hasSize(2);
        assertThat(fresh.countMessages("acme", conv.id())).isEqualTo(2);
    }

    @Test
    @DisplayName("isolamento por tenant no Postgres")
    void tenantIsolation() {
        Conversation acme = Conversation.start("acme", "alice", "API");
        conversations.save(acme);

        assertThat(conversations.findById("globex", acme.id())).isEmpty();
        assertThat(conversations.listByTenant("globex")).isEmpty();
    }

    @Test
    @DisplayName("versionamento de prompts com troca de versão ativa")
    void promptVersioning() {
        prompts.register("acme", "greeting", "v1 {{name}}");
        prompts.register("acme", "greeting", "v2 {{name}}");
        prompts.activateVersion("acme", "greeting", 2);

        JdbcPromptRegistry fresh = new JdbcPromptRegistry(dataSource);
        assertThat(fresh.getActive("acme", "greeting").orElseThrow().version()).isEqualTo(2);
        assertThat(fresh.listVersions("acme", "greeting")).hasSize(2);
    }
}
