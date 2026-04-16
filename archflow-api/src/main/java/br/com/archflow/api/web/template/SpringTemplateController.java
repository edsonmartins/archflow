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

    public SpringTemplateController(TemplateController delegate) {
        this.delegate = delegate;
    }

    @GetMapping
    public List<TemplateResponse> listTemplates() { return delegate.listTemplates(); }

    @GetMapping("/{id}")
    public TemplateResponse getTemplate(@PathVariable String id) { return delegate.getTemplate(id); }

    @PostMapping("/{id}/install")
    public Object installTemplate(@PathVariable String id, @RequestBody InstallTemplateRequest request) {
        return delegate.installTemplate(id, request);
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
