package br.com.archflow.api.decision;

import br.com.archflow.api.agent.mcp.ContadorDeUso;
import br.com.archflow.api.agent.mcp.TabelaDePrecos;
import br.com.archflow.decision.DecisionComponent;
import br.com.archflow.decision.DecisionProviders;
import br.com.archflow.langchain4j.provider.LLMConfigResolver;
import br.com.archflow.langchain4j.provider.LLMResolutionRequest;
import br.com.archflow.model.config.ResolvedLLMConfig;
import br.com.archflow.model.engine.DefaultExecutionContext;
import br.com.archflow.model.engine.ExecutionContext;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O consumo de uma decisão por {@code llm} chega ao contador como o de qualquer turno de LLM
 * (RCHFLW-170).
 *
 * <p>No QA, com o modelo padrão precificado ({@code openrouter/google/gemini-3.5-flash-lite=10,40}),
 * a decisão saía com {@code model=null}, {@code provider=llm} e sem custo: o rótulo do consumo era
 * {@code llm/<modelo>} (ou nulo), que nunca casa com a tabela. E a decisão que falhava depois de o
 * modelo responder — sem JSON — não relatava nada, embora a chamada tivesse sido cobrada.</p>
 */
@DisplayName("consumo da decisão por LLM")
class UsoDaDecisaoPorLlmTest {

    private static final ResolvedLLMConfig PADRAO = ResolvedLLMConfig.builder()
            .provider("openrouter").model("google/gemini-3.5-flash-lite").build();

    private static final TabelaDePrecos PRECOS = TabelaDePrecos.daConfiguracao(Map.of(
            "openrouter/google/gemini-3.5-flash-lite", Map.of("entrada", "10", "saida", "40")));

    /** Um LLM que responde sempre o mesmo texto e informa o uso, como o OpenRouter. */
    private static LlmDecisionProvider provedor(String resposta) {
        ChatModel modelo = new ChatModel() {
            @Override public ChatResponse chat(ChatRequest request) {
                return ChatResponse.builder().aiMessage(AiMessage.from(resposta))
                        .tokenUsage(new TokenUsage(434, 39)).build();
            }
        };
        LLMConfigResolver resolver = new LLMConfigResolver() {
            @Override public ResolvedLLMConfig resolve(LLMResolutionRequest r) { return PADRAO; }
            @Override public ChatModel resolveModel(LLMResolutionRequest r) { return modelo; }
        };
        return new LlmDecisionProvider(resolver, PADRAO);
    }

    /** O nó como o QA o montou: provider llm, sem `model`, duas perguntas. */
    private static DecisionComponent no(LlmDecisionProvider provedor) {
        Map<String, Object> perguntas = new LinkedHashMap<>();
        perguntas.put("assunto", Map.of("type", "choice", "instructions", "Do que se trata?",
                "criteria", Map.of("defeito", "produto com defeito", "duvida", "dúvida")));
        perguntas.put("urgente", Map.of("type", "noul", "instructions", "É urgente?"));
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("provider", "llm");
        config.put("questions", perguntas);
        DecisionComponent c = new DecisionComponent(DecisionProviders.empty().register(provedor), null);
        c.initialize(config);
        return c;
    }

    private static ContadorDeUso decidir(String resposta) throws Exception {
        ContadorDeUso uso = new ContadorDeUso();
        ExecutionContext ctx = new DefaultExecutionContext("t1", null, null, null);
        ContadorDeUso.injetar(ctx, uso);
        no(provedor(resposta)).execute("decide", "veio com defeito, é urgente!", ctx);
        return uso;
    }

    @Test
    @DisplayName("decisão bem-sucedida: rotulada pelo LLM resolvido e precificada pela tabela")
    void precificada() throws Exception {
        ContadorDeUso uso = decidir("""
                {"assunto":{"choice":"defeito","confidence":0.9},"urgente":{"probability":0.95}}""");

        ContadorDeUso.Resumo r = uso.resumo().orElseThrow();
        assertThat(r.modelo())
                .as("antes: nulo (modelo do nó ausente) ou llm/<modelo> — nenhum casa com a tabela")
                .isEqualTo("openrouter/google/gemini-3.5-flash-lite");
        assertThat(r.provedor()).isEqualTo("openrouter");
        assertThat(r.tokens()).isEqualTo(473);
        assertThat(PRECOS.custoEmCentavos(r)).as("costCents").isNotNull().isPositive();
        assertThat(PRECOS.custoEmReais(r)).as("costReais").isNotNull().isPositive();
    }

    @Test
    @DisplayName("decisão que falha depois de o modelo responder (sem JSON): o consumo ainda é contado")
    void falhaContada() throws Exception {
        ContadorDeUso uso = decidir("não sei responder isso");

        assertThat(uso.resumo())
                .as("antes: vazio — a chamada cobrada sumia do uso da execução")
                .get().satisfies(r -> {
                    assertThat(r.turnos()).isEqualTo(1);
                    assertThat(r.tokens()).isEqualTo(473);
                    assertThat(r.modelo()).isEqualTo("openrouter/google/gemini-3.5-flash-lite");
                });
    }

    @Test
    @DisplayName("o modelo do resultado é o que respondeu, não o `model` vazio do nó")
    void modeloDoResultado() throws Exception {
        var r = provedor("""
                {"assunto":{"choice":"defeito","confidence":0.9},"urgente":{"probability":0.95}}""")
                .decide(new br.com.archflow.decision.DecisionRequest("x", List.of(
                        new br.com.archflow.decision.Question.YesNo("urgente", "É urgente?", null, null))),
                        new br.com.archflow.decision.DecisionCall("t1", null, null,
                                java.time.Duration.ofSeconds(5), Map.of()));

        assertThat(r.model()).as("antes: null").isEqualTo("google/gemini-3.5-flash-lite");
        assertThat(r.provider()).as("quem serviu a decisão continua sendo o llm").isEqualTo("llm");
    }
}
