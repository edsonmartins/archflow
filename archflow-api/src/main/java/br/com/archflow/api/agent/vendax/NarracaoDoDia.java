package br.com.archflow.api.agent.vendax;

import br.com.archflow.api.agent.mcp.McpAgentRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A narração do dia do vendedor pelo AP (VendaX, ADR-035, plano T8).
 *
 * <h2>Verbalizado, não decidido</h2>
 *
 * <p>O Core calcula tudo — quantas tarefas foram cumpridas, quantas faltam, qual vence a seguir — e
 * manda a contagem pronta. O modelo só <b>redige</b>. Ele não escolhe tarefa, não decide o que é
 * urgente e não julga o vendedor (isso é do PGU). Por isso o agente roda sem ferramenta nenhuma e
 * numa volta só: tudo o que a frase pode dizer está na entrada.</p>
 *
 * <h2>O Core confere</h2>
 *
 * <p>Todo número da frase tem de estar no payload; qualquer outro derruba a frase inteira. O texto
 * vai como o modelo o escreveu — reescrevê-lo aqui seria decidir o que a conferência do Core vê.</p>
 *
 * <h2>Classe de lote</h2>
 *
 * <p>Ninguém está olhando para a tela esperando: o Core pede no fim da tarde, uma vez por vendedor,
 * e mostra a frase quando ela chegar. Sem prazo interativo — um {@code OK} atrasado é aproveitado.</p>
 */
final class NarracaoDoDia {

    static final String REASON = "ap:narracao";
    static final String TIPO = "ap_narracao";

    /** Entrada → frase. Uma volta a mais só serviria para consultar dado, e não há o que consultar. */
    static final int MAX_ITERACOES = 1;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String SYSTEM_PROMPT = """
            Você é o agente AP do VendaX. Redige a frase que fecha o dia do vendedor, mostrada no
            topo do cockpit, embaixo dos números do dia. Você NÃO calcula nada: a contagem já vem
            pronta em `payload`, e você só a coloca em palavras.

            O payload traz:
            - dia (AAAA-MM-DD) e agora (HH:MM), já no fuso do vendedor — não converta;
            - total, cumpridas, comResultado, abertas, naoCumpridas, atrasadas;
            - proxima, quando existe: cliente (pode ser nulo), acao, venceAs (HH:MM), atrasada.

            Escreva de UMA a TRÊS frases curtas em português, para ler no celular, sobre a FILA:
            - quantas foram cumpridas, de quantas;
            - o que vence a seguir e a que horas, se houver `proxima`;
            - quanto falta para fechar o dia.

            Regras — a frase é conferida, e a frase inteira é descartada se alguma falhar:
            - TODO número citado tem de estar no payload: as contagens, as horas e minutos de `agora`
              e de `proxima.venceAs`, o dia e o mês de `dia`, e números que façam parte do nome do
              cliente. "17h", "17:00" e "17h30" são lidos como 17, 0 e 30.
            - NÃO converta horas, NÃO some, NÃO calcule porcentagem: "75%" é recusado.
            - Sem `proxima`, não fale de próximo vencimento, horário nem cliente.
            - Com `proxima.cliente` nulo, não nomeie o cliente ("a próxima tarefa vence às 17h").
            - Não nomeie nenhum cliente além de `proxima.cliente`.
            - Não fale de dinheiro.
            - Não julgue o vendedor ("você tem deixado para o fim") e não recomende além da conta:
              "uma ligação fecha o dia" é contagem; "ligue primeiro para X porque responde melhor de
              tarde" é julgamento e não sai daqui.
            - Não prometa nada ao cliente: só o vendedor lê esta frase.
            - Com total 0, diga que não há tarefa para hoje, sem inventar nada.
            - No máximo 500 caracteres. Sem markdown, sem emoji, sem aspas em volta.

            Responda APENAS com a frase.
            """;

    private NarracaoDoDia() {
    }

    /** {@code OK} com a frase, ou {@code ERROR} se o modelo não redigiu nada. */
    static VendaxResult resultado(VendaxInvoke invoke, McpAgentRunner.Result result) {
        String narracao = narracao(result.finalText());
        if (narracao.isBlank()) {
            return VendaxResult.error(invoke, "O AP não redigiu a narração do dia");
        }
        ObjectNode rich = MAPPER.createObjectNode();
        rich.put("narracao", narracao);
        try {
            return VendaxResult.ok(invoke, TIPO, MAPPER.writeValueAsString(rich));
        } catch (Exception e) {
            return VendaxResult.error(invoke, "Falha ao serializar a narração: " + e.getMessage());
        }
    }

    /**
     * A frase. O prompt pede texto puro; se o modelo embrulhar num {@code {"narracao": ...}}, vale o
     * campo — é a forma do rich object, e o Core aceita as duas.
     */
    private static String narracao(String texto) {
        if (texto == null) {
            return "";
        }
        String limpo = texto.strip();
        if (limpo.startsWith("{")) {
            try {
                JsonNode no = MAPPER.readTree(limpo);
                if (no.path("narracao").isTextual()) {
                    return no.path("narracao").asText().strip();
                }
            } catch (Exception naoEraJson) {
                // texto que começa com chave e não é JSON: vale como está
            }
        }
        return limpo;
    }
}
