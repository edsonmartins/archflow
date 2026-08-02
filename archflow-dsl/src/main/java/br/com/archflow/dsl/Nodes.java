package br.com.archflow.dsl;

import br.com.archflow.model.flow.StepType;

/**
 * Fábricas de nó da DSL.
 *
 * <h2>Três camadas, com garantias diferentes — de propósito</h2>
 *
 * <ol>
 *   <li><b>Passos do próprio motor</b> ({@link #approval()}, {@link #orchestrate()}):
 *       o motor os reconhece por {@link StepType} e os constrói com classes
 *       dedicadas, não pelo catálogo. São os únicos nós cujo comportamento a
 *       DSL pode prometer sem consultar nada em runtime.</li>
 *
 *   <li><b>Nós servidos por adapter LangChain4j</b> ({@link #llmChat},
 *       {@link #embedding}, {@link #vectorSearch}, {@link #memory},
 *       {@link #rag}, …): o conjunto de <i>tipos de nó</i> é fechado e está em
 *       {@code AdapterNodeTypes} no archflow-api — os métodos aqui espelham
 *       exatamente aquele mapa. O <i>provider</i> ("openai", "pgvector") segue
 *       sendo string: quais existem depende de quais adapters estão no
 *       classpath, e isso só se sabe em runtime.</li>
 *
 *   <li><b>Qualquer componente do catálogo</b> ({@link #component(String)}):
 *       string pura. Não é preguiça — o catálogo é populado por SPI e por jars
 *       de plugin carregados de um diretório, então <b>não existe</b> lista
 *       fechada em tempo de compilação.</li>
 * </ol>
 *
 * <p>A consequência prática: um id de componente errado não é erro de
 * compilação. O que a DSL faz é checar, em {@link WorkflowBuilder#build()}, a
 * coerência que ela <i>pode</i> checar — sobretudo componente usado mas fora
 * do {@code allowedComponents}, que em runtime viraria um
 * "component not found" difícil de ler.
 *
 * @since 1.1.0
 */
public final class Nodes {

    /**
     * Tipos de nó que o {@code AdapterNodeTypes} do archflow-api reconhece como
     * servidos por um adapter. Espelhados aqui, e não importados, porque a DSL
     * não depende do archflow-api (que traz Spring junto) — a
     * {@code AdapterNodeTypesMirrorTest}, no archflow-api, falha se os dois
     * conjuntos divergirem.
     */
    public static final String LLM_CHAT = "llm-chat";
    public static final String LLM_STREAMING = "llm-streaming";
    public static final String CHAT = "chat";
    public static final String EMBEDDING = "embedding";
    public static final String MEMORY = "memory";
    public static final String VECTOR_STORE = "vector-store";
    public static final String VECTOR_SEARCH = "vector-search";
    public static final String VECTORSTORE = "vectorstore";
    public static final String RAG = "rag";

    private Nodes() {
    }

    // ---------------------------------------------------------------- motor

    /**
     * Portão humano: suspende o fluxo em {@code AWAITING_APPROVAL} com uma
     * proposta para alguém revisar, e só segue quando houver decisão. A
     * suspensão é durável — sobrevive a restart do processo.
     */
    public static NodeSpec approval() {
        return new NodeSpec(StepType.APPROVAL.name(), null);
    }

    /** Orquestração dinâmica multi-agente (decompõe, delega, verifica, converge). */
    public static NodeSpec orchestrate() {
        return new NodeSpec(StepType.ORCHESTRATE.name(), null);
    }

    // -------------------------------------------------------------- adapter

    /** Nó de chat servido por um adapter — {@code provider} é "openai", "anthropic", … */
    public static NodeSpec llmChat(String provider) {
        return adapter(LLM_CHAT, provider);
    }

    /** Como {@link #llmChat}, com resposta token a token. */
    public static NodeSpec llmStreaming(String provider) {
        return adapter(LLM_STREAMING, provider);
    }

    /** Geração de embeddings. */
    public static NodeSpec embedding(String provider) {
        return adapter(EMBEDDING, provider);
    }

    /** Busca vetorial — {@code provider} é "pgvector", "redis", "pinecone", … */
    public static NodeSpec vectorSearch(String provider) {
        return adapter(VECTOR_SEARCH, provider);
    }

    /** Escrita no vector store. */
    public static NodeSpec vectorStore(String provider) {
        return adapter(VECTOR_STORE, provider);
    }

    /** Memória de conversa — {@code provider} é "redis", "jdbc", … */
    public static NodeSpec memory(String provider) {
        return adapter(MEMORY, provider);
    }

    /** Cadeia RAG pronta. */
    public static NodeSpec rag(String provider) {
        return adapter(RAG, provider);
    }

    /** Agente nativo que conduz um laço de tool-calling contra um servidor MCP. */
    public static NodeSpec mcpAgent() {
        return new NodeSpec("agent", "mcp-agent").operation("execute");
    }

    /**
     * Nó de adapter com o tipo escrito à mão — para um tipo novo que esta
     * versão da DSL ainda não tenha método próprio.
     */
    public static NodeSpec adapter(String nodeType, String provider) {
        NodeSpec spec = new NodeSpec(nodeType, provider);
        // O factory do motor lê o provider de config.provider e cai para o
        // componentId quando ausente; gravar os dois deixaria duas fontes
        // capazes de discordar.
        return provider == null ? spec : spec.with("provider", provider);
    }

    // -------------------------------------------------------------- catálogo

    /**
     * Qualquer componente resolvido pelo catálogo em runtime, pelo id.
     *
     * <p>Sem verificação em tempo de compilação: ver a nota de classe.
     */
    public static NodeSpec component(String componentId) {
        return new NodeSpec(componentId, componentId);
    }

    /**
     * Componente aberto com tipo visual distinto do id de runtime. Necessário
     * para round-trips como {@code type=agent, componentId=mcp-agent}.
     */
    public static NodeSpec component(String nodeType, String componentId) {
        return new NodeSpec(nodeType, componentId);
    }
}
