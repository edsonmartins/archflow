package br.com.archflow.api.web.template;

import br.com.archflow.api.flow.NodeSupport;
import br.com.archflow.api.template.TemplateController;
import br.com.archflow.api.template.dto.InstallTemplateRequest;
import br.com.archflow.api.template.dto.TemplateResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/templates")
public class SpringTemplateController {

    private final TemplateController delegate;
    private final br.com.archflow.api.web.workflow.WorkflowRuntimeStore workflowStore;
    private final NodeSupport nodeSupport;

    public SpringTemplateController(TemplateController delegate,
                                    br.com.archflow.api.web.workflow.WorkflowRuntimeStore workflowStore,
                                    NodeSupport nodeSupport) {
        this.delegate = delegate;
        this.workflowStore = workflowStore;
        this.nodeSupport = nodeSupport;
    }

    @GetMapping
    public List<TemplateResponse> listTemplates() { return withSupport(delegate.listTemplates()); }

    @GetMapping("/{id}")
    public TemplateResponse getTemplate(@PathVariable String id) { return withSupport(delegate.getTemplate(id)); }

    /**
     * Tipos de nó sem executor neste deployment, para documentos que não vêm do catálogo SPI — os
     * templates do pacote da UI ({@code public/templates}). Corpo: {@code {"<id>": [steps...]}};
     * resposta: {@code {"<id>": ["tipo", ...]}}. Um lote só para a galeria inteira, e a regra é
     * a mesma dos templates do backend ({@link NodeSupport}), então a marcação não diverge.
     */
    @PostMapping("/unsupported-node-types")
    public Map<String, List<String>> unsupportedNodeTypes(
            @RequestBody Map<String, List<Map<String, Object>>> stepsByTemplate) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (stepsByTemplate != null) {
            stepsByTemplate.forEach((templateId, steps) ->
                    result.put(templateId, nodeSupport.unsupportedNodeTypes(steps)));
        }
        return result;
    }

    /**
     * Instala o template: cria a instância E a persiste como workflow em
     * rascunho no runtime store — antes o endpoint só devolvia o JSON,
     * "instalar" não criava nada no servidor. O rascunho leva o grafo real do
     * template ({@code steps[].connections}, parâmetros do pedido sobre os
     * defaults) — antes ia {@code steps: []} e o canvas abria vazio (RCHFLW-18).
     * Os nós sem executor no motor continuam lá: a resposta os lista em
     * {@code unsupportedNodeTypes}, e a execução falha neles com
     * "component not found" em vez de parecer vazia.
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
        List<Map<String, Object>> steps = delegate.templateSteps(id, request.parameters());
        document.put("steps", steps);
        document.put("status", "draft");
        document.put("template", id);
        document.put("updatedAt", java.time.Instant.now().toString());
        // O rascunho instalado é do tenant de quem instalou (RCHFLW-73); sem isso ele nasceria num
        // tenant que o próprio instalador não lista.
        workflowStore.putWorkflow(br.com.archflow.api.config.TenantContext.currentTenantId(), workflowId, document);

        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                "workflowId", workflowId,
                "workflow", document,
                "instance", instance,
                "unsupportedNodeTypes", nodeSupport.unsupportedNodeTypes(steps)));
    }

    @GetMapping("/{id}/preview")
    public TemplateResponse previewTemplate(@PathVariable String id) {
        return withSupport(delegate.previewTemplate(id));
    }

    @GetMapping("/categories")
    public List<String> listCategories() { return delegate.listCategories(); }

    @GetMapping("/search")
    public List<TemplateResponse> searchTemplates(@RequestParam(defaultValue = "") String q) {
        return withSupport(delegate.searchTemplates(q));
    }

    private List<TemplateResponse> withSupport(List<TemplateResponse> responses) {
        return responses.stream().map(this::withSupport).toList();
    }

    /** A resposta com os tipos sem executor — só esta camada conhece o catálogo do runtime. */
    private TemplateResponse withSupport(TemplateResponse response) {
        return response.withUnsupportedNodeTypes(nodeSupport.unsupportedNodeTypes(response.steps()));
    }

    @ExceptionHandler(TemplateController.TemplateNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(TemplateController.TemplateNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }
}
