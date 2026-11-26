package br.com.archflow.api.flow;

import br.com.archflow.agent.persistence.InMemoryFlowRepository;
import br.com.archflow.api.decision.LlmDecisionProvider;
import br.com.archflow.api.orchestration.DynamicWorkflowService;
import br.com.archflow.decision.DecisionKeys;
import br.com.archflow.decision.DecisionProviders;
import br.com.archflow.engine.api.FlowEngine;
import br.com.archflow.engine.core.StateManager;
import br.com.archflow.langchain4j.provider.LLMConfigResolver;
import br.com.archflow.langchain4j.provider.LLMResolutionRequest;
import br.com.archflow.model.config.ResolvedLLMConfig;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.enums.ExecutionStatus;
import br.com.archflow.model.flow.Flow;
import br.com.archflow.model.flow.FlowResult;
import br.com.archflow.model.flow.FlowStatus;
import br.com.archflow.plugin.api.catalog.DefaultComponentCatalog;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RCHFLW-123, o caminho certo no motor de verdade: o fluxo que o assistente agora ensina (copiloto e nl-to-flow) para
 * "reembolso acima de 500 reais pede aprovação do gerente; abaixo, responder automaticamente". O valor vem no texto
 * do cliente, então um decision ({@code llm}, real, sobre um classificador determinístico) extrai a faixa, e um switch
 * por {@code ${faixa.decision}} leva {@code ate_500} à resposta e o resto ({@code default}) ao gerente — inclusive a
 * decisão que falhou ({@code decision: null}), que o motor que falha fechado trata como valor, não como ausência.
 */
@DisplayName("reembolso pelo valor do texto: decision extrai, switch ramifica, falha vai ao gerente")
class ReembolsoPeloValorDoTextoTest {

    private static final ResolvedLLMConfig PLATAFORMA = ResolvedLLMConfig.builder()
            .provider("openrouter").model("modelo-padrao").build();

    private static Map<String, Object> no(String id, String type, String label, Map<String, Object> config,
                                          List<Map<String, Object>> arestas) {
        Map<String, Object> node = new HashMap<>();
        node.put("id", id);
        node.put("type", type);
        node.put("componentId", type);
        node.put("label", label);
        node.put("configuration", config);
        node.put("connections", arestas);
        return node;
    }

    private static Map<String, Object> documento() {
        Map<String, Object> pergunta = Map.of("type", "choice", "instructions", "Qual o valor do reembolso pedido?",
                "criteria", Map.of("acima_de_500", "mais de R$ 500", "ate_500", "R$ 500 ou menos",
                        "sem_valor", "o pedido não diz o valor"));
        return Map.of("id", "reembolso", "metadata", Map.of("name", "Reembolso"), "steps", List.of(
                no("receber", "input", "Receber pedido", Map.of(), List.of(Map.of("targetId", "faixa"))),
                no("faixa", "decision", "Faixa de valor", Map.of("provider", "llm",
                        "questions", Map.of("valor", pergunta), "primary", "valor"),
                        List.of(Map.of("targetId", "por_valor"))),
                no("por_valor", "switch", "Por valor", Map.of("switchExpression", "${faixa.decision}",
                        "switchCases", "ate_500"), List.of(
                                Map.of("targetId", "responder", "branch", "ate_500"),
                                Map.of("targetId", "gerente", "branch", "default"))),
                no("gerente", "approval", "Aprovação do gerente", Map.of("approvalTitle", "Reembolso"),
                        List.of()),
                no("responder", "output", "Resposta automática", Map.of(), List.of())));
    }

    private record Execucao(FlowResult resultado, DefaultExecutionContext contexto, StateManager estado) {
    }

    @SuppressWarnings("unchecked")
    private static Execucao executar(String pedido, String respostaDoClassificador) throws Exception {
        StateManager estado = new InMemoryStateManager();
        InMemoryFlowRepository repositorio = new InMemoryFlowRepository();
        FlowEngine motor = FlowEngineFactory.create(repositorio, null, null, estado);
        ObjectProvider<FlowEngine> provedor = mock(ObjectProvider.class);
        when(provedor.getObject()).thenReturn(motor);
        DefaultFlowStepFactory fabrica = new DefaultFlowStepFactory(new DefaultComponentCatalog(),
                mock(DynamicWorkflowService.class), null, estado, provedor);
        ChatModel classificador = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                return ChatResponse.builder().aiMessage(AiMessage.from(respostaDoClassificador)).build();
            }
        };
        LLMConfigResolver resolver = new LLMConfigResolver() {
            @Override public ResolvedLLMConfig resolve(LLMResolutionRequest r) { return PLATAFORMA; }
            @Override public ChatModel resolveModel(LLMResolutionRequest r) { return classificador; }
        };
        fabrica.setDecisionSupport(DecisionProviders.withDefaults()
                .register(new LlmDecisionProvider(resolver, PLATAFORMA)), DecisionKeys.NONE);
        Flow flow = new DefaultWorkflowDeserializer(fabrica).toFlow(documento());
        repositorio.save(flow);
        DefaultExecutionContext ctx = new DefaultExecutionContext("SYSTEM", "u1", flow.getId(),
                MessageWindowChatMemory.builder().maxMessages(20).build());
        RunInputSeeding.seedVariables(ctx, Map.of("input", pedido));
        FlowResult resultado = motor.execute(flow, ctx).get(15, TimeUnit.SECONDS);
        return new Execucao(resultado, ctx, estado);
    }

    @Test
    @DisplayName("R$ 900 no texto: vai à aprovação do gerente, e a resposta automática não sai")
    void acimaDe500VaiAoGerente() throws Exception {
        Execucao r = executar("Olá, sou a Carla e quero reembolso de 900 reais do pedido 555, veio quebrado.",
                "{\"valor\":{\"choice\":\"acima_de_500\",\"confidence\":0.95}}");

        assertThat(r.resultado().getStatus()).as(String.valueOf(r.resultado().getErrors()))
                .isEqualTo(ExecutionStatus.PAUSED);
        assertThat(r.estado().loadState("reembolso").getStatus()).isEqualTo(FlowStatus.AWAITING_APPROVAL);
        assertThat(r.contexto().get("responder")).isEmpty();
    }

    @Test
    @DisplayName("R$ 120 no texto: a resposta automática, sem passar pelo gerente")
    void ate500Responde() throws Exception {
        Execucao r = executar("Quero reembolso de 120 reais, o produto veio errado.",
                "{\"valor\":{\"choice\":\"ate_500\",\"confidence\":0.95}}");

        assertThat(r.resultado().getStatus()).as(String.valueOf(r.resultado().getErrors()))
                .isEqualTo(ExecutionStatus.COMPLETED);
        assertThat(r.contexto().get("responder")).isPresent();
        assertThat(r.estado().loadState("reembolso").getStatus()).isNotEqualTo(FlowStatus.AWAITING_APPROVAL);
    }

    @Test
    @DisplayName("a decisão que falhou (decision null) cai no default e vai ao gerente — não falha, não aprova")
    void decisaoQueFalhouVaiAoGerente() throws Exception {
        Execucao r = executar("Quero reembolso.", "isto não é JSON");

        assertThat(String.valueOf(r.contexto().get("faixa").orElseThrow())).contains("route=ESCALATE");
        assertThat(r.resultado().getStatus()).as(String.valueOf(r.resultado().getErrors()))
                .isEqualTo(ExecutionStatus.PAUSED);
        assertThat(r.contexto().get("responder")).isEmpty();
    }
}
