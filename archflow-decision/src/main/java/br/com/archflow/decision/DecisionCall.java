package br.com.archflow.decision;

import java.time.Duration;
import java.util.Map;

/**
 * Como esta chamada deve ser feita: o que o nó configurou, mais a chave que o hospedeiro resolveu.
 *
 * @param tenantId quem pede (para o provedor que precise isolar por tenant)
 * @param model    o modelo a usar; vazio deixa o provedor usar o seu padrão
 * @param apiKey   resolvida pelo hospedeiro; <b>nunca</b> vem do documento do fluxo
 * @param timeout  teto da chamada
 * @param options  as opções do nó para este provedor (endpoint, limites, regras…)
 */
public record DecisionCall(String tenantId, String model, String apiKey, Duration timeout,
                           Map<String, Object> options) {
    public DecisionCall {
        timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        options = options == null ? Map.of() : Map.copyOf(options);
    }
}
