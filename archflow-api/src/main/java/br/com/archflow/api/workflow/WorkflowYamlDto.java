package br.com.archflow.api.workflow;

/**
 * Wire payload for YAML round-trip requests/responses.
 *
 * @param id      Workflow identifier. May be {@code null} on {@code PUT}
 *                requests (the path parameter wins).
 * @param yaml    YAML text body of the workflow.
 * @param version Semantic version string of the stored workflow — helps
 *                the frontend show dirty/conflicted state when multiple
 *                editors collaborate on the same workflow.
 */
public record WorkflowYamlDto(String id, String yaml, String version) {

    public static WorkflowYamlDto of(String id, String yaml) {
        return new WorkflowYamlDto(id, yaml, null);
    }
}
