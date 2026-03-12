package br.com.archflow.api.template.impl;

import br.com.archflow.api.template.TemplateController;
import br.com.archflow.api.template.dto.InstallTemplateRequest;
import br.com.archflow.api.template.dto.TemplateResponse;
import br.com.archflow.template.TemplateMetadata;
import br.com.archflow.template.WorkflowTemplate;
import br.com.archflow.template.WorkflowTemplateRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * Default implementation of {@link TemplateController}.
 *
 * <p>This implementation uses the {@link WorkflowTemplateRegistry} for template operations.
 * It can be used directly or wrapped by framework-specific adapters (Spring, etc.).</p>
 */
public class TemplateControllerImpl implements TemplateController {

    private final WorkflowTemplateRegistry registry;

    public TemplateControllerImpl() {
        this.registry = WorkflowTemplateRegistry.getInstance();
    }

    public TemplateControllerImpl(WorkflowTemplateRegistry registry) {
        this.registry = registry;
    }

    @Override
    public List<TemplateResponse> listTemplates() {
        return registry.getAllTemplates().stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    public TemplateResponse getTemplate(String templateId) {
        return registry.getTemplate(templateId)
                .map(this::toResponse)
                .orElseThrow(() -> new TemplateNotFoundException(templateId));
    }

    @Override
    public Object installTemplate(String templateId, InstallTemplateRequest request) {
        WorkflowTemplate template = registry.getTemplate(templateId)
                .orElseThrow(() -> new TemplateNotFoundException(templateId));

        template.validateParameters(request.parameters());
        return template.createInstance(request.name(), request.parameters());
    }

    @Override
    public TemplateResponse previewTemplate(String templateId) {
        return getTemplate(templateId);
    }

    @Override
    public List<String> listCategories() {
        return new ArrayList<>(registry.getCategories());
    }

    @Override
    public List<TemplateResponse> searchTemplates(String query) {
        return registry.search(query).stream()
                .map(this::toResponse)
                .toList();
    }

    private TemplateResponse toResponse(WorkflowTemplate template) {
        TemplateMetadata meta = TemplateMetadata.from(template);
        List<TemplateResponse.ParameterInfo> params = template.getParameters().entrySet().stream()
                .map(e -> new TemplateResponse.ParameterInfo(
                        e.getKey(),
                        e.getValue().description(),
                        e.getValue().type().getSimpleName(),
                        e.getValue().defaultValue(),
                        e.getValue().required(),
                        e.getValue().options()
                ))
                .toList();

        return new TemplateResponse(
                meta.id(), meta.displayName(), meta.description(),
                meta.category(), meta.version(), meta.author(),
                meta.icon(), meta.complexity(), meta.tags(),
                params, meta.stepCount()
        );
    }
}
