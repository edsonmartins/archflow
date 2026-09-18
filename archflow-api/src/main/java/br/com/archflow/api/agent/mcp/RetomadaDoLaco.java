package br.com.archflow.api.agent.mcp;

import br.com.archflow.langchain4j.mcp.McpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * Quem traz de volta um laço de agente suspenso por decisão humana.
 *
 * <h2>A metade que faltava</h2>
 *
 * <p>O gate humano <b>dentro</b> do laço existia por inteiro de um lado só: o laço suspendia antes
 * de executar a tool, gravava {@link McpAgentState} durável e devolvia o pedido de aprovação. Do
 * outro lado, nada — {@code McpAgentRunner.resume} não tinha <b>um único chamador</b> em todo o
 * runtime. Um laço suspenso ficava órfão: o estado permanecia no store, ninguém o listava, ninguém
 * decidia, e a tool nunca era executada nem recusada.</p>
 *
 * <h2>Retomar com as mesmas regras</h2>
 *
 * <p>O que mais importa aqui é <b>não afrouxar</b> na volta. As políticas do laço são funções, e
 * função não sobrevive a um restart; então elas voltam dos dados gravados em
 * {@link McpAgentState.Retomada}: a mesma allowlist, o mesmo gate, o mesmo teto de voltas, o mesmo
 * servidor MCP. Reconstruir com {@code allowAll} seria transformar o gate — que existe para
 * <i>restringir</i> — na porta de entrada de tudo o que o agente não podia fazer.</p>
 *
 * <p>Por isso também o laço recusa suspender sem esses dados: melhor falhar na hora do que gravar
 * um estado que ninguém consegue retomar.</p>
 */
public class RetomadaDoLaco {

    private static final Logger log = LoggerFactory.getLogger(RetomadaDoLaco.class);

    private final McpAgentStateStore store;
    private final McpAgentHost host;
    private final List<OuvinteDaRetomada> ouvintes;

    public RetomadaDoLaco(McpAgentStateStore store, McpAgentHost host) {
        this(store, host, List.of());
    }

    public RetomadaDoLaco(McpAgentStateStore store, McpAgentHost host,
                          List<OuvinteDaRetomada> ouvintes) {
        this.store = Objects.requireNonNull(store, "store");
        this.host = Objects.requireNonNull(host, "host");
        this.ouvintes = ouvintes == null ? List.of() : List.copyOf(ouvintes);
    }

    /**
     * Uma chamada de tool esperando decisão.
     *
     * @param requestId   o id que o decisor referencia
     * @param runId       a execução suspensa
     * @param toolName    a tool que o modelo quer chamar
     * @param argumentos  os argumentos que ele emitiu, já validados contra o schema
     * @param suspensoEm  quando o laço parou
     */
    public record Pendencia(String requestId, String tenantId, String runId, String toolName,
                            Map<String, Object> argumentos, Instant suspensoEm) {

        static Pendencia de(McpAgentState estado) {
            return new Pendencia(estado.pending().requestId(), estado.tenantId(), estado.runId(),
                    estado.pending().toolName(), estado.pending().arguments(), estado.suspendedAt());
        }
    }

    /** O que saiu da retomada: o laço pode ter concluído, suspendido de novo ou encerrado. */
    public record Conclusao(McpAgentState estado, McpAgentRunner.Result resultado,
                            ContadorDeUso uso) {
    }

    /**
     * Avisado quando um laço retomado termina — é por aqui que um produto devolve o resultado a
     * quem pediu a execução (no VendaX, o result ao Core).
     */
    @FunctionalInterface
    public interface OuvinteDaRetomada {
        void concluida(Conclusao conclusao);
    }

    /** Os laços deste tenant esperando decisão. */
    public List<Pendencia> pendentes(String tenantId) {
        return store.findPendingByTenant(tenantId).stream()
                .filter(e -> e.pending() != null)
                .map(Pendencia::de)
                .toList();
    }

    public Optional<Pendencia> detalhe(String requestId) {
        return store.findByRequestId(requestId)
                .filter(e -> e.pending() != null)
                .map(Pendencia::de);
    }

    /**
     * Aplica a decisão e continua o laço de onde ele parou.
     *
     * @param aprovado           {@code false} devolve a recusa ao modelo, que pode propor outra coisa
     * @param argumentosEditados argumentos ajustados pelo humano, ou {@code null} para usar os que o
     *                           modelo emitiu
     * @throws NoSuchElementException se o id não corresponde a nenhum laço suspenso
     */
    public Conclusao decidir(String requestId, boolean aprovado,
                             Map<String, Object> argumentosEditados) {
        McpAgentState estado = store.findByRequestId(requestId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Nenhum laço suspenso para a aprovação " + requestId));
        McpAgentState.Retomada retomada = estado.retomada();
        if (retomada == null) {
            // Estado gravado antes de a retomada existir: não há como reconstruir a allowlist, e
            // adivinhá-la é o que esta classe existe para não fazer.
            throw new IllegalStateException("O laço " + estado.runId() + " foi suspenso por uma "
                    + "versão anterior, sem dados de retomada: não há como reconstruir as políticas "
                    + "da execução. Recuse a aprovação e acione o agente de novo.");
        }

        ContadorDeUso uso = new ContadorDeUso();
        McpClient client = host.clientFor(estado.tenantId(), retomada.serverRef());
        McpAgentRunner.Result resultado = host.runner().resume(requestId, aprovado,
                argumentosEditados, client, opcoesDe(retomada, uso));

        log.info("Laço {} retomado ({}): {}", estado.runId(),
                aprovado ? "aprovado" : "recusado",
                resultado.isSuspended() ? "suspendeu de novo" : "concluiu");

        Conclusao conclusao = new Conclusao(estado, resultado, uso);
        if (!resultado.isSuspended()) {
            avisar(conclusao);
        }
        return conclusao;
    }

    /**
     * As opções da execução original, reconstruídas dos dados gravados.
     *
     * <p>{@code toolsPermitidas} nulo significa "todas" — é diferente de conjunto vazio, que é
     * "nenhuma". Confundir os dois aqui abriria ou fecharia o agente inteiro.</p>
     */
    private static McpAgentRunner.Options opcoesDe(McpAgentState.Retomada retomada,
                                                   ContadorDeUso uso) {
        return new McpAgentRunner.Options(
                retomada.toolsPermitidas() == null
                        ? ToolAccessPolicy.allowAll()
                        : ToolAccessPolicy.allowOnly(retomada.toolsPermitidas()),
                ToolTrustPolicy.untrustedByDefault(),
                ToolApprovalPolicy.requiringFor(retomada.toolsComAprovacao()),
                retomada.maxIteracoes(),
                null, null,
                retomada.saidaPorTool(),
                retomada.tier())
                .encerrandoEm(retomada.codigosQueEncerram())
                .comUso(uso)
                .comRetomada(retomada);
    }

    /** Um ouvinte que falha não pode desfazer uma retomada que já aconteceu. */
    private void avisar(Conclusao conclusao) {
        for (OuvinteDaRetomada ouvinte : ouvintes) {
            try {
                ouvinte.concluida(conclusao);
            } catch (RuntimeException e) {
                log.error("Ouvinte da retomada do laço {} falhou: {}",
                        conclusao.estado().runId(), e.getMessage(), e);
            }
        }
    }
}
