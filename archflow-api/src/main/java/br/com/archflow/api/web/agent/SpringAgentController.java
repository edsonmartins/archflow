package br.com.archflow.api.web.agent;

import br.com.archflow.api.agent.AgentController;
import br.com.archflow.api.agent.dto.InvokeRequest;
import br.com.archflow.api.agent.dto.InvokeResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/archflow/agents")
public class SpringAgentController {

    private final AgentController delegate;

    public SpringAgentController(AgentController delegate) {
        this.delegate = delegate;
    }

    @PostMapping("/{agentId}/invoke")
    public InvokeResponse invoke(@PathVariable String agentId, @RequestBody InvokeRequest request) {
        return delegate.invoke(agentId, request);
    }
}
