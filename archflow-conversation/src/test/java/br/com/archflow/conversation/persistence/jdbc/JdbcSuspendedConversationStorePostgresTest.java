package br.com.archflow.conversation.persistence.jdbc;

import br.com.archflow.conversation.form.FormData;
import br.com.archflow.conversation.state.SuspendedConversation;
import br.com.archflow.conversation.state.SuspendedConversation.ConversationStatus;
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
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integração com PostgreSQL real: aplica a migration
 * {@code V2_2__create_suspended_conversations.sql} e verifica que suspend/resume
 * sobrevive a restart (nova instância do store sobre o mesmo DataSource),
 * incluindo o round-trip do {@link FormData}.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Store durável de conversas suspensas (PostgreSQL real)")
class JdbcSuspendedConversationStorePostgresTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static DataSource dataSource;
    private JdbcSuspendedConversationStore store;

    @BeforeAll
    static void applyMigration() throws Exception {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        dataSource = ds;

        String ddl;
        try (var in = JdbcSuspendedConversationStorePostgresTest.class.getResourceAsStream(
                "/db/migration/V2_2__create_suspended_conversations.sql")) {
            ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute(ddl);
        }
    }

    @BeforeEach
    void cleanTable() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute("DELETE FROM suspended_conversations");
        }
        store = new JdbcSuspendedConversationStore(dataSource);
    }

    private static SuspendedConversation waiting(String id, String token) {
        return SuspendedConversation.builder()
                .conversationId(id)
                .resumeToken(token)
                .workflowId("wf-1")
                .workflowExecutionId("exec-1")
                .form(FormData.Templates.userRegistration())
                .createdAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(1800))
                .status(ConversationStatus.WAITING)
                .context(Map.of("locale", "pt-BR"))
                .priority(5)
                .build();
    }

    @Test
    @DisplayName("conversa suspensa sobrevive a restart, com form e contexto")
    void survivesRestart() {
        store.save(waiting("conv-1", "tok-1"));

        JdbcSuspendedConversationStore fresh = new JdbcSuspendedConversationStore(dataSource);
        SuspendedConversation loaded = fresh.findByToken("tok-1").orElseThrow();

        assertThat(loaded.getConversationId()).isEqualTo("conv-1");
        assertThat(loaded.getWorkflowExecutionId()).isEqualTo("exec-1");
        assertThat(loaded.getStatus()).isEqualTo(ConversationStatus.WAITING);
        assertThat(loaded.getPriority()).isEqualTo(5);
        assertThat(loaded.getContext()).containsEntry("locale", "pt-BR");
        // form round-trip
        assertThat(loaded.getForm()).isNotNull();
        assertThat(loaded.getForm().getId()).isEqualTo("user-registration");
        assertThat(loaded.getForm().getFields())
                .extracting(FormData.FormField::getName)
                .contains("name", "email", "password", "terms");
        // também acessível por id
        assertThat(fresh.findById("conv-1")).isPresent();
    }

    @Test
    @DisplayName("resume atualiza no lugar (status RESUMED, sem duplicar)")
    void resumeUpdatesInPlace() {
        SuspendedConversation waiting = waiting("conv-1", "tok-1");
        store.save(waiting);

        store.save(waiting.resume(Map.of("email", "ada@x.com")));

        assertThat(store.findAll()).hasSize(1);
        SuspendedConversation loaded = store.findByToken("tok-1").orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(ConversationStatus.RESUMED);
        assertThat(loaded.getContext()).containsKey("formData");
    }

    @Test
    @DisplayName("deleteById remove por id e por token")
    void deleteRemoves() {
        store.save(waiting("conv-1", "tok-1"));

        assertThat(store.deleteById("conv-1")).isTrue();
        assertThat(store.deleteById("conv-1")).isFalse();
        assertThat(store.findByToken("tok-1")).isEmpty();
        assertThat(store.findById("conv-1")).isEmpty();
    }

    @Test
    @DisplayName("findAll lista todas as suspensas")
    void findAllLists() {
        store.save(waiting("conv-1", "tok-1"));
        store.save(waiting("conv-2", "tok-2"));

        assertThat(store.findAll()).hasSize(2);
    }
}
