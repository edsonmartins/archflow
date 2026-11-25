package br.com.archflow.api.flow;

import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.enums.StepStatus;
import br.com.archflow.model.flow.StepResult;
import br.com.archflow.plugin.api.catalog.DefaultComponentCatalog;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Com {@code archflow.components.require-allowlist=true}, um fluxo sem
 * {@code configuration.allowedComponents} diz por que falhou (RCHFLW-105).
 *
 * <p>Na homologação, o passo falhava com "component not found: decision" e o motivo só ia para o log
 * do servidor: quem rodava o fluxo procurava um componente que existe. A mensagem continua começando
 * por "component not found" — é por esse prefixo que quem já trata o erro o reconhece.</p>
 */
@DisplayName("Motivo do require-allowlist na falha do passo")
class MotivoDoRequireAllowlistTest {

    private final DefaultWorkflowDeserializer estrito = new DefaultWorkflowDeserializer(
            new DefaultFlowStepFactory(new DefaultComponentCatalog(), mock(DynamicWorkflowService.class),
                    null, null, null),
            true);

    private static ExecutionContext contexto() {
        return new DefaultExecutionContext("acme", "u", "run-1",
                MessageWindowChatMemory.builder().maxMessages(5).build());
    }

    private StepResult rodar(Map<String, Object> passo, List<String> allowed) {
        Map<String, Object> json = new HashMap<>();
        json.put("id", "wf-estrito");
        json.put("metadata", Map.of("name", "Estrito"));
        json.put("steps", List.of(passo));
        if (allowed != null) {
            json.put("configuration", Map.of("allowedComponents", allowed));
        }
        return estrito.toFlow(json).getSteps().get(0).execute(contexto()).join();
    }

    private static String mensagem(StepResult r) {
        return String.valueOf(r.getOutput().orElseThrow());
    }

    @ParameterizedTest(name = "componentId={0}")
    @ValueSource(strings = {"decision", "mcp-agent", "text-transform-tool"})
    @DisplayName("sem lista, a falha nomeia allowedComponents e a propriedade do servidor")
    void falhaDizOMotivo(String componentId) {
        StepResult r = rodar(Map.of("id", "s1", "type", "TOOL", "componentId", componentId), null);

        assertThat(r.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(mensagem(r))
                .startsWith("component not found: " + componentId)
                .contains("configuration.allowedComponents")
                .contains("archflow.components.require-allowlist=true");
    }

    @Test
    @DisplayName("nó de chat com provider: o motivo do servidor vem no lugar da dica de 'provider fora da lista'")
    void chatComProvider() {
        StepResult r = rodar(Map.of("id", "s1", "type", "llm-chat",
                "config", Map.of("provider", "openai", "model", "gpt-4o-mini")), null);

        assertThat(r.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(mensagem(r))
                .contains("archflow.components.require-allowlist=true");
    }

    @Test
    @DisplayName("com a lista declarada, o componente fora dela não fala de require-allowlist")
    void comListaNaoCulpaOServidor() {
        StepResult r = rodar(Map.of("id", "s1", "type", "TOOL", "componentId", "decision"), List.of("outro"));

        assertThat(r.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(mensagem(r))
                .startsWith("component not found: decision")
                .doesNotContain("require-allowlist");
    }
}
