package br.com.archflow.api.flow;

import br.com.archflow.agent.tool.ToolInterceptorChain;
import br.com.archflow.model.ai.AIComponent;
import br.com.archflow.model.config.LLMConfigPatch;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.model.flow.StepConnection;
import br.com.archflow.plugin.api.catalog.ComponentAccessPolicy;
import br.com.archflow.plugin.api.catalog.ComponentCatalog;

import java.util.List;
import java.util.Map;

/**
 * Os passos que só o servidor archflow constrói (RCHFLW-37) — a extensão que o archflow-api entrega à
 * {@link PortableFlowStepFactory}.
 *
 * <p><b>Por que uma interface, e não a fábrica inteira no servidor.</b> O runner standalone e o SDK
 * embarcado precisam executar o mesmo documento que o servidor executa, e o archflow-api depende do
 * archflow-standalone (a ponte YAML): a fábrica não podia ficar lá. Ela fica no módulo comum, decide
 * <i>qual</i> passo cada nó é — na mesma ordem de sempre — e só pergunta a esta interface como construir
 * os que dependem de serviço do servidor (fila de aprovação, laço do mcp-agent, base de conhecimento,
 * catálogo de agentes da orquestração).</p>
 *
 * <p>Sem implementação, a fábrica não inventa um substituto: o nó vira {@link ServerOnlyNodeStep} e o
 * {@link ExecutableNodesValidator} recusa o fluxo antes do primeiro passo, dizendo o nó e o motivo
 * ({@link ServerCapability}).</p>
 */
public interface ServerNodes {

    /** O id de componente do nó de agente MCP — o mesmo {@code McpAgentComponent.COMPONENT_ID} do servidor. */
    String MCP_AGENT_COMPONENT_ID = "mcp-agent";

    /**
     * O nó como a fábrica o leu.
     *
     * @param id            id do nó
     * @param node          o nó cru do documento
     * @param config        {@code config} + {@code configuration} do nó, já mesclados
     * @param connections   as arestas, com o {@code branch} já convertido em condição
     * @param policy        o escopo de componentes do fluxo ({@code configuration.allowedComponents})
     * @param scopedCatalog o catálogo recortado por esse escopo
     * @param flowPatch     {@code configuration.llmConfig} do fluxo
     * @param interceptors  a cadeia de interceptors dos componentes, ou {@code null}
     * @param operation     a operação do nó (a declarada, senão a padrão histórica)
     */
    record Request(String id, Map<String, Object> node, Map<String, Object> config,
                   List<StepConnection> connections, ComponentAccessPolicy policy,
                   ComponentCatalog scopedCatalog, LLMConfigPatch flowPatch,
                   ToolInterceptorChain interceptors, String operation) {
    }

    /** {@code type="ORCHESTRATE"}. */
    FlowStep orchestrate(Request request);

    /** {@code type="APPROVAL"}. */
    FlowStep approval(Request request);

    /** {@code componentId="mcp-agent"} — uma instância por passo, com a config do nó. */
    FlowStep mcpAgent(Request request);

    /**
     * {@code vector-search}/{@code rag} com uma Base de conhecimento escolhida. O escopo do fluxo e o chat
     * do RAG já foram resolvidos pela fábrica (são regras do documento, não do servidor).
     *
     * @param rag              {@code true} no nó {@code rag}, {@code false} na busca
     * @param generator        o chat do RAG; {@code null} na busca ou quando não houve chat possível
     * @param generatorProblem por que o RAG não tem chat, ou {@code null}
     */
    FlowStep knowledgeBase(Request request, boolean rag, AIComponent generator, String generatorProblem);
}
