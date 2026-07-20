package br.com.archflow.api.web.template;

import br.com.archflow.api.template.TemplateController;
import br.com.archflow.api.template.dto.InstallTemplateRequest;
import br.com.archflow.api.template.dto.TemplateResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/templates")
public class SpringTemplateController {

    private final TemplateController delegate;
    private final br.com.archflow.api.web.workflow.WorkflowRuntimeStore workflowStore;

    public SpringTemplateController(TemplateController delegate,
                                    br.com.archflow.api.web.workflow.WorkflowRuntimeStore workflowStore) {
        this.delegate = delegate;
        this.workflowStore = workflowStore;
    }

    @GetMapping
    public List<TemplateResponse> listTemplates() { return delegate.listTemplates(); }

    @GetMapping("/{id}")
    public TemplateResponse getTemplate(@PathVariable String id) { return delegate.getTemplate(id); }

    /**
     * Instala o template: cria a instância E a persiste como workflow em
     * rascunho no runtime store — antes o endpoint só devolvia o JSON,
     * "instalar" não criava nada no servidor. Os templates atuais produzem
     * metadados/configuração (não steps de designer), então o rascunho nasce
     * com canvas vazio e a configuração do template anexada.
     */
    @PostMapping("/{id}/install")
    public ResponseEntity<Map<String, Object>> installTemplate(@PathVariable String id,
                                                               @RequestBody InstallTemplateRequest request) {
        Object instance = delegate.installTemplate(id, request);

        String workflowId = "wf-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> document = new java.util.LinkedHashMap<>();
        document.put("id", workflowId);
        if (instance instanceof br.com.archflow.model.Workflow wf) {
            document.put("metadata", Map.of(
                    "name", wf.getName() != null ? wf.getName() : request.name(),
                    "description", wf.getDescription() != null ? wf.getDescription() : "",
                    "version", "1.0.0"));
            document.put("configuration", wf.getMetadata() != null ? wf.getMetadata() : Map.of());
        } else {
            document.put("metadata", Map.of("name", request.name(), "description", "", "version", "1.0.0"));
            document.put("configuration", Map.of());
        }
        document.put("steps", java.util.List.of());
        document.put("status", "draft");
        document.put("template", id);
        document.put("updatedAt", java.time.Instant.now().toString());
        workflowStore.putWorkflow(workflowId, document);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "workflowId", workflowId,
                "workflow", document,
                "instance", instance));
    }

    @GetMapping("/{id}/preview")
    public TemplateResponse previewTemplate(@PathVariable String id) { return delegate.previewTemplate(id); }

    @GetMapping("/categories")
    public List<String> listCategories() { return delegate.listCategories(); }

    @GetMapping("/search")
    public List<TemplateResponse> searchTemplates(@RequestParam(defaultValue = "") String q) { return delegate.searchTemplates(q); }

    @ExceptionHandler(TemplateController.TemplateNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(TemplateController.TemplateNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }
}
