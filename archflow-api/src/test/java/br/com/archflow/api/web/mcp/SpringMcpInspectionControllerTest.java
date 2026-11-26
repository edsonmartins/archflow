package br.com.archflow.api.web.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.archflow.api.mcp.McpInspectionController;
import br.com.archflow.api.mcp.dto.McpInspectionDtos.McpServerIntrospectionDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * RCHFLW-188 (item 7): {@code GET /api/admin/mcp/servers/{nome}} de um servidor que não está na
 * configuração respondia 200 {@code {connected:false, error:"server not registered"}}, e a tela de
 * detalhe montava um servidor fantasma "offline" para quem seguia um link velho. Agora é 404; o
 * servidor configurado continua respondendo 200, conectado ou não.
 */
@DisplayName("Inspeção MCP — servidor desconhecido")
class SpringMcpInspectionControllerTest {

    private final List<String> inspecionados = new ArrayList<>();

    private final McpInspectionController delegate = new McpInspectionController() {
        @Override
        public List<String> listServerNames() {
            return List.of("linktor");
        }

        @Override
        public McpServerIntrospectionDto introspect(String serverName) {
            inspecionados.add(serverName);
            boolean registrado = "linktor".equals(serverName);
            return new McpServerIntrospectionDto(serverName, false, List.of(), List.of(), List.of(),
                    registrado ? "connect/initialize failed: recusado" : "server not registered");
        }
    };

    private final SpringMcpInspectionController controller = new SpringMcpInspectionController(delegate);

    @Test
    @DisplayName("servidor fora da configuração é 404, sem tentar inspecionar")
    void desconhecidoE404() {
        ResponseEntity<?> resposta = controller.introspect("nope-server");

        assertThat(resposta.getStatusCode().value()).isEqualTo(404);
        assertThat(resposta.getBody()).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) resposta.getBody()).get("error")).asString().contains("nope-server");
        assertThat(inspecionados).isEmpty();
    }

    @Test
    @DisplayName("servidor configurado que não conecta continua 200 offline, com o erro")
    void configuradoOfflineE200() {
        ResponseEntity<?> resposta = controller.introspect("linktor");

        assertThat(resposta.getStatusCode().value()).isEqualTo(200);
        McpServerIntrospectionDto dto = (McpServerIntrospectionDto) resposta.getBody();
        assertThat(dto.connected()).isFalse();
        assertThat(dto.error()).startsWith("connect/initialize failed");
    }
}
