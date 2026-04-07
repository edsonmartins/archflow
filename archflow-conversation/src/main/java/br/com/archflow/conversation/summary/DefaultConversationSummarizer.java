package br.com.archflow.conversation.summary;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

public class DefaultConversationSummarizer implements ConversationSummarizer {

    private static final Logger log = LoggerFactory.getLogger(DefaultConversationSummarizer.class);

    private final Function<String, String> summarizationFunction;
    private final Map<String, Integer> versionTracker;

    public DefaultConversationSummarizer(Function<String, String> summarizationFunction) {
        this.summarizationFunction = summarizationFunction;
        this.versionTracker = new ConcurrentHashMap<>();
    }

    public DefaultConversationSummarizer() { this(null); }

    @Override
    public SummarizationResult summarize(String conversationId, List<ConversationMessage> messages,
                                          int maxMessages, int recentToKeep) {
        if (messages.size() <= maxMessages) {
            return SummarizationResult.unchanged(messages);
        }

        int splitPoint = messages.size() - recentToKeep;
        List<ConversationMessage> toSummarize = messages.subList(0, splitPoint);
        List<ConversationMessage> recent = messages.subList(splitPoint, messages.size());

        log.info("Summarizing conversation {} ({} messages -> {} summarized + {} recent)",
                conversationId, messages.size(), toSummarize.size(), recent.size());

        String conversationText = toSummarize.stream()
                .map(m -> m.role() + ": " + m.content())
                .collect(Collectors.joining("\n"));

        String summary;
        if (summarizationFunction != null) {
            try {
                summary = summarizationFunction.apply(conversationText);
            } catch (Exception e) {
                log.error("LLM summarization failed for {}, using extractive fallback", conversationId, e);
                summary = extractiveSummary(toSummarize);
            }
        } else {
            summary = extractiveSummary(toSummarize);
        }

        int version = versionTracker.merge(conversationId, 1, Integer::sum);
        return SummarizationResult.summarized(summary, List.copyOf(recent),
                messages.size(), toSummarize.size(), version);
    }

    private String extractiveSummary(List<ConversationMessage> messages) {
        var userMsgs = messages.stream().filter(m -> "user".equals(m.role()))
                .map(ConversationMessage::content).toList();
        var assistantMsgs = messages.stream().filter(m -> "assistant".equals(m.role()))
                .map(ConversationMessage::content).toList();

        StringBuilder sb = new StringBuilder("Customer asked about: ");
        sb.append(userMsgs.stream()
                .map(s -> s.length() > 80 ? s.substring(0, 80) + "..." : s)
                .collect(Collectors.joining("; ")));
        if (!assistantMsgs.isEmpty()) {
            sb.append(". Assistant provided: ");
            sb.append(assistantMsgs.stream()
                    .map(s -> s.length() > 80 ? s.substring(0, 80) + "..." : s)
                    .limit(3).collect(Collectors.joining("; ")));
        }
        return sb.toString();
    }
}
