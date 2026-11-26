package br.com.archflow.api.agui;

import br.com.archflow.agent.streaming.ArchflowEvent;
import br.com.archflow.agent.streaming.EventStreamRegistry;
import br.com.archflow.api.agent.mcp.McpAgentHost;
import br.com.archflow.api.config.TenantContext;
import br.com.archflow.api.flow.ErroDoProvedorLegivel;
import br.com.archflow.api.flow.RunInputSeeding;
import br.com.archflow.api.flow.WorkflowDeserializer;
import br.com.archflow.api.web.workflow.WorkflowRuntimeStore;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.core.StateManager;
import br.com.archflow.engine.persistence.FlowRepository;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.flow.FlowState;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static br.com.archflow.api.agui.AgUiEvent.fields;

/**
 * AG-UI-compliant endpoint (ADR-0003 / design-0006): runs a workflow and streams
 * its progress as AG-UI events in a single request, by teeing the existing
 * {@link EventStreamRegistry} through the {@link AgUiEventMapper}. Reuses the
 * engine, the registry (wired in design-0005 step 3) and the execution store —
 * no new streaming infra. The controller owns the RUN_* lifecycle bracket.
 */
@RestController
@RequestMapping("/ag-ui")
public class AgUiController {

    private final WorkflowRuntimeStore store;
    private final WorkflowDeserializer deserializer;
    private final FlowEngine flowEngine;
    private final EventStreamRegistry streamRegistry;
    private final AgUiEventMapper mapper;
    private final StateManager stateManager;
    private final FlowRepository flowRepository;
    private final ObjectMapper json;

    /**
     * Relógio único do batimento, daemon como o da rota de streaming ({@code InvocacaoEmFluxo}): só
     * escreve comentários SSE e não pode segurar o processo no shutdown.
     */
    private final ScheduledThreadPoolExecutor relogio = novoRelogio();
    /** Intervalo do batimento; zero ou negativo desliga. */
    private volatile Duration batimento = Duration.ofSeconds(15);

    public AgUiController(WorkflowRuntimeStore store,
                          WorkflowDeserializer deserializer,
                          FlowEngine flowEngine,
                          EventStreamRegistry streamRegistry,
                          AgUiEventMapper mapper,
                          StateManager stateManager,
                          FlowRepository flowRepository,
                          ObjectMapper jackson2ObjectMapper) {
        this.store = store;
        this.deserializer = deserializer;
        this.flowEngine = flowEngine;
        this.streamRegistry = streamRegistry;
        this.mapper = mapper;
        this.stateManager = stateManager;
        this.flowRepository = flowRepository;
        this.json = jackson2ObjectMapper;
    }

    /**
     * Sem batimento, uma conexão fechada durante um passo longo só é notada na próxima escrita — e
     * um passo de LLM não escreve nada até terminar. É o comentário periódico que revela a
     * desconexão a tempo de cancelar (RCHFLW-54). {@code PT0S} desliga.
     */
    @Autowired(required = false)
    public void setBatimento(@Value("${archflow.agui.heartbeat:PT15S}") Duration batimento) {
        this.batimento = batimento != null ? batimento : Duration.ZERO;
    }

    /** Host de MCP dos nós {@code mcp-agent}; nulo = instalação sem MCP (o nó falha dizendo isso). */
    private volatile McpAgentHost mcpAgentHost;

    /**
     * O mesmo host que o {@code POST /execute} injeta (RCHFLW-48): sem ele, o Executar do editor
     * falhava no nó {@code mcp-agent} com "nenhum McpAgentHost no contexto", e o mesmo fluxo rodava
     * pela lista. Por setter, como o batimento, para não mudar o construtor de quem já o monta.
     */
    @Autowired
    public void setMcpAgentHost(McpAgentHost mcpAgentHost) {
        this.mcpAgentHost = mcpAgentHost;
    }

    @PreDestroy
    public void close() {
        relogio.shutdownNow();
    }

    /** O emissor da resposta. Um ponto só para o teste trocar a conexão por uma que ele controla. */
    SseEmitter novoEmissor() {
        return new SseEmitter(0L); // no server-side timeout; closed on run completion
    }

    @PostMapping(value = "/workflows/{id}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter run(@PathVariable String id, @RequestBody(required = false) RunAgentInput input) {
        SseEmitter sse = novoEmissor();

        // Tenant da requisição, lido aqui, na thread do servlet — a execução segue em outra thread,
        // onde não há requisição. O mesmo valor filtra o workflow (de outro tenant = não encontrado,
        // RCHFLW-73), é o dono do registro da execução e vai para o contexto do motor (RCHFLW-13).
        String tenantId = TenantContext.currentTenantId();
        var workflow = store.getWorkflow(tenantId, id);
        if (workflow == null) {
            Conexao semRun = new Conexao(sse, null);
            semRun.emitir(AgUiEvent.of("RUN_ERROR", fields("message", "workflow not found: " + id)));
            sse.complete();
            return sse;
        }

        var execution = store.createExecution(tenantId, id, workflowName(workflow));
        String runId = String.valueOf(execution.get("id"));        // = flow id, as in /execute
        String threadId = input != null && input.threadId() != null ? input.threadId() : runId;

        Map<String, Object> flowJson = new HashMap<>(workflow);
        flowJson.put("id", runId);
        Flow flow = deserializer.toFlow(flowJson);
        // A retomada busca o fluxo no FlowRepository, como em /execute. Sem o registro, uma
        // execução do editor que parasse num portão de aprovação nunca mais seguia: aprovar
        // consumia o pedido e a retomada falhava sem ninguém ver (RCHFLW-59).
        flowRepository.save(flow);

        ExecutionContext ctx = new DefaultExecutionContext(
                tenantId, "ag-ui", runId,
                MessageWindowChatMemory.builder().maxMessages(20).build());
        seedInput(ctx, input);
        // Depois do input, como no REST: uma variável de entrada com a chave do host não pode apontar
        // o nó para outro servidor MCP.
        McpAgentHost.inject(ctx, mcpAgentHost);

        Conexao conexao = new Conexao(sse, runId);

        // Tee the hub into this response, filtered to this run. The lifecycle
        // listener broadcasts each event twice (flow-scoped + admin-scoped) and
        // both reach global listeners, so dedup by event id. (The tenant-scoped
        // channel is a deliver, not a broadcast: it does not reach this listener.)
        java.util.Set<String> seen = java.util.concurrent.ConcurrentHashMap.newKeySet();
        Consumer<ArchflowEvent> listener = ev -> {
            if (runId.equals(ev.getExecutionId()) && seen.add(ev.getId())) {
                for (AgUiEvent agUi : mapper.toAgUi(ev)) {
                    conexao.emitir(agUi);
                }
            }
        };
        conexao.listener = listener;
        streamRegistry.addGlobalListener(listener);
        // O cliente saiu: Parar do editor (abort do fetch), aba fechada, rede. onCompletion também
        // dispara no nosso próprio complete() do fim do run — a Conexao distingue os dois.
        sse.onCompletion(conexao::clienteSaiu);
        sse.onError(t -> conexao.clienteSaiu());
        sse.onTimeout(conexao::clienteSaiu);

        // Controller owns the RUN_* bracket (it knows threadId/runId).
        conexao.emitir(AgUiEvent.of("RUN_STARTED", fields("threadId", threadId, "runId", runId)));
        conexao.emitir(AgUiEvent.of("STATE_SNAPSHOT",
                fields("snapshot", Map.of("status", "RUNNING", "executionPaths", List.of()))));

        // A conexão caiu antes de o motor receber o fluxo: o cancelar não teria o que parar (o motor
        // ainda não o conhece) e a execução rodaria inteira sem ninguém olhando. Não começa.
        if (conexao.cancelada.get()) {
            streamRegistry.removeGlobalListener(listener);
            store.completeExecution(runId, "CANCELLED", null);
            sse.complete();
            return sse;
        }

        conexao.iniciarBatimento();
        flowEngine.execute(flow, ctx).whenComplete((result, err) -> {
            // Daqui em diante, uma escrita que falha ou o onCompletion do nosso complete() não é
            // pedido de parar: a execução já terminou (e, num portão de aprovação, a retomada pode
            // estar rodando sob o mesmo id — cancelar a mataria).
            conexao.encerrar();
            synchronized (conexao) {
                // Final shared-state snapshot: the materialized dynamic tree (D9).
                if (err == null) {
                    FlowState state = stateManager.loadState(runId);
                    if (state != null && state.getExecutionPaths() != null) {
                        conexao.escrever(AgUiEvent.of("STATE_SNAPSHOT", fields("snapshot",
                                Map.of("status", statusOf(result, null),
                                        "executionPaths", state.getExecutionPaths()))));
                    }
                }
                // A mensagem vai para a tela do editor: a do provedor de LLM sai legível, sem o corpo HTTP nem
                // o user_id da conta da plataforma (RCHFLW-130). O registro passa pela mesma regra no
                // completeExecution; o texto original fica no log do StepRecordingListener.
                AgUiEvent terminal = err != null
                        ? AgUiEvent.of("RUN_ERROR", fields("runId", runId,
                                "message", ErroDoProvedorLegivel.paraUsuario(errorOf(err))))
                        : AgUiEvent.of("RUN_FINISHED", fields("threadId", threadId, "runId", runId,
                                "result", Map.of("status", statusOf(result, null))));
                conexao.escrever(terminal);
            }
            streamRegistry.removeGlobalListener(listener);
            store.completeExecution(runId, statusOf(result, err), errorOf(err));
            sse.complete();
        });

        return sse;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Uma resposta AG-UI em andamento. Serializa as escritas (o motor pode transmitir de threads
     * paralelas) e transforma a saída do cliente em cancelamento da execução (RCHFLW-54).
     *
     * <p>Antes só {@code onError}/{@code onTimeout} cancelavam e a falha de escrita era engolida: o
     * servidor não sabia do Parar até escrever o próximo evento, e durante um passo longo nada era
     * escrito. O passo ia até o fim, com custo e efeitos, e a execução terminava COMPLETED.</p>
     */
    private final class Conexao {
        private final SseEmitter sse;
        /** {@code null} quando não há execução (workflow não encontrado). */
        private final String runId;
        /** A execução terminou: o que vier depois (inclusive o nosso complete) não é desconexão. */
        private final AtomicBoolean encerrada = new AtomicBoolean();
        /** O cliente saiu enquanto a execução existia. */
        private final AtomicBoolean cancelada = new AtomicBoolean();
        private volatile Consumer<ArchflowEvent> listener;
        private volatile ScheduledFuture<?> tarefaBatimento;

        Conexao(SseEmitter sse, String runId) {
            this.sse = sse;
            this.runId = runId;
        }

        void emitir(AgUiEvent event) {
            synchronized (this) {
                escrever(event);
            }
        }

        /** Escreve sob o lock de quem chama; uma escrita que falha é o cliente que saiu. */
        void escrever(AgUiEvent event) {
            try {
                sse.send(SseEmitter.event().data(json.writeValueAsString(event), MediaType.APPLICATION_JSON));
            } catch (Exception clienteSaiu) {
                clienteSaiu();
            }
        }

        void iniciarBatimento() {
            long passo = batimento.toMillis();
            if (passo <= 0) {
                return;
            }
            tarefaBatimento = relogio.scheduleWithFixedDelay(this::batimento, passo, passo, TimeUnit.MILLISECONDS);
        }

        private void batimento() {
            synchronized (this) {
                if (encerrada.get()) {
                    return;
                }
                try {
                    // Comentário SSE: o cliente (agui-client) só lê frames com "data:", então ignora.
                    sse.send(SseEmitter.event().comment("batimento"));
                } catch (Exception clienteSaiu) {
                    clienteSaiu();
                }
            }
        }

        void encerrar() {
            encerrada.set(true);
            pararBatimento();
        }

        private void pararBatimento() {
            ScheduledFuture<?> tarefa = tarefaBatimento;
            if (tarefa != null) {
                tarefa.cancel(false);
            }
        }

        /**
         * O cliente saiu antes do fim: cancela a execução no motor, o mesmo pedido do
         * {@code POST /api/executions/{id}/cancel}. O passo em curso termina (cooperativo, sem
         * interromper a thread), o resultado dele é descartado, nenhum passo seguinte roda e o
         * {@code whenComplete} grava CANCELLED, o status que o motor devolve.
         */
        void clienteSaiu() {
            if (runId == null || encerrada.get() || !cancelada.compareAndSet(false, true)) {
                return;
            }
            pararBatimento();
            Consumer<ArchflowEvent> l = listener;
            if (l != null) {
                streamRegistry.removeGlobalListener(l);
            }
            try {
                flowEngine.cancel(runId);
            } catch (RuntimeException naoAtiva) {
                // Já terminou. Antes do execute o run() checa a flag; entre o execute e o registro
                // no motor (espera do semáforo) o motor guarda o pedido e a execução não roda passo.
            }
        }
    }

    private static ScheduledThreadPoolExecutor novoRelogio() {
        var executor = new ScheduledThreadPoolExecutor(1, tarefa -> {
            Thread t = new Thread(tarefa, "batimento-ag-ui");
            t.setDaemon(true);
            return t;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    static void seedInput(ExecutionContext ctx, RunAgentInput input) {
        if (input == null) {
            return;
        }
        // Mesma regra do POST /api/workflows/{id}/execute (ver RunInputSeeding), também sem state: o
        // Executar do editor com a entrada vazia não manda state, e o input tem de ser "" (RCHFLW-101).
        RunInputSeeding.seedVariables(ctx, input.state());
        if (input.messages() != null && !input.messages().isEmpty()) {
            Object content = input.messages().get(input.messages().size() - 1).get("content");
            if (content != null) {
                ctx.set("input", content);
            }
        }
    }

    private static String workflowName(Map<String, Object> workflow) {
        Object meta = workflow.get("metadata");
        if (meta instanceof Map<?, ?> m && m.get("name") != null) {
            return m.get("name").toString();
        }
        return "Untitled";
    }

    private static String statusOf(FlowResult result, Throwable err) {
        if (err != null || result == null) {
            return "FAILED";
        }
        return result.getStatus() != null ? result.getStatus().name() : "COMPLETED";
    }

    private static String errorOf(Throwable err) {
        if (err == null) {
            return null;
        }
        Throwable cause = err;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.toString();
    }
}
