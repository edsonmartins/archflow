package br.com.archflow.api.agent.vendax;

import br.com.archflow.api.agent.mcp.McpAgentRunner;
import br.com.archflow.api.agent.mcp.ToolAccessPolicy;
import br.com.archflow.api.agent.qp.QpAgentService;
import br.com.archflow.api.mcp.vendax.VendaxMcpClientProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
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

    private final QpAgentService qpAgent;
    private final McpAgentRunner runner;
    private final VendaxMcpClientProvider vendax;
    private final VendaxResultSender resultSender;
    private final ExecutorService executor;
    private final VendaxAgentMetrics metrics;
    /** Nulo numa instalação sem motor de fluxo: só o caminho por nome de agente responde. */
    private final AgentFlowRunner fluxo;

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
        br.com.archflow.langchain4j.mcp.client.CorrelacaoMcp.definir(
                invoke.idempotencyKey(), invoke.traceId());
        try {
            // O caminho genérico vem ANTES do switch, e é o que o deve substituir: quando a
            // definição traz um fluxo, este runtime não precisa saber que agente é. O switch
            // continua abaixo só enquanto houver skill em PROMPT — cada agente que virar FLUXO
            // apaga um `case`.
            if (invoke.definicao() != null && invoke.definicao().eFluxo()) {
                VendaxResult porFluxo = runFluxo(invoke);
                if (porFluxo != null) {
                    resultSender.send(porFluxo);
                }
                if (metrics != null) metrics.completed(startedAt);
                return;
            }

            VendaxResult result = switch (agent) {
                case "QP" -> runQp(invoke);
                case "CS" -> runCs(invoke);
                default -> {
                    // Agente que o Core aciona e o ArchFlow ainda não implementa (US, ASSISTANT,
                    // TRANSCRIBER). Devolver ERROR é deliberado: o vendedor vê que algo não rodou,
                    // em vez de esperar por uma resposta que nunca vem.
                    log.warn("Agente '{}' não implementado no ArchFlow (conv={})",
                            invoke.agent(), invoke.conversationId());
                    yield VendaxResult.error(invoke,
                            "Agente " + invoke.agent() + " ainda não é executado pelo ArchFlow");
                }
            };
            if (result != null) {
                resultSender.send(result);
            }
            if (metrics != null) metrics.completed(startedAt);
        } catch (Exception e) {
            if (metrics != null) metrics.failed(startedAt, e);
            log.error("Agente {} falhou (conv={}): {}",
                    invoke.agent(), invoke.conversationId(), e.getMessage(), e);
            resultSender.send(VendaxResult.error(invoke, causeOf(e)));
        } finally {
            // A thread é reusada entre invokes. Sem limpar, o PRÓXIMO agente a rodar aqui mandaria
            // a correlação deste — e o evento da cotação sairia amarrado à conversa errada. Um
            // evento errado com confiança é pior que um evento sem correlação nenhuma.
            br.com.archflow.langchain4j.mcp.client.CorrelacaoMcp.limpar();
        }
    }

    /**
     * O agente como documento: executa e devolve o que saiu, sem interpretar.
     *
     * <p>O tipo do rich object vem do {@code saidaSchema} da definição — {@code sentiment@1} vira
     * {@code sentiment}. Quem declara é o Core, e é o que mantém a regra da {@code ADR-025} D-1: o
     * executor não decide se produziu uma cotação ou um sentimento, porque não sabe o que são.</p>
     */
    private VendaxResult runFluxo(VendaxInvoke invoke) {
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
                entradaDoAgente(invoke));

        if (saida.suspenso()) {
            log.info("Fluxo de {} suspenso aguardando decisão humana (conv={})",
                    invoke.agent(), invoke.conversationId());
            return null;
        }
        String conteudo = extractJson(saida.texto());
        if (conteudo == null) {
            // Mesmo tratamento do CS embutido: o Core recusa o que não desserializa, então mandar
            // texto solto só empurra a falha para lá com menos contexto.
            return VendaxResult.error(invoke,
                    "O fluxo de " + invoke.agent() + " não devolveu um JSON");
        }
        return VendaxResult.ok(invoke, tipo, conteudo);
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

    private VendaxResult runQp(VendaxInvoke invoke) {
        var def = invoke.definicao();
        QpAgentService.QpResult qp = qpAgent.quote(new QpAgentService.QpRequest(
                invoke.tenantId(), invoke.customerRef(), invoke.vendorRef(),
                modoEntrada(invoke), invoke.text(), List.of(),
                def != null && def.temPrompt() ? def.systemPrompt() : null,
                // A chave que o Core embutiu no prompt tem de ser a mesma que o resultado carrega.
                def != null ? chaveDe(def) : null),
                def != null ? def.versao() : null);

        if (qp.quote() == null || qp.quote().isBlank()) {
            // O agente rodou mas não chegou a cotar (pediu confirmação, não achou o SKU). Não é
            // erro: virar agent.error poluiria a conversa a cada mensagem sem intenção de pedido.
            log.info("QP concluiu sem cotação (conv={}): {}",
                    invoke.conversationId(), qp.finalText());
            return null;
        }
        return VendaxResult.ok(invoke, TYPE_QUOTE, qp.quote());
    }

    /**
     * CS: avalia o sentimento da conversa. O contrato do Core é um JSON
     * {@code {score:-10..10, trend, tone, bigCustomer}} — o modelo devolve exatamente isso como
     * texto final, e o Core recusa (com log) o que não desserializar.
     */
    private VendaxResult runCs(VendaxInvoke invoke) {
        var client = vendax.clientFor(invoke.tenantId(),
                invoke.definicao() != null ? invoke.definicao().versao() : null);
        McpAgentRunner.Result result = runner.run(invoke.tenantId(),
                promptDe(invoke, CS_SYSTEM_PROMPT),
                entradaDoAgente(invoke),
                client, politicaDe(invoke, CS_TOOLS));

        String json = extractJson(result.finalText());
        if (json == null) {
            return VendaxResult.error(invoke, "CS não devolveu um sentimento em JSON");
        }
        return VendaxResult.ok(invoke, TYPE_SENTIMENT, json);
    }

    private static final String CS_SYSTEM_PROMPT = """
            Você é o agente CS (Customer Success) do VendaX. Avalie o sentimento do CLIENTE no
            TRECHO DE CONVERSA recebido, não em uma frase isolada — o que importa é para onde a
            conversa está indo, no contexto do relacionamento dele com o vendedor.

            Se a mensagem sugerir insatisfação com entrega, corte de itens ou atraso, use
            `obter_eventos_operacionais` para confirmar se houve um evento real antes de concluir —
            um cliente que reclama de atraso comprovado é diferente de um mal-humorado.

            Responda APENAS com um JSON, sem texto em volta e sem cercas de código:
            {"score": <int -10..10>, "trend": "SUBINDO|ESTAVEL|CAINDO",
             "tone": "<orientação ao vendedor, UMA frase de no máximo 140 caracteres>",
             "bigCustomer": <true|false>}

            O tom aparece num selo acima da conversa, ao lado do score: precisa ser lido de relance.

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
        return sb.toString();
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
