package br.com.archflow.api.agent.vendax;

import br.com.archflow.api.agent.mcp.McpAgentRunner;
import br.com.archflow.api.agent.qp.QpAgentService;
import br.com.archflow.api.flow.WorkflowDeserializer;
import br.com.archflow.api.mcp.vendax.VendaxMcpClientProvider;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.langchain4j.mcp.client.CorrelacaoMcp;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.enums.ExecutionStatus;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A task que originou a execução atravessa até a chamada MCP — e <b>não sobrevive a ela</b>.
 *
 * <h2>Por que a origem entra pelo transporte</h2>
 *
 * <p>Do outro lado, este valor decide <b>a quem creditar uma venda</b>: o Core amarra a cotação à
 * task e passa a derivar dela o impacto, no lugar do número que o vendedor digita hoje na tela de
 * conclusão. Um id que o modelo pudesse preencher seria um id que o modelo erra — de forma
 * plausível, bem formada, e sem produzir erro nem log. Ver {@code CorrelacaoMcp.HEADER_TASK}.</p>
 *
 * <h2>O caso que importa é o terceiro</h2>
 *
 * <p>Presente e ausente são fáceis de acertar. O que reprova uma implementação que "funciona" é
 * <b>duas execuções em sequência na mesma thread</b>, a primeira com task e a segunda sem: se o
 * valor sobreviver, uma venda que nada tem a ver com a task é creditada a ela — e isso não aparece
 * em lugar nenhum, porque a cotação existe, o valor está certo e o cliente é real.</p>
 */
@DisplayName("A task de origem no header da chamada MCP")
class TaskDeOrigemTest {

    private final QpAgentService qp = mock(QpAgentService.class);
    private final McpAgentRunner runner = mock(McpAgentRunner.class);
    private final VendaxMcpClientProvider vendax = mock(VendaxMcpClientProvider.class);
    private final VendaxAgentDispatcherTest.CapturingSender sender =
            new VendaxAgentDispatcherTest.CapturingSender();

    private final VendaxAgentDispatcher dispatcher =
            new VendaxAgentDispatcher(qp, runner, vendax, sender, mock(ExecutorService.class));

    /** O que a correlação carregava <b>durante</b> cada execução — depois já é tarde. */
    private final List<String> tasksDurante = new CopyOnWriteArrayList<>();

    private VendaxInvoke invoke(String taskId) {
        return new VendaxInvoke("1.0", "tenant-1", "conv-1", "QP", "msg-1",
                "quero 2 caixas de arroz", "STRONG", "cockpit", "trace-1", null,
                "cliente-1", "vendedor-1", "QP:conv-1:3", taskId, null);
    }

    /**
     * Espiona a correlação no ponto em que ela é lida de verdade: a thread que chama as tools é
     * esta, e {@code HttpMcpClient.callTool} captura o ThreadLocal aqui, não lá dentro.
     */
    private void espionarAExecucao() {
        when(qp.quote(any(), nullable(String.class), any())).thenAnswer(chamada -> {
            tasksDurante.add(CorrelacaoMcp.atual().taskId());
            return new QpAgentService.QpResult("cotação pronta", List.of(), "{\"total\":10}", "qp-1");
        });
    }

    @Test
    @DisplayName("execução iniciada por task: a task acompanha a execução inteira")
    void comTask() {
        espionarAExecucao();

        dispatcher.runAndReport(invoke("task-77"));

        assertThat(tasksDurante)
                .as("sem isto o header não sai, e o Core fica sem a quem creditar a cotação")
                .containsExactly("task-77");
    }

    @Test
    @DisplayName("execução sem task: nada é inventado — ausência é o caso comum")
    void semTask() {
        espionarAExecucao();

        dispatcher.runAndReport(invoke(null));

        assertThat(tasksDurante).containsExactly((String) null);
        assertThat(CorrelacaoMcp.atual().janelaChave())
                .as("a correlação segue independente da origem")
                .isNull();
    }

    /**
     * O caso 3, e o único que pega vazamento.
     *
     * <p>Mesma thread, duas execuções: a primeira veio do cockpit de tasks, a segunda é uma
     * conversa qualquer. Se o {@code finally} do dispatcher não limpasse — ou se a ausência não
     * fosse escrita como ausência —, a segunda sairia com a task da primeira, e a venda dela seria
     * creditada a uma task que não a produziu. Nenhum erro, nenhum log, nenhum número destoante:
     * só o dono do crédito é outro.</p>
     */
    @Test
    @DisplayName("duas execuções na mesma thread: a segunda, sem task, sai sem a task da primeira")
    void naoVazaEntreExecucoes() {
        espionarAExecucao();

        dispatcher.runAndReport(invoke("task-77"));
        dispatcher.runAndReport(invoke(null));

        assertThat(tasksDurante)
                .as("presença errada vira um número que ninguém contesta; ausência é honesta")
                .containsExactly("task-77", null);
        assertThat(CorrelacaoMcp.atual().taskId())
                .as("a thread do executor de agentes é reusada pelo próximo invoke")
                .isNull();
    }

    /**
     * O caminho de FLUXO não passa pelo ThreadLocal do dispatcher: o passo roda noutra thread
     * (medido: {@code [vendax-agent-N]} contra {@code [virtual-N]}). A origem viaja no contexto,
     * como a correlação e a identidade, e o {@code McpAgentComponent} a repõe do outro lado.
     */
    @Test
    @DisplayName("no caminho de fluxo, a task viaja no contexto da execução")
    void fluxoLevaATaskNoContexto() {
        ExecutionContext contexto = executarFluxoCom(invoke("task-77"));

        assertThat(contexto.get(CorrelacaoMcp.CTX_TASK))
                .as("ficando só no ThreadLocal do dispatcher, o header não sairia — e nada falharia")
                .contains("task-77");
    }

    /**
     * Sem task não há chave — e, principalmente, não há explosão.
     *
     * <p>As variáveis do contexto vivem num {@code ConcurrentHashMap}, que recusa valor nulo. Um
     * {@code set} direto derrubaria toda execução que não veio de task, que é a maioria delas.</p>
     */
    @Test
    @DisplayName("fluxo sem task: a chave não existe, e a execução roda igual")
    void fluxoSemTaskNaoQuebra() {
        VendaxInvoke semNada = new VendaxInvoke("1.0", "tenant-1", "conv-1", "CS", "msg-1",
                "chegou?", null, "cockpit", null, null, null, null, null, null, null);

        ExecutionContext contexto = executarFluxoCom(semNada);

        assertThat(contexto.get(CorrelacaoMcp.CTX_TASK)).isEmpty();
        assertThat(contexto.get(CorrelacaoMcp.CTX_CLIENTE))
                .as("conversa sem vínculo de cliente é legítima, e não pode derrubar o fluxo")
                .isEmpty();
    }

    @Test
    @DisplayName("o invoke sem taskId continua desserializável pelos construtores antigos")
    void compatDoEnvelope() {
        VendaxInvoke antigo = new VendaxInvoke("1.0", "t", "c", "QP", "m", "oi", "LIGHT",
                "r", "trace", null, "cli", "vend", null);

        assertThat(antigo.taskId()).isNull();
        assertThatCode(() -> new VendaxInvoke("1.0", "t", "c", "QP", "m", "oi", "LIGHT",
                "r", "trace", null, "cli", "vend", "QP:c:1", (DefinicaoDeAgente) null))
                .doesNotThrowAnyException();
    }

    /** Roda o fluxo com o motor todo dublado e devolve o contexto que o motor recebeu. */
    private ExecutionContext executarFluxoCom(VendaxInvoke invoke) {
        WorkflowDeserializer deserializer = mock(WorkflowDeserializer.class);
        FlowEngine engine = mock(FlowEngine.class);
        FlowRepository repositorio = mock(FlowRepository.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<FlowEngine> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(engine);
        Flow flow = mock(Flow.class);
        when(deserializer.toFlow(any())).thenReturn(flow);
        when(engine.execute(any(), any()))
                .thenReturn(CompletableFuture.completedFuture(new FlowResultOk()));

        new AgentFlowRunner(deserializer, provider, repositorio, null)
                .executar(invoke, Map.of("steps", List.of()), "entrada");

        ArgumentCaptor<ExecutionContext> ctx = ArgumentCaptor.forClass(ExecutionContext.class);
        verify(engine).execute(any(), ctx.capture());
        return ctx.getValue();
    }

    /** Conclusão trivial: o que se mede aqui é o contexto que entrou, não o que saiu. */
    private static class FlowResultOk implements FlowResult {
        @Override public Optional<Object> getOutput() { return Optional.of("{\"ok\":true}"); }
        @Override public ExecutionStatus getStatus() { return ExecutionStatus.COMPLETED; }
        @Override public List<br.com.archflow.model.error.ExecutionError> getErrors() { return List.of(); }
        @Override public br.com.archflow.model.engine.ExecutionMetrics getMetrics() { return null; }
    }
}
