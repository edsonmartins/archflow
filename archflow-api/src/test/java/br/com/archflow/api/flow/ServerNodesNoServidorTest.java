package br.com.archflow.api.flow;

import br.com.archflow.api.agent.mcp.McpAgentComponent;
import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.model.flow.FlowStep;
import br.com.archflow.plugin.api.catalog.DefaultComponentCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * No servidor, os nós que dependem dele continuam sendo os passos de sempre (RCHFLW-37): a fábrica comum os
 * reconhece e o {@link DefaultFlowStepFactory} os constrói. A recusa ({@link ServerOnlyNodeStep}) é só de
 * quem roda sem servidor.
 */
@DisplayName("ServerNodes no servidor")
class ServerNodesNoServidorTest {

    private final DefaultFlowStepFactory factory = new DefaultFlowStepFactory(new DefaultComponentCatalog(),
            mock(DynamicWorkflowService.class), null, null, null);

    @Test
    @DisplayName("o id do mcp-agent da fábrica comum é o do componente do servidor")
    void mesmoIdDoMcpAgent() {
        assertThat(ServerNodes.MCP_AGENT_COMPONENT_ID).isEqualTo(McpAgentComponent.COMPONENT_ID);
        assertThat(KnowledgeNodes.COMPONENT_ID).isEqualTo(KnowledgeNodeComponent.COMPONENT_ID);
    }

    @Test
    @DisplayName("aprovação, orquestração, mcp-agent e base de conhecimento viram os passos do servidor")
    void passosDoServidor() {
        FlowStep orquestra = factory.create(Map.of("id", "o", "type", "ORCHESTRATE",
                "configuration", Map.of("goal", "x")));
        FlowStep aprova = factory.create(Map.of("id", "a", "type", "APPROVAL"));
        FlowStep agente = factory.create(Map.of("id", "m", "type", "agent", "componentId", "mcp-agent",
                "configuration", Map.of("systemPrompt", "Você ajuda.")));
        FlowStep busca = factory.create(Map.of("id", "b", "type", "vector-search", "componentId", "vector-search",
                "configuration", Map.of("knowledgeBaseId", "kb-1")));

        assertThat(orquestra).isInstanceOf(OrchestrateStep.class);
        assertThat(aprova).isInstanceOf(HumanApprovalStep.class);
        // O mcp-agent vem dentro do passo que segura o fluxo quando a tool pede aprovação (RCHFLW-185).
        assertThat(agente).isInstanceOfSatisfying(McpAgentStep.class,
                step -> assertThat(step.componente().resolvesComponent()).isTrue());
        assertThat(busca).isInstanceOfSatisfying(ComponentStep.class,
                step -> assertThat(step.resolvesComponent()).isTrue());
    }
}
