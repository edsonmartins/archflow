package br.com.archflow.api.flow;

import br.com.archflow.api.agent.mcp.McpAgentHost;
import br.com.archflow.api.agent.mcp.McpAgentRunner;
import br.com.archflow.api.agent.mcp.SemFerramentas;
import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.dsl.McpAgent;
import br.com.archflow.dsl.WorkflowDocument;
import br.com.archflow.dsl.Workflows;
import br.com.archflow.langchain4j.mcp.McpClient;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * O que a DSL escreve para o nó {@code mcp-agent} é o que o nó executa.
 *
 * <p>O {@link DslConformanceTest} prova a forma do documento — passos, tipos,
 * grafo. Este prova a <b>configuração</b>, que é onde a DSL e o runtime podem
 * divergir sem ninguém ver: a chave é um texto de um lado e um
 * {@code config.get("...")} do outro. Pior, há chave cujo <i>valor</i> decide o
 * comportamento: {@code tools} ausente é "todas as ferramentas do servidor" e
 * {@code tools: []} é "nenhuma". Um {@code noTools()} que emitisse a chave
 * errada daria um agente com o servidor inteiro à mão, e o sintoma apareceria
 * na frase que o Core recusa — a três repositórios da causa.
 *
 * <p>Por isso o caminho vai inteiro: DSL → desserializador real → fábrica de
 * passos → {@code McpAgentComponent} → as opções com que o laço foi chamado.
 */
@DisplayName("DSL → nó mcp-agent: a config emitida é a que o nó executa")
class DslMcpAgentConformanceTest {

    private final WorkflowDeserializer deserializer = new DefaultWorkflowDeserializer(
            new DefaultFlowStepFactory(mock(ComponentCatalog.class), mock(DynamicWorkflowService.class),
                    mock(br.com.archflow.agent.streaming.EventStreamRegistry.class),
                    mock(br.com.archflow.engine.core.StateManager.class), null));

    /** Registra com que opções e com que cliente MCP o laço foi chamado. */
    private static final class HostFalso implements McpAgentHost {
        final AtomicReference<McpAgentRunner.Options> options = new AtomicReference<>();
        final AtomicReference<McpClient> cliente = new AtomicReference<>();
        final AtomicReference<String> serverAberto = new AtomicReference<>();

        @Override
        public McpAgentRunner runner() {
            return new McpAgentRunner(null, null) {
                @Override
                public Result run(String tenantId, String systemPrompt, String userMessage,
                                  McpClient client, Options opts) {
                    options.set(opts);
                    cliente.set(client);
                    return new Result("3 de 4 cumpridas.", List.of());
                }
            };
        }

        @Override
        public McpClient clientFor(String tenantId, String ref) {
            serverAberto.set(String.valueOf(ref));
            return mock(McpClient.class);
        }

        @Override
        public Set<String> toolCeiling(String tenantId) {
            return Set.of();
        }
    }

    private ExecutionContext contextoCom(McpAgentHost host) {
        ExecutionContext ctx = mock(ExecutionContext.class);
        when(ctx.getTenantId()).thenReturn("t-1");
        when(ctx.get(any())).thenReturn(Optional.empty());
        when(ctx.get(McpAgentHost.CONTEXT_KEY)).thenReturn(Optional.of(host));
        return ctx;
    }

    private static FlowStep passo(Flow flow, String id) {
        return flow.getSteps().stream().filter(s -> id.equals(s.getId())).findFirst()
                .orElseThrow(() -> new AssertionError("passo ausente: " + id));
    }

    private HostFalso executar(WorkflowDocument doc, String stepId) {
        HostFalso host = new HostFalso();
        passo(deserializer.toFlow(doc.toMap()), stepId).execute(contextoCom(host)).join();
        return host;
    }

    /** O caso do AP: o agente que só redige. */
    @Test
    @DisplayName("noTools(): o laço recebe política vazia, cliente sem ferramentas e nenhum servidor")
    void semFerramentas() {
        HostFalso host = executar(Workflows.define("ap-narracao")
                .step("narra", McpAgent.node()
                        .systemPrompt("Você redige a frase que fecha o dia.")
                        .server("vendax")
                        .noTools()
                        .maxIterations(1))
                .build(), "narra");

        assertThat(host.options.get()).isNotNull();
        assertThat(host.options.get().access().isAllowed("obter_cliente_360")).isFalse();
        assertThat(host.options.get().maxIterations()).isEqualTo(1);
        assertThat(host.cliente.get()).isSameAs(SemFerramentas.INSTANCIA);
        assertThat(host.serverAberto.get()).as("sem ferramenta, sem ida ao servidor").isNull();
    }

    /** O par: sem a chamada, a chave não é emitida, e o nó volta a oferecer o servidor. */
    @Test
    @DisplayName("sem noTools(): o servidor inteiro, e o cliente do host")
    void comFerramentas() {
        HostFalso host = executar(Workflows.define("qp")
                .step("cota", McpAgent.node().systemPrompt("p").server("vendax"))
                .build(), "cota");

        assertThat(host.options.get().access().isAllowed("obter_cliente_360")).isTrue();
        assertThat(host.cliente.get()).isNotSameAs(SemFerramentas.INSTANCIA);
        assertThat(host.serverAberto.get()).isEqualTo("vendax");
    }

    /** As demais chaves escritas por método chegam ao laço com o sentido que o nó lhes dá. */
    @Test
    @DisplayName("tools, encerrarEmCodigos e aprovação humana chegam ao laço")
    void demaisChaves() {
        HostFalso host = executar(Workflows.define("ns")
                .step("consulta", McpAgent.node()
                        .systemPrompt("p")
                        .tools("plano_negociacao", "obter_cliente_360")
                        .humanApproval("plano_negociacao")
                        .stopOnToolErrorCodes(-32001)
                        .maxIterations(4))
                .build(), "consulta");

        McpAgentRunner.Options o = host.options.get();
        assertThat(o.access().isAllowed("plano_negociacao")).isTrue();
        assertThat(o.access().isAllowed("firmar_cotacao")).isFalse();
        assertThat(o.approval().requiresApproval("plano_negociacao")).isTrue();
        assertThat(o.codigosQueEncerram()).containsExactly(-32001);
        assertThat(o.maxIterations()).isEqualTo(4);
    }

    /** O raciocínio e o teto de tokens saem no patch do passo, que é quem vence a cadeia. */
    @Test
    @DisplayName("reasoningEffort e maxTokens viram patch do passo")
    void patchDoPasso() {
        HostFalso host = executar(Workflows.define("cs")
                .step("avalia", McpAgent.node()
                        .systemPrompt("p")
                        .reasoningEffort("minimal")
                        .maxTokens(1024))
                .build(), "avalia");

        assertThat(host.options.get().stepPatch().reasoning())
                .contains(java.util.Map.of("effort", "minimal"));
        assertThat(host.options.get().stepPatch().maxTokens().getAsInt()).isEqualTo(1024);
    }
}
