package br.com.archflow.api.flow;

import br.com.archflow.api.agent.mcp.McpAgentComponent;
import br.com.archflow.api.agent.mcp.McpAgentHost;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.model.config.LLMConfigPatch;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.engine.ExecutionKeys;
import br.com.archflow.model.enums.StepStatus;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepConnection;
import br.com.archflow.model.flow.StepResult;
import br.com.archflow.model.flow.StepType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * O nó {@code mcp-agent} de um fluxo, com o gate humano do laço segurando <b>o fluxo</b> (RCHFLW-185).
 *
 * <h2>O que acontecia</h2>
 *
 * <p>Uma tool sob {@code aprovacaoHumana} suspendia o laço antes de executá-la — isso sempre funcionou
 * —, mas o passo devolvia a suspensão como uma saída comum ({@code suspended: true}). O motor via um
 * passo concluído: a execução terminava COMPLETED, os nós seguintes rodavam sobre um texto vazio, e a
 * aprovação ia para a fila do laço, sem prazo, sem a execução e sem gravar quem decidiu. Aprovada, a
 * tool rodava, mas a resposta não voltava ao fluxo que a pediu — ele já tinha acabado.</p>
 *
 * <h2>O que este passo faz</h2>
 *
 * <p>Reusa os dois mecanismos que já existem, sem um terceiro. O laço continua suspendendo do jeito
 * dele ({@code ToolApprovalPolicy} → {@code McpAgentState} no store, antes de executar a tool); o passo
 * então abre o portão da execução pelo {@link FlowEngine#requestApproval}, como o
 * {@link HumanApprovalStep}: AWAITING_APPROVAL durável, a travessia para aqui, o pedido entra na fila
 * com o prazo e o nome do fluxo, e o motor grava decisor e comentário sob o lock da decisão. Aprovado,
 * o motor retoma a travessia, este passo roda de novo e o laço continua de onde parou
 * ({@code McpAgentRunner.resume}) — a resposta dele é a saída do passo e a entrada do seguinte.
 * Recusado (por alguém ou pelo prazo), o fluxo para: "ninguém autorizou" nunca vira "segue".</p>
 *
 * <p>O laço suspenso fica marcado como do fluxo ({@code Retomada.execucaoDoFluxo}) para não aparecer
 * também na fila avulsa do laço: seriam dois pedidos para a mesma tool, e decidir o avulso executaria a
 * tool com o fluxo parado.</p>
 *
 * <p>O laço guardado sai do store em todo caminho em que o fluxo não vai mais retomá-lo: recusa e prazo
 * (pela fila), portão que não abriu e retomada que falhou antes de consumi-lo (aqui), execução que
 * terminou de outro jeito com o laço ainda marcado, como um cancelamento ({@link LacoDoFluxoListener}).
 * Ficar nele seria lixo durável com o transcript e os argumentos da tool, fora de todas as filas.</p>
 *
 * <p>A execução do componente continua sendo a do {@link ComponentStep} (interceptors, erro como falha
 * do passo, saída em {@code input}): este passo só decide o que fazer com uma suspensão.</p>
 */
public final class McpAgentStep implements FlowStep {

    private static final Logger log = LoggerFactory.getLogger(McpAgentStep.class);

    /**
     * O laço que o portão corrente da execução segura (o {@code requestId} do laço, não o do portão).
     *
     * <p>Uma chave só, e não uma por nó: o motor tem um pedido pendente por execução. Persistida com o
     * estado — é por ela que a retomada acha o laço depois de um restart, e que a fila descarta o laço
     * de um portão recusado. Fica {@code ""} quando não há laço esperando (o mapa de variáveis não aceita
     * remoção).</p>
     */
    public static final String LACO_PENDENTE = ExecutionKeys.RESERVED_PREFIX + "approvalToolLoop";

    private final ComponentStep componente;
    private final Supplier<FlowEngine> engineSupplier;

    public McpAgentStep(ComponentStep componente, Supplier<FlowEngine> engineSupplier) {
        this.componente = java.util.Objects.requireNonNull(componente, "componente");
        this.engineSupplier = engineSupplier;
    }

    @Override public String getId() { return componente.getId(); }
    @Override public StepType getType() { return componente.getType(); }
    @Override public List<StepConnection> getConnections() { return componente.getConnections(); }
    @Override public LLMConfigPatch getLLMPatch() { return componente.getLLMPatch(); }

    /** O passo do componente, para quem inspeciona o nó ({@link NodeSupport}). */
    ComponentStep componente() {
        return componente;
    }

    @Override
    public CompletableFuture<StepResult> execute(ExecutionContext context) {
        String flowId = context.getState() != null ? context.getState().getFlowId() : null;
        FlowEngine engine = flowId == null || flowId.isBlank() || engineSupplier == null
                ? null : engineSupplier.get();
        if (engine == null) {
            // Sem execução durável não há portão onde segurar: vale o contrato do componente, a
            // suspensão explícita na saída (design 0007 §A.2) — o mesmo de antes deste passo.
            return componente.execute(context);
        }

        String id = getId();
        String lacoARetomar = lacoAprovadoDeste(context, id);
        Map<String, Object> editados = null;
        if (lacoARetomar != null) {
            Object edicao = HumanApprovalStep.editedProposal(context, id);
            if (edicao != null && !(edicao instanceof Map<?, ?>)) {
                // A fila recusa isto antes de decidir; um estado assim veio de outro caminho. Executar
                // a tool com algo que não são argumentos seria executar o que ninguém aprovou.
                return CompletableFuture.completedFuture(SimpleStepResult.failed(id,
                        "A edição aprovada para a tool deste passo não é um objeto de argumentos", 0L));
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> convertidos = (Map<String, Object>) edicao;
            editados = convertidos;
            // Consumido antes de retomar: se o laço suspender de novo, o portão novo grava o laço novo.
            context.set(LACO_PENDENTE, "");
            log.info("Passo {} do fluxo {} retomado: o laço {} continua com a decisão do portão",
                    id, flowId, lacoARetomar);
        }

        Optional<Object> entradaAnterior = context.get(ComponentStep.INPUT_KEY);
        StepResult resultado;
        context.set(McpAgentComponent.CTX_PORTAO_DO_FLUXO,
                new McpAgentComponent.PortaoDoFluxo(flowId, lacoARetomar, editados));
        try {
            resultado = componente.execute(context).join();
        } finally {
            // Instrução desta chamada, não da execução: um segundo nó de agente não pode herdá-la.
            context.set(McpAgentComponent.CTX_PORTAO_DO_FLUXO, "");
        }
        if (lacoARetomar != null && resultado.getStatus() != StepStatus.COMPLETED) {
            // A retomada aprovada falhou antes de o runner consumir o laço (host ou cliente MCP
            // indisponível, por exemplo). LACO_PENDENTE já foi limpo: ninguém mais acha este laço, e
            // uma nova tentativa do passo roda o laço do zero e pede a aprovação de novo.
            descartarLaco(context, lacoARetomar, flowId);
        }

        Map<?, ?> saida = resultado.getStatus() == StepStatus.COMPLETED
                ? resultado.getOutput().filter(Map.class::isInstance).map(Map.class::cast).orElse(null)
                : null;
        if (saida == null || !Boolean.TRUE.equals(saida.get(McpAgentComponent.SAIDA_SUSPENSO))) {
            return CompletableFuture.completedFuture(resultado);
        }
        return CompletableFuture.completedFuture(segurar(context, engine, flowId, saida, entradaAnterior,
                resultado));
    }

    /**
     * O laço suspendeu: abre o portão da execução e para a travessia aqui.
     *
     * <p>A saída parcial não pode virar a entrada de ninguém — o {@link ComponentStep} já a escreveu em
     * {@code input}, e a entrada de antes volta. A proposta do pedido são os argumentos da tool, como a
     * fila do laço mostrava: é sobre eles que se decide, e é o que um {@code EDITED} substitui.</p>
     */
    private StepResult segurar(ExecutionContext context, FlowEngine engine, String flowId, Map<?, ?> saida,
                               Optional<Object> entradaAnterior, StepResult suspenso) {
        String id = getId();
        String laco = String.valueOf(saida.get(McpAgentComponent.SAIDA_APROVACAO));
        String tool = String.valueOf(saida.get(McpAgentComponent.SAIDA_TOOL_PENDENTE));
        entradaAnterior.ifPresent(entrada -> context.set(ComponentStep.INPUT_KEY, entrada));
        context.set(LACO_PENDENTE, laco);
        // Cada chave descreve só ESTE pedido, como no nó de aprovação; o prazo é o global.
        HumanApprovalStep.setOrClear(context, ExecutionKeys.APPROVAL_TITLE, null);
        HumanApprovalStep.setOrClear(context, ExecutionKeys.APPROVAL_DESCRIPTION,
                "Execução da tool " + tool + " aguardando aprovação");
        HumanApprovalStep.setOrClear(context, ExecutionKeys.APPROVAL_TIMEOUT, null);
        Object argumentos = saida.get(McpAgentComponent.SAIDA_ARGUMENTOS_PENDENTES);
        try {
            String requestId = engine.requestApproval(flowId, id, argumentos != null ? argumentos : Map.of());
            log.info("Fluxo {} suspenso no passo {}: a tool {} aguarda aprovação (pedido {}, laço {})",
                    flowId, id, tool, requestId, laco);
            return SimpleStepResult.suspended(id, suspenso.getOutput().orElse(null),
                    duracao(suspenso));
        } catch (RuntimeException e) {
            // Sem portão, o passo não pode concluir com uma tool esperando: falhar mantém a execução
            // visível e impede que os nós seguintes rodem sobre uma resposta que não existe.
            log.error("Falha ao abrir o portão da tool {} no passo {} do fluxo {}", tool, id, flowId, e);
            context.set(LACO_PENDENTE, "");
            // O runner já gravou o laço, marcado como deste fluxo — fora da fila avulsa e sem portão
            // que o decida. Sem apagá-lo aqui, ninguém mais o acharia.
            descartarLaco(context, laco, flowId);
            return SimpleStepResult.failed(id, "Failed to request approval for tool " + tool + ": "
                    + e.getMessage(), duracao(suspenso));
        }
    }

    /**
     * O laço a retomar, quando a execução está voltando de uma aprovação <b>deste</b> passo: o portão
     * dele foi decidido (o pedido foi consumido e a decisão é deste passo) e há um laço guardado.
     * {@code null} numa execução nova do passo — inclusive quando a decisão em mãos é de outro portão do
     * mesmo fluxo, como no {@link HumanApprovalStep} (RCHFLW-47).
     */
    private static String lacoAprovadoDeste(ExecutionContext context, String id) {
        String laco = texto(context, LACO_PENDENTE);
        String decisao = texto(context, ExecutionKeys.APPROVAL_DECISION);
        String pendente = texto(context, ExecutionKeys.APPROVAL_REQUEST_ID);
        String passoDecidido = texto(context, ExecutionKeys.APPROVAL_STEP_ID);
        if (laco == null || !"APPROVED".equals(decisao) || pendente != null || !id.equals(passoDecidido)) {
            return null;
        }
        return laco;
    }

    /**
     * Apaga o laço {@code laco} que esta execução segurava (só se for dela — ver
     * {@link br.com.archflow.api.agent.mcp.McpAgentRunner#descartarDoFluxo}). Nunca lança: o desfecho
     * do passo ou do fluxo já está decidido, e um store indisponível não pode trocá-lo por outro.
     */
    static void descartarLaco(ExecutionContext context, String laco, String flowId) {
        if (laco == null || laco.isBlank()) {
            return;
        }
        try {
            McpAgentHost.from(context).ifPresent(host -> {
                if (host.runner().descartarDoFluxo(laco, flowId)) {
                    log.info("Laço {} do fluxo {} descartado: o fluxo não vai mais retomá-lo", laco, flowId);
                }
            });
        } catch (RuntimeException e) {
            log.warn("Não foi possível descartar o laço {} do fluxo {}: {}", laco, flowId, e.getMessage());
        }
    }

    private static long duracao(StepResult resultado) {
        return resultado.getMetrics() != null ? resultado.getMetrics().executionTime() : 0L;
    }

    private static String texto(ExecutionContext context, String chave) {
        return context.get(chave).map(String::valueOf).filter(v -> !v.isBlank()).orElse(null);
    }
}
