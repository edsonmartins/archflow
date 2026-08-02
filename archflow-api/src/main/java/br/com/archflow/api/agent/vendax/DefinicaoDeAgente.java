package br.com.archflow.api.agent.vendax;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * A definição do agente, resolvida pelo VendaX Core e entregue pronta no invoke (RFC-013).
 *
 * <p>O executor não consulta catálogo, não compõe prompt e não escolhe tools: recebe e executa. É o
 * que permite ao Core customizar comportamento por cliente — onde a multi-tenancy dele já existe —
 * sem que este runtime precise ganhar catálogo, versionamento e autorização próprios.</p>
 *
 * <p>Ausente no envelope significa "use o comportamento embutido": invokes antigos continuam válidos,
 * e um problema no catálogo do Core não impede o agente de rodar.</p>
 *
 * <p><b>PROMPT ou FLUXO.</b> Um agente de um passo chega como {@code systemPrompt}; um agente que é
 * composição de nós chega como {@code fluxo} — o documento já com os slots aplicados, que este
 * runtime executa sem saber o que ele faz. É o que dispensa um caminho de código por agente: com
 * prompt, cada agente novo pedia um {@code case} aqui dentro, e foi por aí que negócio do cliente
 * entrou num executor que deveria ser agnóstico.</p>
 *
 * @param tipo         {@code PROMPT} (default quando ausente) ou {@code FLUXO}
 * @param fluxo        documento do fluxo com os slots aplicados; nulo em PROMPT
 * @param systemPrompt prompt já composto com os slots do tenant
 * @param saidaSchema  contrato da resposta; nulo = saída livre
 * @param tools        allowlist; vazia = usar o default do agente
 * @param modelo       provider/model/baseUrl do tenant — inclusive endpoint on-premise
 * @param politica     custo máximo e timeout
 * @param versao       {@code cs@1+def3}; vai para a telemetria, para comparar versões de prompt
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DefinicaoDeAgente(
        String tipo,
        Map<String, Object> fluxo,
        String systemPrompt,
        String saidaSchema,
        List<String> tools,
        Map<String, Object> modelo,
        Map<String, Object> politica,
        String versao) {

    /**
     * Ausência de {@code tipo} é PROMPT, não erro: invoke de uma versão anterior do Core continua
     * válido, e o campo novo não pode transformar compatibilidade em falha.
     */
    public boolean eFluxo() {
        return "FLUXO".equalsIgnoreCase(tipo) && fluxo != null && !fluxo.isEmpty();
    }

    public boolean temPrompt() {
        return systemPrompt != null && !systemPrompt.isBlank();
    }

    public boolean temTools() {
        return tools != null && !tools.isEmpty();
    }
}
