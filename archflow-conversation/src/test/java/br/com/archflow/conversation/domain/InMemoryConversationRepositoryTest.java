package br.com.archflow.conversation.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryConversationRepositoryTest {

    private InMemoryConversationRepository repo;

    @BeforeEach
    void setUp() {
        repo = new InMemoryConversationRepository();
    }

    @Nested
    @DisplayName("Conversation CRUD")
    class ConversationCrud {

        @Test
        void savesAndFindsConversation() {
            Conversation c = repo.save(Conversation.start("t1", "+5511999", "WHATSAPP"));

            assertThat(c.id()).isNotNull();
            assertThat(c.status()).isEqualTo(Conversation.ConversationStatus.ACTIVE);
            assertThat(repo.findById("t1", c.id())).isPresent();
        }

        @Test
        void findByIdIsTenantScoped() {
            Conversation c = repo.save(Conversation.start("tenant-A", "user-1", "API"));

            assertThat(repo.findById("tenant-A", c.id())).isPresent();
            assertThat(repo.findById("tenant-B", c.id())).isEmpty();
        }

        @Test
        void listsByTenantNewestFirst() throws InterruptedException {
            Conversation c1 = repo.save(Conversation.start("t1", "u1", "API"));
            Thread.sleep(5);
            Conversation c2 = repo.save(Conversation.start("t1", "u2", "API"));
            Thread.sleep(5);
            Conversation c3 = repo.save(Conversation.start("t1", "u3", "API"));

            List<Conversation> list = repo.listByTenant("t1");

            assertThat(list).extracting(Conversation::id)
                    .containsExactly(c3.id(), c2.id(), c1.id());
        }

        @Test
        void listByUserFiltersCorrectly() {
            Conversation c1 = repo.save(Conversation.start("t1", "user-A", "API"));
            repo.save(Conversation.start("t1", "user-B", "API"));
            Conversation c3 = repo.save(Conversation.start("t1", "user-A", "API"));

            List<Conversation> list = repo.listByUser("t1", "user-A");

            assertThat(list).extracting(Conversation::id).containsExactlyInAnyOrder(c1.id(), c3.id());
        }

        @Test
        void deleteRemovesConversationAndMessages() {
            Conversation c = repo.save(Conversation.start("t1", "u1", "API"));
            repo.addMessage(Message.userText(c.id(), "t1", "hi"));
            repo.addMessage(Message.agentText(c.id(), "t1", "hello"));

            boolean removed = repo.delete("t1", c.id());

            assertThat(removed).isTrue();
            assertThat(repo.findById("t1", c.id())).isEmpty();
            assertThat(repo.countMessages("t1", c.id())).isZero();
        }

        @Test
        void withStatusCreatesUpdatedCopy() {
            Conversation c = Conversation.start("t1", "u1", "API");
            Conversation escalated = c.withStatus(Conversation.ConversationStatus.ESCALATED);

            assertThat(escalated.status()).isEqualTo(Conversation.ConversationStatus.ESCALATED);
            assertThat(escalated.id()).isEqualTo(c.id());
            assertThat(c.status()).isEqualTo(Conversation.ConversationStatus.ACTIVE);
        }

        @Test
        void withPersonaSwitchesPersona() {
            Conversation c = Conversation.start("t1", "u1", "API");
            Conversation withPersona = c.withPersona("order_tracking");

            assertThat(withPersona.persona()).isEqualTo("order_tracking");
            assertThat(c.persona()).isNull();
        }

        @Test
        void withMetadataAccumulates() {
            Conversation c = Conversation.start("t1", "u1", "API")
                    .withMetadata("source", "whatsapp")
                    .withMetadata("priority", "high");

            assertThat(c.metadata()).containsEntry("source", "whatsapp").containsEntry("priority", "high");
        }
    }

    @Nested
    @DisplayName("Messages")
    class Messages {

        @Test
        void appendsAndListsMessagesInOrder() {
            Conversation c = repo.save(Conversation.start("t1", "u1", "API"));
            repo.addMessage(Message.userText(c.id(), "t1", "msg 1"));
            repo.addMessage(Message.agentText(c.id(), "t1", "msg 2"));
            repo.addMessage(Message.userText(c.id(), "t1", "msg 3"));

            List<Message> msgs = repo.listMessages("t1", c.id());

            assertThat(msgs).extracting(Message::content).containsExactly("msg 1", "msg 2", "msg 3");
            assertThat(repo.countMessages("t1", c.id())).isEqualTo(3);
        }

        @Test
        void listRecentReturnsLastN() {
            Conversation c = repo.save(Conversation.start("t1", "u1", "API"));
            for (int i = 1; i <= 10; i++) {
                repo.addMessage(Message.userText(c.id(), "t1", "m" + i));
            }

            List<Message> last3 = repo.listRecentMessages("t1", c.id(), 3);

            assertThat(last3).extracting(Message::content).containsExactly("m8", "m9", "m10");
        }

        @Test
        void listRecentHandlesLimitLargerThanHistory() {
            Conversation c = repo.save(Conversation.start("t1", "u1", "API"));
            repo.addMessage(Message.userText(c.id(), "t1", "only"));

            assertThat(repo.listRecentMessages("t1", c.id(), 50)).hasSize(1);
        }

        @Test
        void messagesIsolatedByTenant() {
            Conversation shared = repo.save(Conversation.start("tenant-A", "u1", "API"));
            repo.addMessage(Message.userText(shared.id(), "tenant-A", "tenant A message"));

            assertThat(repo.listMessages("tenant-A", shared.id())).hasSize(1);
            assertThat(repo.listMessages("tenant-B", shared.id())).isEmpty();
            assertThat(repo.countMessages("tenant-B", shared.id())).isZero();
        }

        @Test
        void messageFactoriesAssignCorrectSenderType() {
            assertThat(Message.userText("c", "t", "x").senderType()).isEqualTo(Message.SenderType.USER);
            assertThat(Message.agentText("c", "t", "x").senderType()).isEqualTo(Message.SenderType.AGENT);
            assertThat(Message.systemText("c", "t", "x").senderType()).isEqualTo(Message.SenderType.SYSTEM);
        }
    }

    @Test
    void deleteUnknownConversationReturnsFalse() {
        assertThat(repo.delete("t1", "nope")).isFalse();
    }
}
