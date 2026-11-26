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
 * @param updatedAt O {@code updatedAt} do documento de onde o YAML saiu. É o que a aba Código devolve no
 *                {@code If-Match} do Salvar YAML (RCHFLW-174), como o Salvar do canvas faz com o JSON: o
 *                {@code version} acima é o semver do autor e não muda a cada gravação, então não serve para
 *                detectar conflito. Ignorado no corpo de um {@code PUT}.
 */
public record WorkflowYamlDto(String id, String yaml, String version, String updatedAt) {

    public WorkflowYamlDto(String id, String yaml, String version) {
        this(id, yaml, version, null);
    }

    public static WorkflowYamlDto of(String id, String yaml) {
        return new WorkflowYamlDto(id, yaml, null);
    }
}
