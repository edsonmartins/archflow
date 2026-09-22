package br.com.archflow.api.agent.vendax;

import br.com.archflow.api.agent.mcp.ContadorDeUso;
import br.com.archflow.api.agent.mcp.McpAgentRunner;
import br.com.archflow.api.agent.mcp.TabelaDePrecos;
import br.com.archflow.api.agent.mcp.ToolAccessPolicy;
import br.com.archflow.api.agent.mcp.ToolApprovalPolicy;
import br.com.archflow.api.agent.mcp.ToolTrustPolicy;
import br.com.archflow.api.agent.qp.QpAgentService;
import br.com.archflow.api.mcp.vendax.VendaxMcpClientProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executa o agente que o VendaX Core pediu e devolve o resultado.
 *
 * <p>É o elo que faltava entre os dois sistemas: o Core já decidia quais agentes acionar (Nexus +
 * Playbook) e gravava a ordem no outbox, mas ela ia para um assunto NATS que ninguém consumia —
 * cada mensagem de cliente disparava um acionamento que morria em silêncio.</p>
 *
 * <p>A execução é assíncrona por necessidade: um agente leva dezenas de segundos (LLM + tools) e o
 * Core não pode ficar segurando a conexão do outbox. A ordem é aceita com 202 e o resultado volta
 * pelo {@link VendaxResultSender}.</p>
 */
public class VendaxAgentDispatcher {

    private static final Logger log = LoggerFactory.getLogger(VendaxAgentDispatcher.class);

    /** Sentimento é estado da conversa (CS); o Core o aplica sem virar mensagem. */
    private static final String TYPE_SENTIMENT = "sentiment";
    private static final String TYPE_QUOTE = "quote";

    /**
     * O CS lê o histórico operacional para explicar por que o cliente esfriou (corte, atraso), mas
     * não pode cotar nem enviar pedido — daí a allowlist própria, distinta da do QP.
     */
    private static final Set<String> CS_TOOLS = Set.of("obter_eventos_operacionais", "obter_cliente_360");

    /** O NS só é executado aqui para a consulta do vendedor (ADR-038 D-3); é o que o reason diz. */
    static final String NS_CONSULTA = "ns:consulta";

    /** A consulta lê o plano de negociação, e nada mais: ela não grava nem promete. */
    private static final Set<String> NS_TOOLS = Set.of(ConsultaAoNegociador.TOOL);

    /**
     * Entender → chamar a tool → redigir, com folga para UMA correção de argumento. Mais que isso
     * não cabe no prazo de quem está esperando, e um laço longo só adiaria o mesmo erro.
     */
    private static final int NS_MAX_ITERACOES = 4;

    /**
     * {@code -32001}: o agente NS não foi contratado pelo tenant. Nenhuma volta a mais muda isso,
     * então o laço para na hora em vez de devolver o erro ao modelo para ele tentar de novo.
     */
    static final int NS_NAO_CONTRATADO = -32001;

    /** Prazo padrão da classe interativa (ADR-025 D-2): a tela espera 30 s; aqui sobra margem. */
    static final Duration PRAZO_INTERATIVO_PADRAO = Duration.ofSeconds(20);

    private final QpAgentService qpAgent;
    private final McpAgentRunner runner;
    private final VendaxMcpClientProvider vendax;
    private final VendaxResultSender resultSender;
    private final ExecutorService executor;
    private final VendaxAgentMetrics metrics;
    /** Nulo numa instalação sem motor de fluxo: só o caminho por nome de agente responde. */
    private final AgentFlowRunner fluxo;
    private volatile Duration prazoInterativo = PRAZO_INTERATIVO_PADRAO;
    /** Nulo: o roteiro do CPA espera o mesmo que o resto da classe interativa. */
    private volatile Duration prazoDoRoteiro;
    private volatile TabelaDePrecos precos = TabelaDePrecos.vazia();

    public VendaxAgentDispatcher(QpAgentService qpAgent, McpAgentRunner runner,
                                 VendaxMcpClientProvider vendax, VendaxResultSender resultSender,
                                 ExecutorService executor) {
        this(qpAgent, runner, vendax, resultSender, executor, null, null);
    }

    public VendaxAgentDispatcher(QpAgentService qpAgent, McpAgentRunner runner,
                                 VendaxMcpClientProvider vendax, VendaxResultSender resultSender,
                                 ExecutorService executor, VendaxAgentMetrics metrics) {
        this(qpAgent, runner, vendax, resultSender, executor, metrics, null);
    }

    public VendaxAgentDispatcher(QpAgentService qpAgent, McpAgentRunner runner,
                                 VendaxMcpClientProvider vendax, VendaxResultSender resultSender,
                                 ExecutorService executor, VendaxAgentMetrics metrics,
                                 AgentFlowRunner fluxo) {
        this.qpAgent = qpAgent;
        this.runner = runner;
        this.vendax = vendax;
        this.resultSender = resultSender;
        this.executor = executor;
        this.metrics = metrics;
        this.fluxo = fluxo;
    }

    /** Quanto a classe interativa espera antes de devolver {@code ERROR}. */
    public void setPrazoInterativo(Duration prazo) {
        if (prazo == null || prazo.isNegative() || prazo.isZero()) {
            throw new IllegalArgumentException("prazo interativo deve ser positivo: " + prazo);
        }
        this.prazoInterativo = prazo;
    }

    /**
     * Prazo próprio do roteiro do CPA; nulo volta ao da classe interativa.
     *
     * <p>É uma volta sem ferramenta, e quem espera é um botão com "pensando…": o que para a consulta
     * do NS é folga, aqui é o vendedor que já ligou. Separado do prazo da classe para poder ser
     * apertado depois de medido, sem apertar junto quem chama tool.</p>
     */
    public void setPrazoDoRoteiro(Duration prazo) {
        if (prazo != null && (prazo.isNegative() || prazo.isZero())) {
            throw new IllegalArgumentException("prazo do roteiro deve ser positivo: " + prazo);
        }
        this.prazoDoRoteiro = prazo;
    }

    /** Preços por modelo, para o custo que acompanha cada result; sem tabela, custo nulo. */
    public void setPrecos(TabelaDePrecos precos) {
        this.precos = precos == null ? TabelaDePrecos.vazia() : precos;
    }

    /** Aceita a ordem e executa fora da requisição. */
    public void dispatch(VendaxInvoke invoke) {
        if (metrics != null) metrics.received();
        try {
            executor.execute(() -> runAndReport(invoke));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            if (metrics != null) metrics.failed(0, e);
            throw e;
        }
    }

    void runAndReport(VendaxInvoke invoke) {
        long startedAt = metrics != null ? metrics.started() : 0;
        String agent = invoke.agent() == null ? "" : invoke.agent().toUpperCase();
        // A CORRELAÇÃO ACOMPANHA A EXECUÇÃO INTEIRA, e sai por header em cada chamada de tool.
        //
        // Sem ela o Core grava o evento da cotação sem saber a que conversa pertence — medido em
        // 05/08, quando a fronteira MCP carregava só o tenant. A chave da janela já contém a
        // conversa, então um header basta.
        //
        // Definida AQUI e não no client porque o client é cacheado POR TENANT e compartilhado
        // entre execuções: pôr correlação nele misturaria conversas.
        // A IDENTIDADE VAI JUNTO, e pelo mesmo caminho. Ela já estava no invoke desde que
        // customerRef/vendorRef entraram, e chegava ao server apenas como argumento que o MODELO
        // preenchia — ver CorrelacaoMcp.HEADER_CLIENTE para o que isso permite quando o modelo
        // copia o ref errado.
        // A ORIGEM VAI JUNTO, quando houve uma. Ela decide a quem o Core credita a venda, e por isso
        // nunca é inferida nem reaproveitada: o que sai daqui é o que veio neste invoke, e o
        // `finally` abaixo garante que a próxima execução nesta mesma thread não herde nada.
        definirCorrelacao(invoke);
        // O CONSUMO É CONTADO DESDE O PRIMEIRO TURNO, e lido no fim aconteça o que acontecer: uma
        // execução que estoura o prazo ou cai por erro também gastou, e é essa que um teto de custo
        // mais precisa ver.
        ContadorDeUso uso = new ContadorDeUso();
        try {
            // O caminho genérico vem ANTES do switch, e é o que o deve substituir: quando a
            // definição traz um fluxo, este runtime não precisa saber que agente é. O switch
            // continua abaixo só enquanto houver skill em PROMPT — cada agente que virar FLUXO
            // apaga um `case`.
            if (invoke.definicao() != null && invoke.definicao().eFluxo()) {
                VendaxResult porFluxo = runFluxo(invoke, uso);
                if (porFluxo != null) {
                    enviar(porFluxo, uso, invoke);
                }
                if (metrics != null) metrics.completed(startedAt);
                return;
            }

            VendaxResult result = switch (agent) {
                case "QP" -> runQp(invoke, uso);
                case "CS" -> runCs(invoke, uso);
                // O NS de negociação autônoma não roda aqui; só a consulta do vendedor, que o Core
                // distingue pelo reason.
                case "NS" -> NS_CONSULTA.equals(invoke.reason())
                        ? runNsConsulta(invoke, uso)
                        : naoImplementado(invoke);
                // O AP aqui só narra o dia; o reason distingue, como no NS.
                case "AP" -> NarracaoDoDia.REASON.equals(invoke.reason())
                        ? runApNarracao(invoke, uso)
                        : naoImplementado(invoke);
                // O US sugere o item que costuma vir junto, antes do fechamento.
                case "US" -> SugestaoDeUpsell.REASON.equals(invoke.reason())
                        ? runUsSugestao(invoke, uso)
                        : naoImplementado(invoke);
                // O CPA aqui só roteiriza a abordagem de uma tarefa aberta.
                case "CPA" -> RoteiroDeAbordagem.REASON.equals(invoke.reason())
                        ? runCpaRoteiro(invoke, uso)
                        : naoImplementado(invoke);
                default -> naoImplementado(invoke);
            };
            if (result != null) {
                enviar(result, uso, invoke);
            }
            if (metrics != null) metrics.completed(startedAt);
        } catch (Exception e) {
            if (metrics != null) metrics.failed(startedAt, e);
            log.error("Agente {} falhou (conv={}): {}",
                    invoke.agent(), invoke.conversationId(), e.getMessage(), e);
            enviar(VendaxResult.error(invoke, causeOf(e)), uso, invoke);
        } finally {
            // A thread é reusada entre invokes. Sem limpar, o PRÓXIMO agente a rodar aqui mandaria
            // a correlação deste — e o evento da cotação sairia amarrado à conversa errada. Um
            // evento errado com confiança é pior que um evento sem correlação nenhuma.
            //
            // Com a task no pacote isso deixa de ser só um relatório torto: uma execução que não
            // veio de task herdaria a task da anterior, e uma venda que nada tem a ver com ela
            // seria creditada à task — sem erro, sem log, e sem nada que destoe na conferência.
            br.com.archflow.langchain4j.mcp.client.CorrelacaoMcp.limpar();
        }
    }

    /**
     * Agente que o Core aciona e o ArchFlow ainda não implementa (US, ASSISTANT, TRANSCRIBER).
     * Devolver ERROR é deliberado: o vendedor vê que algo não rodou, em vez de esperar por uma
     * resposta que nunca vem.
     */
    private VendaxResult naoImplementado(VendaxInvoke invoke) {
        log.warn("Agente '{}' (reason={}) não implementado no ArchFlow (conv={})",
                invoke.agent(), invoke.reason(), invoke.conversationId());
        return VendaxResult.error(invoke,
                "Agente " + invoke.agent() + " ainda não é executado pelo ArchFlow");
    }

    /**
     * A correlação e a identidade desta execução, na thread atual.
     *
     * <p>Um método só porque são dois lugares que precisam dela: a thread do executor e a da
     * consulta interativa. Repetir a lista de campos em cada um é como um deles acaba esquecendo
     * um — e o header que falta não produz erro, só some.</p>
     */
    private static void definirCorrelacao(VendaxInvoke invoke) {
        br.com.archflow.langchain4j.mcp.client.CorrelacaoMcp.definir(
                invoke.idempotencyKey(), invoke.traceId(),
                invoke.customerRef(), invoke.vendorRef(), invoke.taskId());
    }

    /**
     * O que toda execução leva ao laço, qualquer que seja o agente: o contador de consumo, a
     * memória do cliente que o Core mandou e o tier. Um lugar só, para nenhum caminho esquecer um.
     *
     * <p>O tier estava aqui só para o NS. QP e CS, pelo caminho por nome, rodavam sem ele — e é
     * pelo tier que o Core degrada o modelo sob pressão de custo (ADR-025 do VendaX). A política
     * não tinha efeito justamente nos dois agentes que mais gastam.</p>
     */
    static McpAgentRunner.Options daExecucao(McpAgentRunner.Options opcoes, VendaxInvoke invoke,
                                             ContadorDeUso uso) {
        return opcoes.comUso(uso).comContextoRecuperado(invoke.memoria()).comTier(invoke.tier());
    }

    /** Envia carregando o consumo — em todo result, OK ou ERROR. */
    private void enviar(VendaxResult result, ContadorDeUso uso, VendaxInvoke invoke) {
        // O traceId não está no result; vai junto para a desistência, se houver, dizer qual foi.
        resultSender.send(comUso(result, uso), invoke.traceId());
    }

    VendaxResult comUso(VendaxResult result, ContadorDeUso uso) {
        if (result.uso() != null) {
            // Já veio com o recorte certo — o ERROR por prazo leva só o gasto até o prazo.
            return result;
        }
        return uso.resumo().map(r -> result.comUso(usoDe(r))).orElse(result);
    }

    private VendaxResult.Uso usoDe(ContadorDeUso.Resumo r) {
        return VendaxResult.Uso.de(r, precos.custoEmCentavos(r));
    }

    /** Relata o consumo de uma execução que não vai num result; nada gasto, nada relatado. */
    private void relatar(VendaxInvoke invoke, VendaxUsoRelato.Motivo motivo,
                         ContadorDeUso.Resumo resumo) {
        if (resumo == null || resumo.semTurnos()) {
            return;
        }
        resultSender.relatarUso(VendaxUsoRelato.de(invoke, motivo, usoDe(resumo)));
    }

    /**
     * NS, consulta do vendedor: uma pergunta em linguagem vira um parâmetro, uma chamada ao plano
     * de negociação e uma frase curta.
     *
     * <h2>Classe interativa</h2>
     *
     * <p>O vendedor está olhando para a tela, que espera até 30 s. Por isso há prazo, e por isso
     * estourá-lo devolve {@code ERROR} na hora — com a mesma chave de idempotência, que é o que faz
     * a tela parar de esperar — em vez de reenfileirar.</p>
     *
     * <h2>O parâmetro vem da chamada, não da afirmação</h2>
     *
     * <p>O Core refaz a conta com o {@code parametro} devolvido e recusa a frase que citar número
     * fora dela. Ver {@link ConsultaAoNegociador} para por que o parâmetro é lido dos argumentos que
     * de fato foram à tool.</p>
     */
    private VendaxResult runNsConsulta(VendaxInvoke invoke, ContadorDeUso uso) {
        var client = vendax.clientFor(invoke.tenantId(),
                invoke.definicao() != null ? invoke.definicao().versao() : null);
        McpAgentRunner.Options opcoes = new McpAgentRunner.Options(
                politicaDe(invoke, NS_TOOLS),
                ToolTrustPolicy.untrustedByDefault(),
                ToolApprovalPolicy.none(),
                NS_MAX_ITERACOES,
                null, null, Set.of(),
                invoke.tier())
                .encerrandoEm(Set.of(NS_NAO_CONTRATADO));
        McpAgentRunner.Options opcoesDaExecucao = daExecucao(opcoes, invoke, uso);
        String systemPrompt = promptDe(invoke, ConsultaAoNegociador.SYSTEM_PROMPT);
        String entrada = entradaDoAgente(invoke);

        McpAgentRunner.Result result;
        try {
            result = comPrazo(invoke, prazoInterativo, () ->
                    runner.run(invoke.tenantId(), systemPrompt, entrada, client, opcoesDaExecucao));
        } catch (PrazoEstourado e) {
            log.warn("Consulta ao NS passou do prazo de {} ms (trace={})",
                    prazoInterativo.toMillis(), invoke.traceId());
            ContadorDeUso.Resumo ate = uso.resumo().orElse(null);
            relatarDepoisDoPrazo(invoke, uso, ate, e.execucao());
            VendaxResult erro = VendaxResult.error(invoke, "O negociador não respondeu em "
                    + prazoInterativo.toSeconds() + " s");
            return ate == null ? erro : erro.comUso(usoDe(ate));
        }

        if (result.isEncerrado()) {
            McpAgentRunner.ToolCall causa = result.encerradoPor();
            log.info("Consulta ao NS encerrada pela tool {} (código {}, trace={})",
                    causa.name(), causa.errorCode(), invoke.traceId());
            return VendaxResult.error(invoke, NS_NAO_CONTRATADO == causa.errorCode()
                    ? "O agente NS não foi contratado por este tenant"
                    : causa.resultText());
        }
        return ConsultaAoNegociador.resultado(invoke, result);
    }

    /**
     * US, upsell pré-fechamento: a tool devolve as candidatas que o Core filtrou, e o modelo escolhe
     * uma e escreve a frase.
     *
     * <p>Interativo (ADR-025 D-2): o vendedor está olhando a cotação que acabou de sair, então vale
     * o mesmo prazo da consulta ao NS — passou, sai {@code ERROR} e o que o laço gastar depois é
     * relatado à parte.</p>
     *
     * <p>Medido em 18/09, no homolog do VendaX: <b>544 acionamentos de US</b> entre 03 e 24/08
     * caíram no ramo "agente não implementado" deste switch, e nenhuma sugestão chegou ao vendedor.
     * Nada do lado do Core acusou — por isso este caminho existe agora.</p>
     */
    private VendaxResult runUsSugestao(VendaxInvoke invoke, ContadorDeUso uso) {
        var client = vendax.clientFor(invoke.tenantId(),
                invoke.definicao() != null ? invoke.definicao().versao() : null);
        McpAgentRunner.Options opcoes = daExecucao(new McpAgentRunner.Options(
                politicaDe(invoke, Set.of(SugestaoDeUpsell.TOOL)),
                ToolTrustPolicy.untrustedByDefault(),
                ToolApprovalPolicy.none(),
                SugestaoDeUpsell.MAX_ITERACOES), invoke, uso);
        String systemPrompt = promptDe(invoke, SugestaoDeUpsell.SYSTEM_PROMPT);
        String entrada = entradaDoAgente(invoke);

        McpAgentRunner.Result result;
        try {
            result = comPrazo(invoke, prazoInterativo, () ->
                    runner.run(invoke.tenantId(), systemPrompt, entrada, client, opcoes));
        } catch (PrazoEstourado e) {
            log.warn("Sugestão do US passou do prazo de {} ms (trace={})",
                    prazoInterativo.toMillis(), invoke.traceId());
            ContadorDeUso.Resumo ate = uso.resumo().orElse(null);
            relatarDepoisDoPrazo(invoke, uso, ate, e.execucao());
            VendaxResult erro = VendaxResult.error(invoke, "O US não respondeu em "
                    + prazoInterativo.toSeconds() + " s").comChave(SugestaoDeUpsell.chave(invoke));
            return ate == null ? erro : erro.comUso(usoDe(ate));
        }
        return SugestaoDeUpsell.resultado(invoke, result);
    }

    /**
     * AP, narração do dia: a contagem que o Core calculou vira uma a três frases.
     *
     * <p>Sem ferramenta e numa volta só — ver {@link NarracaoDoDia}. O laço recebe um cliente
     * {@link br.com.archflow.api.agent.mcp.SemFerramentas}: nenhuma ida ao servidor MCP, nenhum
     * catálogo no prompt, e a narração não depende de o servidor estar no ar. A política vazia é a
     * segunda barreira.</p>
     *
     * <p>Classe de lote: sem prazo interativo. Um {@code OK} atrasado é aproveitado pelo Core.</p>
     */
    private VendaxResult runApNarracao(VendaxInvoke invoke, ContadorDeUso uso) {
        McpAgentRunner.Options opcoes = daExecucao(new McpAgentRunner.Options(
                ToolAccessPolicy.allowOnly(Set.of()),
                ToolTrustPolicy.untrustedByDefault(),
                ToolApprovalPolicy.none(),
                NarracaoDoDia.MAX_ITERACOES), invoke, uso);
        McpAgentRunner.Result result = runner.run(invoke.tenantId(),
                promptDe(invoke, NarracaoDoDia.SYSTEM_PROMPT),
                entradaDoAgente(invoke),
                br.com.archflow.api.agent.mcp.SemFerramentas.INSTANCIA, opcoes);
        return NarracaoDoDia.resultado(invoke, result);
    }

    /**
     * CPA, roteiro de abordagem: o dossiê que o Core montou vira duas a quatro frases sobre como
     * chegar no cliente.
     *
     * <p>Sem ferramenta e numa volta só, como a narração do AP — mesmo cliente
     * {@link br.com.archflow.api.agent.mcp.SemFerramentas}, mesma política vazia como segunda
     * barreira. Duas diferenças: é interativo (passou do prazo, sai {@code ERROR} e o {@code OK}
     * atrasado é descartado), e as notas dos vendedores saem do payload e entram cercadas, junto da
     * memória do cliente — ver {@link RoteiroDeAbordagem}.</p>
     */
    private VendaxResult runCpaRoteiro(VendaxInvoke invoke, ContadorDeUso uso) {
        RoteiroDeAbordagem.Preparado preparado = RoteiroDeAbordagem.preparar(invoke.payload());
        if (preparado == null) {
            // Sem dossiê o modelo só teria o prompt — e escreveria um roteiro convincente sobre nada.
            return VendaxResult.error(invoke, "O dossiê do roteiro não veio ou não é um JSON");
        }
        List<String> cercado = new java.util.ArrayList<>(invoke.memoria());
        cercado.addAll(preparado.notas());
        McpAgentRunner.Options opcoes = daExecucao(new McpAgentRunner.Options(
                ToolAccessPolicy.allowOnly(Set.of()),
                ToolTrustPolicy.untrustedByDefault(),
                ToolApprovalPolicy.none(),
                RoteiroDeAbordagem.MAX_ITERACOES), invoke, uso)
                .comContextoRecuperado(cercado);
        String systemPrompt = promptDe(invoke, RoteiroDeAbordagem.SYSTEM_PROMPT);
        String entrada = entradaDoAgente(invoke.comPayload(preparado.payload()));
        Duration prazo = prazoDoRoteiro != null ? prazoDoRoteiro : prazoInterativo;

        McpAgentRunner.Result result;
        try {
            result = comPrazo(invoke, prazo, () -> runner.run(invoke.tenantId(), systemPrompt, entrada,
                    br.com.archflow.api.agent.mcp.SemFerramentas.INSTANCIA, opcoes));
        } catch (PrazoEstourado e) {
            log.warn("Roteiro do CPA passou do prazo de {} ms (trace={})",
                    prazo.toMillis(), invoke.traceId());
            ContadorDeUso.Resumo ate = uso.resumo().orElse(null);
            relatarDepoisDoPrazo(invoke, uso, ate, e.execucao());
            VendaxResult erro = VendaxResult.error(invoke, "O CPA não respondeu em "
                    + prazo.toMillis() + " ms");
            return ate == null ? erro : erro.comUso(usoDe(ate));
        }
        return RoteiroDeAbordagem.resultado(invoke, result);
    }

    /**
     * O laço não para no prazo: a chamada de modelo em voo termina e é cobrada. Quando ele acabar,
     * relata <b>só</b> o que gastou depois do {@code ERROR}, com o mesmo {@code execucaoId}.
     *
     * <p>Numa thread nova, e não na do laço: aquela foi interrompida pelo prazo e o runner restaura
     * a flag ao sair — um HTTP feito nela falharia na hora, e o relato se perderia.</p>
     */
    private void relatarDepoisDoPrazo(VendaxInvoke invoke, ContadorDeUso uso,
                                      ContadorDeUso.Resumo ate, CompletableFuture<?> execucao) {
        execucao.whenCompleteAsync((ignorado, erro) -> uso.resumo()
                        .map(depois -> depois.menos(ate))
                        .ifPresent(resto -> relatar(invoke, VendaxUsoRelato.Motivo.APOS_PRAZO, resto)),
                tarefa -> Thread.ofVirtual().name("vendax-uso-apos-prazo").start(tarefa));
    }

    /** O prazo acabou; {@link #execucao()} é o laço, que ainda pode estar rodando. */
    static final class PrazoEstourado extends TimeoutException {
        private final transient CompletableFuture<?> execucao;

        PrazoEstourado(CompletableFuture<?> execucao) {
            super("prazo interativo estourado");
            this.execucao = execucao;
        }

        CompletableFuture<?> execucao() {
            return execucao;
        }
    }

    /**
     * Executa com prazo, <b>repondo a correlação na thread que trabalha</b>.
     *
     * <p>O trabalho roda noutra thread para que o prazo valha mesmo com uma chamada de modelo
     * pendurada. E é exatamente aí que mora a armadilha já medida duas vezes neste runtime: o
     * ThreadLocal da correlação não atravessa threads, e o {@code callTool} lê o valor na thread do
     * chamador. Sem repor aqui, os headers {@code X-Vendax-Cliente} e {@code X-Vendax-Vendedor}
     * simplesmente não sairiam — sem erro, sem log.</p>
     *
     * <p>Estourado o prazo, a thread é interrompida e o que ela ainda produzir é descartado: o Core
     * já recebeu o {@code ERROR}, e um {@code OK} atrasado com a mesma chave seria uma segunda
     * resposta para uma pergunta já encerrada.</p>
     */
    private <T> T comPrazo(VendaxInvoke invoke, Duration prazo, Supplier<T> trabalho)
            throws PrazoEstourado {
        CompletableFuture<T> futuro = new CompletableFuture<>();
        Thread thread = Thread.ofVirtual().name("vendax-interativo-", 0).unstarted(() -> {
            definirCorrelacao(invoke);
            try {
                futuro.complete(trabalho.get());
            } catch (Throwable t) {
                futuro.completeExceptionally(t);
            } finally {
                br.com.archflow.langchain4j.mcp.client.CorrelacaoMcp.limpar();
            }
        });
        thread.start();
        try {
            return futuro.get(prazo.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            thread.interrupt();
            throw new PrazoEstourado(futuro);
        } catch (InterruptedException e) {
            thread.interrupt();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Execução interativa interrompida", e);
        } catch (ExecutionException e) {
            Throwable causa = e.getCause();
            if (causa instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (causa instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(causa);
        }
    }

    /**
     * O agente como documento: executa e devolve o que saiu, sem interpretar.
     *
     * <p>O tipo do rich object vem do {@code saidaSchema} da definição — {@code sentiment@1} vira
     * {@code sentiment}. Quem declara é o Core, e é o que mantém a regra da {@code ADR-025} D-1: o
     * executor não decide se produziu uma cotação ou um sentimento, porque não sabe o que são.</p>
     */
    private VendaxResult runFluxo(VendaxInvoke invoke, ContadorDeUso uso) {
        if (fluxo == null) {
            return VendaxResult.error(invoke,
                    "Definição veio como FLUXO e este runtime não tem motor de fluxo configurado");
        }
        String tipo = tipoDoRichObject(invoke.definicao().saidaSchema());
        if (tipo == null) {
            // Sem o tipo, o Core recebe um JSON que não sabe onde encaixar. Adivinhar aqui seria
            // este runtime decidindo o que o resultado significa — exatamente o que ele não faz.
            return VendaxResult.error(invoke,
                    "Definição do tipo FLUXO sem saidaSchema: não há como tipar o rich object");
        }

        AgentFlowRunner.Saida saida = fluxo.executar(invoke, invoke.definicao().fluxo(),
                entradaDoAgente(invoke), uso);

        if (saida.suspenso()) {
            log.info("Fluxo de {} suspenso aguardando decisão humana (conv={})",
                    invoke.agent(), invoke.conversationId());
            // O gasto até aqui. Hoje uma retomada não manda result ao Core, e o que ela gastar não
            // é relatado — o contador desta execução não sobrevive à suspensão.
            relatar(invoke, VendaxUsoRelato.Motivo.SUSPENSO, uso.resumo().orElse(null));
            return null;
        }
        String conteudo = extractJson(saida.texto());
        if (conteudo == null) {
            // Mesmo tratamento do CS embutido: o Core recusa o que não desserializa, então mandar
            // texto solto só empurra a falha para lá com menos contexto.
            //
            // O ERROR também leva as chamadas e o encerramento: um laço que parou porque a tool
            // disse "não contratado" não produz JSON nenhum, e sem isso o Core não distingue esse
            // caso de "o modelo não redigiu" — que é a diferença que ele pediu para ver.
            String motivo = saida.encerradoPor() == null ? ""
                    : " (encerrado por " + saida.encerradoPor().name()
                            + ", código " + saida.encerradoPor().code() + ")";
            return VendaxResult.error(invoke,
                            "O fluxo de " + invoke.agent() + " não devolveu um JSON" + motivo)
                    .comChamadas(saida.toolCalls(), saida.encerradoPor());
        }
        // AS CHAMADAS DE TOOL SOBEM COM O RESULT, e a lista vai mesmo vazia: o Core monta o
        // parâmetro da resposta a partir dos ARGUMENTOS que foram à tool, e não do que o modelo
        // declara — é a garantia que o `case` do NS tem dentro daqui e que o fluxo não tinha.
        // Ausente significaria "não informado"; vazia significa "não chamou nada".
        return VendaxResult.ok(invoke, tipo, conteudo)
                .comChamadas(saida.toolCalls(), saida.encerradoPor());
    }

    /** {@code sentiment@1} → {@code sentiment}. Sem schema não há tipo. */
    static String tipoDoRichObject(String saidaSchema) {
        if (saidaSchema == null || saidaSchema.isBlank()) {
            return null;
        }
        int arroba = saidaSchema.indexOf('@');
        String tipo = arroba < 0 ? saidaSchema : saidaSchema.substring(0, arroba);
        return tipo.isBlank() ? null : tipo.trim();
    }

    private VendaxResult runQp(VendaxInvoke invoke, ContadorDeUso uso) {
        var def = invoke.definicao();
        QpAgentService.QpResult qp = qpAgent.quote(new QpAgentService.QpRequest(
                invoke.tenantId(), invoke.customerRef(), invoke.vendorRef(),
                modoEntrada(invoke), invoke.text(), List.of(),
                def != null && def.temPrompt() ? def.systemPrompt() : null,
                // A chave que o Core embutiu no prompt tem de ser a mesma que o resultado carrega.
                def != null ? chaveDe(def) : null),
                def != null ? def.versao() : null,
                opcoes -> daExecucao(opcoes, invoke, uso));

        if (qp.quote() == null || qp.quote().isBlank()) {
            // O agente rodou mas não chegou a cotar (pediu confirmação, não achou o SKU). Não é
            // erro: virar agent.error poluiria a conversa a cada mensagem sem intenção de pedido.
            log.info("QP concluiu sem cotação (conv={}): {}",
                    invoke.conversationId(), qp.finalText());
            // Sem result, o uso não teria onde ir — e é o caso mais comum do QP.
            relatar(invoke, VendaxUsoRelato.Motivo.SEM_RESULT, uso.resumo().orElse(null));
            return null;
        }
        return VendaxResult.ok(invoke, TYPE_QUOTE, qp.quote());
    }

    /**
     * CS: avalia o sentimento da conversa. O contrato do Core é um JSON
     * {@code {score:-10..10, trend, tone, bigCustomer}} — o modelo devolve exatamente isso como
     * texto final, e o Core recusa (com log) o que não desserializar.
     */
    private VendaxResult runCs(VendaxInvoke invoke, ContadorDeUso uso) {
        var client = vendax.clientFor(invoke.tenantId(),
                invoke.definicao() != null ? invoke.definicao().versao() : null);
        McpAgentRunner.Options opcoes = daExecucao(new McpAgentRunner.Options(
                politicaDe(invoke, CS_TOOLS),
                ToolTrustPolicy.untrustedByDefault(),
                ToolApprovalPolicy.none(),
                McpAgentRunner.DEFAULT_MAX_ITERATIONS,
                br.com.archflow.model.config.LLMConfigPatch.empty(),
                CS_RACIOCINIO_MINIMO), invoke, uso);
        String systemPrompt = promptDe(invoke, CS_SYSTEM_PROMPT);
        String entrada = entradaDoAgente(invoke);

        // UMA nova tentativa quando a resposta não traz o sentimento.
        //
        // Medido em 21/09 na bancada do VendaX: 21 de 48 leituras falharam aqui, e a falha não
        // seguia a janela — a mesma janela deu certo numa versão do prompt e errado na outra. Das
        // que deixaram rastro, a resposta veio VAZIA: sem texto, sem finishReason e sem uso
        // reportado, ou com o teto de tokens gasto inteiro em raciocínio. É falha do provedor, não
        // da conversa, e a segunda chamada costuma acertar. O mesmo contador soma as duas: o custo
        // da tentativa extra aparece no uso, e não fica escondido.
        String resposta = null;
        for (int tentativa = 1; tentativa <= CS_TENTATIVAS; tentativa++) {
            resposta = runner.run(invoke.tenantId(), systemPrompt, entrada, client, opcoes).finalText();
            String json = sentimentoEm(resposta);
            if (json != null) {
                // Vale também para o prompt que o Core manda na definição: a trava do vocabulário
                // não pode depender de qual prompt rodou.
                return VendaxResult.ok(invoke, TYPE_SENTIMENT, MotivoDaLeitura.normalizar(json));
            }
            log.warn("CS sem sentimento em JSON (tentativa {}/{}, trace={}): {}",
                    tentativa, CS_TENTATIVAS, invoke.traceId(), trechoDaResposta(resposta));
        }
        // O trecho vai no erro: sem ele, o próximo diagnóstico depende de alguém ler este log.
        return VendaxResult.error(invoke, "CS não devolveu um sentimento em JSON ("
                + CS_TENTATIVAS + " tentativas); última resposta: " + trechoDaResposta(resposta));
    }

    /** O prefixo do erro tem ~75 caracteres; com o trecho, fica abaixo de 255. */
    static final int TRECHO = 170;

    /**
     * O CS pensa o mínimo antes de responder: devolve quatro campos (cinco com o motivo).
     *
     * <p>Medido em 21/09/2026 contra {@code google/gemini-2.5-flash-lite} no OpenRouter, no formato
     * do CS (system + tools + janela). Sem parâmetro, o modelo gasta ~980 dos 1.024 tokens pensando,
     * e metade das chamadas falha: {@code MALFORMED_FUNCTION_CALL} (a "resposta vazia" que o VendaX
     * via), JSON cortado no teto, ou nada escrito. Os três jeitos documentados de DESLIGAR —
     * {@code effort: none}, {@code max_tokens: 0}, {@code enabled: false} — são ignorados por este
     * modelo: o raciocínio continua em ~980. {@code effort: minimal} é o que funciona: ~350 tokens
     * de raciocínio e o JSON inteiro. Subir o teto piora — o raciocínio cresce junto.</p>
     *
     * <p>Vale só aqui, no passo do CS; os outros agentes seguem como estão. Vai como patch do passo,
     * então um patch de fluxo ou de tenant não o desfaz. Trocar de modelo pede medir de novo:
     * {@code minimal} é o que ESTE aceita.</p>
     */
    static final br.com.archflow.model.config.LLMConfigPatch CS_RACIOCINIO_MINIMO =
            br.com.archflow.model.config.LLMConfigPatch.fromMap(
                    java.util.Map.of("reasoning", java.util.Map.of("effort", "minimal")));

    /** Uma tentativa e uma repetição. Mais que isso seria insistir num provedor que está falhando. */
    static final int CS_TENTATIVAS = 2;

    /**
     * O objeto JSON da resposta, ou {@code null} se não há um que se leia.
     *
     * <p>Antes, qualquer texto com chaves passava — prosa com "{" e "}" chegava ao Core como
     * sentimento e era recusada lá, sem contexto. Agora o que não desserializa como objeto é falha
     * aqui, onde ainda dá para tentar de novo.</p>
     */
    static String sentimentoEm(String resposta) {
        String json = extractJson(resposta);
        if (json == null) {
            return null;
        }
        try {
            // Sem FAIL_ON_TRAILING_TOKENS o Jackson lê o PRIMEIRO objeto e ignora o resto — e
            // "{…} ou talvez {…}" passaria por um sentimento só.
            return MAPPER.reader()
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(json) instanceof com.fasterxml.jackson.databind.node.ObjectNode ? json : null;
        } catch (Exception naoLe) {
            return null;
        }
    }

    /**
     * O começo do que o modelo devolveu, numa linha, para o erro e o log.
     *
     * <p>Cabe, com o prefixo da mensagem, nos 255 caracteres de {@code resultado_bancada.erro} do
     * Core — um trecho cortado lá perderia justamente o fim, que é onde um JSON truncado se
     * denuncia. A resposta foi escrita a partir de uma conversa com o cliente e vai para o Core,
     * que já é dono dessa conversa. Quebras de linha viram espaço, para o trecho não se passar por
     * outra linha do log.</p>
     */
    static String trechoDaResposta(String resposta) {
        if (resposta == null || resposta.isBlank()) {
            return "(vazia)";
        }
        String limpo = resposta.strip().replaceAll("\\s+", " ");
        return "\"" + (limpo.length() <= TRECHO ? limpo : limpo.substring(0, TRECHO) + "…") + "\"";
    }

    static final String CS_SYSTEM_PROMPT = """
            Você é o agente CS (Customer Success) do VendaX. Avalie o sentimento do CLIENTE no
            TRECHO DE CONVERSA recebido, não em uma frase isolada — o que importa é para onde a
            conversa está indo, no contexto do relacionamento dele com o vendedor.

            Se a mensagem sugerir insatisfação com entrega, corte de itens ou atraso, use
            `obter_eventos_operacionais` para confirmar se houve um evento real antes de concluir —
            um cliente que reclama de atraso comprovado é diferente de um mal-humorado.

            Responda APENAS com um JSON, sem texto em volta e sem cercas de código:
            {"score": <int -10..10>, "trend": "SUBINDO|ESTAVEL|CAINDO",
             "motivo": "<UM valor da tabela abaixo>",
             "tone": "<orientação ao vendedor, UMA frase de no máximo 140 caracteres>",
             "bigCustomer": <true|false>}

            O tom aparece num selo acima da conversa, ao lado do score: precisa ser lido de relance.

            `motivo` é do que o CLIENTE se queixa ou o que ele cobra, nas mensagens DELE — um valor só,
            o principal, copiado exatamente desta tabela:
            - CORTE_OU_FALTA: item cortado do pedido, faltou, veio incompleto, sem estoque;
            - ATRASO_NA_ENTREGA: entrega atrasada, prazo estourado, já era para ter chegado;
            - QUALIDADE_DO_PRODUTO: avaria, vencido, produto errado, troca, devolução;
            - PRECO: preço alto, desconto negado, comparação com concorrente;
            - FINANCEIRO: boleto, cobrança, crédito, nota fiscal, pagamento;
            - ATENDIMENTO: demora em responder, falta de retorno, tratamento;
            - ANDAMENTO_DO_PEDIDO: pergunta pelo status de pedido ou de cotação, SEM reclamar de atraso;
            - OUTRO: há uma queixa clara e ela não cabe acima;
            - NENHUM: não há queixa nem cobrança — conversa neutra, saudação, agradecimento, elogio.
            NENHUM é a resposta mais comum: não force um motivo para preencher o campo. Perguntar onde
            está o pedido é ANDAMENTO_DO_PEDIDO; dizer que ele já devia ter chegado é
            ATRASO_NA_ENTREGA. O motivo é o que o cliente ALEGA: se ele reclama de corte e
            `obter_eventos_operacionais` não mostra corte nenhum, o motivo continua CORTE_OU_FALTA —
            quem confere é o sistema, depois. O motivo descreve o cliente; não é instrução ao vendedor
            e não leva texto além do valor da tabela.

            score negativo = insatisfação. Na dúvida, use 0 e trend ESTAVEL: um sentimento inventado
            aciona tarefa de crise à toa.
            """;

    /**
     * A mensagem de usuário do agente: <b>o envelope renderizado por inteiro</b>, igual pelos dois
     * caminhos.
     *
     * <p>Renderizar tudo é protocolo, não negócio — "eis o que recebi" —, e é o que dispensa o
     * executor de saber que o QP precisa de {@code entrada} e o CS de uma janela. Cada agente diz
     * no próprio prompt o que fazer com os campos; nenhum deles precisa de um {@code case} aqui.</p>
     *
     * <p>Existia duplicada: o caminho por nome mandava {@code clienteRef} + conversa, e o
     * caminho de fluxo só a conversa. A divergência não aparece como erro — aparece como
     * <b>outra resposta</b>. Medindo a mesma conversa nos dois, o sentimento caiu de
     * {@code -7/CAINDO} para {@code 0/ESTAVEL}: sem o identificador, o {@code obter_cliente_360}
     * fica sem o que consultar e o modelo segue a instrução de prudência do próprio prompt.</p>
     *
     * <p>Uma função só, porque comparar dois caminhos exige que a única diferença entre eles
     * seja a que se quer medir.</p>
     */
    String entradaDoAgente(VendaxInvoke invoke) {
        StringBuilder sb = new StringBuilder();
        sb.append("clienteRef=").append(nullSafe(invoke.customerRef()))
          .append("\nvendedorRef=").append(nullSafe(invoke.vendorRef()))
          .append("\nmodoEntrada=").append(modoEntrada(invoke));
        if (invoke.text() != null && !invoke.text().isBlank()) {
            sb.append("\nentrada=").append(invoke.text());
        }
        String conversa = conversaDe(invoke);
        if (conversa != null && !conversa.isBlank()) {
            sb.append("\n").append(conversa);
        }
        String dados = dadosDoPayload(invoke);
        if (dados != null) {
            sb.append("\npayload=").append(dados);
        }
        return sb.toString();
    }

    /**
     * O resto do payload — tudo menos a janela de conversa, que já foi renderizada acima.
     *
     * <p>Até a consulta ao NS, o único payload era a janela do CS, e o que viesse além dela era
     * descartado aqui sem aviso. A consulta carrega no payload o SKU, o preço e a quantidade que o
     * agente precisa passar à tool; sem esta linha o modelo teria de inventá-los. Vai inteiro, como
     * JSON, pelo mesmo motivo do resto do envelope: dizer "eis o que recebi" é protocolo, e cada
     * agente diz no próprio prompt o que fazer com os campos.</p>
     *
     * @return {@code null} quando não há nada além da janela — a entrada do CS fica como era
     */
    String dadosDoPayload(VendaxInvoke invoke) {
        if (invoke.payload() == null || invoke.payload().isBlank()) {
            return null;
        }
        try {
            var no = MAPPER.readTree(invoke.payload());
            if (!(no instanceof com.fasterxml.jackson.databind.node.ObjectNode objeto)) {
                return null;
            }
            var resto = objeto.deepCopy();
            resto.remove("messages");
            return resto.isEmpty() ? null : MAPPER.writeValueAsString(resto);
        } catch (Exception e) {
            return null;                  // ilegível: conversaDe já registrou
        }
    }

    /** Modo de entrada: o Core manda texto de canal; ditado/imagem/PDF entram quando o multimodal for fiado. */
    private String modoEntrada(VendaxInvoke invoke) {
        return "TEXTO_CLIENTE";
    }

    /**
     * O trecho recente da conversa, como o CS precisa lê-lo.
     *
     * <p>O Core manda a janela em {@code payload.messages} (direção + texto) porque sentimento sobre
     * uma frase solta não entende o que está acontecendo: "chegou?" é neutro sozinho e é
     * impaciência logo depois de uma reclamação. Sem a janela — acionamento antigo, ou payload
     * ausente — cai no texto da mensagem, para não deixar de avaliar.</p>
     */
    String conversaDe(VendaxInvoke invoke) {
        if (invoke.payload() == null || invoke.payload().isBlank()) {
            return "";                    // sem janela; `entrada` já carrega o texto
        }
        try {
            var mensagens = MAPPER.readTree(invoke.payload()).path("messages");
            if (!mensagens.isArray() || mensagens.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder("conversa (mais antiga primeiro):");
            for (var m : mensagens) {
                String texto = m.path("text").asText("");
                if (texto.isBlank()) {
                    continue;
                }
                String quem = "INBOUND".equalsIgnoreCase(m.path("direction").asText())
                        ? "cliente" : "vendedor";
                sb.append("\n").append(quem).append(": ").append(texto);
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("Janela de conversa ilegível (conv={}): {}",
                    invoke.conversationId(), e.getMessage());
            return "";
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * O prompt que vai rodar: o do Core quando ele mandou (RFC-013), senão o embutido.
     *
     * <p>O fallback existe para a migração ser reversível: se a skill sair do ar ou a composição
     * falhar, o agente continua rodando com o comportamento que já rodava — em vez de parar.</p>
     */
    private String promptDe(VendaxInvoke invoke, String embutido) {
        var def = invoke.definicao();
        if (def != null && def.temPrompt()) {
            log.debug("Agente {} usando definição do Core ({})", invoke.agent(), def.versao());
            return def.systemPrompt();
        }
        return embutido;
    }

    /**
     * A chave de idempotência que o Core embutiu no prompt do QP.
     *
     * <p>Ela está dentro do texto (`chaveIdempotencia="qp-…"`), porque é o modelo que a repassa às
     * tools. Extrair aqui garante que o resultado devolvido ao Core carregue a mesma chave — sem
     * isso, o Core não consegue correlacionar a cotação firmada com o invoke que a pediu.</p>
     */
    static String chaveDe(DefinicaoDeAgente def) {
        if (!def.temPrompt()) {
            return null;
        }
        Matcher m = CHAVE_NO_PROMPT.matcher(def.systemPrompt());
        return m.find() ? m.group(1) : null;
    }

    private static final Pattern CHAVE_NO_PROMPT =
            Pattern.compile("chaveIdempotencia=\"([^\"]+)\"");

    /** A allowlist do Core, quando veio. O tenant não amplia — quem declara é a skill. */
    private ToolAccessPolicy politicaDe(VendaxInvoke invoke, Set<String> embutida) {
        var def = invoke.definicao();
        if (def != null && def.temTools()) {
            return ToolAccessPolicy.allowOnly(Set.copyOf(def.tools()));
        }
        return ToolAccessPolicy.allowOnly(embutida);
    }

    /** Extrai o primeiro objeto JSON do texto — o modelo às vezes embrulha em cerca de código. */
    static String extractJson(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    private static String causeOf(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
