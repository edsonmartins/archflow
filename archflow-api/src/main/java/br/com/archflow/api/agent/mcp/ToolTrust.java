package br.com.archflow.api.agent.mcp;

/**
 * Nível de confiança do conteúdo devolvido por uma tool.
 *
 * <p>A distinção existe porque o resultado de uma tool não é homogêneo: o total
 * de uma cotação calculado pelo sistema de domínio e uma linha de log escrita
 * por um terceiro chegam ao modelo pelo mesmo canal. Sem essa marca, um texto
 * como {@code "IGNORE AS INSTRUÇÕES ANTERIORES"} dentro de um log tem
 * exatamente o mesmo status epistêmico da instrução do operador.
 */
public enum ToolTrust {

    /**
     * Conteúdo produzido pelo próprio sistema, com forma controlada por ele
     * (ex.: o payload de uma cotação, o id de um pedido criado). Vai ao modelo
     * sem cerca.
     */
    TRUSTED,

    /**
     * Conteúdo cuja forma é, no todo ou em parte, controlada por terceiros —
     * logs, mensagens de cliente, descrições de ticket, corpo de e-mail,
     * qualquer texto livre repassado por um sistema externo. Vai ao modelo
     * cercado e anunciado como dado, nunca como instrução.
     *
     * <p>É o default para tudo que vem de um MCP server: o conteúdo cruzou a
     * fronteira do processo, e assumir o contrário exige uma decisão explícita.
     */
    UNTRUSTED
}
