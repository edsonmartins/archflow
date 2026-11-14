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
public final class ContadorDeUso implements br.com.archflow.decision.DecisionUsageListener {

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
    /** Do início da execução ao primeiro token do provedor; {@code null} se nunca foi medido. */
    private Long msAtePrimeiroToken;
    /**
     * Uma tentativa falhou antes de informar uso — não há turno onde anotar, mas o provedor pode ter
     * cobrado. O total fica desconhecido. O custo dos turnos fica em {@link #usoPorTurno}: é de lá
     * que o total sai, para que um custo que chega depois (consulta da geração) entre na soma.
     */
    private boolean custoForaDeTurnoDesconhecido;
    /** Consultas de custo ainda em voo — ver {@link #aguardarCusto}. */
    private final java.util.List<java.util.concurrent.CompletableFuture<?>> custosPendentes =
            new java.util.ArrayList<>();
    private final Set<String> modelos = new LinkedHashSet<>();
    private final Set<String> provedores = new LinkedHashSet<>();
    private final java.util.List<Resumo.TurnoUso> usoPorTurno = new java.util.ArrayList<>();

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
        somarTurno(provedor, modelo, null, null,
                entrada == null ? null : entrada.longValue(), saida == null ? null : saida.longValue(),
                null, null);
    }

    /** Soma um turno e o custo declarado de forma atômica para snapshots concorrentes. */
    public synchronized void somarComCustoDeclaradoUsd(String provedor, String modelo,
                                                        Long entrada, Long saida, Double custoUsd) {
        somarComDetalhe(provedor, modelo, null, null, entrada, saida, custoUsd);
    }

    public synchronized void somarComDetalhe(String provedorSolicitado, String modeloSolicitado,
                                              String provedorEfetivo, String modeloEfetivo,
                                              Long entrada, Long saida, Double custoUsd) {
        somarTurno(provedorSolicitado, modeloSolicitado, provedorEfetivo, modeloEfetivo, entrada,
                saida, custoUsd, null);
    }

    /**
     * Um turno de modelo, com o id da geração no provedor.
     *
     * @param idDaGeracao o id que o provedor deu à geração ({@code gen-…} no OpenRouter), ou
     *                    {@code null}. É a chave com que o custo pode ser conciliado depois.
     * @return a posição do turno, para {@link #aguardarCusto} preencher o custo que faltou
     */
    public synchronized int somarTurno(String provedorSolicitado, String modeloSolicitado,
                                       String provedorEfetivo, String modeloEfetivo,
                                       Long entrada, Long saida, Double custoUsd, String idDaGeracao) {
        somarContadores(provedorSolicitado, modeloSolicitado, entrada, saida);
        usoPorTurno.add(new Resumo.TurnoUso(provedorSolicitado, modeloSolicitado, provedorEfetivo,
                modeloEfetivo, entrada, saida, custoValido(custoUsd) ? custoUsd : null, idDaGeracao));
        return usoPorTurno.size() - 1;
    }

    /**
     * O custo do turno {@code indice} chega depois — de uma consulta ao provedor pelo id da geração.
     *
     * <p>Quando {@code custo} completa com um valor válido, ele entra no turno e, portanto, no total.
     * Nulo, falha ou demora deixam o turno como estava: sem custo, e o total desconhecido. Quem vai
     * ler o {@link #resumo()} chama {@link #aguardarCustosPendentes} antes, com um prazo.</p>
     */
    public void aguardarCusto(int indice, java.util.concurrent.CompletableFuture<Double> custo) {
        if (custo == null) {
            return;
        }
        java.util.concurrent.CompletableFuture<Void> preenchido = custo
                .thenAccept(valor -> preencherCusto(indice, valor))
                .exceptionally(e -> null);
        synchronized (this) {
            if (!preenchido.isDone()) {
                custosPendentes.add(preenchido);
            }
        }
    }

    /**
     * Espera as consultas de custo em voo, até {@code prazo}. Nunca lança: o que não chegou fica
     * desconhecido, como ficaria sem a consulta.
     *
     * @return {@code true} se todas terminaram dentro do prazo
     */
    public boolean aguardarCustosPendentes(java.time.Duration prazo) {
        java.util.concurrent.CompletableFuture<?>[] emVoo;
        synchronized (this) {
            custosPendentes.removeIf(java.util.concurrent.CompletableFuture::isDone);
            if (custosPendentes.isEmpty()) {
                return true;
            }
            emVoo = custosPendentes.toArray(new java.util.concurrent.CompletableFuture<?>[0]);
        }
        try {
            java.util.concurrent.CompletableFuture.allOf(emVoo)
                    .get(Math.max(0, prazo.toMillis()), java.util.concurrent.TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            return false;
        }
    }

    private synchronized void preencherCusto(int indice, Double custoUsd) {
        if (!custoValido(custoUsd) || indice < 0 || indice >= usoPorTurno.size()) {
            return;
        }
        Resumo.TurnoUso t = usoPorTurno.get(indice);
        if (t.declaredCostUsd() != null) {
            return;
        }
        usoPorTurno.set(indice, new Resumo.TurnoUso(t.requestedProvider(), t.requestedModel(),
                t.effectiveProvider(), t.effectiveModel(), t.inputTokens(), t.outputTokens(), custoUsd,
                t.generationId()));
    }

    private static boolean custoValido(Double custoUsd) {
        return custoUsd != null && Double.isFinite(custoUsd) && custoUsd >= 0;
    }

    private void somarContadores(String provedor, String modelo, Long entrada, Long saida) {
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

    /**
     * O primeiro token do provedor chegou. Só o primeiro conta: o que interessa é quanto o
     * chamador esperou até ver algo, não a latência de cada turno.
     */
    public synchronized void registrarPrimeiroToken() {
        if (msAtePrimeiroToken == null) {
            msAtePrimeiroToken = (relogioNanos.getAsLong() - inicioNanos) / 1_000_000;
        }
    }

    /**
     * Uma decisão (modelo de decisão, LLM classificador): conta como turno de modelo e soma o custo
     * que o próprio provedor declarou. O custo vem em USD — este contador não o converte, e o
     * {@code costCents} (reais) segue sendo só o da tabela de preços.
     */
    @Override
    public void onDecision(String provedor, String modelo, Long entrada, Long saida, Double custoUsd) {
        somarComCustoDeclaradoUsd(provedor, modelo == null || provedor == null ? modelo
                : provedor + "/" + modelo, entrada, saida, custoUsd);
    }

    /**
     * Soma dos custos declarados; {@code null} se algum turno ficou sem custo — não se expõe soma
     * parcial como se fosse o total.
     */
    private Double custoDeclaradoUsd() {
        if (custoForaDeTurnoDesconhecido) {
            return null;
        }
        double total = 0.0;
        for (Resumo.TurnoUso t : usoPorTurno) {
            if (t.declaredCostUsd() == null) {
                return null;
            }
            total += t.declaredCostUsd();
        }
        return Double.isFinite(total) ? total : null;
    }

    /** Marca uma tentativa que falhou antes de fornecer uso/custo reconciliável. */
    public synchronized void marcarCustoDeclaradoDesconhecido() {
        custoForaDeTurnoDesconhecido = true;
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
                (relogioNanos.getAsLong() - inicioNanos) / 1_000_000,
                msAtePrimeiroToken,
                custoDeclaradoUsd(),
                java.util.List.copyOf(usoPorTurno)));
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
     * @param custoDeclaradoUsd soma dos custos declarados em USD; {@code null} se algum turno não
     *                      informou custo ou se o valor informado era inválido
     * @param msAtePrimeiroToken tempo do início da execução ao primeiro token do provedor;
     *                      {@code null} quando o provedor não transmitiu e não há como medir
     */
    public record Resumo(String execucaoId, String provedor, String modelo,
                         Long tokensEntrada, Long tokensSaida,
                         Integer turnos, Integer chamadasDeTool, Long duracaoMs,
                         Long msAtePrimeiroToken, Double custoDeclaradoUsd, java.util.List<TurnoUso> usoPorTurno) {

        public Resumo {
            usoPorTurno = usoPorTurno == null ? java.util.List.of() : java.util.List.copyOf(usoPorTurno);
        }

        public Resumo(String execucaoId, String provedor, String modelo, Long tokensEntrada,
                      Long tokensSaida, Integer turnos, Integer chamadasDeTool, Long duracaoMs,
                      Long msAtePrimeiroToken, Double custoDeclaradoUsd) {
            this(execucaoId, provedor, modelo, tokensEntrada, tokensSaida, turnos, chamadasDeTool,
                    duracaoMs, msAtePrimeiroToken, custoDeclaradoUsd, java.util.List.of());
        }

        /**
         * Um turno de modelo.
         *
         * @param declaredCostUsd custo declarado pelo provedor — no corpo, no stream ou na consulta
         *                        da geração; {@code null} se nenhum dos três o trouxe
         * @param generationId    id da geração no provedor, para conciliar depois; {@code null}
         *                        quando o provedor não o informa
         */
        public record TurnoUso(String requestedProvider, String requestedModel,
                               String effectiveProvider, String effectiveModel,
                               Long inputTokens, Long outputTokens, Double declaredCostUsd,
                               String generationId) {

            /** Compat: turno sem id de geração. */
            public TurnoUso(String requestedProvider, String requestedModel,
                            String effectiveProvider, String effectiveModel,
                            Long inputTokens, Long outputTokens, Double declaredCostUsd) {
                this(requestedProvider, requestedModel, effectiveProvider, effectiveModel,
                        inputTokens, outputTokens, declaredCostUsd, null);
            }
        }

        /**
         * Turnos sem custo declarado. Com {@link #custoDeclaradoUsd} nulo, é o que separa "um turno
         * de dez sem custo" de "nada medido". Zero com o total nulo quer dizer que uma tentativa
         * falhou antes de informar uso (não há turno onde contar).
         */
        public int turnosSemCusto() {
            int n = 0;
            for (TurnoUso t : usoPorTurno) {
                if (t.declaredCostUsd() == null) {
                    n++;
                }
            }
            return n;
        }

        /** Compat: resumo anterior ao custo declarado. */
        public Resumo(String execucaoId, String provedor, String modelo, Long tokensEntrada,
                      Long tokensSaida, Integer turnos, Integer chamadasDeTool, Long duracaoMs,
                      Long msAtePrimeiroToken) {
            this(execucaoId, provedor, modelo, tokensEntrada, tokensSaida, turnos, chamadasDeTool,
                    duracaoMs, msAtePrimeiroToken, null);
        }

        /** Compat: resumo anterior à medição do primeiro token. */
        public Resumo(String execucaoId, String provedor, String modelo, Long tokensEntrada,
                      Long tokensSaida, Integer turnos, Integer chamadasDeTool, Long duracaoMs) {
            this(execucaoId, provedor, modelo, tokensEntrada, tokensSaida, turnos, chamadasDeTool,
                    duracaoMs, null, null);
        }

        /** Compat: a forma do PR #51, sem id nem detalhamento. */
        public Resumo(String modelo, Long tokensEntrada, Long tokensSaida) {
            this(null, null, modelo, tokensEntrada, tokensSaida, null, null, null, null, null);
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
                    diferenca(duracaoMs, anterior.duracaoMs),
                    // É um instante, não um consumo: subtrair daria um número sem sentido.
                    msAtePrimeiroToken,
                    custoDeclaradoUsd == null ? null
                            : Math.max(0.0, custoDeclaradoUsd
                                    - (anterior.custoDeclaradoUsd == null ? 0.0 : anterior.custoDeclaradoUsd)),
                    usoPorTurno.subList(Math.min(anterior.usoPorTurno.size(), usoPorTurno.size()), usoPorTurno.size()));
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
            // O mesmo objeto, visto pelo nó de decisão — que não conhece este módulo.
            context.set(br.com.archflow.decision.DecisionUsageListener.CONTEXT_KEY, contador);
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
