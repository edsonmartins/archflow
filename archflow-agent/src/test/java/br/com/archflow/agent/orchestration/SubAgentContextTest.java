package br.com.archflow.agent.orchestration;

import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.flow.FlowState;
import br.com.archflow.model.flow.FlowStatus;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A restrição de catálogo impedia o sub-agente de <em>chamar</em> o que não
 * devia; nada o impedia de <em>ler</em> o que não devia — ele recebia o contexto
 * do fluxo inteiro.
 */
@DisplayName("SubAgentContext")
class SubAgentContextTest {

    private ExecutionContext parent;

    @BeforeEach
    void setUp() {
        parent = new DefaultExecutionContext("acme", "sre-1", "sess-1",
                MessageWindowChatMemory.builder().maxMessages(10).build());
        parent.set("research_data", "dado de domínio");
        parent.set("step1", "saída do passo anterior");
        parent.set("__archflow.approvalProposal", Map.of("acao", "reiniciar traefik"));
        parent.set("__archflow.approvalDecidedBy", "sre-1");
        parent.set("__archflow.completedSteps", List.of("step1"));
        parent.getChatMemory().add(UserMessage.from("conversa do fluxo principal"));
        parent.setState(FlowState.builder()
                .tenantId("acme").flowId("f1").status(FlowStatus.RUNNING)
                .variables(parent.getVariables())
                .build());
    }

    @Test
    @DisplayName("estado interno do motor não chega ao sub-agente")
    void internalStateIsWithheld() {
        ExecutionContext sub = SubAgentContext.of(parent);

        assertThat(sub.get("__archflow.approvalProposal"))
                .as("a proposta pendente nao e assunto de um sub-agente")
                .isEmpty();
        assertThat(sub.get("__archflow.approvalDecidedBy")).isEmpty();
        assertThat(sub.get("__archflow.completedSteps")).isEmpty();
    }

    @Test
    @DisplayName("variáveis de domínio continuam passando — agentes reais as leem")
    void domainVariablesStillPass() {
        ExecutionContext sub = SubAgentContext.of(parent);

        assertThat(sub.get("research_data")).contains("dado de domínio");
        assertThat(sub.get("step1")).contains("saída do passo anterior");
    }

    @Test
    @DisplayName("o filtro vale também via getState() — senão o recorte seria contornável")
    void stateDoesNotLeakWhatWasFiltered() {
        ExecutionContext sub = SubAgentContext.of(parent);

        assertThat(sub.getState().getVariables())
                .doesNotContainKey("__archflow.approvalProposal")
                .containsKey("research_data");
    }

    @Test
    @DisplayName("memória é nova e vazia — não vaza nem polui a conversa principal")
    void memoryIsFresh() {
        ExecutionContext sub = SubAgentContext.of(parent);

        assertThat(sub.getChatMemory()).isNotSameAs(parent.getChatMemory());
        assertThat(sub.getChatMemory().messages()).isEmpty();

        sub.getChatMemory().add(UserMessage.from("turno do sub-agente"));
        assertThat(parent.getChatMemory().messages())
                .as("o turno do sub-agente nao pode entrar no dialogo principal")
                .hasSize(1);
    }

    @Test
    @DisplayName("allowlist fechada entrega só o que foi listado")
    void closedAllowlist() {
        ExecutionContext sub = SubAgentContext.of(parent, List.of("research_data"));

        assertThat(sub.get("research_data")).isPresent();
        assertThat(sub.get("step1")).isEmpty();
    }

    @Test
    @DisplayName("allowlist vazia entrega o sub-agente sem variável nenhuma")
    void emptyAllowlist() {
        assertThat(SubAgentContext.of(parent, List.of()).getVariables()).isEmpty();
    }

    @Test
    @DisplayName("identidade do tenant é preservada — a resolução por tenant depende dela")
    void identityIsPreserved() {
        ExecutionContext sub = SubAgentContext.of(parent);

        assertThat(sub.getTenantId()).isEqualTo("acme");
        assertThat(sub.getUserId()).isEqualTo("sre-1");
        assertThat(sub.getSessionId()).isEqualTo("sess-1");
    }

    @Test
    @DisplayName("contexto nulo não explode")
    void nullParentIsSafe() {
        assertThat(SubAgentContext.of(null)).isNull();
    }
}
