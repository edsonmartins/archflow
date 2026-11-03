package br.com.archflow.api.agent.vendax;

import br.com.archflow.decision.StateTemplate;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O texto e o payload do invoke no contexto do fluxo (pedido do VendaX de 18/10, VEN-397). O {@code state} do nó
 * {@code decision} resolve caminhos do contexto pelo {@link StateTemplate}; o teste resolve pelo próprio
 * {@code StateTemplate}, como o nó resolve, e não pela chave crua.
 */
class TextoEPayloadNoContextoTest {

    private static VendaxInvoke invoke(String texto, String payload) {
        return new VendaxInvoke("1.0", "t1", "c1", "vc", "m1", texto, "LIGHT", "teste", "trace", payload, null, null,
                "k1", null, null);
    }

    private static ExecutionContext contexto() {
        return new DefaultExecutionContext("t1", "vendax", "e1", MessageWindowChatMemory.builder().maxMessages(5).build());
    }

    /** O state do vc@1 do VendaX, como está no fluxo dele. */
    private static final Map<String, Object> STATE =
            Map.of("pergunta", "${payload.pergunta}", "resposta_do_cliente", "${texto}");

    @Test
    @DisplayName("o state do nó de decisão resolve a pergunta do payload e a resposta do texto")
    void stateComoObjeto() {
        ExecutionContext c = contexto();
        AgentFlowRunner.textoEPayload(c, invoke("confirmo", "{\"pergunta\":\"Fecho 10 cx de leite?\",\"n\":3}"));

        Object estado = StateTemplate.resolve(STATE, StateTemplate.fromContext(c, null));

        assertThat(estado).isEqualTo(Map.of("pergunta", "Fecho 10 cx de leite?", "resposta_do_cliente", "confirmo"));
    }

    @Test
    @DisplayName("payload ilegível, ou que não é objeto, fica fora: o caminho não resolve, e nada quebra")
    void payloadIlegivelFicaFora() {
        for (String ruim : new String[]{"não é json", "[1,2]", "\"texto\"", "", null}) {
            ExecutionContext c = contexto();
            AgentFlowRunner.textoEPayload(c, invoke("sim", ruim));
            assertThat(c.get(AgentFlowRunner.PAYLOAD)).as(String.valueOf(ruim)).isEmpty();
            assertThat(c.get(AgentFlowRunner.TEXTO)).contains("sim");
        }
    }

    @Test
    @DisplayName("sem texto, a chave fica ausente — e não vazia")
    void semTexto() {
        ExecutionContext c = contexto();
        AgentFlowRunner.textoEPayload(c, invoke(null, "{\"pergunta\":\"x\"}"));
        assertThat(c.get(AgentFlowRunner.TEXTO)).isEmpty();
        assertThat(c.get(AgentFlowRunner.PAYLOAD)).isPresent();
    }
}
