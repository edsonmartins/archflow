package br.com.archflow.api.agent.mcp;

import br.com.archflow.api.agent.mcp.ConfiguredMcpAgentHost.Server;
import br.com.archflow.langchain4j.mcp.McpClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** Servidores MCP declarados em configuração, escolhidos por referência no nó. */
@DisplayName("ConfiguredMcpAgentHost — de onde o passo fala MCP")
class ConfiguredMcpAgentHostTest {

    private final McpAgentRunner runner = mock(McpAgentRunner.class);
    private final Server vendax = new Server("https://copilot.example/mcp", "tok-1");
    private final Server outro = new Server("https://outro.example/mcp", "tok-2");

    @Test
    @DisplayName("com um único servidor, ele é o default sem ninguém declarar")
    void unicoServidorEhODefault() {
        var host = new ConfiguredMcpAgentHost(runner, Map.of("vendax", vendax), null);

        assertThat(host.clientFor("t1", null)).isNotNull();
    }

    /**
     * Escolher em silêncio faria o fluxo falar com o servidor errado, e o sintoma apareceria como
     * uma tool que não existe — longe da causa.
     */
    @Test
    @DisplayName("com vários e sem default, o nó precisa dizer qual — e o erro lista as opções")
    void variosSemDefault() {
        var host = new ConfiguredMcpAgentHost(runner,
                Map.of("vendax", vendax, "outro", outro), null);

        assertThatThrownBy(() -> host.clientFor("t1", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("vendax")
                .hasMessageContaining("outro");
    }

    @Test
    @DisplayName("servidor desconhecido falha dizendo quais existem")
    void servidorDesconhecido() {
        var host = new ConfiguredMcpAgentHost(runner, Map.of("vendax", vendax), null);

        assertThatThrownBy(() -> host.clientFor("t1", "inexistente"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inexistente")
                .hasMessageContaining("vendax");
    }

    @Test
    @DisplayName("default declarado precisa existir")
    void defaultInexistente() {
        assertThatThrownBy(() -> new ConfiguredMcpAgentHost(runner, Map.of("vendax", vendax), "xx"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("xx");
    }

    /**
     * O HttpMcpClient carrega o X-TENANT-ID em toda chamada: reaproveitar o client de outro tenant
     * mandaria a chamada com a identidade errada.
     */
    @Test
    @DisplayName("o client é cacheado por servidor E por tenant")
    void cachePorServidorETenant() {
        var host = new ConfiguredMcpAgentHost(runner,
                Map.of("vendax", vendax, "outro", outro), "vendax");

        McpClient t1 = host.clientFor("t1", "vendax");
        McpClient t1DeNovo = host.clientFor("t1", "vendax");
        McpClient t2 = host.clientFor("t2", "vendax");
        McpClient t1NoOutro = host.clientFor("t1", "outro");

        assertThat(t1).isSameAs(t1DeNovo);
        assertThat(t2).isNotSameAs(t1);
        assertThat(t1NoOutro).isNotSameAs(t1);
    }

    @Test
    @DisplayName("teto de tools por tenant; ausente é sem teto")
    void tetoPorTenant() {
        var host = new ConfiguredMcpAgentHost(runner, Map.of("vendax", vendax), null,
                Map.of("t1", Set.of("ler")));

        assertThat(host.toolCeiling("t1")).containsExactly("ler");
        assertThat(host.toolCeiling("t2")).isEmpty();
    }

    @Test
    @DisplayName("servidor sem baseUrl é recusado na construção")
    void servidorSemUrl() {
        assertThatThrownBy(() -> new Server("  ", "tok"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
