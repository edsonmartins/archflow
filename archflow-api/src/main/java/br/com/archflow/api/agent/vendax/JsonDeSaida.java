package br.com.archflow.api.agent.vendax;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Apenas documento completo ou uma cerca externa completa. Não extrai fragmentos nem repara JSON.
 *
 * <p>Há duas normalizações, ambas <b>nomeadas, estreitas e registradas</b> em
 * {@code agent_output_normalized}. Nenhuma é reparo genérico.</p>
 * <ul>
 *   <li>{@code externalFence}: uma cerca {@code ```json} em volta do documento inteiro.</li>
 *   <li>{@code emptySpan}: um {@code <span>} vazio entre tokens, <b>fora de qualquer string</b>.
 *       Medido no VendaX em 15/11 (VEN-1132): o gemini-3.5-flash-lite, no NS de consulta, às vezes
 *       devolvia {@code {"parametro": {"descontoPedidoPb": null,<span> </span>"caixas": 20}, …}}.
 *       Eram 5, 0 e 3 casos em três rodadas de 120, com a resposta certa nos que deu para ler. Só
 *       se tenta quando a primeira leitura falha, e só uma vez.</li>
 * </ul>
 */
final class JsonDeSaida {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(JsonDeSaida.class);
    /** Exatamente {@code <span>}, só espaço em branco e {@code </span>}: sem atributo, sem conteúdo. */
    private static final Pattern SPAN_VAZIO = Pattern.compile("<span>\\s*</span>");

    private JsonDeSaida() {}

    static String objeto(String texto) {
        if (texto == null || texto.isBlank()) return null;
        String s = texto.trim();
        boolean cerca = s.startsWith("```");
        if (cerca) {
            int linha = s.indexOf('\n');
            if (linha < 0 || !s.endsWith("```")) return null;
            String linguagem = s.substring(3, linha).trim();
            if (!linguagem.isEmpty() && !"json".equals(linguagem)) return null;
            s = s.substring(linha + 1, s.length() - 3).trim();
        }
        if (ehObjeto(s)) {
            if (cerca) log.info("agent_output_normalized: externalFence=true");
            return s;
        }
        SemSpan normalizado = semSpanVazioForaDeString(s);
        if (normalizado.removidos() == 0 || !ehObjeto(normalizado.texto())) {
            return null;
        }
        log.info("agent_output_normalized: {}emptySpan=true removed={}",
                cerca ? "externalFence=true " : "", normalizado.removidos());
        return normalizado.texto();
    }

    private static boolean ehObjeto(String s) {
        try {
            var n = MAPPER.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(s);
            return n != null && n.isObject();
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalido) {
            return false;
        }
    }

    record SemSpan(String texto, int removidos) {}

    /**
     * Remove os {@code <span>} vazios que estão fora de string JSON.
     *
     * <p>Acompanha aspas e escape ({@code \"}) para saber onde está. O que está dentro de uma
     * string (o valor de {@code "resposta"}, por exemplo) é texto do modelo e fica como veio.
     * Span com conteúdo, outra tag, vírgula ou aspa faltando: nada é tocado, e a leitura seguinte
     * falha como falharia.</p>
     */
    static SemSpan semSpanVazioForaDeString(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean emString = false;
        boolean escape = false;
        int removidos = 0;
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (emString) {
                out.append(c);
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    emString = false;
                }
                i++;
                continue;
            }
            if (c == '"') {
                emString = true;
            } else if (c == '<') {
                Matcher m = SPAN_VAZIO.matcher(s).region(i, s.length());
                if (m.lookingAt()) {
                    removidos++;
                    i = m.end();
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return new SemSpan(out.toString(), removidos);
    }
}
