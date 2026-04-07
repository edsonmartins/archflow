package br.com.archflow.conversation.summary;

import java.time.Instant;
import java.util.Objects;

public record ConversationMessage(String role, String content, Instant timestamp) {
    public ConversationMessage {
        Objects.requireNonNull(role, "role is required");
        Objects.requireNonNull(content, "content is required");
        if (timestamp == null) timestamp = Instant.now();
    }
    public static ConversationMessage user(String content) { return new ConversationMessage("user", content, null); }
    public static ConversationMessage assistant(String content) { return new ConversationMessage("assistant", content, null); }
    public static ConversationMessage system(String content) { return new ConversationMessage("system", content, null); }
    public static ConversationMessage tool(String content) { return new ConversationMessage("tool", content, null); }
}
