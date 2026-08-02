package br.com.archflow.api.config;

import br.com.archflow.api.agent.mcp.ConfiguredMcpAgentHost;
import br.com.archflow.api.agent.mcp.McpAgentHost;
import br.com.archflow.api.agent.mcp.McpAgentRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * De onde saem os servidores MCP da instalação.
 *
 * <p>O bean existe porque o {@code McpAgentComponent} busca o host no contexto de execução e falha
 * sem ele. A configuração é o único lugar onde endereço e credencial podem morar: no documento do
 * fluxo eles seriam versionados e visíveis no designer, e o mesmo documento não rodaria em dois
 * ambientes.</p>
 *
 * <p>Binding de mapa aninhado falha em silêncio quando o prefixo está errado — devolve vazio, e o
 * sintoma só aparece na execução do primeiro fluxo com o nó. Por isso o teste afirma o conteúdo, e
 * não que o bean subiu.</p>
 */
@DisplayName("Bean do McpAgentHost")
class McpAgentHostBeanTest {

    private final ArchflowBeanConfiguration config = new ArchflowBeanConfiguration();
    private final McpAgentRunner runner = mock(McpAgentRunner.class);

    private McpAgentHost host(MockEnvironment env, String legadoUrl, String legadoToken) {
        return config.mcpAgentHost(runner, env, "", legadoUrl, legadoToken);
    }

    @Test
    @DisplayName("servidores declarados em archflow.mcp.servers.* são lidos")
    void leServidoresDeclarados() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("archflow.mcp.servers.vendax.base-url", "http://core:8080/mcp")
                .withProperty("archflow.mcp.servers.vendax.service-token", "t-1")
                .withProperty("archflow.mcp.servers.outro.base-url", "http://outro/mcp");

        var servers = ((ConfiguredMcpAgentHost) host(env, "", "")).servers();

        assertThat(servers).containsOnlyKeys("vendax", "outro");
        assertThat(servers.get("vendax").baseUrl()).isEqualTo("http://core:8080/mcp");
        assertThat(servers.get("vendax").serviceToken()).isEqualTo("t-1");
    }

    /**
     * Uma instalação que já roda hoje só tem a chave antiga. Se o bean exigisse a nova, ela subiria
     * sem servidor nenhum e o primeiro fluxo com nó de MCP falharia — sem ninguém ter mudado nada.
     */
    @Test
    @DisplayName("a chave legada archflow.vendax.mcp.* vira o servidor 'vendax'")
    void semeiaConfiguracaoLegada() {
        var servers = ((ConfiguredMcpAgentHost) host(
                new MockEnvironment(), "http://core:8080/mcp", "t-legado")).servers();

        assertThat(servers).containsOnlyKeys("vendax");
        assertThat(servers.get("vendax").serviceToken()).isEqualTo("t-legado");
    }

    @Test
    @DisplayName("o declarado explicitamente vence o legado, sem duplicar")
    void declaradoVenceLegado() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("archflow.mcp.servers.vendax.base-url", "http://novo/mcp");

        var servers = ((ConfiguredMcpAgentHost) host(env, "http://antigo/mcp", "t")).servers();

        assertThat(servers).containsOnlyKeys("vendax");
        assertThat(servers.get("vendax").baseUrl()).isEqualTo("http://novo/mcp");
    }

    /**
     * Instalação sem MCP é válida e não pode impedir a subida — mas quem for usar o nó precisa de uma
     * mensagem que diga o que fazer, e não "há mais de um servidor configurado ()".
     */
    @Test
    @DisplayName("sem servidor o bean sobe, e o nó falha dizendo o que configurar")
    void semServidorSobeEExplica() {
        McpAgentHost host = host(new MockEnvironment(), "", "");

        assertThat(host).isNotNull();
        assertThatThrownBy(() -> host.clientFor("t1", null))
                .hasMessageContaining("Nenhum servidor MCP configurado")
                .hasMessageContaining("archflow.mcp.servers");
    }
}
