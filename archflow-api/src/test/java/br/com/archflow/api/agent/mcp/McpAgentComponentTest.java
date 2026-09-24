package br.com.archflow.api.agent.mcp;

import br.com.archflow.langchain4j.mcp.McpClient;
import br.com.archflow.langchain4j.mcp.client.CorrelacaoMcp;
import br.com.archflow.model.ai.type.ComponentType;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.config.LLMConfigPatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
        /** O cliente MCP que chegou ao laço: é como se vê que o passo não abriu servidor nenhum. */
        final AtomicReference<McpClient> clienteDoLaco = new AtomicReference<>();
        private final Set<String> teto;
        private final McpAgentRunner runner;

        HostFalso(Set<String> teto, McpAgentRunner.Result resultado) {
            this.teto = teto;
            this.runner = new McpAgentRunner(null, null) {
                @Override
                public Result run(String tenantId, String systemPrompt, String userMessage,
                                  McpClient client, Options opts) {
                    options.set(opts);
                    clienteDoLaco.set(client);
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
        when(ctx.get(CorrelacaoMcp.CTX_CLIENTE)).thenReturn(Optional.of("cli-64336"));
        when(ctx.get(CorrelacaoMcp.CTX_VENDEDOR)).thenReturn(Optional.of("vend-7"));
        when(ctx.get(CorrelacaoMcp.CTX_TASK)).thenReturn(Optional.of("task-77"));

        componente(Map.of("systemPrompt", "você é um agente",
                "tools", List.of("resolver_sku"))).execute("execute", "oi", ctx);

        assertThat(durante.get()).isNotNull();
        assertThat(durante.get().janelaChave())
                .as("sem isto o header não sai, e nada falha para avisar")
                .isEqualTo("QP:conversa-abc:42");
        assertThat(durante.get().traceId()).isEqualTo("trace-xyz");
        assertThat(durante.get().clienteRef())
                .as("a identidade paga a MESMA armadilha de thread, e cala do mesmo jeito — "
                        + "so que aqui o silencio devolve a identidade ao modelo")
                .isEqualTo("cli-64336");
        assertThat(durante.get().vendedorRef()).isEqualTo("vend-7");
        assertThat(durante.get().taskId())
                .as("a origem paga a mesma armadilha de thread; sem ela o Core fica sem "
                        + "a quem creditar a cotação que este passo produzir")
                .isEqualTo("task-77");
        assertThat(CorrelacaoMcp.atual().janelaChave())
                .as("a thread é do pool do motor e será reusada por outro passo, de outro tenant")
                .isNull();
        assertThat(CorrelacaoMcp.atual().clienteRef())
                .as("identidade vazada para o passo seguinte seria pior que ausente")
                .isNull();
        assertThat(CorrelacaoMcp.atual().taskId())
                .as("origem vazada credita a venda de outra conversa a esta task, e não aparece")
                .isNull();
    }

    /**
     * O tier atravessa do contexto até as opções do runner.
     *
     * <p>Sem estes testes a correção ficaria pela metade: o resolvedor passaria
     * a saber usar o tier e continuaria não recebendo nenhum — que é
     * exatamente o defeito original, um mecanismo correto sem quem o alimente.
     * O tier viaja pelo {@link ExecutionContext} e não por ThreadLocal porque o
     * passo executa noutra thread, pelo mesmo motivo da correlação acima.
     */
    @Test
    @DisplayName("o tier do contexto chega às options do runner")
    void tierAtravessaOContexto() {
        HostFalso host = new HostFalso(Set.of(), concluido());
        ExecutionContext ctx = contextoCom(host);
        when(ctx.get(McpAgentComponent.CTX_TIER)).thenReturn(Optional.of("STRONG"));

        componente(Map.of("systemPrompt", "p")).execute("execute", "oi", ctx);

        assertThat(host.options.get().tier())
                .as("invoke.tier() era descartado antes de chegar ao resolvedor")
                .isEqualTo("STRONG");
    }

    @Test
    @DisplayName("sem tier no contexto, as options ficam sem tier — nada muda")
    void semTierNoContexto() {
        HostFalso host = new HostFalso(Set.of(), concluido());

        componente(Map.of("systemPrompt", "p")).execute("execute", "oi", contextoCom(host));

        assertThat(host.options.get().tier())
                .as("quem nao usa tier nao pode ganhar um por engano")
                .isNull();
    }

    /**
     * O contador de uso chega ao runner pelo contexto.
     *
     * <p>No caminho de fluxo, o passo não conhece quem montará o resultado. Sem o contador nas
     * opções, os tokens gastos aqui não apareceriam em result nenhum — e o teto de custo do tenant
     * contaria menos exatamente no caminho que vai substituir os outros.</p>
     */
    @Test
    @DisplayName("o contador de uso do contexto vai nas opções do runner")
    void contadorDeUsoChegaAoRunner() {
        HostFalso host = new HostFalso(Set.of("ler"), concluido());
        ContadorDeUso contador = new ContadorDeUso();
        ExecutionContext ctx = contextoCom(host);
        when(ctx.get(ContadorDeUso.CONTEXT_KEY)).thenReturn(Optional.of(contador));

        componente(Map.of("systemPrompt", "p", "tools", List.of("ler")))
                .execute("execute", "oi", ctx);

        assertThat(host.options.get().uso()).isSameAs(contador);
    }

    @Test
    @DisplayName("sem contador no contexto, o passo roda igual")
    void semContador() {
        HostFalso host = new HostFalso(Set.of("ler"), concluido());

        componente(Map.of("systemPrompt", "p", "tools", List.of("ler")))
                .execute("execute", "oi", contextoCom(host));

        assertThat(host.options.get().uso()).isNull();
    }

    /**
     * A memória que quem acionou pôs no contexto chega ao laço — e é o laço que a cerca.
     */
    @Test
    @DisplayName("a memória do contexto vai nas opções do runner")
    void memoriaChegaAoRunner() {
        HostFalso host = new HostFalso(Set.of("ler"), concluido());
        ExecutionContext ctx = contextoCom(host);
        when(ctx.get(McpAgentComponent.CTX_MEMORIA))
                .thenReturn(Optional.of(List.of("prefere caixa fechada", " ")));

        componente(Map.of("systemPrompt", "p", "tools", List.of("ler")))
                .execute("execute", "oi", ctx);

        assertThat(host.options.get().contextoRecuperado())
                .as("itens em branco não viram fato")
                .containsExactly("prefere caixa fechada");
    }

    @Test
    @DisplayName("valor que não é lista é ignorado, sem quebrar o passo")
    void memoriaEmFormaInesperada() {
        HostFalso host = new HostFalso(Set.of("ler"), concluido());
        ExecutionContext ctx = contextoCom(host);
        when(ctx.get(McpAgentComponent.CTX_MEMORIA)).thenReturn(Optional.of("não é lista"));

        componente(Map.of("systemPrompt", "p", "tools", List.of("ler")))
                .execute("execute", "oi", ctx);

        assertThat(host.options.get().contextoRecuperado()).isEmpty();
    }

    /**
     * O nó declara quais tools exigem decisão humana — e, com isso, como o laço volta.
     *
     * <p>Sem os dados de retomada, o laço recusa suspender: um estado que ninguém consegue retomar
     * é lixo durável, e foi o que existiu enquanto {@code resume} não teve chamador.</p>
     */
    @Test
    @DisplayName("aprovacaoHumana do nó vira gate e dados de retomada")
    void gateDeclaradoNoNo() {
        HostFalso host = new HostFalso(Set.of("ler", "reiniciar"), concluido());

        componente(Map.of("systemPrompt", "p",
                "server", "vendax",
                "tools", List.of("ler", "reiniciar"),
                "aprovacaoHumana", List.of("reiniciar")))
                .execute("execute", "oi", contextoCom(host));

        McpAgentRunner.Options o = host.options.get();
        assertThat(o.approval().requiresApproval("reiniciar")).isTrue();
        assertThat(o.approval().requiresApproval("ler")).isFalse();
        McpAgentState.Retomada retomada = o.retomada();
        assertThat(retomada).isNotNull();
        assertThat(retomada.serverRef()).isEqualTo("vendax");
        assertThat(retomada.toolsPermitidas()).containsExactlyInAnyOrder("ler", "reiniciar");
        assertThat(retomada.toolsComAprovacao()).containsExactly("reiniciar");
    }

    /** Pedir decisão humana sobre uma tool que a allowlist já nega é uma pergunta sem consequência. */
    @Test
    @DisplayName("gate sobre tool fora da allowlist é descartado")
    void gateForaDaAllowlist() {
        HostFalso host = new HostFalso(Set.of("ler"), concluido());

        componente(Map.of("systemPrompt", "p",
                "tools", List.of("ler"),
                "aprovacaoHumana", List.of("apagar")))
                .execute("execute", "oi", contextoCom(host));

        assertThat(host.options.get().approval().requiresApproval("apagar")).isFalse();
        assertThat(host.options.get().retomada().toolsComAprovacao()).isEmpty();
    }

    /** Nó sem allowlist e host sem teto: "todas" — que é diferente de "nenhuma". */
    @Test
    @DisplayName("sem allowlist declarada, a retomada grava 'todas' (nulo), não conjunto vazio")
    void semAllowlist() {
        HostFalso host = new HostFalso(Set.of(), concluido());

        componente(Map.of("systemPrompt", "p")).execute("execute", "oi", contextoCom(host));

        assertThat(host.options.get().retomada().toolsPermitidas()).isNull();
    }

    /**
     * Encerrar em código de erro da tool (pedido do VendaX de 22/09/2026).
     *
     * <p>"O agente não foi contratado por este tenant" não muda na volta seguinte: sem encerrar, o
     * erro volta ao modelo, que tenta até o teto e acaba redigindo uma resposta SEM o dado — o pior
     * desfecho, porque parece resposta.</p>
     */
    @Nested
    @DisplayName("encerrarEmCodigos")
    class EncerrarEmCodigos {

        @Test
        @DisplayName("os códigos do nó chegam ao laço")
        void chegamAoLaco() {
            HostFalso host = new HostFalso(Set.of(), concluido());
            McpAgentComponent c = componente(Map.of("systemPrompt", "p",
                    "encerrarEmCodigos", List.of(-32001, -32002)));

            c.execute("execute", "oi", contextoCom(host));

            assertThat(host.options.get().codigosQueEncerram()).containsExactlyInAnyOrder(-32001, -32002);
        }

        /** Sem a lista, todo erro de tool volta ao modelo — um erro transitório merece a segunda volta. */
        @Test
        @DisplayName("sem a lista, nada encerra")
        void semLista() {
            HostFalso host = new HostFalso(Set.of(), concluido());

            componente(Map.of("systemPrompt", "p")).execute("execute", "oi", contextoCom(host));

            assertThat(host.options.get().codigosQueEncerram()).isEmpty();
        }

        @Test
        @DisplayName("lista malformada é recusada na inicialização, não no meio da execução")
        void malformada() {
            assertThatThrownBy(() -> componente(Map.of("systemPrompt", "p",
                    "encerrarEmCodigos", "-32001")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("encerrarEmCodigos");
            assertThatThrownBy(() -> componente(Map.of("systemPrompt", "p",
                    "encerrarEmCodigos", List.of("nao-e-numero"))))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("o passo diz o que encerrou: nome e código")
        void oQueEncerrou() {
            McpAgentRunner.ToolCall causa = new McpAgentRunner.ToolCall("plano_negociacao",
                    Map.of(), "sem contrato", true, ToolTrust.UNTRUSTED, -32001);
            HostFalso host = new HostFalso(Set.of(),
                    new McpAgentRunner.Result("", List.of(causa), null, causa));
            McpAgentComponent c = componente(Map.of("systemPrompt", "p",
                    "encerrarEmCodigos", List.of(-32001)));

            Object saida = c.execute("execute", "oi", contextoCom(host));

            assertThat(saida).isInstanceOf(Map.class);
            Object encerrou = ((Map<?, ?>) saida).get(McpAgentComponent.SAIDA_ENCERROU);
            assertThat(encerrou).isEqualTo(Map.of("name", "plano_negociacao", "code", -32001));
        }

        /** Nada encerrou: o campo não aparece, e quem lê não precisa distinguir nulo de ausente. */
        @Test
        @DisplayName("sem encerramento, o campo não vai na saída")
        void semEncerramento() {
            HostFalso host = new HostFalso(Set.of(), concluido());

            Object saida = componente(Map.of("systemPrompt", "p"))
                    .execute("execute", "oi", contextoCom(host));

            assertThat(((Map<?, ?>) saida).containsKey(McpAgentComponent.SAIDA_ENCERROU)).isFalse();
        }
    }

    /**
     * "Nenhuma ferramenta" precisa ser declarável (pedido do VendaX de 22/09/2026).
     *
     * <p>Ausente e vazia significavam o mesmo — "sem lista" —, então um fluxo escrito com
     * {@code "tools": []} rodava com o server inteiro. Para o agente que só verbaliza o que o Core
     * calculou, isso é o contrato quebrado em silêncio.</p>
     */
    @Nested
    @DisplayName("tools: [] é nenhuma, e ausente continua sendo todas")
    class SemFerramentaNenhuma {

        @Test
        @DisplayName("lista vazia: nenhuma tool passa, e nem o server é aberto")
        void listaVazia() {
            HostFalso host = new HostFalso(Set.of(), concluido());

            componente(Map.of("systemPrompt", "p", "server", "vendax", "tools", List.of()))
                    .execute("execute", "oi", contextoCom(host));

            ToolAccessPolicy politica = host.options.get().access();
            assertThat(politica.isAllowed("obter_cliente_360")).isFalse();
            assertThat(politica.isAllowed("qualquer_outra")).isFalse();
            assertThat(host.clienteDoLaco.get()).isSameAs(SemFerramentas.INSTANCIA);
            assertThat(host.serverRef.get()).as("nada a listar: não se abre o server").isNull();
        }

        /** O par do pedido: sem a chave, o passo continua oferecendo o server inteiro. */
        @Test
        @DisplayName("chave ausente: o server inteiro, e o cliente do host")
        void chaveAusente() {
            HostFalso host = new HostFalso(Set.of(), concluido());

            componente(Map.of("systemPrompt", "p", "server", "vendax"))
                    .execute("execute", "oi", contextoCom(host));

            assertThat(host.options.get().access().isAllowed("obter_cliente_360")).isTrue();
            assertThat(host.clienteDoLaco.get()).isNotSameAs(SemFerramentas.INSTANCIA);
            assertThat(host.serverRef.get()).isEqualTo("vendax");
        }

        /** O teto do host é limite, não fonte: ele não devolve tools a quem declarou nenhuma. */
        @Test
        @DisplayName("lista vazia com teto do host: continua nenhuma")
        void listaVaziaComTeto() {
            HostFalso host = new HostFalso(Set.of("ler", "escrever"), concluido());

            componente(Map.of("systemPrompt", "p", "tools", List.of()))
                    .execute("execute", "oi", contextoCom(host));

            assertThat(host.options.get().access().isAllowed("ler")).isFalse();
        }

        /** Vazia é "nenhuma" e null é "todas": a retomada tem de voltar sem tool, como foi. */
        @Test
        @DisplayName("a retomada volta com o conjunto vazio, não com null")
        void retomada() {
            HostFalso host = new HostFalso(Set.of("ler"), concluido());

            componente(Map.of("systemPrompt", "p", "tools", List.of()))
                    .execute("execute", "oi", contextoCom(host));

            assertThat(host.options.get().retomada().toolsPermitidas()).isNotNull().isEmpty();
        }

        /**
         * O par que o pedido pede provado até o fim: com {@code []}, o modelo pode pedir a tool —
         * ela não roda, e nada vai ao servidor. Com o laço de verdade, não com o dublê.
         */
        @Test
        @DisplayName("lista vazia: o modelo pede a tool e ela não roda")
        void modeloPedeAToolEElaNaoRoda() {
            var config = br.com.archflow.model.config.ResolvedLLMConfig.builder()
                    .provider("openrouter").model("modelo-de-teste").build();
            var pedidos = new java.util.ArrayList<dev.langchain4j.model.chat.request.ChatRequest>();
            dev.langchain4j.model.chat.ChatModel modelo = new dev.langchain4j.model.chat.ChatModel() {
                @Override
                public dev.langchain4j.model.chat.response.ChatResponse chat(
                        dev.langchain4j.model.chat.request.ChatRequest request) {
                pedidos.add(request);
                // Primeiro turno: o modelo tenta a tool. Segundo: desiste e escreve.
                return dev.langchain4j.model.chat.response.ChatResponse.builder()
                        .aiMessage(pedidos.size() == 1
                                ? dev.langchain4j.data.message.AiMessage.from(
                                        dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                                                .id("c1").name("obter_cliente_360")
                                                .arguments("{\"clienteRef\":\"20572\"}").build())
                                : dev.langchain4j.data.message.AiMessage.from("3 de 4 cumpridas."))
                        .build();
                }
            };
            var resolver = new br.com.archflow.langchain4j.provider.LLMConfigResolver() {
                @Override
                public br.com.archflow.model.config.ResolvedLLMConfig resolve(
                        br.com.archflow.langchain4j.provider.LLMResolutionRequest r) {
                    return config;
                }

                @Override
                public dev.langchain4j.model.chat.ChatModel resolveModel(
                        br.com.archflow.langchain4j.provider.LLMResolutionRequest r) {
                    return modelo;
                }
            };
            AtomicReference<String> servidorAberto = new AtomicReference<>();
            McpAgentHost host = new McpAgentHost() {
                private final McpAgentRunner runner = new McpAgentRunner(resolver, config);

                @Override
                public McpAgentRunner runner() {
                    return runner;
                }

                @Override
                public McpClient clientFor(String tenantId, String ref) {
                    servidorAberto.set(ref == null ? "(padrão)" : ref);
                    return mock(McpClient.class);
                }

                @Override
                public Set<String> toolCeiling(String tenantId) {
                    return Set.of();
                }
            };

            Object saida = componente(Map.of("systemPrompt", "p", "server", "vendax",
                    "tools", List.of())).execute("execute", "oi", contextoCom(host));

            assertThat(servidorAberto.get()).as("nenhuma ida ao servidor MCP").isNull();
            assertThat(pedidos.get(0).toolSpecifications()).as("catálogo vazio no prompt").isEmpty();
            // A TENTATIVA NEGADA FICA REGISTRADA, com error=true e o motivo. Ela não rodou — não
            // houve servidor —, mas some-la esconderia do Core que o modelo tentou.
            List<?> chamadas = (List<?>) ((Map<?, ?>) saida).get(McpAgentComponent.SAIDA_TOOLS);
            assertThat(chamadas).singleElement().satisfies(c -> {
                Map<?, ?> chamada = (Map<?, ?>) c;
                assertThat(chamada.get("name")).isEqualTo("obter_cliente_360");
                assertThat(chamada.get("error")).isEqualTo(true);
                assertThat(String.valueOf(chamada.get("result"))).contains("não está autorizada");
            });
            assertThat(((Map<?, ?>) saida).get(McpAgentComponent.SAIDA_TEXTO))
                    .isEqualTo("3 de 4 cumpridas.");
        }

        /** Declaração malformada cai no lado seguro: "nenhuma", e não o server inteiro. */
        @Test
        @DisplayName("lista só de espaços também é nenhuma")
        void listaDeEspacos() {
            HostFalso host = new HostFalso(Set.of(), concluido());

            componente(Map.of("systemPrompt", "p", "tools", List.of("  ")))
                    .execute("execute", "oi", contextoCom(host));

            assertThat(host.options.get().access().isAllowed("ler")).isFalse();
            assertThat(host.clienteDoLaco.get()).isSameAs(SemFerramentas.INSTANCIA);
        }
    }
}
