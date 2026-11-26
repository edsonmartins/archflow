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

    /**
     * Os laços avulsos deste tenant esperando decisão; {@code tenantId} nulo, em branco ou
     * {@code "all"} atravessa todos os tenants, como a fila do fluxo (RCHFLW-141).
     *
     * <p>O laço de um nó {@code mcp-agent} fica de fora: quem o segura é o portão da execução, que
     * já está na fila com o prazo, o fluxo e o decisor gravado. Listá-lo aqui também daria dois
     * pedidos para a mesma tool — e decidir o daqui executaria a tool com o fluxo parado.</p>
     */
    public List<Pendencia> pendentes(String tenantId) {
        return store.findPendingByTenant(tenantId).stream()
                .filter(RetomadaDoLaco::decididoAqui)
                .map(Pendencia::de)
                .toList();
    }

    /** Só laços avulsos: o de um fluxo é achado pelo pedido do portão dele. */
    public Optional<Pendencia> detalhe(String requestId) {
        return store.findByRequestId(requestId)
                .filter(RetomadaDoLaco::decididoAqui)
                .map(Pendencia::de);
    }

    /**
     * Apaga o laço que um portão de fluxo segurava, quando o portão foi recusado (por alguém ou pelo
     * prazo): o fluxo parou, ninguém mais vai retomar aquele laço, e o estado dele seria lixo durável
     * com a proposta e o transcript dentro. Só apaga o laço que é <b>daquela</b> execução — um avulso
     * é decidido por {@link #decidir}, e o de outro fluxo pelo portão dele, nunca descartados por aqui.
     *
     * @return {@code true} se havia o laço e ele foi apagado
     */
    public boolean descartarDoFluxo(String requestId, String execucaoDoFluxo) {
        return store.findByRequestId(requestId)
                .filter(e -> e.retomada() != null && e.retomada().seguroPor(execucaoDoFluxo))
                .map(e -> store.delete(e.runId()))
                .orElse(false);
    }

    private static boolean decididoAqui(McpAgentState estado) {
        return estado.pending() != null
                && (estado.retomada() == null || !estado.retomada().seguroPorFluxo());
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
        if (retomada != null && retomada.seguroPorFluxo()) {
            // Mesmo com o id em mãos, não: a decisão é do portão da execução, que grava quem decidiu
            // sob o lock dela e continua o grafo. Por aqui a tool rodaria e o fluxo seguiria parado.
            throw new IllegalStateException("O laço " + estado.runId() + " é de um nó da execução "
                    + retomada.execucaoDoFluxo() + ": decida pelo pedido de aprovação dessa execução");
        }
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
                .encerrandoNaSaida(retomada.toolsQueEncerram())
                .comLembreteDaSaida(retomada.lembreteDaSaida())
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
