package br.com.archflow.dsl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O construtor do passo de agente MCP.
 *
 * <p>O que se protege: o documento emitido tem as chaves que o nó lê, e a
 * diferença entre "todas as ferramentas" e "nenhuma" — que é o valor, não a
 * presença da chave — fica dita por extenso.
 */
@DisplayName("McpAgent — a config do nó por método")
class McpAgentTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> configDoPasso(NodeSpec spec) {
        Map<String, Object> documento = Workflows.define("f").step("p", spec).build().toMap();
        List<Map<String, Object>> steps = (List<Map<String, Object>>) documento.get("steps");
        return (Map<String, Object>) steps.get(0).get("config");
    }

    @Test
    @DisplayName("o passo sai como agent/mcp-agent, com a operação do nó")
    void forma() {
        Map<String, Object> documento = Workflows.define("f")
                .step("narra", McpAgent.node().systemPrompt("p"))
                .build().toMap();

        @SuppressWarnings("unchecked")
        Map<String, Object> passo = ((List<Map<String, Object>>) documento.get("steps")).get(0);
        assertThat(passo).containsEntry("type", "agent").containsEntry("componentId", "mcp-agent");
        assertThat(passo).containsEntry("operation", "execute");
    }

    /** A distinção que motivou a classe: ausente é "todas", vazia é "nenhuma". */
    @Test
    @DisplayName("noTools emite a lista VAZIA; sem a chamada, a chave não aparece")
    void nenhumaFerramenta() {
        assertThat(configDoPasso(McpAgent.node().systemPrompt("p").noTools().spec()))
                .containsEntry("tools", List.of());

        assertThat(configDoPasso(McpAgent.node().systemPrompt("p").spec()))
                .doesNotContainKey("tools");
    }

    @Test
    @DisplayName("as chaves que o nó lê, com os nomes que ele lê")
    void chaves() {
        Map<String, Object> config = configDoPasso(McpAgent.node()
                .systemPrompt("instrução")
                .server("vendax")
                .tools("obter_cliente_360", "plano_negociacao")
                .maxIterations(4)
                .outputFromTool("firmar_cotacao", "simular_cotacao")
                .requireOutputFromTool()
                .humanApproval("firmar_cotacao")
                .stopOnToolErrorCodes(-32001)
                .model("google/gemini-2.5-flash")
                .maxTokens(1024)
                .reasoningEffort("minimal")
                .spec());

        assertThat(config)
                .containsEntry("systemPrompt", "instrução")
                .containsEntry("server", "vendax")
                .containsEntry("tools", List.of("obter_cliente_360", "plano_negociacao"))
                .containsEntry("maxIterations", 4)
                .containsEntry("saidaDaTool", List.of("firmar_cotacao", "simular_cotacao"))
                .containsEntry("exigirSaidaDaTool", true)
                .containsEntry("aprovacaoHumana", List.of("firmar_cotacao"))
                .containsEntry("encerrarEmCodigos", List.of(-32001))
                .containsEntry("model", "google/gemini-2.5-flash")
                .containsEntry("maxTokens", 1024)
                .containsEntry("reasoning", Map.of("effort", "minimal"));
    }

    /** Uma DSL mais velha que o runtime continua útil: a chave crua passa. */
    @Test
    @DisplayName("with(Map) leva chave que esta versão não conhece")
    void chaveCrua() {
        assertThat(configDoPasso(McpAgent.node().systemPrompt("p")
                .with(Map.of("chaveFutura", 7)).spec()))
                .containsEntry("chaveFutura", 7);
    }

    @Test
    @DisplayName("o passo é imutável: cada método devolve outro")
    void imutavel() {
        McpAgent base = McpAgent.node().systemPrompt("p");

        McpAgent comTools = base.tools("a");

        assertThat(configDoPasso(base.spec())).doesNotContainKey("tools");
        assertThat(configDoPasso(comTools.spec())).containsEntry("tools", List.of("a"));
    }

    @Test
    @DisplayName("valores que o nó recusaria são recusados aqui, na escrita")
    void recusaValorInvalido() {
        assertThatThrownBy(() -> McpAgent.node().maxIterations(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxIterations");
        assertThatThrownBy(() -> McpAgent.node().maxTokens(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> McpAgent.node().reasoning(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reasoning");
    }

    @Test
    @DisplayName("step(id, agent) dispensa o .spec() no fim")
    void semSpec() {
        Map<String, Object> documento = Workflows.define("f")
                .step("narra", McpAgent.node().systemPrompt("p").noTools())
                .build().toMap();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) documento.get("steps");
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0)).containsEntry("componentId", "mcp-agent");
    }

    @Test
    @DisplayName("labeled fecha o passo com o rótulo do designer")
    void rotulo() {
        NodeSpec spec = McpAgent.node().systemPrompt("p").labeled("Narra o dia");

        Map<String, Object> documento = Workflows.define("f").step("narra", spec).build().toMap();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) documento.get("steps");
        assertThat(steps.get(0)).containsEntry("label", "Narra o dia");
    }
}
