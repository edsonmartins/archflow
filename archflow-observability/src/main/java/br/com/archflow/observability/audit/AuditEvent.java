package br.com.archflow.observability.audit;

import java.time.Instant;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

/**
 * Represents an audit event for tracking system operations.
 *
 * <p>Audit events capture:
 * <ul>
 *   <li><b>Who:</b> The user/actor performing the action</li>
 *   <li><b>What:</b> The action performed (see {@link AuditAction})</li>
 *   <li><b>When:</b> The timestamp of the event</li>
 *   <li><b>Where:</b> The resource type and identifier</li>
 *   <li><b>Context:</b> Additional metadata (IP address, user agent, etc.)</li>
 *   <li><b>Result:</b> Success/failure status</li>
 * </ul>
 *
 * <p>Usage:</p>
 * <pre>
 * AuditEvent event = AuditEvent.builder()
 *     .action(AuditAction.WORKFLOW_EXECUTE)
 *     .userId("user-123")
 *     .resourceType("workflow")
 *     .resourceId("wf-456")
 *     .success(true)
 *     .build();
 *
 * auditLogger.log(event);
 * </pre>
 */
public class AuditEvent {

    private final String id;
    private final Instant timestamp;
    private final AuditAction action;
    private final String userId;
    private final String username;
    private final String resourceType;
    private final String resourceId;
    private final boolean success;
    private final String errorMessage;
    private final Map<String, String> context;
    private final String ipAddress;
    private final String userAgent;
    private final String sessionId;
    private final String traceId;

    private AuditEvent(Builder builder) {
        this.id = builder.id;
        this.timestamp = builder.timestamp != null ? builder.timestamp : Instant.now();
        this.action = builder.action;
        this.userId = builder.userId;
        this.username = builder.username;
        this.resourceType = builder.resourceType;
        this.resourceId = builder.resourceId;
        this.success = builder.success;
        this.errorMessage = builder.errorMessage;
        this.context = builder.context != null ? builder.context : new HashMap<>();
        this.ipAddress = builder.ipAddress;
        this.userAgent = builder.userAgent;
        this.sessionId = builder.sessionId;
        this.traceId = builder.traceId;
    }

    /**
     * Gets the unique event identifier.
     */
    public String getId() {
        return id;
    }

    /**
     * Gets the timestamp when the event occurred.
     */
    public Instant getTimestamp() {
        return timestamp;
    }

    /**
     * Gets the action performed.
     */
    public AuditAction getAction() {
        return action;
    }

    /**
     * Gets the user ID who performed the action.
     */
    public String getUserId() {
        return userId;
    }

    /**
     * Gets the username who performed the action.
     */
    public String getUsername() {
        return username;
    }

    /**
     * Gets the resource type affected.
     */
    public String getResourceType() {
        return resourceType;
    }

    /**
     * Gets the resource identifier affected.
     */
    public String getResourceId() {
        return resourceId;
    }

    /**
     * Checks if the operation was successful.
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * Gets the error message if the operation failed.
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /**
     * Gets the additional context metadata.
     */
    public Map<String, String> getContext() {
        return context;
    }

    /**
     * Gets the IP address of the actor.
     */
    public String getIpAddress() {
        return ipAddress;
    }

    /**
     * Gets the user agent of the actor.
     */
    public String getUserAgent() {
        return userAgent;
    }

    /**
     * Gets the session identifier.
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * Gets the trace ID for distributed tracing correlation.
     */
    public String getTraceId() {
        return traceId;
    }

    /**
     * Creates a new builder for AuditEvent.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a new builder with values copied from this event.
     */
    public Builder toBuilder() {
        return new Builder()
                .id(id)
                .timestamp(timestamp)
                .action(action)
                .userId(userId)
                .username(username)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .success(success)
                .errorMessage(errorMessage)
                .context(new HashMap<>(context))
                .ipAddress(ipAddress)
                .userAgent(userAgent)
                .sessionId(sessionId)
                .traceId(traceId);
    }

    @Override
    public String toString() {
        return "AuditEvent{" +
                "id='" + id + '\'' +
                ", timestamp=" + timestamp +
                ", action=" + action +
                ", userId='" + userId + '\'' +
                ", username='" + username + '\'' +
                ", resourceType='" + resourceType + '\'' +
                ", resourceId='" + resourceId + '\'' +
                ", success=" + success +
                '}';
    }

    /**
     * Builder for creating AuditEvent instances.
     */
    public static class Builder {
        private String id = UUID.randomUUID().toString();
        private Instant timestamp;
        private AuditAction action;
        private String userId;
        private String username;
        private String resourceType;
        private String resourceId;
        private boolean success = true;
        private String errorMessage;
        private Map<String, String> context;
        private String ipAddress;
        private String userAgent;
        private String sessionId;
        private String traceId;

        private Builder() {
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder action(AuditAction action) {
            this.action = action;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder username(String username) {
            this.username = username;
            return this;
        }

        public Builder resourceType(String resourceType) {
            this.resourceType = resourceType;
            return this;
        }

        public Builder resourceId(String resourceId) {
            this.resourceId = resourceId;
            return this;
        }

        public Builder success(boolean success) {
            this.success = success;
            return this;
        }

        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            this.success = errorMessage == null || errorMessage.isEmpty();
            return this;
        }

        public Builder context(Map<String, String> context) {
            this.context = context;
            return this;
        }

        public Builder addContext(String key, String value) {
            if (this.context == null) {
                this.context = new HashMap<>();
            }
            this.context.put(key, value);
            return this;
        }

        public Builder ipAddress(String ipAddress) {
            this.ipAddress = ipAddress;
            return this;
        }

        public Builder userAgent(String userAgent) {
            this.userAgent = userAgent;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        /**
         * Builds the AuditEvent.
         *
         * @return The constructed AuditEvent
         * @throws IllegalStateException if action is not set
         */
        public AuditEvent build() {
            if (action == null) {
                throw new IllegalStateException("action is required");
            }
            return new AuditEvent(this);
        }
    }
}
