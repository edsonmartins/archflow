package br.com.archflow.api.web.mcp;

import br.com.archflow.api.mcp.McpInspectionController;
import br.com.archflow.api.mcp.dto.McpInspectionDtos.McpServerIntrospectionDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** HTTP binding for {@link McpInspectionController}. */
@RestController
@RequestMapping("/api/admin/mcp")
public class SpringMcpInspectionController {

    private final McpInspectionController delegate;

    public SpringMcpInspectionController(McpInspectionController delegate) {
        this.delegate = delegate;
    }

    @GetMapping("/servers")
    public List<String> listServerNames() {
        return delegate.listServerNames();
    }

    @GetMapping("/servers/{name}")
    public McpServerIntrospectionDto introspect(@PathVariable String name) {
        return delegate.introspect(name);
    }
}
