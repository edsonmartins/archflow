package br.com.archflow.api.flow;

import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.engine.ExecutionKeys;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepConnection;
import br.com.archflow.model.flow.StepResult;
import br.com.archflow.model.flow.StepType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Step {@code APPROVAL}: o produtor que faltava para o gate human-in-the-loop.
 *
 * <p>O {@link FlowEngine} já implementava um gate durável — {@code requestApproval}
 * marca {@code AWAITING_APPROVAL}, persiste o estado e suspende a travessia;
 * {@code submitApproval} valida o id e retoma a partir do estado persistido,
 * sobrevivendo a restart. Só que <b>nada chamava {@code requestApproval}</b>:
 * nenhum tipo de step, nenhum endpoint. O gate era inalcançável.
 *
 * <p>Este step fecha esse circuito. Ele monta o pedido a partir do config do nó e
 * chama {@code requestApproval}, devolvendo {@link SimpleStepResult#suspended}: a
 * travessia para nele, ele <b>não</b> entra nos passos concluídos e o canvas, o
 * registro da execução e o AG-UI o mostram aguardando — não concluído (RCHFLW-32).
 * Na retomada ele roda de novo, encontra a decisão deste portão e conclui com
 * {@code {decision}} — ou, quando quem decidiu editou a proposta ({@code EDITED}), com a proposta
 * editada, que vira também a entrada do passo seguinte.
 *
 * <p>Config do nó — as chaves que o designer grava, com as antigas como fallback:
 * <ul>
 *   <li>{@code approvalTitle} ({@code title}) — título do pedido na fila;</li>
 *   <li>{@code approvalDescription} ({@code description}) — o que o revisor decide;</li>
 *   <li>{@code approvalProposal} ({@code proposal}) — o que é exibido para avaliação. O
 *       designer grava texto: JSON válido vira objeto, outro texto vai como está. Ausente
 *       ou vazio, vale a saída do step anterior (a variável {@code input});</li>
 *   <li>{@code approvalTimeoutMinutes} — prazo deste pedido; ausente, vale o global;</li>
 *   <li>{@code approvalOnTimeout} — só {@code reject}. Qualquer outro valor é ignorado com
 *       WARN: vencido o prazo, a decisão é sempre REJECTED.</li>
 * </ul>
 *
 * <p>Por que o nó não pode escolher "aprovar no timeout": vencer o prazo quer dizer que
 * ninguém olhou, e "ninguém olhou" nunca pode virar "pode executar". Quem desenha o fluxo
 * não é quem responde pela governança — como as preferências de provedor, é uma regra da
 * implantação que nenhum nó desliga. O designer oferecia "Aprovação automática" e o motor
 * sempre rejeitava: a opção prometia o que, por segurança, não pode existir (RCHFLW-60).
 *
 * <p>Após a decisão, os steps seguintes leem
 * {@link ExecutionKeys#APPROVAL_DECISION} (para ramificar com
 * {@code ${__archflow.approvalDecision} == APPROVED}) e
 * {@link ExecutionKeys#APPROVAL_PAYLOAD} (a proposta editada pelo humano, se houve).
 *
 * <p>O engine chega por {@link Supplier} porque o grafo de beans vai
 * {@code FlowEngine → FlowRepository → WorkflowDeserializer → FlowStepFactory}:
 * uma injeção direta fecharia o ciclo.
 */
public final class HumanApprovalStep implements FlowStep {

    private static final Logger log = LoggerFactory.getLogger(HumanApprovalStep.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A única ação de timeout que existe; ver o javadoc da classe. */
    static final String ON_TIMEOUT_REJECT = "reject";

    private final String id;
    private final List<StepConnection> connections;
    private final Map<String, Object> config;
    private final Supplier<FlowEngine> engineSupplier;

    public HumanApprovalStep(String id, List<StepConnection> connections,
                             Map<String, Object> config, Supplier<FlowEngine> engineSupplier) {
        this.id = id;
        this.connections = connections == null ? List.of() : List.copyOf(connections);
        this.config = config == null ? Map.of() : config;
        this.engineSupplier = engineSupplier;
    }

    @Override public String getId() { return id; }
    @Override public StepType getType() { return StepType.APPROVAL; }
    @Override public List<StepConnection> getConnections() { return connections; }

    @Override
    public CompletableFuture<StepResult> execute(ExecutionContext context) {
        long start = System.nanoTime();

        // Retomada: submitApproval já gravou a decisão e o engine reexecuta a
        // travessia — e este step, que suspendeu sem concluir, roda de novo. Ele
        // não pode pedir aprovação outra vez (seria um laço infinito de gates):
        // apenas repassa a decisão adiante.
        //
        // Mas só a decisão DESTE portão: a chave é única por execução, e um segundo
        // portão do mesmo fluxo via a decisão do primeiro e passava direto, sem pedido
        // na fila e "aprovado" por quem nunca o viu (RCHFLW-47). Daí a comparação com
        // o step que pediu a aprovação.
        String decision = context.get(ExecutionKeys.APPROVAL_DECISION)
                .map(String::valueOf).filter(d -> !d.isBlank()).orElse(null);
        String pendingRequest = context.get(ExecutionKeys.APPROVAL_REQUEST_ID)
                .map(String::valueOf).orElse("");
        String decidedStep = context.get(ExecutionKeys.APPROVAL_STEP_ID)
                .map(String::valueOf).orElse(null);
        if (decision != null && pendingRequest.isBlank() && id.equals(decidedStep)) {
            Object edited = editedProposal(context);
            if (edited != null) {
                // Editar antes de aprovar substitui a proposta: o que segue é a versão de quem
                // decidiu, como saída deste passo e entrada do próximo — do mesmo jeito que um
                // ComponentStep entrega a sua. Antes devolvia só {decision}, e o valor editado não
                // chegava a passo nenhum (RCHFLW-121).
                log.info("Step {} retomado com a proposta editada por quem aprovou", id);
                context.set(id, edited);
                context.set(ComponentStep.INPUT_KEY, edited);
                return CompletableFuture.completedFuture(SimpleStepResult.ok(id, edited, elapsedMs(start)));
            }
            log.info("Step {} retomado com decisão {}", id, decision);
            return CompletableFuture.completedFuture(
                    SimpleStepResult.ok(id, Map.of("decision", decision), elapsedMs(start)));
        }

        FlowEngine engine = engineSupplier != null ? engineSupplier.get() : null;
        if (engine == null) {
            return CompletableFuture.completedFuture(SimpleStepResult.failed(
                    id, "APPROVAL step requires a FlowEngine", elapsedMs(start)));
        }

        Object proposal = proposalOf(context);

        // Cada chave abaixo descreve só ESTE pedido. Sem valor próprio, um pedido não pode
        // exibir o título, a descrição ou o prazo do portão anterior do mesmo fluxo.
        setOrClear(context, ExecutionKeys.APPROVAL_TITLE, text("approvalTitle", "title"));
        setOrClear(context, ExecutionKeys.APPROVAL_DESCRIPTION, text("approvalDescription", "description"));
        Duration timeout = timeoutOf();
        setOrClear(context, ExecutionKeys.APPROVAL_TIMEOUT, timeout != null ? timeout.toString() : null);
        warnOnUnsupportedTimeoutAction();

        String flowId = context.getState() != null ? context.getState().getFlowId() : null;
        if (flowId == null || flowId.isBlank()) {
            return CompletableFuture.completedFuture(SimpleStepResult.failed(
                    id, "APPROVAL step requires a flow state with an id", elapsedMs(start)));
        }

        try {
            String requestId = engine.requestApproval(flowId, id, proposal);
            log.info("Fluxo {} suspenso para aprovação humana no step {} (requestId={})",
                    flowId, id, requestId);
            return CompletableFuture.completedFuture(
                    SimpleStepResult.suspended(id, Map.of("requestId", requestId), elapsedMs(start)));
        } catch (RuntimeException e) {
            log.error("Falha ao solicitar aprovação no step {} do fluxo {}", id, flowId, e);
            return CompletableFuture.completedFuture(SimpleStepResult.failed(
                    id, "Failed to request approval: " + e.getMessage(), elapsedMs(start)));
        }
    }

    /**
     * A proposta digitada no nó, ou a saída do step anterior. O designer grava a proposta como
     * texto ({@code "{\"desconto\": 15}"}); exibida assim, o revisor veria uma string com aspas
     * escapadas no lugar do objeto. Texto que não é JSON vai como está: é o que alguém digitou, e
     * o revisor ainda consegue lê-lo.
     */
    private Object proposalOf(ExecutionContext context) {
        String key = config.containsKey("approvalProposal") ? "approvalProposal"
                : config.containsKey("proposal") ? "proposal" : null;
        Object configured = key != null ? config.get(key) : null;
        if (configured instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.isEmpty()) {
                configured = null;
            } else {
                try {
                    configured = JSON.readValue(trimmed, Object.class);
                } catch (Exception notJson) {
                    log.warn("Proposta do step {} não é JSON válido; exibida como texto", id);
                    configured = s;
                }
            }
        }
        if (configured != null) {
            return configured;
        }
        // Sem proposta própria (ou vazia), o revisor avalia o que o passo anterior produziu.
        return context.get(ComponentStep.INPUT_KEY).orElse(null);
    }

    /**
     * A proposta editada na decisão deste portão, ou {@code null} quando a decisão não foi uma
     * edição. Vem da última entrada do histórico durável para este passo, e não de
     * {@link ExecutionKeys#APPROVAL_PAYLOAD}: aquela chave também carrega os dados de um formulário
     * de conversa, que aprovam sem substituir proposta nenhuma — só a fila grava {@code EDITED}.
     */
    private Object editedProposal(ExecutionContext context) {
        Object history = context.get(ExecutionKeys.APPROVAL_HISTORY).orElse(null);
        if (!(history instanceof java.util.Collection<?> all)) {
            return null;
        }
        List<?> entries = List.copyOf(all);
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i) instanceof Map<?, ?> entry && id.equals(String.valueOf(entry.get("stepId")))) {
                return "EDITED".equals(String.valueOf(entry.get("decision"))) ? entry.get("editedPayload") : null;
            }
        }
        return null;
    }

    /** Primeira das chaves com valor, como texto; {@code null} quando nenhuma tem. */
    private String text(String... keys) {
        for (String key : keys) {
            Object value = config.get(key);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    /**
     * O prazo do nó, ou {@code null} para usar o global. Valor inválido ou não positivo também
     * cai no global — com WARN, porque quem configurou esperava outro prazo.
     */
    private Duration timeoutOf() {
        Object raw = config.get("approvalTimeoutMinutes");
        if (raw == null || String.valueOf(raw).isBlank()) {
            return null;
        }
        try {
            double minutes = raw instanceof Number n
                    ? n.doubleValue()
                    : Double.parseDouble(String.valueOf(raw).trim());
            if (minutes > 0 && Double.isFinite(minutes)) {
                return Duration.ofSeconds(Math.round(minutes * 60));
            }
        } catch (NumberFormatException ignored) {
            // cai no WARN abaixo
        }
        log.warn("Timeout do step de aprovação {} inválido ({}); vale o prazo global", id, raw);
        return null;
    }

    private void warnOnUnsupportedTimeoutAction() {
        Object action = config.get("approvalOnTimeout");
        if (action != null && !ON_TIMEOUT_REJECT.equalsIgnoreCase(String.valueOf(action).trim())) {
            log.warn("Step de aprovação {} pede '{}' no timeout; ignorado — um pedido vencido é sempre "
                    + "REJECTED (ninguém olhou não vira aprovação)", id, action);
        }
    }

    /**
     * Grava o valor do pedido, ou limpa o do portão anterior. O mapa de variáveis não aceita
     * remoção nem null, então "limpo" é "" — a mesma convenção do pedido consumido.
     */
    private static void setOrClear(ExecutionContext context, String key, String value) {
        if (value != null) {
            context.set(key, value);
        } else if (context.get(key).isPresent()) {
            context.set(key, "");
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
