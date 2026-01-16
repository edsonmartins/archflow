package br.com.archflow.security.permission;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation for specifying permission requirements on methods or classes.
 *
 * Usage:
 * <pre>
 * // Requires permission to read workflows
 * {@code @}RequiresPermission(resource = "workflow", action = "read")
 * public List&lt;Workflow&gt; getWorkflows() { ... }
 *
 * // Requires permission to execute workflows (any action on workflow resource)
 * {@code @}RequiresPermission(resource = "workflow", action = "*")
 * public void executeWorkflow(String id) { ... }
 *
 * // Requires admin access (all permissions)
 * {@code @}RequiresPermission(resource = "*", action = "*")
 * public void deleteUser(String userId) { ... }
 * </pre>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {

    /**
     * The resource being accessed.
     * Examples: "workflow", "agent", "execution", "apikey"
     */
    String resource();

    /**
     * The action being performed on the resource.
     * Examples: "read", "create", "update", "delete", "execute", "*"
     */
    String action();

    /**
     * Optional error message when permission is denied.
     * If not specified, a default message will be used.
     */
    String message() default "Access denied: insufficient permissions";
}
