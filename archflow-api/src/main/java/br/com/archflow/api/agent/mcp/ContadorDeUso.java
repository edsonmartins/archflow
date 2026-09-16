package br.com.archflow.api.agent.mcp;

import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.engine.ExecutionKeys;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * O que uma execução de agente gastou de modelo, somado <b>turno a turno</b>.
 *
 * <h2>Por que existe</h2>
 *
 * <p>Medido em 16/09, do lado do VendaX: a tabela {@code llm_usage} do Core estava vazia. O uso
 * só chegaria por um assunto NATS que nada publicava, e o teto de custo por tenant não tinha o
 * que contar — contava perguntas por mês, no lugar de dinheiro. O runner já via os tokens de cada
 * turno, mas eles iam só para a métrica global, sem dono.</p>
 *
 * <h2>Por que somar a cada turno, e não no fim</h2>
 *
 * <p>Uma execução que estoura o prazo, cai por erro de tool ou é derrubada pelo provedor já
 * <b>gastou</b>. Se o total só existisse no {@code Result}, essas execuções sairiam sem custo — e
 * justamente as que falham são as que mais interessam a um teto. Quem cria o contador o lê depois,
 * aconteça o que acontecer no meio.</p>
 *
 * <h2>Por que ele viaja no contexto do fluxo</h2>
 *
 * <p>No caminho de fluxo o laço roda noutra thread, dentro de um passo que não conhece o
 * dispatcher. O contador vai no contexto sob uma chave {@code transient}: é infraestrutura, não
 * estado, e não pode ir para o checkpoint (ver {@link ExecutionKeys#TRANSIENT_PREFIX}).</p>
 *
 * <p>Thread-safe: um fluxo pode ter passos paralelos alimentando o mesmo contador.</p>
 */
public final class ContadorDeUso {

    /** Onde o contador viaja no contexto do fluxo. */
    public static final String CONTEXT_KEY = ExecutionKeys.TRANSIENT_PREFIX + "contadorDeUso";

    private long tokensEntrada;
    private long tokensSaida;
    /** Turnos em que o provedor informou uso — sem nenhum, "zero tokens" seria mentira. */
    private int turnosMedidos;
    private final Set<String> modelos = new LinkedHashSet<>();

    /**
     * Um turno de modelo.
     *
     * @param modelo  {@code provider/modelo}, ou {@code null} se não se sabe
     * @param entrada tokens de entrada informados pelo provedor, ou {@code null}
     * @param saida   tokens de saída informados pelo provedor, ou {@code null}
     */
    public synchronized void somar(String modelo, Integer entrada, Integer saida) {
        if (modelo != null && !modelo.isBlank()) {
            modelos.add(modelo);
        }
        if (entrada == null && saida == null) {
            return;
        }
        tokensEntrada += entrada == null ? 0 : entrada;
        tokensSaida += saida == null ? 0 : saida;
        turnosMedidos++;
    }

    /**
     * O retrato até aqui, ou vazio quando nenhum modelo foi chamado.
     *
     * <p>Vazio não é "zero": é "não houve consumo a relatar", e quem monta o resultado omite o
     * campo em vez de afirmar um custo nulo.</p>
     */
    public synchronized Optional<Resumo> resumo() {
        if (modelos.isEmpty() && turnosMedidos == 0) {
            return Optional.empty();
        }
        return Optional.of(new Resumo(
                modelos.isEmpty() ? null : String.join(",", modelos),
                turnosMedidos == 0 ? null : tokensEntrada,
                turnosMedidos == 0 ? null : tokensSaida));
    }

    /**
     * Consumo acumulado.
     *
     * @param modelo        {@code provider/modelo}; mais de um, separados por vírgula, quando os
     *                      passos de um fluxo usaram modelos diferentes
     * @param tokensEntrada {@code null} quando o provedor não informou uso em turno nenhum
     * @param tokensSaida   idem
     */
    public record Resumo(String modelo, Long tokensEntrada, Long tokensSaida) {

        /** Total de tokens, ou {@code null} se o provedor não informou. */
        public Long tokens() {
            if (tokensEntrada == null && tokensSaida == null) {
                return null;
            }
            return (tokensEntrada == null ? 0 : tokensEntrada)
                    + (tokensSaida == null ? 0 : tokensSaida);
        }
    }

    /** Põe o contador no contexto do fluxo; nulo não entra. */
    public static void injetar(ExecutionContext context, ContadorDeUso contador) {
        if (context != null && contador != null) {
            context.set(CONTEXT_KEY, contador);
        }
    }

    /** O contador desta execução, se quem a hospeda pôs um. */
    public static Optional<ContadorDeUso> de(ExecutionContext context) {
        if (context == null) {
            return Optional.empty();
        }
        return context.get(CONTEXT_KEY)
                .filter(ContadorDeUso.class::isInstance)
                .map(ContadorDeUso.class::cast);
    }
}
