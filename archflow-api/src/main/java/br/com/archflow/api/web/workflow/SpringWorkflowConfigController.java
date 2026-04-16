package br.com.archflow.api.web.workflow;

import br.com.archflow.api.workflow.WorkflowConfigController;
import br.com.archflow.api.workflow.dto.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/workflow")
public class SpringWorkflowConfigController {

    private final WorkflowConfigController delegate;

    public SpringWorkflowConfigController(WorkflowConfigController delegate) {
        this.delegate = delegate;
    }

    @GetMapping("/providers")
    public List<ProviderDto> listProviders() { return delegate.listProviders(); }

    @GetMapping("/agent-patterns")
    public List<AgentPatternDto> listAgentPatterns() { return delegate.listAgentPatterns(); }

    @GetMapping("/personas")
    public List<PersonaDto> listPersonas() { return delegate.listPersonas(); }

    @GetMapping("/governance-profiles")
    public List<GovernanceProfileDto> listGovernanceProfiles() { return delegate.listGovernanceProfiles(); }

    @GetMapping("/mcp-servers")
    public List<McpServerDto> listMcpServers() { return delegate.listMcpServers(); }
}
