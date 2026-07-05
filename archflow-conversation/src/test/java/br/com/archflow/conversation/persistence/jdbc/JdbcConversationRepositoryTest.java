package br.com.archflow.conversation.persistence.jdbc;

import br.com.archflow.conversation.domain.Conversation;
import br.com.archflow.conversation.domain.Message;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests JdbcConversationRepository against H2 (portable ANSI SQL).
 */
@DisplayName("JdbcConversationRepository")
class JdbcConversationRepositoryTest {

    private static javax.sql.DataSource dataSource;
    private JdbcConversationRepository repo;

    @BeforeAll
    static void createDataSource() throws SQLException {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:convrepo;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        dataSource = ds;

        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS conversations (
                    id          VARCHAR(64)  PRIMARY KEY,
                    tenant_id   VARCHAR(64)  NOT NULL,
                    user_id     VARCHAR(64)  NOT NULL,
                    channel     VARCHAR(32)  NOT NULL,
                    status      VARCHAR(32)  NOT NULL,
                    persona     VARCHAR(128),
                    metadata    TEXT,
                    created_at  TIMESTAMP    NOT NULL,
                    updated_at  TIMESTAMP    NOT NULL
                )
            """);
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS conversation_messages (
                    id              VARCHAR(64)  PRIMARY KEY,
                    conversation_id VARCHAR(64)  NOT NULL,
                    tenant_id       VARCHAR(64)  NOT NULL,
                    sender_type     VARCHAR(16)  NOT NULL,
                    message_type    VARCHAR(16)  NOT NULL,
                    content         TEXT         NOT NULL,
                    media_url       TEXT,
                    metadata        TEXT,
                    created_at      TIMESTAMP    NOT NULL
                )
            """);
        }
    }

    @BeforeEach
    void setUp() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute("DELETE FROM conversation_messages");
            conn.createStatement().execute("DELETE FROM conversations");
        }
        repo = new JdbcConversationRepository(dataSource);
    }

    @Test
    @DisplayName("save + findById round-trip preserves all fields")
    void saveAndFindRoundTrip() {
        Conversation conv = new Conversation(
                "c1", "acme", "user-1", "WHATSAPP",
                Conversation.ConversationStatus.ACTIVE, "support",
                Map.of("origin", "campaign-42"), null, null);
        repo.save(conv);

        Optional<Conversation> found = repo.findById("acme", "c1");
        assertThat(found).isPresent();
        assertThat(found.get().userId()).isEqualTo("user-1");
        assertThat(found.get().channel()).isEqualTo("WHATSAPP");
        assertThat(found.get().persona()).isEqualTo("support");
        assertThat(found.get().metadata()).containsEntry("origin", "campaign-42");
    }

    @Test
    @DisplayName("findById enforces tenant isolation")
    void tenantIsolation() {
        repo.save(Conversation.start("acme", "user-1", "API"));
        Conversation conv = repo.listByTenant("acme").get(0);

        assertThat(repo.findById("other-tenant", conv.id())).isEmpty();
        assertThat(repo.findById("acme", conv.id())).isPresent();
    }

    @Test
    @DisplayName("save again updates the existing conversation")
    void saveUpdates() {
        Conversation conv = Conversation.start("acme", "user-1", "API");
        repo.save(conv);
        repo.save(conv.withStatus(Conversation.ConversationStatus.CLOSED));

        assertThat(repo.findById("acme", conv.id()).orElseThrow().status())
                .isEqualTo(Conversation.ConversationStatus.CLOSED);
        assertThat(repo.listByTenant("acme")).hasSize(1);
    }

    @Test
    @DisplayName("listByUser filters by tenant and user")
    void listByUser() {
        repo.save(Conversation.start("acme", "alice", "API"));
        repo.save(Conversation.start("acme", "bob", "API"));
        repo.save(Conversation.start("globex", "alice", "API"));

        assertThat(repo.listByUser("acme", "alice")).hasSize(1);
        assertThat(repo.listByUser("acme", "bob")).hasSize(1);
        assertThat(repo.listByUser("globex", "alice")).hasSize(1);
    }

    @Test
    @DisplayName("delete removes the conversation and its messages")
    void deleteCascades() {
        Conversation conv = Conversation.start("acme", "user-1", "API");
        repo.save(conv);
        repo.addMessage(Message.userText(conv.id(), "acme", "hello"));
        repo.addMessage(Message.agentText(conv.id(), "acme", "hi!"));

        assertThat(repo.delete("acme", conv.id())).isTrue();
        assertThat(repo.findById("acme", conv.id())).isEmpty();
        assertThat(repo.countMessages("acme", conv.id())).isZero();
    }

    @Test
    @DisplayName("delete returns false for unknown conversation")
    void deleteUnknown() {
        assertThat(repo.delete("acme", "ghost")).isFalse();
    }

    @Test
    @DisplayName("messages are listed in chronological order")
    void messagesChronological() {
        Conversation conv = Conversation.start("acme", "user-1", "API");
        repo.save(conv);
        repo.addMessage(Message.userText(conv.id(), "acme", "first"));
        repo.addMessage(Message.agentText(conv.id(), "acme", "second"));
        repo.addMessage(Message.userText(conv.id(), "acme", "third"));

        List<Message> messages = repo.listMessages("acme", conv.id());
        assertThat(messages).extracting(Message::content)
                .containsExactly("first", "second", "third");
        assertThat(repo.countMessages("acme", conv.id())).isEqualTo(3);
    }

    @Test
    @DisplayName("listRecentMessages returns last N in chronological order")
    void recentMessages() {
        Conversation conv = Conversation.start("acme", "user-1", "API");
        repo.save(conv);
        for (int i = 1; i <= 5; i++) {
            repo.addMessage(Message.userText(conv.id(), "acme", "msg-" + i));
        }

        List<Message> recent = repo.listRecentMessages("acme", conv.id(), 2);
        assertThat(recent).extracting(Message::content).containsExactly("msg-4", "msg-5");
    }
}
