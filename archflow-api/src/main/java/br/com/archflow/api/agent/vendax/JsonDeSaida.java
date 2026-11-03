package br.com.archflow.api.agent.vendax;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Apenas documento completo ou uma cerca externa completa. Não extrai fragmentos nem repara JSON. */
final class JsonDeSaida {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(JsonDeSaida.class);
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
        try {
            var n = MAPPER.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(s);
            if (n == null || !n.isObject()) return null;
            if (cerca) log.info("agent_output_normalized: externalFence=true");
            return s;
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalido) {
            return null;
        }
    }
}
