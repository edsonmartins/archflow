package br.com.archflow.api.agent.vendax;

import br.com.archflow.api.agent.mcp.McpAgentRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * A consulta do vendedor ao NS (VendaX, ADR-038 D-3): o prompt, e como a resposta vira o rich
 * object {@code ns_consulta}.
 *
 * <h2>O parâmetro vem da chamada, não da afirmação do modelo</h2>
 *
 * <p>O Core refaz a conta com o {@code parametro} devolvido e recusa a frase inteira se ela citar
 * um número que não sai daquela conta. Se o parâmetro fosse o que o modelo <i>diz</i> ter usado,
 * uma divergência entre o dito e o feito — o modelo chama a tool com 850 e relata 800 — teria dois
 * desfechos, ambos ruins: a frase certa seria recusada pela conta errada, ou uma frase errada
 * passaria por coincidir com ela.</p>
 *
 * <p>Os números da frase saíram da tool, com os argumentos que <b>foram</b> à tool. Então o
 * parâmetro é lido de lá — da última chamada bem-sucedida de {@value #TOOL}, porque é dela que a
 * frase fala. É a mesma regra que tirou a identidade do cliente do argumento que o modelo
 * preenche: o que o transporte sabe não se pergunta ao modelo.</p>
 *
 * <h2>O volume só entra quando foi perguntado</h2>
 *
 * <p>A tool sempre recebe uma quantidade — a que o vendedor mencionou, ou a do pedido. O Core
 * escreve o eco do parâmetro antes da frase ("Com 8%: …"), então devolver a quantidade do pedido
 * como se fosse parte da pergunta produziria um eco que ninguém pediu. O volume entra quando o
 * modelo disse que ele fazia parte da pergunta, ou quando a quantidade que foi à tool difere da do
 * pedido — nos dois casos, com o valor da chamada.</p>
 *
 * <h2>Sem chamada, sem parâmetro</h2>
 *
 * <p>Se o modelo não chamou a tool, qualquer número na frase foi inventado. O resultado sai
 * {@code OK} com {@code parametro} vazio, que o Core registra como recusada, mantendo a resposta
 * por regra — é o contrato para "não entendi".</p>
 */
final class ConsultaAoNegociador {

    static final String TOOL = "plano_negociacao";
    static final String TIPO = "ns_consulta";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String SYSTEM_PROMPT = """
            Você é o agente NS (negociador) do VendaX respondendo a uma CONSULTA do vendedor, no meio
            de uma cotação. O vendedor está olhando para a tela esperando: seja direto, e chame a
            ferramenta uma vez só, salvo se ela recusar os argumentos.

            A consulta chega em `payload`: consultaId, pergunta, clienteRef, skuRef,
            precoUnitarioCents, quantidadeCaixas (a do pedido) e respostaPorRegra.

            1. Entenda a pergunta e decida o parâmetro:
               - o DESCONTO pedido, em ponto-base: 8% = 800; 8,5% ou "8 e meio" = 850;
               - e/ou o VOLUME, em caixas: "se levar 40" = 40.
               "Levar mais", sem número, não é um volume: não invente um. Use a quantidadeCaixas do
               payload e diga na frase qual volume a faixa exige.
               Se não entender nem desconto nem volume, NÃO chame a ferramenta: responda com o
               parâmetro vazio.

            2. Chame `plano_negociacao` com:
               {"clienteRef": <clienteRef>, "vendedorRef": <vendedorRef>,
                "itens": [{"skuRef": <skuRef>, "precoUnitarioCents": <precoUnitarioCents>,
                           "quantidadeCaixas": <o volume que você entendeu, ou a quantidadeCaixas do payload>,
                           "descontoPedidoPb": <o desconto em ponto-base, ou null>}]}

            3. Redija UMA frase curta em português, para ler no celular.

            Regras da frase — ela é conferida, e a frase inteira é descartada se alguma falhar:
            - NÃO comece pelo parâmetro. "Com 8%:" já é escrito antes da sua frase; comece pela
              conclusão.
            - Todo número citado precisa ter vindo da ferramenta — o teto sem escalar, o máximo pela
              margem, o volume exigido, o volume do pedido — ou ser o próprio desconto ou volume que
              você entendeu. Nenhum outro número.
            - Pode arredondar para uma casa (18,41 → 18,4) ou para o inteiro (18).
            - NÃO cite o número do passo ("Passo 2"): diga "o teto do seu passo".
            - NÃO prometa, não grave e não peça confirmação. "Então coloca 8%" continua sendo
              consulta: responda a conta.

            Responda APENAS com um JSON, sem texto em volta e sem cercas de código:
            {"parametro": {"descontoPedidoPb": <inteiro ou null>, "caixas": <número ou null>},
             "resposta": "<a frase>"}
            Se não entendeu a pergunta: {"parametro": {}, "resposta": ""}
            """;

    private ConsultaAoNegociador() {
    }

    /** O result da consulta: {@code OK} com o rich object, ou {@code ERROR} se não houver frase. */
    static VendaxResult resultado(VendaxInvoke invoke, McpAgentRunner.Result result) {
        JsonNode declarado = declaradoPeloModelo(result.finalText());
        String resposta = resposta(declarado, result.finalText());
        McpAgentRunner.ToolCall chamada = result.lastSuccessfulCall(TOOL);

        ObjectNode parametro = MAPPER.createObjectNode();
        if (chamada != null) {
            Map<String, Object> item = primeiroItem(chamada.arguments());
            Number desconto = numero(item.get("descontoPedidoPb"));
            Number caixas = volumePerguntado(item, declarado, invoke);
            if (desconto != null || caixas != null) {
                putNumero(parametro, "descontoPedidoPb", desconto);
                putNumero(parametro, "caixas", caixas);
            }
        }

        if (!parametro.isEmpty() && resposta.isBlank()) {
            // A conta foi feita e não virou frase: a tela não tem o que mostrar além da regra, e um
            // OK aqui seria registrado como resposta vazia. ERROR faz a tela parar de esperar.
            return VendaxResult.error(invoke, "O negociador consultou o plano e não redigiu resposta");
        }

        ObjectNode rich = MAPPER.createObjectNode();
        rich.set("parametro", parametro);
        rich.put("resposta", resposta);
        try {
            return VendaxResult.ok(invoke, TIPO, MAPPER.writeValueAsString(rich));
        } catch (Exception e) {
            return VendaxResult.error(invoke, "Falha ao serializar a consulta: " + e.getMessage());
        }
    }

    /**
     * O volume do parâmetro, sempre com o valor que foi à tool.
     *
     * @return {@code null} quando o volume não fazia parte da pergunta
     */
    private static Number volumePerguntado(Map<String, Object> item, JsonNode declarado,
                                           VendaxInvoke invoke) {
        Number naChamada = numero(item.get("quantidadeCaixas"));
        if (naChamada == null) {
            return null;
        }
        JsonNode caixasDeclaradas = declarado == null ? null
                : declarado.path("parametro").path("caixas");
        boolean modeloDisse = caixasDeclaradas != null && caixasDeclaradas.isNumber();
        Number doPedido = quantidadeDoPedido(invoke);
        boolean diferente = doPedido == null || !mesmoNumero(doPedido, naChamada);
        return modeloDisse || diferente ? naChamada : null;
    }

    private static Number quantidadeDoPedido(VendaxInvoke invoke) {
        if (invoke.payload() == null || invoke.payload().isBlank()) {
            return null;
        }
        try {
            JsonNode q = MAPPER.readTree(invoke.payload()).path("quantidadeCaixas");
            return q.isNumber() ? q.numberValue() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** O JSON que o modelo devolveu, se for um objeto legível. */
    private static JsonNode declaradoPeloModelo(String texto) {
        String json = VendaxAgentDispatcher.extractJson(texto);
        if (json == null) {
            return null;
        }
        try {
            JsonNode no = MAPPER.readTree(json);
            return no.isObject() ? no : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * A frase. Sem JSON legível, o texto cru vale como frase: o Core confere os números de
     * qualquer jeito, e descartar uma resposta boa por causa da embalagem só deixaria a tela
     * esperando pela regra.
     */
    private static String resposta(JsonNode declarado, String textoFinal) {
        if (declarado != null) {
            JsonNode r = declarado.path("resposta");
            return r.isTextual() ? r.asText().strip() : "";
        }
        return textoFinal == null ? "" : textoFinal.strip();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> primeiroItem(Map<String, Object> argumentos) {
        if (argumentos == null) {
            return Map.of();
        }
        Object itens = argumentos.get("itens");
        if (itens instanceof List<?> lista && !lista.isEmpty() && lista.get(0) instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of();
    }

    private static Number numero(Object valor) {
        return valor instanceof Number n ? n : null;
    }

    private static boolean mesmoNumero(Number a, Number b) {
        return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0;
    }

    /** Ponto-base é inteiro no contrato; um {@code 850.0} vindo do JSON sai como {@code 850}. */
    private static void putNumero(ObjectNode destino, String campo, Number valor) {
        if (valor == null) {
            destino.putNull(campo);
            return;
        }
        BigDecimal d = new BigDecimal(valor.toString()).stripTrailingZeros();
        if (d.scale() <= 0) {
            destino.put(campo, d.longValueExact());
        } else {
            destino.put(campo, d);
        }
    }
}
