package br.com.archflow.api.flow;

/**
 * O que um nó precisa do servidor archflow para executar (RCHFLW-37).
 *
 * <p>É a lista de passos que a fábrica comum ({@link PortableFlowStepFactory}) reconhece, mas não
 * sabe construir sem o servidor: quem os constrói é o {@link ServerNodes} que o archflow-api entrega.
 * Fora dele (runner standalone, SDK embarcado) o nó vira um {@link ServerOnlyNodeStep} e a execução é
 * recusada antes do primeiro passo, com o motivo daqui — sem cair num substituto que faria outra coisa.</p>
 */
public enum ServerCapability {

    /** Nó {@code APPROVAL}: o pedido fica na fila do servidor e a decisão retoma o fluxo. */
    APPROVAL("aprovação humana: a fila de aprovação e a retomada do fluxo são do servidor"),

    /** Nó {@code ORCHESTRATE}: planeja e delega a agentes do catálogo do servidor. */
    ORCHESTRATE("orquestração: o planejador e o catálogo de agentes são do servidor"),

    /** Nó {@code mcp-agent}: o laço do agente com as ferramentas MCP roda no host do servidor. */
    MCP_AGENT("agente MCP: o laço do agente e as ferramentas MCP rodam no servidor"),

    /** Nó {@code vector-search}/{@code rag} com uma Base de conhecimento escolhida. */
    KNOWLEDGE_BASE("base de conhecimento: a busca usa a ingestão e o índice do servidor"),

    /**
     * Nó {@code decision} que cita um provedor que só o servidor registra ({@code llm},
     * {@code langchain4j-decisions}). Sem a recusa, {@code onError: ESCALATE} transformava o provedor ausente numa
     * rota ESCALATE e o fluxo terminava COMPLETED pelo ramo de escalada.
     */
    DECISION_PROVIDER("decisão: o provedor escolhido é registrado só no servidor"),

    /**
     * Nó de dados (vector store, embedding, memória, RAG sem Base de conhecimento) sem adapter. Os adapters de
     * dados e as conexões por nome ({@code archflow.adapters.*}) são do servidor: o standalone não os traz, e o
     * nó falharia no meio da execução, depois dos passos anteriores.
     */
    DATA_ADAPTER("nó de dados: os adapters de vector store, embedding e memória e as conexões deles são do "
            + "servidor");

    private final String reason;

    ServerCapability(String reason) {
        this.reason = reason;
    }

    /** Por que o nó precisa do servidor — o que a recusa diz entre parênteses. */
    public String reason() {
        return reason;
    }
}
