package br.com.archflow.api.config;

import br.com.archflow.api.agent.mcp.McpAgentHost;
import br.com.archflow.api.agent.mcp.McpAgentRunner;
import br.com.archflow.api.linktor.LinktorConfigController;
import br.com.archflow.api.mcp.McpInspectionController;
import br.com.archflow.api.mcp.dto.McpInspectionDtos.McpServerIntrospectionDto;
import br.com.archflow.api.mcp.dto.McpInspectionDtos.McpToolDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A tela Servidores MCP mostra os servidores que o nó 'mcp-agent' usa (RCHFLW-100).
 *
 * <p>Antes, o registro da inspeção só recebia o Linktor: os servidores de
 * {@code archflow.mcp.servers.<ref>} moravam no bean do host e a tela não os via — logo, os
 * servidores que o fluxo chama eram justamente os que não dava para inspecionar. O teste sobe um
 * servidor MCP de verdade e afirma que a inspeção fala com ele sob o mesmo {@code ref} do nó: só
 * listar o nome não provaria que a introspecção chega ao endereço configurado.</p>
 */
@DisplayName("Inspeção MCP dos servidores do agente")
class InspecaoDosServidoresDoAgenteTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> autorizacoes = new CopyOnWriteArrayList<>();
    private final List<String> tenants = new CopyOnWriteArrayList<>();
    private volatile boolean recusarToolsList;
    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void subirServidor() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            autorizacoes.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            JsonNode req = mapper.readTree(exchange.getRequestBody().readAllBytes());
            String id = req.path("id").asText();
            String method = req.path("method").asText();
            if ("tools/list".equals(method)) {
                tenants.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-TENANT-ID")));
            }
            if (recusarToolsList && "tools/list".equals(method)) {
                byte[] out = ("{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"error\":"
                        + "{\"code\":-32001,\"message\":\"tenant obrigatorio\"}}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, out.length);
                exchange.getResponseBody().write(out);
                exchange.close();
                return;
            }
            String result = switch (method) {
                case "initialize" -> "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{\"tools\":{}},"
                        + "\"serverInfo\":{\"name\":\"teste\",\"version\":\"1\"}}";
                case "tools/list" -> "{\"tools\":[{\"name\":\"buscar_pedido\",\"description\":\"Busca\","
                        + "\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}]}";
                default -> "{}";
            };
            byte[] out = ("{\"jsonrpc\":\"2.0\",\"id\":\"" + id + "\",\"result\":" + result + "}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    }

    @AfterEach
    void pararServidor() {
        server.stop(0);
        RequestContextHolder.resetRequestAttributes();
    }

    /** Uma requisição cujo tenant o ImpersonationFilter já resolveu. */
    private static void requisicaoDoTenant(String tenant) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(ImpersonationFilter.ATTR_TENANT_ID, tenant);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @SuppressWarnings("unchecked")
    private McpInspectionController inspecao(McpAgentHost host) {
        ObjectProvider<McpAgentHost> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(host);
        // Linktor ativado: desativado ele sai da lista (RCHFLW-194, LinktorDesativadoForaDaInspecaoTest).
        LinktorConfigController linktor = mock(LinktorConfigController.class);
        when(linktor.get()).thenReturn(new br.com.archflow.api.linktor.dto.LinktorConfigDto(
                true, "http://linktor", null, null, "node linktor-mcp", 30));
        return new ArchflowBeanConfiguration().mcpInspectionController(linktor, provider);
    }

    @Test
    @DisplayName("RCHFLW-194: Linktor desativado em /admin/linktor não aparece na lista, como no seletor do editor")
    @SuppressWarnings("unchecked")
    void linktorDesativadoForaDaLista() {
        ObjectProvider<McpAgentHost> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        LinktorConfigController linktor = mock(LinktorConfigController.class);
        when(linktor.get()).thenReturn(new br.com.archflow.api.linktor.dto.LinktorConfigDto(
                false, null, null, null, null, 30));

        McpInspectionController inspecao = new ArchflowBeanConfiguration().mcpInspectionController(linktor, provider);

        assertThat(inspecao.listServerNames()).isEmpty();
        assertThat(inspecao.introspect("linktor").connected()).isFalse();
    }

    private McpAgentHost host(MockEnvironment env) {
        return new ArchflowBeanConfiguration().mcpAgentHost(mock(McpAgentRunner.class), env, "", "", "");
    }

    @Test
    @DisplayName("lista os servidores de archflow.mcp.servers com o ref do nó, além do Linktor")
    void listaOsServidoresDoAgente() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("archflow.mcp.servers.self.base-url", baseUrl);

        assertThat(inspecao(host(env)).listServerNames()).containsExactlyInAnyOrder("linktor", "self");
    }

    @Test
    @DisplayName("a introspecção fala com o base-url configurado, com o service-token")
    void inspecionaOServidorDoAgente() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("archflow.mcp.servers.self.base-url", baseUrl)
                .withProperty("archflow.mcp.servers.self.service-token", "tok-1");

        McpServerIntrospectionDto dto = inspecao(host(env)).introspect("self");

        assertThat(dto.error()).isNull();
        assertThat(dto.connected()).isTrue();
        assertThat(dto.tools()).extracting(McpToolDto::name).containsExactly("buscar_pedido");
        assertThat(autorizacoes).contains("Bearer tok-1");
    }

    @Test
    @DisplayName("inspeciona com o tenant de quem olha, um client por tenant")
    void inspecionaComOTenantDaRequisicao() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("archflow.mcp.servers.self.base-url", baseUrl);
        McpInspectionController inspecao = inspecao(host(env));

        requisicaoDoTenant("tenant_demo");
        inspecao.introspect("self");
        requisicaoDoTenant("tenant_outro");
        inspecao.introspect("self");

        // Sem o header, o /mcp do archflow responderia pelo tenant padrão: a tela mostraria um
        // catálogo diferente do que o nó 'mcp-agent' desse tenant recebe.
        assertThat(tenants).containsExactly("tenant_demo", "tenant_outro");
    }

    @Test
    @DisplayName("um tools/list recusado aparece como erro, não como '0 tools'")
    void toolsListRecusadoViraErro() {
        recusarToolsList = true;
        MockEnvironment env = new MockEnvironment()
                .withProperty("archflow.mcp.servers.self.base-url", baseUrl);

        McpServerIntrospectionDto dto = inspecao(host(env)).introspect("self");

        assertThat(dto.tools()).isEmpty();
        assertThat(dto.error()).startsWith("tools/list failed:").contains("tenant obrigatorio");
    }

    @Test
    @DisplayName("um McpAgentHost que não é o configurado deixa só o Linktor")
    void hostDeOutroTipoSoLinktor() {
        assertThat(inspecao(mock(McpAgentHost.class)).listServerNames()).containsExactly("linktor");
    }

    @Test
    @DisplayName("sem servidores configurados continua só o Linktor")
    void semServidoresSoLinktor() {
        assertThat(inspecao(host(new MockEnvironment())).listServerNames()).containsExactly("linktor");
        assertThat(inspecao(null).listServerNames()).containsExactly("linktor");
    }
}
