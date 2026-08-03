package br.com.archflow.api.agent.mcp;

import br.com.archflow.langchain4j.mcp.McpClient;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.engine.ExecutionKeys;

import java.util.Optional;
import java.util.Set;

/**
 * O que um nó de fluxo precisa para falar MCP, fornecido por quem hospeda a execução.
 *
 * <p>Existe porque um componente do catálogo é instanciado por construtor sem argumentos: ele não
 * recebe o {@code McpAgentRunner}, nem sabe a qual servidor MCP a execução pertence. O host —
 * servidor ou agente local — coloca uma implementação disto no {@link ExecutionContext} e o
 * componente a busca em tempo de execução.</p>
 *
 * <p><b>Por que injeção e não config do nó.</b> O endereço do servidor MCP e a credencial do tenant
 * dentro do documento do fluxo seriam versionados e visíveis no designer, e mudariam por ambiente.
 * O documento descreve <em>o que</em> o passo faz; quem sabe <em>onde</em> é o host. É também o que
 * permite o mesmo fluxo rodar na nuvem e no edge sem tratamento especial: cada lado fornece o seu.</p>
 */
public interface McpAgentHost {

    /**
     * Chave sob a qual o host se anuncia no {@link ExecutionContext}.
     *
     * <p>Prefixo {@code transient} porque o host é <b>infraestrutura, não estado</b>: ele
     * carrega cliente HTTP e credencial, e o checkpoint do motor copia as variáveis do
     * contexto para o estado durável. Com a chave anterior o Jackson estourava ao serializar
     * o host, o checkpoint falhava com WARN e a retomada sumia sem ninguém notar.</p>
     */
    String CONTEXT_KEY = ExecutionKeys.TRANSIENT_PREFIX + "mcpHost";

    /** O runner já construído com o resolvedor de LLM, as métricas e o store de estado do host. */
    McpAgentRunner runner();

    /**
     * O cliente MCP desta execução.
     *
     * @param tenantId  tenant em execução, vindo do contexto
     * @param serverRef referência ao servidor declarada no nó, ou {@code null} para o padrão do host
     */
    McpClient clientFor(String tenantId, String serverRef);

    /**
     * O teto de tools do tenant — a allowlist do nó é <b>interseccionada</b> com este conjunto,
     * nunca somada.
     *
     * <p>É o que impede a customização de ampliar a capacidade: num produto onde o cliente edita o
     * fluxo, uma allowlist que viesse só do nó deixaria o próprio cliente conceder ao agente tools
     * que a plataforma não lhe deu. Conjunto vazio significa "sem teto declarado".</p>
     */
    default Set<String> toolCeiling(String tenantId) {
        return Set.of();
    }

    /**
     * Anuncia este host no contexto, para os nós de MCP o encontrarem.
     *
     * <p>É o par de {@link #from}, e existe para que a chave não precise ser escrita fora daqui:
     * quem monta um {@link ExecutionContext} chama isto, sem saber sob que nome o host mora. Host
     * ausente é situação válida — uma instalação sem MCP configurado —, e nesse caso não injetar é
     * melhor que injetar nulo: o componente falha dizendo que não há host, em vez de estourar
     * ponteiro dentro do laço.</p>
     */
    static void inject(ExecutionContext context, McpAgentHost host) {
        if (context != null && host != null) {
            context.set(CONTEXT_KEY, host);
        }
    }

    /** O host desta execução, se algum foi injetado. */
    static Optional<McpAgentHost> from(ExecutionContext context) {
        if (context == null) {
            return Optional.empty();
        }
        return context.get(CONTEXT_KEY)
                .filter(McpAgentHost.class::isInstance)
                .map(McpAgentHost.class::cast);
    }
}
