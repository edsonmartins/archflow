package br.com.archflow.template;

import java.util.Map;
import java.util.Set;

/**
 * Metadata for a workflow template providing additional information
 * beyond the core WorkflowTemplate interface.
 */
public record TemplateMetadata(
        String id,
        String displayName,
        String description,
        String category,
        String version,
        String author,
        String icon,
        String complexity,
        Set<String> tags,
        Map<String, WorkflowTemplate.ParameterDefinition> parameters,
        int stepCount
) {
    public static TemplateMetadata from(WorkflowTemplate template) {
        var def = template.getDefinition();
        int nodeCount = 0;
        if (def != null && def.getStructure() != null && def.getStructure().getNodes() != null) {
            nodeCount = def.getStructure().getNodes().size();
        }
        return new TemplateMetadata(
                template.getId(),
                template.getDisplayName(),
                template.getDescription(),
                template.getCategory(),
                "1.0.0",
                "archflow",
                null,
                null,
                template.getTags(),
                template.getParameters(),
                nodeCount
        );
    }
}
