package br.com.archflow.observability.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * Central audit logger for Archflow.
 *
 * <p>This class provides audit logging capabilities for tracking system operations:
 * <ul>
 *   <li><b>Authentication:</b> Login, logout, token refresh</li>
 *   <li><b>Authorization:</b> Permission grants, role assignments</li>
 *   <li><b>CRUD:</b> Create, read, update, delete operations on resources</li>
 *   <li><b>Execution:</b> Workflow, agent, tool, LLM invocations</li>
 *   <li><b>Security:</b> Access denied, suspicious activity</li>
 * </ul>
 *
 * <p>Usage:</p>
 * <pre>
 * // Log a workflow execution
 * AuditLogger.log(AuditAction.WORKFLOW_EXECUTE)
 *     .userId("user-123")
 *     .resourceType("workflow")
 *     .resourceId("wf-456")
 *     .success(true)
 *     .submit();
 *
 * // Log with builder
 * AuditEvent event = AuditEvent.builder()
 *     .action(AuditAction.LOGIN_SUCCESS)
 *     .userId("user-123")
 *     .ipAddress("192.168.1.1")
 *     .build();
 * AuditLogger.log(event);
 * </pre>
 */
public class AuditLogger {

    private static final Logger log = LoggerFactory.getLogger(AuditLogger.class);

    private static volatile AuditLogger instance;
    private static final Object LOCK = new Object();

    private final AuditRepository repository;
    private final boolean asyncEnabled;

    private AuditLogger(AuditRepository repository, boolean asyncEnabled) {
        this.repository = repository != null ? repository : new InMemoryAuditRepository();
        this.asyncEnabled = asyncEnabled;
    }

    /**
     * Initializes or returns the singleton AuditLogger instance.
     *
     * @param repository The audit repository to use
     * @return The AuditLogger instance
     */
    public static AuditLogger initialize(AuditRepository repository) {
        return initialize(repository, true);
    }

    /**
     * Initializes or returns the singleton AuditLogger instance.
     *
     * @param repository The audit repository to use
     * @param asyncEnabled Whether to log asynchronously
     * @return The AuditLogger instance
     */
    public static AuditLogger initialize(AuditRepository repository, boolean asyncEnabled) {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    instance = new AuditLogger(repository, asyncEnabled);
                    log.info("AuditLogger initialized with repository: {}",
                            repository.getClass().getSimpleName());
                }
            }
        }
        return instance;
    }

    /**
     * Gets the current AuditLogger instance.
     *
     * @return The AuditLogger instance
     * @throws IllegalStateException if not initialized
     */
    public static AuditLogger get() {
        if (instance == null) {
            throw new IllegalStateException("AuditLogger not initialized. Call initialize() first.");
        }
        return instance;
    }

    /**
     * Checks if AuditLogger is initialized.
     */
    public static boolean isInitialized() {
        return instance != null;
    }

    // ========== Logging Methods ==========

    /**
     * Logs an audit event.
     *
     * @param event The event to log
     */
    public void log(AuditEvent event) {
        if (asyncEnabled) {
            logAsync(event);
        } else {
            logSync(event);
        }
    }

    /**
     * Logs a successful action.
     *
     * @param action The action performed
     * @return A builder for setting additional fields
     */
    public AuditBuilder log(AuditAction action) {
        return new AuditBuilder(action, true, this);
    }

    /**
     * Logs a failed action.
     *
     * @param action The action that failed
     * @param errorMessage The error message
     * @return A builder for setting additional fields
     */
    public AuditBuilder logFailure(AuditAction action, String errorMessage) {
        return new AuditBuilder(action, false, this).errorMessage(errorMessage);
    }

    /**
     * Logs a login event.
     *
     * @param userId The user ID
     * @param username The username
     * @param success Whether login was successful
     * @param ipAddress The IP address
     */
    public void logLogin(String userId, String username, boolean success, String ipAddress) {
        AuditAction action = success ? AuditAction.LOGIN_SUCCESS : AuditAction.LOGIN_FAILED;
        log(action)
                .userId(userId)
                .username(username)
                .ipAddress(ipAddress)
                .resourceType("auth")
                .submit();
    }

    /**
     * Logs a logout event.
     *
     * @param userId The user ID
     * @param username The username
     */
    public void logLogout(String userId, String username) {
        log(AuditAction.LOGOUT)
                .userId(userId)
                .username(username)
                .resourceType("auth")
                .submit();
    }

    /**
     * Logs a permission grant.
     *
     * @param adminUserId The admin performing the action
     * @param targetUserId The target user ID
     * @param permission The permission granted
     */
    public void logPermissionGranted(String adminUserId, String targetUserId, String permission) {
        log(AuditAction.PERMISSION_GRANTED)
                .userId(adminUserId)
                .resourceType("permission")
                .resourceId(permission)
                .addContext("targetUser", targetUserId)
                .submit();
    }

    /**
     * Logs a permission revoke.
     *
     * @param adminUserId The admin performing the action
     * @param targetUserId The target user ID
     * @param permission The permission revoked
     */
    public void logPermissionRevoked(String adminUserId, String targetUserId, String permission) {
        log(AuditAction.PERMISSION_REVOKED)
                .userId(adminUserId)
                .resourceType("permission")
                .resourceId(permission)
                .addContext("targetUser", targetUserId)
                .submit();
    }

    /**
     * Logs a workflow execution start.
     *
     * @param userId The user ID
     * @param workflowId The workflow ID
     * @param executionId The execution ID
     */
    public void logWorkflowStart(String userId, String workflowId, String executionId) {
        log(AuditAction.WORKFLOW_EXECUTE)
                .userId(userId)
                .resourceType("workflow")
                .resourceId(workflowId)
                .addContext("executionId", executionId)
                .submit();
    }

    /**
     * Logs a workflow execution completion.
     *
     * @param userId The user ID
     * @param workflowId The workflow ID
     * @param executionId The execution ID
     * @param success Whether execution was successful
     * @param errorMessage Error message if failed
     */
    public void logWorkflowComplete(String userId, String workflowId, String executionId,
                                     boolean success, String errorMessage) {
        AuditAction action = success ? AuditAction.WORKFLOW_COMPLETED : AuditAction.WORKFLOW_FAILED;
        AuditBuilder builder = log(action)
                .userId(userId)
                .resourceType("workflow")
                .resourceId(workflowId)
                .success(success)
                .addContext("executionId", executionId);
        if (!success && errorMessage != null) {
            builder.errorMessage(errorMessage);
        }
        builder.submit();
    }

    /**
     * Logs an agent execution.
     *
     * @param userId The user ID
     * @param agentId The agent ID
     * @param success Whether execution was successful
     */
    public void logAgentExecution(String userId, String agentId, boolean success) {
        log(AuditAction.AGENT_EXECUTE)
                .userId(userId)
                .resourceType("agent")
                .resourceId(agentId)
                .success(success)
                .submit();
    }

    /**
     * Logs a tool invocation.
     *
     * @param userId The user ID
     * @param toolName The tool name
     * @param success Whether invocation was successful
     */
    public void logToolInvocation(String userId, String toolName, boolean success) {
        log(AuditAction.TOOL_INVOKE)
                .userId(userId)
                .resourceType("tool")
                .resourceId(toolName)
                .success(success)
                .submit();
    }

    /**
     * Logs an LLM request.
     *
     * @param userId The user ID
     * @param provider The LLM provider
     * @param model The model name
     * @param success Whether request was successful
     */
    public void logLlmRequest(String userId, String provider, String model, boolean success) {
        log(AuditAction.LLM_REQUEST)
                .userId(userId)
                .resourceType("llm")
                .addContext("provider", provider)
                .addContext("model", model)
                .success(success)
                .submit();
    }

    /**
     * Logs an access denied event.
     *
     * @param userId The user ID
     * @param resourceType The resource type
     * @param resourceId The resource ID
     */
    public void logAccessDenied(String userId, String resourceType, String resourceId) {
        log(AuditAction.ACCESS_DENIED)
                .userId(userId)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .success(false)
                .submit();
    }

    // ========== Static Convenience Methods ==========

    /**
     * Logs an audit event using the singleton instance.
     *
     * @param event The event to log
     */
    public static void logEvent(AuditEvent event) {
        if (isInitialized()) {
            get().log(event);
        } else {
            log.warn("AuditLogger not initialized, event not logged: {}", event);
        }
    }

    /**
     * Logs an action using the singleton instance.
     *
     * @param action The action to log
     * @return A builder for setting additional fields
     */
    public static AuditBuilder logAction(AuditAction action) {
        if (isInitialized()) {
            return get().log(action);
        }
        log.warn("AuditLogger not initialized, creating no-op builder");
        return new AuditBuilder(action, true, null) {
            @Override
            public void submit() {
                log.warn("AuditLogger not initialized, action not logged: {}", action);
            }
        };
    }

    // ========== Internal Methods ==========

    private void logSync(AuditEvent event) {
        try {
            repository.save(event);
            log.debug("Logged audit event: {}", event.getId());
        } catch (Exception e) {
            log.error("Failed to log audit event: {}", event.getId(), e);
        }
    }

    private void logAsync(AuditEvent event) {
        // Simple async logging using a thread
        // In production, use a proper async framework
        try {
            Thread.startVirtualThread(() -> {
                try {
                    repository.save(event);
                } catch (Exception e) {
                    log.error("Failed to log audit event asynchronously: {}", event.getId(), e);
                }
            });
        } catch (Exception e) {
            // Fallback to sync if virtual threads fail
            logSync(event);
        }
    }

    /**
     * Gets the underlying repository.
     */
    public AuditRepository getRepository() {
        return repository;
    }

    /**
     * Builder for fluent audit logging.
     */
    public static class AuditBuilder {
        private final AuditEvent.Builder builder;
        private final AuditLogger logger;

        private AuditBuilder(AuditAction action, boolean success, AuditLogger logger) {
            this.logger = logger;
            this.builder = AuditEvent.builder()
                    .action(action)
                    .success(success);
        }

        public AuditBuilder userId(String userId) {
            builder.userId(userId);
            return this;
        }

        public AuditBuilder username(String username) {
            builder.username(username);
            return this;
        }

        public AuditBuilder resourceType(String resourceType) {
            builder.resourceType(resourceType);
            return this;
        }

        public AuditBuilder resourceId(String resourceId) {
            builder.resourceId(resourceId);
            return this;
        }

        public AuditBuilder success(boolean success) {
            builder.success(success);
            return this;
        }

        public AuditBuilder errorMessage(String errorMessage) {
            builder.errorMessage(errorMessage);
            return this;
        }

        public AuditBuilder ipAddress(String ipAddress) {
            builder.ipAddress(ipAddress);
            return this;
        }

        public AuditBuilder userAgent(String userAgent) {
            builder.userAgent(userAgent);
            return this;
        }

        public AuditBuilder sessionId(String sessionId) {
            builder.sessionId(sessionId);
            return this;
        }

        public AuditBuilder traceId(String traceId) {
            builder.traceId(traceId);
            return this;
        }

        public AuditBuilder addContext(String key, String value) {
            builder.addContext(key, value);
            return this;
        }

        public AuditBuilder timestamp(Instant timestamp) {
            builder.timestamp(timestamp);
            return this;
        }

        /**
         * Submits the audit event to be logged.
         */
        public void submit() {
            AuditEvent event = builder.build();
            if (logger != null) {
                logger.log(event);
            } else {
                log.warn("AuditLogger not initialized, event not logged: {}", event);
            }
        }

        /**
         * Builds and returns the audit event without logging.
         */
        public AuditEvent build() {
            return builder.build();
        }
    }
}
