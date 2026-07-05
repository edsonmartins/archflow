package br.com.archflow.langchain4j.core.security;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Redação de mapas de configuração para logging seguro.
 *
 * <p>Os adapters recebem a config como {@code Map<String, Object>} com chaves
 * sensíveis embutidas (api.key, password, token, secret). Antes de logar uma
 * config, passe-a por {@link #redactForLogging(Map)} — os valores das chaves
 * sensíveis viram {@code ***}, o resto fica legível para diagnóstico.
 */
public final class ConfigSecrets {

    /** Substrings (case-insensitive) que marcam uma chave como sensível. */
    private static final String[] SENSITIVE_MARKERS = {
            "key", "password", "passwd", "secret", "token", "credential", "auth"
    };

    private static final String REDACTED = "***";

    private ConfigSecrets() {
    }

    /**
     * Retorna uma cópia rasa do mapa com os valores das chaves sensíveis
     * mascarados. O mapa original não é alterado.
     */
    public static Map<String, Object> redactForLogging(Map<String, Object> config) {
        if (config == null) {
            return Map.of();
        }
        Map<String, Object> redacted = new LinkedHashMap<>(config.size());
        for (Map.Entry<String, Object> e : config.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            if (value instanceof Secret) {
                redacted.put(key, REDACTED);
            } else if (isSensitive(key) && value != null) {
                redacted.put(key, REDACTED);
            } else {
                redacted.put(key, value);
            }
        }
        return redacted;
    }

    /** {@code true} se o nome da chave indica um valor sensível. */
    public static boolean isSensitive(String key) {
        if (key == null) {
            return false;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        for (String marker : SENSITIVE_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
