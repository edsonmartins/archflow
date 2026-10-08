package br.com.archflow.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Provedor para qualquer serviço que fale o protocolo de decisões tipadas por HTTP: um {@code POST}
 * com {@code {model, state, questions}} que devolve {@code {answers, usage, …}}. É o protocolo do
 * <i>Decisions API</i> do OpenRouter e da API do próprio fabricante do modelo (TypeSafe), e o
 * serviço é escolhido por configuração — {@code endpoint} e o {@code model} do nó —, não por código.
 *
 * <h2>Opções do nó</h2>
 * <ul>
 *   <li>{@code endpoint}: URL completa. Padrão: o endpoint de decisões do OpenRouter.</li>
 *   <li>{@code keyRef}: de qual chave o hospedeiro deve resolver a credencial (padrão
 *       {@code openrouter}).</li>
 *   <li>{@code retries}: quantas vezes repetir em falha que melhora tentando de novo — 429, 5xx e
 *       rede (padrão 1). {@code backoffMs}: espera inicial, dobrada a cada repetição (padrão 250).</li>
 *   <li>{@code headers}: cabeçalhos extras.</li>
 * </ul>
 *
 * <p>O padrão é o caminho <b>estável</b> do OpenRouter ({@code /api/v1/systemone}, o protocolo do
 * TypeSafe); {@code /api/alpha/decisions} responde o mesmo, mas é alfa. O contrato é coberto por teste
 * contra um servidor simulado, e uma resposta que não casa vira {@link DecisionException} não
 * repetível — nunca uma decisão inventada.</p>
 */
public final class HttpDecisionProvider implements DecisionProvider {

    public static final String ID = "http-decisions";
    public static final String DEFAULT_ENDPOINT = "https://openrouter.ai/api/v1/systemone";
    public static final String DEFAULT_KEY_REF = "openrouter";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

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
        return Optional.of(ref instanceof String s && !s.isBlank() ? s : DEFAULT_KEY_REF);
    }

    @Override
    public DecisionResult decide(DecisionRequest request, DecisionCall call) throws DecisionException {
        if (call.model() == null || call.model().isBlank()) {
            throw new DecisionException("o provedor " + ID + " exige o 'model' do nó", false);
        }
        String body;
        try {
            body = JSON.writeValueAsString(toWire(request, call.model()));
        } catch (IOException e) {
            throw new DecisionException("pedido não serializável: " + e.getMessage(), false, e);
        }
        int retries = intOption(call, "retries", 1);
        long backoff = intOption(call, "backoffMs", 250);
        DecisionException ultima = null;
        for (int tentativa = 0; tentativa <= retries; tentativa++) {
            try {
                return parse(send(body, call), request);
            } catch (DecisionException e) {
                ultima = e;
                if (!e.retryable() || tentativa == retries) {
                    throw e;
                }
                dormir(backoff << tentativa);
            }
        }
        throw ultima;
    }

    private HttpResponse<String> doSend(String body, DecisionCall call) throws DecisionException {
        Object endpoint = call.options().get("endpoint");
        URI uri = URI.create(endpoint instanceof String s && !s.isBlank() ? s : DEFAULT_ENDPOINT);
        HttpRequest.Builder req = HttpRequest.newBuilder(uri).timeout(call.timeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (call.apiKey() != null && !call.apiKey().isBlank()) {
            req.header("Authorization", "Bearer " + call.apiKey());
        }
        if (call.options().get("headers") instanceof Map<?, ?> extras) {
            extras.forEach((k, v) -> {
                if (k != null && v != null && !"authorization".equalsIgnoreCase(k.toString())) {
                    req.header(k.toString(), v.toString());
                }
            });
        }
        try {
            return CLIENT.send(req.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DecisionException("chamada interrompida", false, e);
        } catch (IOException e) {
            throw new DecisionException("falha de rede: " + e.getMessage(), true, e);
        }
    }

    private HttpResponse<String> send(String body, DecisionCall call) throws DecisionException {
        HttpResponse<String> resposta = doSend(body, call);
        int status = resposta.statusCode();
        if (status / 100 == 2) {
            return resposta;
        }
        // 429 e 529 (sobrecarga) e 5xx melhoram tentando de novo; o resto é o pedido ou a chave.
        boolean repetivel = status == 429 || status == 529 || status >= 500;
        throw new DecisionException("o serviço de decisão respondeu HTTP " + status
                + (repetivel ? " (repetível)" : ""), repetivel);
    }

    // ── Protocolo ───────────────────────────────────────────────────

    static ObjectNode toWire(DecisionRequest request, String model) {
        ObjectNode raiz = JSON.createObjectNode();
        raiz.put("model", model);
        raiz.set("state", JSON.valueToTree(request.state()));
        ObjectNode perguntas = raiz.putObject("questions");
        for (Question q : request.questions()) {
            ObjectNode no = perguntas.putObject(q.id());
            if (!q.instructions().isBlank()) {
                no.put("instructions", q.instructions());
            }
            switch (q) {
                case Question.Choice c -> {
                    no.put("type", "choice");
                    ObjectNode criterios = no.putObject("criteria");
                    c.options().forEach((opcao, descricao) ->
                            criterios.put(opcao, descricao == null ? "" : descricao));
                }
                case Question.Score s -> {
                    no.put("type", "score");
                    ArrayNode niveis = no.putArray("criteria");
                    s.levels().forEach(niveis::add);
                }
                case Question.YesNo y -> {
                    no.put("type", "noul");
                    if (y.whenTrue() != null || y.whenFalse() != null) {
                        ObjectNode criterios = no.putObject("criteria");
                        criterios.put("true", y.whenTrue() == null ? "" : y.whenTrue());
                        criterios.put("false", y.whenFalse() == null ? "" : y.whenFalse());
                    }
                }
            }
        }
        return raiz;
    }

    static DecisionResult parse(HttpResponse<String> resposta, DecisionRequest request)
            throws DecisionException {
        JsonNode raiz;
        try {
            raiz = JSON.readTree(resposta.body());
        } catch (IOException e) {
            throw new DecisionException("resposta de decisão ilegível: " + e.getMessage(), false, e);
        }
        JsonNode respostas = raiz == null ? null : raiz.get("answers");
        if (respostas == null || !respostas.isObject()) {
            throw new DecisionException("resposta de decisão sem 'answers'", false);
        }
        Map<String, Answer> answers = new LinkedHashMap<>();
        for (Question q : request.questions()) {
            JsonNode no = respostas.get(q.id());
            if (no == null) {
                // Uma pergunta sem resposta não pode virar "baixa confiança": é um serviço que não
                // cumpriu o contrato, e quem decide em cima disso decidiria no escuro.
                throw new DecisionException("a resposta não traz a pergunta '" + q.id() + "'", false);
            }
            answers.put(q.id(), answer(q, no));
        }
        JsonNode uso = raiz.get("usage");
        DecisionResult.Usage usage = uso == null ? null : new DecisionResult.Usage(
                longOrNull(uso.get("input_tokens")), longOrNull(uso.get("output_tokens")),
                uso.hasNonNull("cost") ? uso.get("cost").asDouble() : null);
        return new DecisionResult(answers, text(raiz.get("model")), text(raiz.get("provider")),
                true, usage);
    }

    private static Answer answer(Question q, JsonNode no) throws DecisionException {
        try {
            return switch (q) {
                case Question.Choice c -> {
                    String escolha = text(no.get("choice"));
                    if (escolha == null || !c.options().containsKey(escolha)) {
                        throw new DecisionException("'" + q.id() + "': a opção devolvida ("
                                + escolha + ") não é uma das declaradas", false);
                    }
                    yield new Answer.Choice(escolha, doubles(no.get("probabilities")),
                            required(no, "confidence", q));
                }
                case Question.Score s -> new Answer.Score(required(no, "score", q),
                        doubles(no.get("probabilities")), strings(no.get("legend")),
                        required(no, "confidence", q));
                case Question.YesNo y -> new Answer.YesNo(required(no, "noul", q));
            };
        } catch (NumberFormatException e) {
            throw new DecisionException("'" + q.id() + "': número inválido na resposta", false, e);
        }
    }

    private static double required(JsonNode no, String campo, Question q) throws DecisionException {
        JsonNode valor = no.get(campo);
        if (valor == null || !valor.isNumber()) {
            throw new DecisionException("'" + q.id() + "': a resposta não traz '" + campo + "'", false);
        }
        return valor.asDouble();
    }

    private static Map<String, Double> doubles(JsonNode no) {
        Map<String, Double> mapa = new LinkedHashMap<>();
        if (no != null && no.isObject()) {
            no.fields().forEachRemaining(e -> {
                if (e.getValue().isNumber()) {
                    mapa.put(e.getKey(), e.getValue().asDouble());
                }
            });
        }
        return mapa;
    }

    private static Map<String, String> strings(JsonNode no) {
        Map<String, String> mapa = new LinkedHashMap<>();
        if (no != null && no.isObject()) {
            no.fields().forEachRemaining(e -> mapa.put(e.getKey(), e.getValue().asText()));
        }
        return mapa;
    }

    private static String text(JsonNode no) {
        return no == null || no.isNull() ? null : no.asText();
    }

    private static Long longOrNull(JsonNode no) {
        return no == null || !no.isNumber() ? null : no.asLong();
    }

    private static int intOption(DecisionCall call, String chave, int padrao) {
        return call.options().get(chave) instanceof Number n ? Math.max(0, n.intValue()) : padrao;
    }

    private static void dormir(long ms) throws DecisionException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DecisionException("chamada interrompida", false, e);
        }
    }
}
