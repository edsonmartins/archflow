package br.com.archflow.api.web.workflow;

import br.com.archflow.api.agent.mcp.McpAgentHost;
import br.com.archflow.api.audit.AuditTrail;
import br.com.archflow.api.flow.WorkflowDeserializer;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.observability.audit.InMemoryAuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * O host de MCP chega ao contexto de execução do fluxo.
 *
 * <h2>O que este teste protege</h2>
 *
 * <p>O {@code McpAgentComponent} busca o host no {@link ExecutionContext} e falha sem ele. Por um
 * bom tempo o componente esteve registrado no catálogo, visível no designer, e <b>ninguém injetava
 * o host</b> — porque todo agente era construído por caminho próprio em Java, e nenhum fluxo real
 * chegava a executar um nó de MCP. O componente certo ficou órfão, e o sintoma só apareceria na
 * primeira execução de um fluxo que o usasse.</p>
 *
 * <p>Este teste é o que impede a órfandade de voltar em silêncio: ele afirma a ligação, não a
 * existência das peças.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("McpAgentHost no contexto de execução")
class McpAgentHostNoContextoTest {

    @Mock
    private WorkflowDeserializer deserializer;
    @Mock
    private FlowEngine flowEngine;
    @Mock
    private FlowRepository flowRepository;

    private final McpAgentHost host = mock(McpAgentHost.class);
    private SpringWorkflowCrudController controller;

    @BeforeEach
    void setUp() {
        controller = new SpringWorkflowCrudController(
                new InMemoryWorkflowRuntimeStore(),
                deserializer,
                flowEngine,
                flowRepository,
                new AuditTrail(InMemoryAuditRepository::new),
                host);
        lenient().when(deserializer.toFlow(any())).thenReturn(mock(Flow.class));
        lenient().when(flowEngine.execute(any(), any())).thenReturn(new CompletableFuture<>());
    }

    private ExecutionContext contextoDaExecucao(Map<String, Object> input) {
        var created = controller.create(new HashMap<>(
                Map.of("metadata", Map.of("name", "Fluxo com nó de MCP"))));
        controller.execute(String.valueOf(created.getBody().get("id")), input);

        ArgumentCaptor<ExecutionContext> captor = ArgumentCaptor.forClass(ExecutionContext.class);
        verify(flowEngine).execute(any(), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("executar um fluxo entrega o host ao contexto")
    void hostChegaAoContexto() {
        assertThat(McpAgentHost.from(contextoDaExecucao(Map.of())))
                .as("sem isto o nó 'mcp-agent' falha dizendo que não há host")
                .contains(host);
    }

    /**
     * A entrada do fluxo é dado de quem chama a API. Se ela pudesse sobrescrever a chave do host, um
     * fluxo passaria a falar com o servidor MCP que o chamador apontasse — credencial e destino
     * escolhidos de fora.
     */
    @Test
    @DisplayName("o input do fluxo NÃO sobrescreve o host")
    void inputNaoSobrescreveOHost() {
        ExecutionContext ctx = contextoDaExecucao(
                Map.of(McpAgentHost.CONTEXT_KEY, "servidor-de-fora"));

        assertThat(McpAgentHost.from(ctx))
                .as("quem escreve por último vence — a injeção tem de vir depois do input")
                .contains(host);
    }

    /** Instalação sem MCP é configuração válida: não injetar é melhor que injetar nulo. */
    @Test
    @DisplayName("sem host configurado, executar segue funcionando")
    void semHostNaoQuebra() {
        controller = new SpringWorkflowCrudController(
                new InMemoryWorkflowRuntimeStore(), deserializer, flowEngine, flowRepository,
                new AuditTrail(InMemoryAuditRepository::new), null);

        assertThatCode(() -> assertThat(McpAgentHost.from(contextoDaExecucao(Map.of()))).isEmpty())
                .doesNotThrowAnyException();
    }
}
