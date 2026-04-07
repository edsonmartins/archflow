package br.com.archflow.conversation.summary;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@DisplayName("DefaultConversationSummarizer")
class DefaultConversationSummarizerTest {

    @Test @DisplayName("should not summarize when under threshold")
    void shouldNotSummarizeUnderThreshold() {
        var s = new DefaultConversationSummarizer();
        var r = s.summarize("c1", List.of(ConversationMessage.user("Hi"), ConversationMessage.assistant("Hello")), 20, 5);
        assertThat(r.summarized()).isFalse();
        assertThat(r.recentMessages()).hasSize(2);
    }

    @Test @DisplayName("should summarize with LLM function")
    void shouldSummarizeWithLlm() {
        var s = new DefaultConversationSummarizer(text -> "Summary of conversation");
        var r = s.summarize("c1", createMessages(25), 20, 5);
        assertThat(r.summarized()).isTrue();
        assertThat(r.summary()).isEqualTo("Summary of conversation");
        assertThat(r.recentMessages()).hasSize(5);
        assertThat(r.version()).isEqualTo(1);
    }

    @Test @DisplayName("should use extractive fallback when no LLM")
    void shouldUseExtractiveFallback() {
        var r = new DefaultConversationSummarizer().summarize("c1", createMessages(25), 20, 5);
        assertThat(r.summarized()).isTrue();
        assertThat(r.summary()).contains("Customer asked about");
    }

    @Test @DisplayName("should fallback on LLM failure")
    void shouldFallbackOnLlmFailure() {
        var s = new DefaultConversationSummarizer(t -> { throw new RuntimeException("fail"); });
        var r = s.summarize("c1", createMessages(25), 20, 5);
        assertThat(r.summarized()).isTrue();
        assertThat(r.summary()).contains("Customer asked about");
    }

    @Test @DisplayName("should increment version")
    void shouldIncrementVersion() {
        var s = new DefaultConversationSummarizer(t -> "S");
        var msgs = createMessages(25);
        assertThat(s.summarize("c1", msgs, 20, 5).version()).isEqualTo(1);
        assertThat(s.summarize("c1", msgs, 20, 5).version()).isEqualTo(2);
    }

    @Test @DisplayName("should produce context messages with summary")
    void shouldProduceContextMessages() {
        var s = new DefaultConversationSummarizer(t -> "Prior discussion");
        var r = s.summarize("c1", createMessages(25), 20, 5);
        var ctx = r.toContextMessages();
        assertThat(ctx.get(0).role()).isEqualTo("system");
        assertThat(ctx.get(0).content()).contains("Prior discussion");
        assertThat(ctx).hasSize(6);
    }

    @Test @DisplayName("should preserve recent messages")
    void shouldPreserveRecentMessages() {
        var s = new DefaultConversationSummarizer(t -> "S");
        var msgs = createMessages(10);
        var r = s.summarize("c1", msgs, 5, 3);
        assertThat(r.recentMessages()).hasSize(3);
        assertThat(r.recentMessages().get(2).content()).isEqualTo(msgs.get(9).content());
    }

    @Test @DisplayName("should track versions independently per conversation")
    void shouldTrackVersionsIndependently() {
        var s = new DefaultConversationSummarizer(t -> "S");
        var msgs = createMessages(25);
        s.summarize("c1", msgs, 20, 5);
        s.summarize("c1", msgs, 20, 5);
        assertThat(s.summarize("c2", msgs, 20, 5).version()).isEqualTo(1);
    }

    private List<ConversationMessage> createMessages(int count) {
        var msgs = new ArrayList<ConversationMessage>();
        for (int i = 0; i < count; i++)
            msgs.add(i % 2 == 0 ? ConversationMessage.user("User message " + i) : ConversationMessage.assistant("Assistant response " + i));
        return msgs;
    }
}
