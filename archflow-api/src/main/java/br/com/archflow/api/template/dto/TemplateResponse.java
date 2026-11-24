package br.com.archflow.api.template.dto;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Response DTO for workflow template information.
 *
 * @param steps                os passos do documento canônico (arestas em {@code steps[].connections}),
 *                             com os parâmetros default — é o que o clone grava. Antes só ia
 *                             {@code stepCount} e todo clone de template do backend nascia vazio
 *                             (RCHFLW-18).
 * @param unsupportedNodeTypes tipos de nó do template que o motor deste deployment não executa (o
 *                             passo falharia com "component not found"); vazio quando tudo roda e
 *                             {@code null} quando não foi verificado. Existe para a galeria não
 *                             fingir que um template sem executor roda.
 */
public record TemplateResponse(
        String id,
        String displayName,
        String description,
        String category,
        String version,
        String author,
        String icon,
        String complexity,
        Set<String> tags,
        List<ParameterInfo> parameters,
        int stepCount,
        List<Map<String, Object>> steps,
        List<String> unsupportedNodeTypes
) {
    /** Construtor sem o grafo, para quem só descreve o template. */
    public TemplateResponse(String id, String displayName, String description, String category,
                            String version, String author, String icon, String complexity,
                            Set<String> tags, List<ParameterInfo> parameters, int stepCount) {
        this(id, displayName, description, category, version, author, icon, complexity, tags,
                parameters, stepCount, List.of(), null);
    }

    /** Cópia com os tipos sem executor, calculados por quem conhece o catálogo do runtime. */
    public TemplateResponse withUnsupportedNodeTypes(List<String> types) {
        return new TemplateResponse(id, displayName, description, category, version, author, icon,
                complexity, tags, parameters, stepCount, steps, types);
    }

    public record ParameterInfo(
            String name,
            String description,
            String type,
            Object defaultValue,
            boolean required,
            String[] options
    ) {}
}
