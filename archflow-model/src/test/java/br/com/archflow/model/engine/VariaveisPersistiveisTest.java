package br.com.archflow.model.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O que pode ir para o estado durável.
 *
 * <h2>O que este teste protege</h2>
 *
 * <p>Infraestrutura da execução — cliente MCP, provider, conexão — viaja no contexto para o passo
 * alcançá-la. Se ela entrar no {@code FlowState}, o Jackson estoura na serialização e o checkpoint
 * falha com <b>WARN</b>: o fluxo roda, o resultado sai, e a retomada durável some sem ninguém
 * notar. Foi exatamente assim que o host de MCP quebrou o checkpoint na primeira execução real de
 * um fluxo com nó {@code mcp-agent}.</p>
 *
 * <p>E serializar não seria conserto: a retomada restauraria um mapa onde o passo espera o objeto,
 * e o sintoma apareceria como "não configurado", longe da causa.</p>
 */
@DisplayName("Variáveis que vão para o estado durável")
class VariaveisPersistiveisTest {

    /** Um bean vivo qualquer: o Jackson não sabe serializá-lo, e é esse o ponto. */
    private static final class ClienteFalso {
        @SuppressWarnings("unused")
        private final Object conexao = new Object();
    }

    @Test
    @DisplayName("chave transiente não é persistida")
    void transienteFicaDeFora() {
        Map<String, Object> variaveis = new HashMap<>();
        variaveis.put("saidaDoPasso", "ok");
        variaveis.put(ExecutionKeys.TRANSIENT_PREFIX + "mcpHost", new ClienteFalso());

        Map<String, Object> persistiveis = ExecutionKeys.persistiveis(variaveis);

        assertThat(persistiveis).containsOnlyKeys("saidaDoPasso");
    }

    /**
     * A outra metade da regra, e a que impede o filtro de virar cura pior que a doença: passo
     * concluído e memória de conversa PRECISAM sobreviver ao restart. Um filtro largo demais
     * devolveria o agente amnésico na retomada.
     */
    @Test
    @DisplayName("estado que precisa sobreviver ao restart continua passando")
    void estadoDeVerdadePassa() {
        Map<String, Object> variaveis = new HashMap<>();
        variaveis.put("__archflow.completedSteps", java.util.List.of("a", "b"));
        variaveis.put("__archflow.chatMemory", "[{\"role\":\"user\"}]");
        variaveis.put(ExecutionKeys.LLM_RESOLVED_CONFIG, "config");

        Map<String, Object> persistiveis = ExecutionKeys.persistiveis(variaveis);

        assertThat(persistiveis)
                .as("só o prefixo transient sai; o resto do __archflow. é estado de verdade")
                .containsOnlyKeys("__archflow.completedSteps", "__archflow.chatMemory",
                        ExecutionKeys.LLM_RESOLVED_CONFIG);
    }

    @Test
    @DisplayName("mapa nulo ou vazio devolve mapa vazio mutável")
    void nuloEVazio() {
        assertThat(ExecutionKeys.persistiveis(null)).isEmpty();
        assertThat(ExecutionKeys.persistiveis(Map.of())).isEmpty();
        // O chamador faz setVariables com este mapa; devolver imutável quebraria quem o completa.
        ExecutionKeys.persistiveis(null).put("x", "y");
    }

    /** A chave do host precisa carregar o prefixo — é o que faz o filtro alcançá-la. */
    @Test
    @DisplayName("o prefixo é o contrato, não uma convenção de nome solta")
    void prefixoEhContrato() {
        assertThat(ExecutionKeys.TRANSIENT_PREFIX).endsWith(".");
        assertThat(ExecutionKeys.persistiveis(
                Map.of(ExecutionKeys.TRANSIENT_PREFIX + "qualquerCoisa", new ClienteFalso())))
                .isEmpty();
    }
}
