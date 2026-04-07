package br.com.archflow.conversation.summary;

import java.util.ArrayList;
import java.util.List;

public record SummarizationResult(boolean summarized, String summary, List<ConversationMessage> recentMessages,
                                   int originalCount, int summarizedCount, int version) {
    public static SummarizationResult unchanged(List<ConversationMessage> messages) {
        return new SummarizationResult(false, null, messages, messages.size(), 0, 0);
    }
    public static SummarizationResult summarized(String summary, List<ConversationMessage> recent,
                                                   int originalCount, int summarizedCount, int version) {
        return new SummarizationResult(true, summary, recent, originalCount, summarizedCount, version);
    }
    public List<ConversationMessage> toContextMessages() {
        if (!summarized || summary == null) return recentMessages;
        var result = new ArrayList<ConversationMessage>();
        result.add(ConversationMessage.system("[Conversation summary: " + summary + "]"));
        result.addAll(recentMessages);
        return List.copyOf(result);
    }
}
