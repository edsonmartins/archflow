package br.com.archflow.conversation.summary;

import java.util.List;

public interface ConversationSummarizer {
    SummarizationResult summarize(String conversationId, List<ConversationMessage> messages,
                                   int maxMessages, int recentToKeep);
}
