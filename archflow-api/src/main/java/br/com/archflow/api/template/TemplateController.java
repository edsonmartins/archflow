package br.com.archflow.api.template;

import br.com.archflow.api.template.dto.InstallTemplateRequest;
import br.com.archflow.api.template.dto.TemplateResponse;

import java.util.List;

/**
 * REST controller for workflow templates.
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>GET /api/templates - List all templates</li>
 *   <li>GET /api/templates/{id} - Get template details</li>
 *   <li>POST /api/templates/{id}/install - Install a template as a new workflow</li>
 *   <li>GET /api/templates/{id}/preview - Preview template without installing</li>
 *   <li>GET /api/templates/categories - List available categories</li>
 *   <li>GET /api/templates/search?q=keyword - Search templates</li>
 * </ul>
 */
public interface TemplateController {

    /**
     * Lists all available workflow templates.
     *
     * @return List of template responses
     */
    List<TemplateResponse> listTemplates();

    /**
     * Gets a specific template by ID.
     *
     * @param templateId The template ID
     * @return The template response
     * @throws TemplateNotFoundException if the template doesn't exist
     */
    TemplateResponse getTemplate(String templateId);

    /**
     * Installs a template as a new workflow instance.
     *
     * @param templateId The template ID
     * @param request The installation request with name and parameters
     * @return The created workflow
     * @throws TemplateNotFoundException if the template doesn't exist
     */
    Object installTemplate(String templateId, InstallTemplateRequest request);

    /**
     * Previews a template without installing it.
     *
     * @param templateId The template ID
     * @return The template response
     * @throws TemplateNotFoundException if the template doesn't exist
     */
    TemplateResponse previewTemplate(String templateId);

    /**
     * Lists all available template categories.
     *
     * @return List of category names
     */
    List<String> listCategories();

    /**
     * Searches templates by keyword.
     *
     * @param query The search query
     * @return List of matching template responses
     */
    List<TemplateResponse> searchTemplates(String query);

    /**
     * Exception thrown when a template is not found.
     */
    class TemplateNotFoundException extends RuntimeException {
        public TemplateNotFoundException(String templateId) {
            super("Template not found: " + templateId);
        }
    }
}
