package br.com.archflow.api.template.dto;

import java.util.Map;

/**
 * Request DTO for installing a workflow template as a new workflow.
 */
public record InstallTemplateRequest(
        String name,
        Map<String, Object> parameters
) {}
