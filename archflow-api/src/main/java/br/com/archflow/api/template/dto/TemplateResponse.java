package br.com.archflow.api.template.dto;

import java.util.List;
import java.util.Set;

/**
 * Response DTO for workflow template information.
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
        int stepCount
) {
    public record ParameterInfo(
            String name,
            String description,
            String type,
            Object defaultValue,
            boolean required,
            String[] options
    ) {}
}
