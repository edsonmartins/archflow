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

    public VendaxAgentDispatcher(QpAgentService qpAgent, McpAgentRunner runner,
                                 VendaxMcpClientProvider vendax, VendaxResultSender resultSender,
                                 ExecutorService executor) {
        this.qpAgent = qpAgent;
        this.runner = runner;
        this.vendax = vendax;
        this.resultSender = resultSender;
        this.executor = executor;
    }

    /** Aceita a ordem e executa fora da requisição. */
    public void dispatch(VendaxInvoke invoke) {
        executor.submit(() -> runAndReport(invoke));
    }

    void runAndReport(VendaxInvoke invoke) {
        String agent = invoke.agent() == null ? "" : invoke.agent().toUpperCase();
        try {
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
        } catch (Exception e) {
            log.error("Agente {} falhou (conv={}): {}",
                    invoke.agent(), invoke.conversationId(), e.getMessage(), e);
            resultSender.send(VendaxResult.error(invoke, causeOf(e)));
        }
    }

    private VendaxResult runQp(VendaxInvoke invoke) {
        var def = invoke.definicao();
        QpAgentService.QpResult qp = qpAgent.quote(new QpAgentService.QpRequest(
                invoke.tenantId(), invoke.customerRef(), invoke.vendorRef(),
                modoEntrada(invoke), invoke.text(), List.of(),
                def != null && def.temPrompt() ? def.systemPrompt() : null,
                // A chave que o Core embutiu no prompt tem de ser a mesma que o resultado carrega.
                def != null ? chaveDe(def) : null));

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
        var client = vendax.clientFor(invoke.tenantId());
        McpAgentRunner.Result result = runner.run(invoke.tenantId(),
                promptDe(invoke, CS_SYSTEM_PROMPT),
                "clienteRef=" + nullSafe(invoke.customerRef()) + "\n" + conversaDe(invoke),
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
            return "mensagem=" + nullSafe(invoke.text());
        }
        try {
            var mensagens = MAPPER.readTree(invoke.payload()).path("messages");
            if (!mensagens.isArray() || mensagens.isEmpty()) {
                return "mensagem=" + nullSafe(invoke.text());
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
            return "mensagem=" + nullSafe(invoke.text());
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
