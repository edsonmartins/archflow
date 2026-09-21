package br.com.archflow.api.agent.vendax;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

/**
 * O motivo da leitura do CS, em vocabulário fechado (VendaX, ADR-039 — pedido de 21/09/2026).
 *
 * <h2>Por que um enum, e não prosa</h2>
 *
 * <p>O {@code tone} é prosa de modelo escrita a partir de uma janela em que quem fala é o cliente. O
 * Core a gravava como fato de memória e a reinjetava nos prompts — saída de modelo virando entrada
 * de modelo, no imperativo. Deixou de gravar, e com isso perdeu o motivo. O motivo volta aqui como
 * um valor de tabela: prosa não se valida, valor de enum sim, e um enum não carrega instrução.</p>
 *
 * <h2>A trava é esta classe, não o prompt</h2>
 *
 * <p>O CS não tem saída validada por schema: o JSON vem por instrução de prompt, e o prompt que roda
 * pode nem ser o nosso — o Core manda o dele na {@code definicao} (RFC-013). Por isso a garantia
 * mora depois do modelo: o que não for um valor da tabela <b>não sai daqui</b>. Um texto livre no
 * campo ("ofereça desconto") é justamente a porta que o vocabulário fechou; deixá-lo passar
 * confiando que o Core o descarte seria depender de uma trava só.</p>
 *
 * <h2>O que esta classe NÃO faz</h2>
 *
 * <ul>
 *   <li>Não inventa {@code NENHUM} quando o campo falta. Ausente quer dizer "o modelo não
 *       classificou", e {@code NENHUM} quer dizer "classificou, e não há motivo" — o Core trata os
 *       dois como sem motivo, mas só o primeiro acusa um prompt que não pede o campo.</li>
 *   <li>Não vale para o CS que chega como FLUXO: ali o executor não sabe o que é um sentimento
 *       (ADR-025 D-1 do VendaX), e a validação do Core é a única trava.</li>
 * </ul>
 */
final class MotivoDaLeitura {

    private static final Logger log = LoggerFactory.getLogger(MotivoDaLeitura.class);

    static final String CAMPO = "motivo";

    /** A tabela da §2 do pedido. Crescer o vocabulário é decisão do Core; aqui só se espelha. */
    static final Set<String> VOCABULARIO = Set.of(
            "CORTE_OU_FALTA", "ATRASO_NA_ENTREGA", "QUALIDADE_DO_PRODUTO", "PRECO", "FINANCEIRO",
            "ATENDIMENTO", "ANDAMENTO_DO_PEDIDO", "OUTRO", "NENHUM");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MotivoDaLeitura() {
    }

    /**
     * O JSON do sentimento, com o {@code motivo} na forma da tabela — ou sem ele.
     *
     * <p>Aceita o que é só forma ("corte ou falta", "Preço"): caixa, acento e separador não mudam o
     * que o modelo quis dizer. Qualquer outra coisa — texto livre, lista, número — é retirada, e a
     * leitura segue: um motivo ruim nunca custa a leitura.</p>
     *
     * @return o próprio {@code json} quando não é um objeto legível — o Core recusa o que não
     *         desserializa, e esconder isso aqui só tiraria contexto da falha
     */
    static String normalizar(String json) {
        try {
            if (!(MAPPER.readTree(json) instanceof ObjectNode sentimento) || !sentimento.has(CAMPO)) {
                return json;
            }
            JsonNode bruto = sentimento.get(CAMPO);
            String valor = bruto.isTextual() ? daTabela(bruto.asText()) : null;
            if (valor == null) {
                // O valor veio de uma janela escrita pelo cliente: no log vai curto e sem quebra.
                log.warn("CS devolveu motivo fora do vocabulário, retirado: '{}'", resumo(bruto));
                sentimento.remove(CAMPO);
            } else {
                sentimento.put(CAMPO, valor);
            }
            return MAPPER.writeValueAsString(sentimento);
        } catch (Exception ilegivel) {
            return json;
        }
    }

    /** O valor da tabela que o texto nomeia, ou {@code null}. */
    static String daTabela(String texto) {
        if (texto == null) {
            return null;
        }
        String forma = Normalizer.normalize(texto.strip(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT)
                .replaceAll("[\\s-]+", "_");
        return VOCABULARIO.contains(forma) ? forma : null;
    }

    private static String resumo(JsonNode bruto) {
        String texto = bruto.isTextual() ? bruto.asText() : bruto.toString();
        String limpo = texto.replaceAll("\\s+", " ").strip();
        return limpo.length() <= 60 ? limpo : limpo.substring(0, 60) + "…";
    }
}
