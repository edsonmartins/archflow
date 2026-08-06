package br.com.archflow.api.agent.mcp;

import br.com.archflow.langchain4j.mcp.McpClient;
import br.com.archflow.langchain4j.mcp.client.CorrelacaoMcp;
import br.com.archflow.model.ai.type.ComponentType;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.config.LLMConfigPatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * O laço MCP como passo de fluxo.
 *
 * <p>O que os testes protegem não é o laço — é a política em volta dele. Uma allowlist que não pega
 * deixa o agente chamar tool destrutiva sem ninguém notar, e um laço suspenso tratado como
 * conclusão entrega resposta parcial como se fosse final.</p>
 */
@DisplayName("McpAgentComponent — o laço MCP como passo de fluxo")
class McpAgentComponentTest {

    private static final String TENANT = "t-1";

    /** Host de teste: registra as options com que o runner foi chamado. */
    private static class HostFalso implements McpAgentHost {
        final AtomicReference<McpAgentRunner.Options> options = new AtomicReference<>();
        final AtomicReference<String> serverRef = new AtomicReference<>();
        private final Set<String> teto;
        private final McpAgentRunner runner;

        HostFalso(Set<String> teto, McpAgentRunner.Result resultado) {
            this.teto = teto;
            this.runner = new McpAgentRunner(null, null) {
                @Override
                public Result run(String tenantId, String systemPrompt, String userMessage,
                                  McpClient client, Options opts) {
                    options.set(opts);
                    return resultado;
                }
            };
        }

        @Override
        public McpAgentRunner runner() {
            return runner;
        }

        @Override
        public McpClient clientFor(String tenantId, String ref) {
            serverRef.set(ref);
            return mock(McpClient.class);
        }

        @Override
        public Set<String> toolCeiling(String tenantId) {
            return teto;
        }
    }

    private ExecutionContext contextoCom(McpAgentHost host) {
        ExecutionContext ctx = mock(ExecutionContext.class);
        when(ctx.getTenantId()).thenReturn(TENANT);
        when(ctx.get(McpAgentHost.CONTEXT_KEY))
                .thenReturn(host == null ? Optional.empty() : Optional.of(host));
        return ctx;
    }

    private McpAgentRunner.Result concluido() {
        return new McpAgentRunner.Result("pronto", List.of());
    }

    private McpAgentComponent componente(Map<String, Object> config) {
        McpAgentComponent c = new McpAgentComponent();
        c.initialize(config);
        return c;
    }

    /** Um passo com prompt vazio produziria resposta plausível e sem relação com o fluxo. */
    @Test
    @DisplayName("prompt ausente ou vazio é recusado na inicialização")
    void promptObrigatorio() {
        assertThatThrownBy(() -> componente(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("systemPrompt");
        assertThatThrownBy(() -> componente(Map.of("systemPrompt", "   ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("sem host no contexto, o erro diz o que falta e quem deveria prover")
    void semHost() {
        McpAgentComponent c = componente(Map.of("systemPrompt", "você é um agente"));

        assertThatThrownBy(() -> c.execute("execute", "oi", contextoCom(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(McpAgentHost.CONTEXT_KEY);
    }

    /**
     * O ponto da intersecção: onde o cliente edita o fluxo, uma allowlist que viesse só do nó
     * deixaria ele conceder ao agente tools que a plataforma não lhe deu.
     */
    @Test
    @DisplayName("a allowlist do nó é interseccionada com o teto do host, nunca somada")
    void allowlistIntersecciona() {
        HostFalso host = new HostFalso(Set.of("ler", "consultar"), concluido());
        McpAgentComponent c = componente(Map.of(
                "systemPrompt", "p",
                "tools", List.of("ler", "apagar")));

        c.execute("execute", "oi", contextoCom(host));

        ToolAccessPolicy politica = host.options.get().access();
        assertThat(politica.isAllowed("ler")).isTrue();
        assertThat(politica.isAllowed("apagar"))
                .as("estava no nó mas fora do teto — conceder aqui inverteria a garantia")
                .isFalse();
        assertThat(politica.isAllowed("consultar"))
                .as("está no teto mas o nó não pediu")
                .isFalse();
    }

    @Test
    @DisplayName("sem lista no nó, vale o teto do host")
    void semListaValeOTeto() {
        HostFalso host = new HostFalso(Set.of("ler"), concluido());

        componente(Map.of("systemPrompt", "p")).execute("execute", "oi", contextoCom(host));

        assertThat(host.options.get().access().isAllowed("ler")).isTrue();
        assertThat(host.options.get().access().isAllowed("apagar")).isFalse();
    }

    @Test
    @DisplayName("sem teto e sem lista, o server inteiro — declarado, não por omissão")
    void semTetoESemLista() {
        HostFalso host = new HostFalso(Set.of(), concluido());

        componente(Map.of("systemPrompt", "p")).execute("execute", "oi", contextoCom(host));

        assertThat(host.options.get().access().isAllowed("qualquer")).isTrue();
    }

    /**
     * Tratar um laço suspenso como conclusão entregaria ao passo seguinte uma resposta parcial como
     * se fosse final, e a aprovação pendente sumiria sem ninguém vê-la.
     */
    @Test
    @DisplayName("suspensão por aprovação aparece na saída, com o id da solicitação")
    @SuppressWarnings("unchecked")
    void suspensaoExplicita() {
        McpAgentState.PendingApproval pendente = new McpAgentState.PendingApproval(
                "req-1", "apagar", Map.of("id", "9"), "call-1");
        HostFalso host = new HostFalso(Set.of(),
                new McpAgentRunner.Result("preciso confirmar", List.of(), pendente));

        Map<String, Object> saida = (Map<String, Object>) componente(Map.of("systemPrompt", "p"))
                .execute("execute", "oi", contextoCom(host));

        assertThat(saida.get(McpAgentComponent.SAIDA_SUSPENSO)).isEqualTo(true);
        assertThat(saida.get(McpAgentComponent.SAIDA_APROVACAO)).isEqualTo("req-1");
        assertThat(saida.get("pendingTool")).isEqualTo("apagar");
    }

    @Test
    @DisplayName("resultado concluído traz texto e tools executadas")
    @SuppressWarnings("unchecked")
    void saidaDeConclusao() {
        McpAgentRunner.ToolCall chamada = new McpAgentRunner.ToolCall(
                "ler", Map.of("id", "1"), "{\"ok\":true}", false, null);
        HostFalso host = new HostFalso(Set.of(),
                new McpAgentRunner.Result("respondi", List.of(chamada)));

        Map<String, Object> saida = (Map<String, Object>) componente(Map.of("systemPrompt", "p"))
                .execute("execute", "oi", contextoCom(host));

        assertThat(saida.get(McpAgentComponent.SAIDA_TEXTO)).isEqualTo("respondi");
        assertThat(saida.get(McpAgentComponent.SAIDA_SUSPENSO)).isEqualTo(false);
        List<Map<String, Object>> tools = (List<Map<String, Object>>) saida.get(
                McpAgentComponent.SAIDA_TOOLS);
        assertThat(tools).singleElement().satisfies(t -> {
            assertThat(t.get("name")).isEqualTo("ler");
            assertThat(t.get("result")).isEqualTo("{\"ok\":true}");
            assertThat(t.get("error")).isEqualTo(false);
        });
    }

    /** Passo encadeado recebe o mapa do passo anterior, não uma string solta. */
    @Test
    @DisplayName("entrada aceita texto direto ou a chave input/message de um mapa")
    void entradaDeMapa() {
        HostFalso host = new HostFalso(Set.of(), concluido());
        McpAgentComponent c = componente(Map.of("systemPrompt", "p"));

        c.execute("execute", Map.of("input", "de mapa"), contextoCom(host));
        c.execute("execute", Map.of("message", "outra chave"), contextoCom(host));
        c.execute("execute", "texto direto", contextoCom(host));
        // Sem exceção: as três formas são aceitas. O conteúdo vai ao runner, exercido acima.
        assertThat(host.options.get()).isNotNull();
    }

    @Test
    @DisplayName("o servidor declarado no nó chega ao host")
    void serverRefChegaAoHost() {
        HostFalso host = new HostFalso(Set.of(), concluido());

        componente(Map.of("systemPrompt", "p", "server", "vendax"))
                .execute("execute", "oi", contextoCom(host));

        assertThat(host.serverRef.get()).isEqualTo("vendax");
    }

    @Test
    @DisplayName("maxIterations do nó vale; ausente, o default do runner")
    void iteracoes() {
        HostFalso comValor = new HostFalso(Set.of(), concluido());
        componente(Map.of("systemPrompt", "p", "maxIterations", 3))
                .execute("execute", "oi", contextoCom(comValor));
        assertThat(comValor.options.get().maxIterations()).isEqualTo(3);

        HostFalso semValor = new HostFalso(Set.of(), concluido());
        componente(Map.of("systemPrompt", "p")).execute("execute", "oi", contextoCom(semValor));
        assertThat(semValor.options.get().maxIterations())
                .isEqualTo(McpAgentRunner.DEFAULT_MAX_ITERATIONS);
    }

    @Test
    @DisplayName("maxIterations inválido é recusado na inicialização")
    void iteracoesInvalidas() {
        assertThatThrownBy(() -> componente(Map.of("systemPrompt", "p", "maxIterations", 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxIterations");
    }

    @Test
    @DisplayName("o catálogo o descreve como agente, com o id que o fluxo referencia")
    void metadata() {
        var meta = componente(Map.of("systemPrompt", "p")).getMetadata();

        assertThat(meta.id()).isEqualTo(McpAgentComponent.COMPONENT_ID);
        assertThat(meta.type()).isEqualTo(ComponentType.AGENT);
        assertThat(meta.operations()).extracting(o -> o.id()).contains("execute");
    }

    @Test
    @DisplayName("encaminha patches distintos de fluxo e passo ao runner")
    void llmPatchesReachRunner() {
        HostFalso host = new HostFalso(Set.of(), concluido());
        McpAgentComponent component = new McpAgentComponent(
                LLMConfigPatch.builder().provider("openrouter").model("flow-model").build());
        component.initialize(Map.of(
                "systemPrompt", "p", "model", "step-model", "temperature", 0.2));

        component.execute("execute", "oi", contextoCom(host));

        assertThat(host.options.get().flowPatch().provider()).contains("openrouter");
        assertThat(host.options.get().flowPatch().model()).contains("flow-model");
        assertThat(host.options.get().stepPatch().model()).contains("step-model");
        assertThat(host.options.get().stepPatch().temperature().getAsDouble()).isEqualTo(0.2);
    }

    /**
     * A correlação chega ao runner — e é o teste que faltava na primeira versão.
     *
     * <p>Ela era definida no dispatcher, que roda em outra thread: medido em produção,
     * {@code [vendax-agent-4]} contra {@code [virtual-129]}. O ThreadLocal não atravessava, o
     * header nunca era enviado, e <b>nada falhava</b> — nenhum erro, nenhum log. Só a correlação
     * não aparecia, e quem fosse procurá-la concluiria que o evento não tinha origem.</p>
     *
     * <p>Por isso ela viaja no contexto (que atravessa threads) e é reposta no ThreadLocal AQUI,
     * na thread que chama as tools. E por isso este teste verifica o valor <b>durante</b> o
     * {@code run}, não depois.</p>
     */
    @Test
    @DisplayName("a correlação do contexto chega ao runner, na thread dele")
    void correlacaoChegaAoRunner() {
        java.util.concurrent.atomic.AtomicReference<CorrelacaoMcp.Dados> durante =
                new java.util.concurrent.atomic.AtomicReference<>();
        McpAgentHost espiao = new McpAgentHost() {
            @Override
            public McpAgentRunner runner() {
                return new McpAgentRunner(null, null) {
                    @Override
                    public Result run(String tenantId, String systemPrompt, String userMessage,
                                      McpClient client, Options opts) {
                        durante.set(CorrelacaoMcp.atual());
                        return concluido();
                    }
                };
            }

            @Override
            public McpClient clientFor(String tenantId, String ref) {
                return mock(McpClient.class);
            }

            @Override
            public Set<String> toolCeiling(String tenantId) {
                return Set.of("resolver_sku");
            }
        };

        ExecutionContext ctx = mock(ExecutionContext.class);
        when(ctx.getTenantId()).thenReturn(TENANT);
        when(ctx.get(McpAgentHost.CONTEXT_KEY)).thenReturn(Optional.of(espiao));
        when(ctx.get(CorrelacaoMcp.CTX_JANELA)).thenReturn(Optional.of("QP:conversa-abc:42"));
        when(ctx.get(CorrelacaoMcp.CTX_TRACE)).thenReturn(Optional.of("trace-xyz"));

        componente(Map.of("systemPrompt", "você é um agente",
                "tools", List.of("resolver_sku"))).execute("execute", "oi", ctx);

        assertThat(durante.get()).isNotNull();
        assertThat(durante.get().janelaChave())
                .as("sem isto o header não sai, e nada falha para avisar")
                .isEqualTo("QP:conversa-abc:42");
        assertThat(durante.get().traceId()).isEqualTo("trace-xyz");
        assertThat(CorrelacaoMcp.atual().janelaChave())
                .as("a thread é do pool do motor e será reusada por outro passo, de outro tenant")
                .isNull();
    }
}
