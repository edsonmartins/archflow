package br.com.archflow.observability.audit;

/**
 * Enumeration of audit actions for tracking system events.
 *
 * <p>Each action represents a type of operation that can be audited:
 * <ul>
 *   <li><b>CREATE:</b> Resource creation operations</li>
 *   <li><b>READ:</b> Resource access/query operations</li>
 *   <li><b>UPDATE:</b> Resource modification operations</li>
 *   <li><b>DELETE:</b> Resource deletion operations</li>
 *   <li><b>EXECUTE:</b> Execution operations (workflows, agents, tools)</li>
 *   <li><b>LOGIN:</b> Authentication events</li>
 *   <li><b>LOGOUT:</b> Logout events</li>
 *   <li><b>PERMISSION_GRANTED:</b> Authorization grants</li>
 *   <li><b>PERMISSION_REVOKED:</b> Authorization revocations</li>
 *   <li><b>EXPORT:</b> Data export operations</li>
 *   <li><b>IMPORT:</b> Data import operations</li>
 *   <li><b>CONFIG_CHANGE:</b> Configuration modifications</li>
 * </ul>
 */
public enum AuditAction {

    // ========== CRUD Operations ==========
    /**
     * Resource creation operation.
     */
    CREATE("create"),

    /**
     * Resource read/access operation.
     */
    READ("read"),

    /**
     * Resource update operation.
     */
    UPDATE("update"),

    /**
     * Resource deletion operation.
     */
    DELETE("delete"),

    // ========== Execution Operations ==========
    /**
     * Workflow execution started.
     */
    WORKFLOW_EXECUTE("workflow.execute"),

    /**
     * Workflow execution completed.
     */
    WORKFLOW_COMPLETED("workflow.completed"),

    /**
     * Workflow execution failed.
     */
    WORKFLOW_FAILED("workflow.failed"),

    /**
     * Agent execution started.
     */
    AGENT_EXECUTE("agent.execute"),

    /**
     * Agent execution completed.
     */
    AGENT_COMPLETED("agent.completed"),

    /**
     * Tool invocation started.
     */
    TOOL_INVOKE("tool.invoke"),

    /**
     * Tool invocation completed.
     */
    TOOL_COMPLETED("tool.completed"),

    /**
     * LLM request started.
     */
    LLM_REQUEST("llm.request"),

    /**
     * LLM request completed.
     */
    LLM_COMPLETED("llm.completed"),

    // ========== Authentication Operations ==========
    /**
     * User login successful.
     */
    LOGIN_SUCCESS("login.success"),

    /**
     * User login failed.
     */
    LOGIN_FAILED("login.failed"),

    /**
     * User logout.
     */
    LOGOUT("logout"),

    /**
     * Token refreshed.
     */
    TOKEN_REFRESH("token.refresh"),

    // ========== Authorization Operations ==========
    /**
     * Permission granted to user/role.
     */
    PERMISSION_GRANTED("permission.granted"),

    /**
     * Permission revoked from user/role.
     */
    PERMISSION_REVOKED("permission.revoked"),

    /**
     * Role assigned to user.
     */
    ROLE_ASSIGNED("role.assigned"),

    /**
     * Role removed from user.
     */
    ROLE_REMOVED("role.removed"),

    // ========== Data Operations ==========
    /**
     * Data export operation.
     */
    DATA_EXPORT("data.export"),

    /**
     * Data import operation.
     */
    DATA_IMPORT("data.import"),

    /**
     * Bulk data operation.
     */
    DATA_BULK_OPERATION("data.bulk"),

    // ========== Configuration Operations ==========
    /**
     * Configuration changed.
     */
    CONFIG_CHANGE("config.change"),

    /**
     * System setting modified.
     */
    SETTING_CHANGE("setting.change"),

    // ========== Security Operations ==========
    /**
     * Security event detected.
     */
    SECURITY_EVENT("security.event"),

    /**
     * Suspicious activity detected.
     */
    SUSPICIOUS_ACTIVITY("suspicious.activity"),

    /**
     * Access denied.
     */
    ACCESS_DENIED("access.denied"),

    // ========== Admin Operations ==========
    /**
     * Admin operation performed.
     */
    ADMIN_ACTION("admin.action"),

    /**
     * System maintenance started.
     */
    MAINTENANCE_START("maintenance.start"),

    /**
     * System maintenance ended.
     */
    MAINTENANCE_END("maintenance.end");

    private final String code;

    AuditAction(String code) {
        this.code = code;
    }

    /**
     * Gets the action code.
     *
     * @return The action code
     */
    public String getCode() {
        return code;
    }

    /**
     * Finds an AuditAction by its code.
     *
     * @param code The action code
     * @return The matching AuditAction, or null if not found
     */
    public static AuditAction fromCode(String code) {
        for (AuditAction action : values()) {
            if (action.code.equals(code)) {
                return action;
            }
        }
        return null;
    }

    /**
     * Checks if this action is a CRUD operation.
     */
    public boolean isCrudOperation() {
        return this == CREATE || this == READ || this == UPDATE || this == DELETE;
    }

    /**
     * Checks if this action is an execution operation.
     */
    public boolean isExecutionOperation() {
        return name().contains("WORKFLOW") || name().contains("AGENT")
                || name().contains("TOOL") || name().contains("LLM");
    }

    /**
     * Checks if this action is an authentication/authorization operation.
     */
    public boolean isAuthOperation() {
        return this == LOGIN_SUCCESS || this == LOGIN_FAILED || this == LOGOUT
                || this == TOKEN_REFRESH || this == PERMISSION_GRANTED
                || this == PERMISSION_REVOKED || this == ROLE_ASSIGNED
                || this == ROLE_REMOVED;
    }

    /**
     * Checks if this action is a security-related operation.
     */
    public boolean isSecurityOperation() {
        return this == SECURITY_EVENT || this == SUSPICIOUS_ACTIVITY
                || this == ACCESS_DENIED;
    }
}
