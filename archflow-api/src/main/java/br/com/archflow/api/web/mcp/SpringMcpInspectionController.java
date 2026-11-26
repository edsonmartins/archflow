package br.com.archflow.api.web.mcp;

import br.com.archflow.api.mcp.McpInspectionController;
import br.com.archflow.api.mcp.dto.McpInspectionDtos.McpServerIntrospectionDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

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

    /**
     * Servidor fora da configuração é 404, não um servidor "offline": a resposta 200 com
     * {@code connected=false} fazia a tela montar um servidor que não existe, com Reconectar e o erro
     * "server not registered", para quem seguia um link velho (RCHFLW-188). Offline continua sendo o
     * servidor configurado que não conectou.
     */
    @GetMapping("/servers/{name}")
    public ResponseEntity<?> introspect(@PathVariable String name) {
        if (!delegate.listServerNames().contains(name)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "MCP server not registered: " + name, "status", 404));
        }
        McpServerIntrospectionDto dto = delegate.introspect(name);
        return ResponseEntity.ok(dto);
    }
}
