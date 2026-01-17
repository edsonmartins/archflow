package br.com.archflow.conversation.state;

import br.com.archflow.conversation.form.FormData;

import java.time.Instant;
import java.util.Map;

/**
 * Represents a suspended conversation awaiting user input.
 *
 * <p>When an AI workflow needs human interaction, it can suspend the conversation
 * and present a form to the user. The conversation state is preserved until
 * the user submits the form or the timeout expires.</p>
 *
 * <p>Lifecycle:
 * <pre>
 * 1. AI: Needs user input → suspend conversation → generates resume token
 * 2. System: Stores SuspendedConversation with form data
 * 3. User: Fills out form → submits with resume token
 * 4. System: Validates token → retrieves conversation → resume with form data
 * 5. AI: Processes form data → continues workflow
 * </pre>
 *
 * @see ConversationManager
 */
public class SuspendedConversation {

    private final String conversationId;
    private final String resumeToken;
    private final String workflowId;
    private final String workflowExecutionId;
    private final FormData form;
    private final Instant createdAt;
    private final Instant expiresAt;
    private final ConversationStatus status;
    private final Map<String, Object> context;
    private final int priority;

    private SuspendedConversation(Builder builder) {
        this.conversationId = builder.conversationId;
        this.resumeToken = builder.resumeToken;
        this.workflowId = builder.workflowId;
        this.workflowExecutionId = builder.workflowExecutionId;
        this.form = builder.form;
        this.createdAt = builder.createdAt != null ? builder.createdAt : Instant.now();
        this.expiresAt = builder.expiresAt;
        this.status = builder.status != null ? builder.status : ConversationStatus.WAITING;
        this.context = builder.context != null ? Map.copyOf(builder.context) : Map.of();
        this.priority = builder.priority;
    }

    /**
     * Creates a new builder for constructing suspended conversations.
     */
    public static Builder builder() {
        return new Builder();
    }

    public String getConversationId() {
        return conversationId;
    }

    public String getResumeToken() {
        return resumeToken;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public String getWorkflowExecutionId() {
        return workflowExecutionId;
    }

    public FormData getForm() {
        return form;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public ConversationStatus getStatus() {
        return status;
    }

    public Map<String, Object> getContext() {
        return context;
    }

    public int getPriority() {
        return priority;
    }

    /**
     * Checks if this suspended conversation has expired.
     */
    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    /**
     * Checks if this conversation is still active (not completed/cancelled).
     */
    public boolean isActive() {
        return status == ConversationStatus.WAITING && !isExpired();
    }

    /**
     * Creates a resumed copy of this conversation with submitted form data.
     */
    public SuspendedConversation resume(Map<String, Object> formData) {
        return builder()
                .conversationId(this.conversationId)
                .resumeToken(this.resumeToken)
                .workflowId(this.workflowId)
                .workflowExecutionId(this.workflowExecutionId)
                .form(this.form)
                .createdAt(this.createdAt)
                .expiresAt(this.expiresAt)
                .status(ConversationStatus.RESUMED)
                .context(Map.of("originalContext", this.context, "formData", formData))
                .build();
    }

    /**
     * Creates a cancelled copy of this conversation.
     */
    public SuspendedConversation cancel() {
        return builder()
                .conversationId(this.conversationId)
                .resumeToken(this.resumeToken)
                .workflowId(this.workflowId)
                .workflowExecutionId(this.workflowExecutionId)
                .form(this.form)
                .createdAt(this.createdAt)
                .expiresAt(this.expiresAt)
                .status(ConversationStatus.CANCELLED)
                .context(this.context)
                .build();
    }

    /**
     * Creates a timed-out copy of this conversation.
     */
    public SuspendedConversation timeout() {
        return builder()
                .conversationId(this.conversationId)
                .resumeToken(this.resumeToken)
                .workflowId(this.workflowId)
                .workflowExecutionId(this.workflowExecutionId)
                .form(this.form)
                .createdAt(this.createdAt)
                .expiresAt(this.expiresAt)
                .status(ConversationStatus.TIMED_OUT)
                .context(this.context)
                .build();
    }

    /**
     * Conversation status.
     */
    public enum ConversationStatus {
        /**
         * Waiting for user input.
         */
        WAITING,
        /**
         * User has submitted the form, conversation resumed.
         */
        RESUMED,
        /**
         * User cancelled the conversation.
         */
        CANCELLED,
        /**
         * Conversation timed out waiting for input.
         */
        TIMED_OUT,
        /**
         * Conversation completed successfully.
         */
        COMPLETED
    }

    /**
     * Builder for constructing SuspendedConversation instances.
     */
    public static class Builder {
        private String conversationId;
        private String resumeToken;
        private String workflowId;
        private String workflowExecutionId;
        private FormData form;
        private Instant createdAt;
        private Instant expiresAt;
        private ConversationStatus status;
        private Map<String, Object> context;
        private int priority;

        public Builder conversationId(String conversationId) {
            this.conversationId = conversationId;
            return this;
        }

        public Builder resumeToken(String resumeToken) {
            this.resumeToken = resumeToken;
            return this;
        }

        public Builder workflowId(String workflowId) {
            this.workflowId = workflowId;
            return this;
        }

        public Builder workflowExecutionId(String workflowExecutionId) {
            this.workflowExecutionId = workflowExecutionId;
            return this;
        }

        public Builder form(FormData form) {
            this.form = form;
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder expiresAt(Instant expiresAt) {
            this.expiresAt = expiresAt;
            return this;
        }

        public Builder status(ConversationStatus status) {
            this.status = status;
            return this;
        }

        public Builder context(Map<String, Object> context) {
            this.context = context;
            return this;
        }

        public Builder priority(int priority) {
            this.priority = priority;
            return this;
        }

        public SuspendedConversation build() {
            if (conversationId == null || resumeToken == null) {
                throw new IllegalStateException("conversationId and resumeToken are required");
            }
            return new SuspendedConversation(this);
        }
    }
}
