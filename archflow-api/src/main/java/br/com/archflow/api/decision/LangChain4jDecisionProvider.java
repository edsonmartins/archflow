package br.com.archflow.api.decision;

import br.com.archflow.decision.Answer;
import br.com.archflow.decision.DecisionCall;
import br.com.archflow.decision.DecisionException;
import br.com.archflow.decision.DecisionProvider;
import br.com.archflow.decision.DecisionRequest;
import br.com.archflow.decision.DecisionResult;
import br.com.archflow.decision.Question;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.exception.ContentFilteredException;
import dev.langchain4j.exception.InvalidDecisionResponseException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.UnsupportedFeatureException;
import dev.langchain4j.model.decision.DecisionModel;
import dev.langchain4j.model.decision.request.ChoiceQuestion;
import dev.langchain4j.model.decision.request.ScaleQuestion;
import dev.langchain4j.model.decision.request.YesNoQuestion;
import dev.langchain4j.model.decision.response.ChoiceAnswer;
import dev.langchain4j.model.decision.response.DecisionAnswer;
import dev.langchain4j.model.decision.response.DecisionResponse;
import dev.langchain4j.model.decision.response.RefusalAnswer;
import dev.langchain4j.model.decision.response.ScaleAnswer;
import dev.langchain4j.model.decision.response.YesNoAnswer;
import dev.langchain4j.model.openai.OpenAiDecisionModel;
import dev.langchain4j.model.typesafe.TypeSafeDecisionModel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Encaixa qualquer {@link DecisionModel} da langchain4j no contrato de decisão do archflow.
 *
 * <p>É a ponte para o que a langchain4j já mantém: o cliente da API do TypeSafe (e de quem fala o mesmo
 * protocolo, como o OpenRouter) e a <i>Decisions API</i> da OpenAI, com repetição, listeners e cliente
 * HTTP dela. O provedor {@code http-decisions} continua existindo para quem não quer essa dependência.</p>
 *
 * <h2>Opções do nó</h2>
 * <ul>
 *   <li>{@code backend}: {@code typesafe} (padrão) ou {@code openai};</li>
 *   <li>{@code baseUrl}: para o {@code typesafe}, outro servidor do mesmo protocolo — o OpenRouter é
 *       {@code https://openrouter.ai/api};</li>
 *   <li>{@code keyRef}: de qual chave o hospedeiro resolve a credencial (padrão {@code typesafe} ou
 *       {@code openai}, conforme o {@code backend});</li>
 *   <li>{@code maxRetries}: repetições da própria langchain4j (padrão 1).</li>
 * </ul>
 *
 * <h2>Recusa</h2>
 * <p>A API da OpenAI pode recusar <b>uma</b> pergunta e responder as outras. A recusa vira
 * {@link Answer.Refused}: confiança 0, valor nulo — a rota escala, em vez de seguir com um palpite.</p>
 *
 * <h2>Limites</h2>
 * <p>O custo em USD não vem da langchain4j (só os tokens); quem precisa do custo declarado usa o
 * {@code http-decisions}. Uma pergunta {@code choice} exige ao menos duas opções — a langchain4j recusa
 * menos que isso.</p>
 */
public class LangChain4jDecisionProvider implements DecisionProvider {

    public static final String ID = "langchain4j-decisions";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int CACHE_MAX = 64;

    private final Map<String, DecisionModel> modelos = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean requiresKey() {
        return true;
    }

    @Override
    public Optional<String> keyRef(Map<String, Object> options) {
        Object ref = options == null ? null : options.get("keyRef");
        if (ref instanceof String s && !s.isBlank()) {
            return Optional.of(s);
        }
        return Optional.of("openai".equals(backend(options)) ? "openai" : "typesafe");
    }

    @Override
    public DecisionResult decide(DecisionRequest request, DecisionCall call) throws DecisionException {
        if (call.model() == null || call.model().isBlank()) {
            throw new DecisionException("o provedor " + ID + " exige o 'model' do nó", false);
        }
        DecisionModel modelo = modeloPara(call);
        dev.langchain4j.model.decision.request.DecisionRequest.Builder pedido =
                dev.langchain4j.model.decision.request.DecisionRequest.builder();
        entrada(pedido, request.state());
        for (Question q : request.questions()) {
            pedido.question(q.id(), pergunta(q));
        }
        DecisionResponse resposta;
        try {
            resposta = modelo.decide(pedido.build());
        } catch (RuntimeException e) {
            throw new DecisionException("a langchain4j não decidiu: " + e.getMessage(), repetivel(e), e);
        }
        Map<String, Answer> answers = new LinkedHashMap<>();
        for (Question q : request.questions()) {
            DecisionAnswer a = resposta.answers().get(q.id());
            if (a == null) {
                throw new DecisionException("a resposta não traz a pergunta '" + q.id() + "'", false);
            }
            answers.put(q.id(), resposta(q, a));
        }
        var uso = resposta.tokenUsage() == null ? null : new DecisionResult.Usage(
                resposta.tokenUsage().inputTokenCount() == null ? null
                        : resposta.tokenUsage().inputTokenCount().longValue(),
                resposta.tokenUsage().outputTokenCount() == null ? null
                        : resposta.tokenUsage().outputTokenCount().longValue(), null);
        return new DecisionResult(answers, resposta.modelName() != null ? resposta.modelName() : call.model(),
                backend(call.options()), true, uso);
    }

    // ── Pedido ──────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static void entrada(dev.langchain4j.model.decision.request.DecisionRequest.Builder b, Object state) {
        if (state instanceof CharSequence s) {
            b.input(s.toString());
        } else if (state instanceof Map<?, ?> m) {
            b.input((Map<String, ?>) m);
        } else {
            try {
                b.input(JSON.writeValueAsString(state));
            } catch (Exception e) {
                b.input(String.valueOf(state));
            }
        }
    }

    private static dev.langchain4j.model.decision.request.Question pergunta(Question q) throws DecisionException {
        String texto = q.instructions().isBlank() ? q.id() : q.instructions();
        try {
            return switch (q) {
                case Question.Choice c -> {
                    ChoiceQuestion.Builder b = ChoiceQuestion.builder().text(texto);
                    c.options().forEach((opcao, descricao) -> {
                        if (descricao == null || descricao.isBlank()) {
                            b.option(opcao);
                        } else {
                            b.option(opcao, descricao);
                        }
                    });
                    yield b.build();
                }
                case Question.Score s -> ScaleQuestion.builder().text(texto).levels(s.levels()).build();
                case Question.YesNo y -> {
                    YesNoQuestion.Builder b = YesNoQuestion.builder().text(texto);
                    if (y.whenTrue() != null && !y.whenTrue().isBlank()) {
                        b.yesWhen(y.whenTrue());
                    }
                    if (y.whenFalse() != null && !y.whenFalse().isBlank()) {
                        b.noWhen(y.whenFalse());
                    }
                    yield b.build();
                }
            };
        } catch (IllegalArgumentException e) {
            throw new DecisionException("a pergunta '" + q.id() + "' não cabe na langchain4j: "
                    + e.getMessage(), false, e);
        }
    }

    // ── Resposta ────────────────────────────────────────────────────

    static Answer resposta(Question q, DecisionAnswer a) throws DecisionException {
        if (a instanceof RefusalAnswer) {
            return new Answer.Refused();
        }
        return switch (q) {
            case Question.Choice c -> {
                if (!(a instanceof ChoiceAnswer ca)) {
                    throw tipoErrado(q, a);
                }
                yield new Answer.Choice(ca.value(), ca.probabilities(),
                        ca.confidence() != null ? ca.confidence() : confiancaDe(ca.probabilities().values()));
            }
            case Question.Score s -> {
                if (!(a instanceof ScaleAnswer sa)) {
                    throw tipoErrado(q, a);
                }
                Map<String, Double> probs = new LinkedHashMap<>();
                Map<String, String> legenda = new LinkedHashMap<>();
                List<Double> ps = sa.probabilities();
                for (int i = 0; i < s.levels().size(); i++) {
                    probs.put(String.valueOf(i), i < ps.size() ? ps.get(i) : 0.0);
                    legenda.put(String.valueOf(i), s.levels().get(i));
                }
                yield new Answer.Score(sa.mean(), probs, legenda,
                        sa.confidence() != null ? sa.confidence() : confiancaDe(ps));
            }
            case Question.YesNo y -> {
                if (!(a instanceof YesNoAnswer ya)) {
                    throw tipoErrado(q, a);
                }
                yield new Answer.YesNo(ya.probability());
            }
        };
    }

    /** A mesma confiança do protocolo para quando o modelo não a informa: a distância da uniforme. */
    private static double confiancaDe(java.util.Collection<Double> probabilidades) {
        int n = probabilidades.size();
        if (n < 2) {
            return 1.0;
        }
        double pmax = probabilidades.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        return Math.max(0.0, Math.min(1.0, (pmax - 1.0 / n) / (1.0 - 1.0 / n)));
    }

    private static DecisionException tipoErrado(Question q, DecisionAnswer a) {
        return new DecisionException("'" + q.id() + "': a resposta é do tipo "
                + a.getClass().getSimpleName() + ", incompatível com a pergunta", false);
    }

    private static boolean repetivel(RuntimeException e) {
        return !(e instanceof ContentFilteredException || e instanceof InvalidDecisionResponseException
                || e instanceof UnsupportedFeatureException || e instanceof NonRetriableException
                || e instanceof IllegalArgumentException);
    }

    // ── Modelos ─────────────────────────────────────────────────────

    private static String backend(Map<String, Object> options) {
        Object b = options == null ? null : options.get("backend");
        return b instanceof String s && !s.isBlank() ? s.toLowerCase() : "typesafe";
    }

    /**
     * O modelo para esta chamada. Os modelos da langchain4j são imutáveis e carregam um cliente HTTP,
     * então ficam em cache — por tudo que os distingue, a chave de API incluída (por resumo: o segredo
     * não vira chave de mapa).
     */
    private DecisionModel modeloPara(DecisionCall call) throws DecisionException {
        String backend = backend(call.options());
        String baseUrl = call.options().get("baseUrl") instanceof String s && !s.isBlank() ? s : null;
        int retries = call.options().get("maxRetries") instanceof Number n ? Math.max(0, n.intValue()) : 1;
        if (!backend.equals("typesafe") && !backend.equals("openai")) {
            throw new DecisionException("backend desconhecido: '" + backend + "' (typesafe ou openai)", false);
        }
        String chave = String.join("|", backend, String.valueOf(baseUrl), call.model(),
                String.valueOf(retries), String.valueOf(call.timeout().toMillis()), resumo(call.apiKey()));
        if (modelos.size() >= CACHE_MAX) {
            modelos.clear();
        }
        return modelos.computeIfAbsent(chave, k -> construir(backend, baseUrl, call, retries));
    }

    private static DecisionModel construir(String backend, String baseUrl, DecisionCall call, int retries) {
        Duration timeout = call.timeout();
        if (backend.equals("openai")) {
            OpenAiDecisionModel.OpenAiDecisionModelBuilder b = OpenAiDecisionModel.builder()
                    .apiKey(call.apiKey()).modelName(call.model()).timeout(timeout).maxRetries(retries);
            if (baseUrl != null) {
                b.baseUrl(baseUrl);
            }
            return b.build();
        }
        TypeSafeDecisionModel.TypeSafeDecisionModelBuilder b = TypeSafeDecisionModel.builder()
                .apiKey(call.apiKey()).modelName(call.model()).timeout(timeout).maxRetries(retries);
        if (baseUrl != null) {
            b.baseUrl(baseUrl);
        }
        return b.build();
    }

    private static String resumo(String segredo) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(segredo).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
