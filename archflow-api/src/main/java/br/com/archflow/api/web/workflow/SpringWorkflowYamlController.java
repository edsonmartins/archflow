package br.com.archflow.api.web.workflow;

import br.com.archflow.api.workflow.WorkflowYamlController;
import br.com.archflow.api.workflow.WorkflowYamlDto;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workflows")
public class SpringWorkflowYamlController {

    private final WorkflowYamlController delegate;

    public SpringWorkflowYamlController(WorkflowYamlController delegate) {
        this.delegate = delegate;
    }

    @GetMapping("/{id}/yaml")
    public WorkflowYamlDto getYaml(@PathVariable String id) { return delegate.getYaml(id); }

    @PutMapping("/{id}/yaml")
    public WorkflowYamlDto updateYaml(@PathVariable String id, @RequestBody WorkflowYamlDto request) {
        return delegate.updateYaml(id, request);
    }
}
