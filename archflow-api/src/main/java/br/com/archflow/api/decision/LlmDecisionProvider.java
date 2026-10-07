package br.com.archflow.api.decision;

import br.com.archflow.api.trust.UntrustedContentFence;
import br.com.archflow.decision.Answer;
import br.com.archflow.decision.DecisionCall;
import br.com.archflow.decision.DecisionException;
import br.com.archflow.decision.DecisionProvider;
import br.com.archflow.decision.DecisionRequest;
import br.com.archflow.decision.DecisionResult;
import br.com.archflow.decision.Question;
import br.com.archflow.langchain4j.provider.LLMConfigResolver;
import br.com.archflow.langchain4j.provider.LLMResolutionRequest;
import br.com.archflow.model.config.LLMConfigPatch;
import br.com.archflow.model.config.ResolvedLLMConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Usa um LLM qualquer da plataforma como classificador: o degrau de reserva quando um modelo de
 * decisão não está disponível, e uma forma de começar sem acesso a um.
 *
 * <h2>Não é calibrado</h2>
 * <p>Um LLM que escreve "confiança 0,95" está estimando, não medindo: a probabilidade não sai da
 * distribuição do modelo. Por isso a resposta sai com {@code calibrated=false} e a confiança é
 * <b>limitada</b> a {@code maxConfidence} (padrão 0,89): sem o operador subir esse teto, uma decisão
 * por LLM nunca atinge {@code AUTO} — pede revisão.</p>
 *
 * <p>O estado é conteúdo de terceiros (a mensagem de um cliente, o texto de um ticket): vai
 * cercado por {@link UntrustedContentFence}, com a regra de que é dado e não instrução.</p>
 *
 * <p>Opções do nó: {@code maxConfidence}; o {@code model} do nó escolhe o modelo, na cadeia de
 * resolução da plataforma (chave por tenant, tier etc.).</p>
 */
public class LlmDecisionProvider implements DecisionProvider {

    public static final String ID = "llm";
    public static final double DEFAULT_MAX_CONFIDENCE = 0.89;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final LLMConfigResolver resolver;
    private final ResolvedLLMConfig platformDefault;

    public LlmDecisionProvider(LLMConfigResolver resolver, ResolvedLLMConfig platformDefault) {
        this.resolver = resolver;
        this.platformDefault = platformDefault;
    }

    @Override
    public String id() {
        return ID;
    }

    // A chave é resolvida pela cadeia do próprio LLM (por tenant), não por aqui.
    @Override
    public Optional<String> keyRef(Map<String, Object> options) {
        return Optional.empty();
    }

    @Override
    public DecisionResult decide(DecisionRequest request, DecisionCall call) throws DecisionException {
        double teto = call.options().get("maxConfidence") instanceof Number n
                ? Math.max(0, Math.min(1, n.doubleValue())) : DEFAULT_MAX_CONFIDENCE;
        UntrustedContentFence fence = UntrustedContentFence.create();
        ChatResponse resposta;
        try {
            LLMResolutionRequest.Builder b = LLMResolutionRequest.builder(platformDefault)
                    .tenantId(call.tenantId());
            if (call.model() != null && !call.model().isBlank()) {
                b.stepPatch(LLMConfigPatch.builder().model(call.model()).temperature(0.0).build());
            } else {
                b.stepPatch(LLMConfigPatch.builder().temperature(0.0).build());
            }
            ChatModel model = resolver.resolveModel(b.build());
            resposta = model.chat(ChatRequest.builder().messages(
                    SystemMessage.from(sistema(request) + fence.preamble()),
                    UserMessage.from(fence.wrap("estado", texto(request.state())))).build());
        } catch (RuntimeException e) {
            throw new DecisionException("o LLM de decisão falhou: " + e.getMessage(), true, e);
        }
        String bruto = resposta.aiMessage() == null ? null : resposta.aiMessage().text();
        Map<String, Answer> answers = parse(bruto, request, teto);
        var uso = resposta.tokenUsage() == null ? null : new DecisionResult.Usage(
                resposta.tokenUsage().inputTokenCount() == null ? null
                        : resposta.tokenUsage().inputTokenCount().longValue(),
                resposta.tokenUsage().outputTokenCount() == null ? null
                        : resposta.tokenUsage().outputTokenCount().longValue(), null);
        return new DecisionResult(answers, call.model(), ID, false, uso);
    }

    private static String sistema(DecisionRequest request) {
        StringBuilder sb = new StringBuilder("""
                Você é um classificador. Julgue o ESTADO recebido e responda SOMENTE com um objeto JSON,
                sem texto antes ou depois, com uma chave por pergunta (o id), assim:
                """);
        for (Question q : request.questions()) {
            sb.append("\n- ").append(q.id()).append(": ").append(q.instructions()).append('\n');
            switch (q) {
                case Question.Choice c -> {
                    sb.append("  escolha UMA opção: ");
                    c.options().forEach((o, d) -> sb.append("\"").append(o).append("\"")
                            .append(d == null || d.isBlank() ? "" : " (" + d + ")").append("; "));
                    sb.append("\n  formato: {\"choice\": \"<opção>\", \"confidence\": <0 a 1>}\n");
                }
                case Question.Score s -> {
                    sb.append("  gradue em níveis de índice: ");
                    for (int i = 0; i < s.levels().size(); i++) {
                        sb.append(i).append("=").append(s.levels().get(i)).append("; ");
                    }
                    sb.append("\n  formato: {\"score\": <número entre 0 e ")
                            .append(s.levels().size() - 1).append(">, \"confidence\": <0 a 1>}\n");
                }
                case Question.YesNo y -> sb.append(
                        "  a condição vale?\n  formato: {\"probability\": <0 a 1>}\n");
            }
        }
        return sb.toString();
    }

    private static Map<String, Answer> parse(String bruto, DecisionRequest request, double teto)
            throws DecisionException {
        if (bruto == null || bruto.isBlank()) {
            throw new DecisionException("o LLM não respondeu", true);
        }
        String json = bruto.strip();
        if (json.startsWith("```")) {
            json = json.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```$", "");
        }
        JsonNode raiz;
        try {
            raiz = JSON.readTree(json);
        } catch (Exception e) {
            throw new DecisionException("o LLM não devolveu JSON", false, e);
        }
        Map<String, Answer> answers = new LinkedHashMap<>();
        for (Question q : request.questions()) {
            JsonNode no = raiz.get(q.id());
            if (no == null || !no.isObject()) {
                throw new DecisionException("o LLM não respondeu a '" + q.id() + "'", false);
            }
            answers.put(q.id(), switch (q) {
                case Question.Choice c -> {
                    String escolha = no.path("choice").asText(null);
                    if (escolha == null || !c.options().containsKey(escolha)) {
                        throw new DecisionException("'" + q.id() + "': opção inválida do LLM: " + escolha, false);
                    }
                    double conf = Math.min(teto, clamp(no.path("confidence").asDouble(0)));
                    Map<String, Double> probs = new LinkedHashMap<>();
                    double resto = c.options().size() == 1 ? 0 : (1 - conf) / (c.options().size() - 1);
                    c.options().keySet().forEach(o -> probs.put(o, o.equals(escolha) ? conf : resto));
                    // A distribuição é declarada, não medida; a confiança é a do teto aplicado.
                    yield new Answer.Choice(escolha, probs, conf);
                }
                case Question.Score s -> {
                    double v = no.path("score").asDouble(Double.NaN);
                    if (Double.isNaN(v) || v < 0 || v > s.levels().size() - 1) {
                        throw new DecisionException("'" + q.id() + "': score inválido do LLM", false);
                    }
                    Map<String, String> legenda = new LinkedHashMap<>();
                    for (int i = 0; i < s.levels().size(); i++) {
                        legenda.put(String.valueOf(i), s.levels().get(i));
                    }
                    yield new Answer.Score(v, Map.of(), legenda,
                            Math.min(teto, clamp(no.path("confidence").asDouble(0))));
                }
                case Question.YesNo y -> {
                    double p = no.path("probability").asDouble(Double.NaN);
                    if (Double.isNaN(p)) {
                        throw new DecisionException("'" + q.id() + "': probabilidade inválida do LLM", false);
                    }
                    // A confiança do sim/não sai da probabilidade (|2p-1|); o teto a limita
                    // reduzindo a probabilidade para dentro de [(1-teto)/2, (1+teto)/2].
                    double min = (1 - teto) / 2;
                    yield new Answer.YesNo(Math.max(min, Math.min(1 - min, clamp(p))));
                }
            });
        }
        return answers;
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    private static String texto(Object state) {
        if (state instanceof CharSequence s) {
            return s.toString();
        }
        try {
            return JSON.writeValueAsString(state);
        } catch (Exception e) {
            return String.valueOf(state);
        }
    }
}
