package br.com.archflow.model.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Preferência de roteamento de provedor enviada <b>na requisição</b> ao OpenRouter — o objeto
 * {@code provider} do corpo.
 *
 * <h2>Por que na requisição</h2>
 *
 * <p>Sem isto, retenção zero vale só pela configuração da conta no OpenRouter (Settings → Privacy):
 * uma caixa marcada. Trocar a chave, criar uma chave nova para um tenant ou alguém desmarcar a opção
 * manda a conversa do cliente a um provedor que retém e treina — sem erro, sem log. Na requisição a
 * garantia é testável, e um endpoint que não a atende devolve erro em vez de servir.
 *
 * <h2>Só os dois campos</h2>
 *
 * <p>O {@code ProviderPreferences} do OpenRouter tem {@code additionalProperties: false} no esquema
 * de decisões; por isso o objeto carrega só {@code zdr} e {@code data_collection}, e só os que foram
 * declarados. Ordem, sort, allow-list de provedores… ficam de fora de propósito.
 *
 * <p>Mora em {@code archflow-model} porque dois leitores o enviam — o hub de LLM (agentes) e o
 * provedor HTTP de decisões — e nenhum dos dois depende do outro; o formato no fio fica num lugar só.
 *
 * @param zdr            {@code true} roteia só para endpoints com retenção zero; {@code null} = não enviar
 * @param dataCollection {@code "deny"} ou {@code "allow"}; {@code null} = não enviar
 */
public record ProviderPreferences(Boolean zdr, String dataCollection) {

    /** Nenhuma preferência: o corpo da requisição não ganha o campo. */
    public static final ProviderPreferences NONE = new ProviderPreferences(null, null);

    public ProviderPreferences {
        if (dataCollection != null) {
            dataCollection = dataCollection.trim().toLowerCase(java.util.Locale.ROOT);
            if (dataCollection.isEmpty()) {
                dataCollection = null;
            } else if (!dataCollection.equals("deny") && !dataCollection.equals("allow")) {
                // Falhar aqui, e não mandar o valor: um "dney" ignorado pelo provedor seria
                // exatamente a garantia que parece dada e não está.
                throw new IllegalArgumentException(
                        "data_collection deve ser 'deny' ou 'allow', veio '" + dataCollection + "'");
            }
        }
    }

    /**
     * A partir das propriedades de deployment; valores em branco contam como ausentes.
     */
    public static ProviderPreferences of(String zdr, String dataCollection) {
        Boolean z = null;
        if (zdr != null && !zdr.isBlank()) {
            String v = zdr.trim();
            if (!v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false")) {
                throw new IllegalArgumentException("zdr deve ser true ou false, veio '" + zdr + "'");
            }
            z = Boolean.valueOf(v);
        }
        return new ProviderPreferences(z, dataCollection);
    }

    /** Nada a enviar. */
    public boolean isEmpty() {
        return zdr == null && dataCollection == null;
    }

    /** O objeto {@code provider} como vai no corpo; vazio quando {@link #isEmpty()}. */
    public Map<String, Object> toWire() {
        Map<String, Object> wire = new LinkedHashMap<>();
        if (zdr != null) {
            wire.put("zdr", zdr);
        }
        if (dataCollection != null) {
            wire.put("data_collection", dataCollection);
        }
        return wire;
    }
}
