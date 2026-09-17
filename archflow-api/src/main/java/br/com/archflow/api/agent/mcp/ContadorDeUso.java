package br.com.archflow.api.agent.mcp;

import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.engine.ExecutionKeys;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

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
 * <h2>Uma execução, um id</h2>
 *
 * <p>O {@link #execucaoId()} nasce com o contador e acompanha todo relato dele. É o que deixa o
 * consumidor contar cada relato uma vez só: reentregar o mesmo resultado não soma de novo. Não é a
 * chave de idempotência do resultado — execuções diferentes podem reusar a mesma chave, e cada uma
 * gastou.</p>
 *
 * <h2>Por que ele viaja no contexto do fluxo</h2>
 *
 * <p>No caminho de fluxo o laço roda noutra thread, dentro de um passo que não conhece o
 * dispatcher. O contador vai no contexto sob uma chave {@code transient}: é infraestrutura, não
 * estado, e não pode ir para o checkpoint (ver {@link ExecutionKeys#TRANSIENT_PREFIX}).</p>
 *
 * <p>Thread-safe: um fluxo pode ter passos paralelos alimentando o mesmo contador, e a execução
 * que passou do prazo continua somando noutra thread enquanto o dispatcher lê.</p>
 */
public final class ContadorDeUso {

    /** Onde o contador viaja no contexto do fluxo. */
    public static final String CONTEXT_KEY = ExecutionKeys.TRANSIENT_PREFIX + "contadorDeUso";

    private final String execucaoId = UUID.randomUUID().toString();
    private final LongSupplier relogioNanos;
    private final long inicioNanos;

    private long tokensEntrada;
    private long tokensSaida;
    /** Turnos em que o provedor informou uso — sem nenhum, "zero tokens" seria mentira. */
    private int turnosMedidos;
    private int turnos;
    private int chamadasDeTool;
    private final Set<String> modelos = new LinkedHashSet<>();
    private final Set<String> provedores = new LinkedHashSet<>();

    public ContadorDeUso() {
        this(System::nanoTime);
    }

    /** Com relógio injetado, para teste de duração. */
    ContadorDeUso(LongSupplier relogioNanos) {
        this.relogioNanos = relogioNanos;
        this.inicioNanos = relogioNanos.getAsLong();
    }

    /** O id desta execução — o mesmo em todo relato dela. */
    public String execucaoId() {
        return execucaoId;
    }

    /** Compat: turno sem provedor separado. */
    public void somar(String modelo, Integer entrada, Integer saida) {
        somar(null, modelo, entrada, saida);
    }

    /**
     * Um turno de modelo.
     *
     * @param provedor o provedor ({@code openrouter}), ou {@code null} se não se sabe
     * @param modelo   {@code provider/modelo}, ou {@code null} se não se sabe
     * @param entrada  tokens de entrada informados pelo provedor, ou {@code null}
     * @param saida    tokens de saída informados pelo provedor, ou {@code null}
     */
    public synchronized void somar(String provedor, String modelo, Integer entrada, Integer saida) {
        turnos++;
        if (modelo != null && !modelo.isBlank()) {
            modelos.add(modelo);
        }
        if (provedor != null && !provedor.isBlank()) {
            provedores.add(provedor);
        }
        if (entrada == null && saida == null) {
            return;
        }
        tokensEntrada += entrada == null ? 0 : entrada;
        tokensSaida += saida == null ? 0 : saida;
        turnosMedidos++;
    }

    /** Uma chamada de tool executada (bem-sucedida ou não) — uma ida ao servidor. */
    public synchronized void somarChamadaDeTool() {
        chamadasDeTool++;
    }

    /**
     * O retrato até aqui, ou vazio quando nenhum modelo foi chamado.
     *
     * <p>Vazio não é "zero": é "não houve consumo a relatar", e quem monta o resultado omite o
     * campo em vez de afirmar um custo nulo.</p>
     */
    public synchronized Optional<Resumo> resumo() {
        if (turnos == 0) {
            return Optional.empty();
        }
        return Optional.of(new Resumo(
                execucaoId,
                provedores.isEmpty() ? null : String.join(",", provedores),
                modelos.isEmpty() ? null : String.join(",", modelos),
                turnosMedidos == 0 ? null : tokensEntrada,
                turnosMedidos == 0 ? null : tokensSaida,
                turnos,
                chamadasDeTool,
                (relogioNanos.getAsLong() - inicioNanos) / 1_000_000));
    }

    /**
     * Consumo acumulado.
     *
     * @param execucaoId    a execução a que o consumo pertence
     * @param provedor      o provedor; mais de um, separados por vírgula
     * @param modelo        {@code provider/modelo}; mais de um, separados por vírgula, quando os
     *                      passos de um fluxo usaram modelos diferentes
     * @param tokensEntrada {@code null} quando o provedor não informou uso em turno nenhum
     * @param tokensSaida   idem
     * @param turnos        chamadas ao modelo
     * @param chamadasDeTool idas ao servidor de tools
     * @param duracaoMs     tempo desde o início da execução (ou, num {@link #menos}, do trecho)
     */
    public record Resumo(String execucaoId, String provedor, String modelo,
                         Long tokensEntrada, Long tokensSaida,
                         Integer turnos, Integer chamadasDeTool, Long duracaoMs) {

        /** Compat: a forma do PR #51, sem id nem detalhamento. */
        public Resumo(String modelo, Long tokensEntrada, Long tokensSaida) {
            this(null, null, modelo, tokensEntrada, tokensSaida, null, null, null);
        }

        /** Total de tokens, ou {@code null} se o provedor não informou. */
        public Long tokens() {
            if (tokensEntrada == null && tokensSaida == null) {
                return null;
            }
            return (tokensEntrada == null ? 0 : tokensEntrada)
                    + (tokensSaida == null ? 0 : tokensSaida);
        }

        /**
         * O que foi gasto <b>depois</b> de {@code anterior} — da mesma execução.
         *
         * <p>Existe para o prazo interativo: o {@code ERROR} sai com o que foi gasto até o prazo,
         * e o laço, que não para na hora, continua gastando. Relatar o total de novo contaria o
         * trecho anterior duas vezes.</p>
         */
        public Resumo menos(Resumo anterior) {
            if (anterior == null) {
                return this;
            }
            return new Resumo(execucaoId, provedor, modelo,
                    diferenca(tokensEntrada, anterior.tokensEntrada),
                    diferenca(tokensSaida, anterior.tokensSaida),
                    diferenca(turnos, anterior.turnos),
                    diferenca(chamadasDeTool, anterior.chamadasDeTool),
                    diferenca(duracaoMs, anterior.duracaoMs));
        }

        /** {@code true} quando o trecho não chamou o modelo — nada a relatar. */
        public boolean semTurnos() {
            return turnos == null || turnos <= 0;
        }

        private static Long diferenca(Long depois, Long antes) {
            if (depois == null) {
                return null;
            }
            return Math.max(0, depois - (antes == null ? 0 : antes));
        }

        private static Integer diferenca(Integer depois, Integer antes) {
            if (depois == null) {
                return null;
            }
            return Math.max(0, depois - (antes == null ? 0 : antes));
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
