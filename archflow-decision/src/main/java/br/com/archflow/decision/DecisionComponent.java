package br.com.archflow.decision;

import br.com.archflow.model.ai.AIComponent;
import br.com.archflow.model.ai.metadata.ComponentMetadata;
import br.com.archflow.model.ai.type.ComponentType;
import br.com.archflow.model.engine.ExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Um nó de decisão: faz perguntas tipadas sobre um estado a um {@link DecisionProvider} e devolve a
 * resposta <b>com a rota</b> — o que o fluxo faz com ela é decidido pelas arestas.
 *
 * <h2>Configuração do nó</h2>
 * <pre>
 * provider: http-decisions            # qual provedor (padrão)
 * model: typesafe/jev-1.13            # o modelo; fixe a versão em produção
 * questions:                          # ao menos uma
 *   time:  { type: choice, instructions: "Quem cuida?", criteria: { pagamento: "cobrança", conta: "login" } }
 *   urgencia: { type: score, criteria: ["pode esperar", "esta semana", "agora"] }
 *   e_bug: { type: noul, criteria: { "true": "defeito", "false": "dúvida" } }
 * state: { ticket: "${input}", plano: "${cliente.plano}" }   # opcional; padrão: a entrada do nó
 * thresholds: { auto: 0.9, review: 0.5 }
 * primary: time                     # a pergunta cujo valor vira `decision`; padrão: a primeira
 * gate: [time]                        # quais perguntas decidem a rota; padrão: todas
 * onError: ESCALATE                   # ou FAIL
 * fallbacks: [ { provider: rules, options: { rules: {...} } } ]   # tentados em ordem se um provedor FALHA
 * cascade: [ { provider: http-decisions, model: outro } ]  # degraus seguintes, se o anterior respondeu com pouca confiança
 * acceptAt: 0.9                       # confiança que encerra a cascata; padrão: thresholds.auto
 * consensus: { models: [ { model: outro } ], onDisagree: REVIEW }  # outros modelos votam; discordância rebaixa a rota
 * providerOptions: { http-decisions: { endpoint: "…" } }
 * </pre>
 *
 * <h2>Saída</h2>
 * <p>Um mapa que as arestas lêem direto: {@code ${triagem.route}} ({@code AUTO|REVIEW|ESCALATE}),
 * {@code ${triagem.confidence}} (a menor entre as perguntas que decidem), {@code ${triagem.decision}}
 * (o valor da pergunta {@code primary}) e {@code ${triagem.answers.<id>.value}} /
 * {@code .confidence}.</p>
 *
 * <h2>Falha nunca vira palpite</h2>
 * <p>Provedor fora do ar, sem chave ou com resposta que não casa: com {@code onError: ESCALATE}
 * (padrão) a saída traz {@code route: ESCALATE}, {@code error} e nenhuma resposta — o fluxo segue
 * pelo caminho de escalada em vez de seguir confiante. {@code FAIL} lança, e o motor decide.</p>
 */
public class DecisionComponent implements AIComponent {

    public static final String COMPONENT_ID = "decision";
    public static final String VERSION = "1.0.0";
    public static final String OPERATION = "decide";

    private static final Logger log = LoggerFactory.getLogger(DecisionComponent.class);

    private final DecisionProviders providers;
    private final DecisionKeys keys;

    private String provider = HttpDecisionProvider.ID;
    private String model;
    private List<Question> questions = List.of();
    private Object stateTemplate;
    private RoutePolicy policy = RoutePolicy.DEFAULT;
    private Set<String> gate = Set.of();
    private String primary;
    private boolean failOnError;
    private Duration timeout = Duration.ofSeconds(10);
    private Map<String, Object> providerOptions = Map.of();
    private List<Map<String, Object>> fallbacks = List.of();
    private List<Map<String, Object>> cascade = List.of();
    private double acceptAt = RoutePolicy.DEFAULT.auto();
    private List<Map<String, Object>> consensusModels = List.of();
    private RoutePolicy.Route onDisagree = RoutePolicy.Route.REVIEW;
    private boolean initialized;

    public DecisionComponent(DecisionProviders providers, DecisionKeys keys) {
        this.providers = providers;
        this.keys = keys == null ? DecisionKeys.NONE : keys;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void initialize(Map<String, Object> config) {
        if (config == null) {
            throw new IllegalArgumentException(COMPONENT_ID + ": configuração obrigatória");
        }
        if (config.containsKey("apiKey")) {
            // Segredo no documento do fluxo é um risco que o resolvedor existe para evitar.
            log.warn("{}: 'apiKey' no nó é IGNORADA — a chave vem do resolvedor do tenant", COMPONENT_ID);
        }
        provider = str(config.get("provider"), HttpDecisionProvider.ID);
        model = str(config.get("model"), null);
        questions = parseQuestions(config.get("questions"));
        stateTemplate = config.get("state");
        Map<String, Object> th = config.get("thresholds") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        policy = new RoutePolicy(num(th.get("auto"), RoutePolicy.DEFAULT.auto()),
                num(th.get("review"), RoutePolicy.DEFAULT.review()));
        gate = config.get("gate") instanceof List<?> l ? Set.copyOf(l.stream()
                .map(String::valueOf).toList()) : Set.of();
        for (String id : gate) {
            if (questions.stream().noneMatch(q -> q.id().equals(id))) {
                throw new IllegalArgumentException(COMPONENT_ID + ": 'gate' cita a pergunta '"
                        + id + "', que não existe");
            }
        }
        primary = str(config.get("primary"), questions.get(0).id());
        if (questions.stream().noneMatch(q -> q.id().equals(primary))) {
            throw new IllegalArgumentException(COMPONENT_ID + ": 'primary' cita a pergunta '"
                    + primary + "', que não existe");
        }
        String onError = str(config.get("onError"), "ESCALATE").toUpperCase();
        if (!onError.equals("ESCALATE") && !onError.equals("FAIL")) {
            throw new IllegalArgumentException(COMPONENT_ID + ": 'onError' deve ser ESCALATE ou FAIL");
        }
        failOnError = onError.equals("FAIL");
        timeout = Duration.ofMillis((long) num(config.get("timeoutMs"), 10_000));
        providerOptions = config.get("providerOptions") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        fallbacks = config.get("fallbacks") instanceof List<?> l
                ? l.stream().filter(Map.class::isInstance).map(o -> (Map<String, Object>) o).toList()
                : List.of();
        cascade = tiers(config.get("cascade"));
        acceptAt = num(config.get("acceptAt"), policy.auto());
        if (acceptAt < 0 || acceptAt > 1) {
            throw new IllegalArgumentException(COMPONENT_ID + ": 'acceptAt' deve estar entre 0 e 1");
        }
        Map<String, Object> consenso = config.get("consensus") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        consensusModels = tiers(consenso.get("models"));
        String desacordo = str(consenso.get("onDisagree"), "REVIEW").toUpperCase();
        if (!desacordo.equals("REVIEW") && !desacordo.equals("ESCALATE")) {
            throw new IllegalArgumentException(COMPONENT_ID + ": 'consensus.onDisagree' deve ser REVIEW ou ESCALATE");
        }
        onDisagree = RoutePolicy.Route.valueOf(desacordo);
        initialized = true;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> tiers(Object raw) {
        return raw instanceof List<?> l
                ? l.stream().filter(Map.class::isInstance).map(o -> (Map<String, Object>) o).toList()
                : List.of();
    }

    @Override
    public ComponentMetadata getMetadata() {
        return new ComponentMetadata(COMPONENT_ID, "Decision",
                "Decisões tipadas (escolha, escala, sim/não) com confiança e rota, por modelo de "
                        + "decisão, LLM ou regras",
                ComponentType.TOOL, VERSION, Set.of("decision", "classification", "routing"),
                List.of(new ComponentMetadata.OperationMetadata(OPERATION, "Decidir",
                        "Responde às perguntas do nó sobre o estado e calcula a rota",
                        List.of(new ComponentMetadata.ParameterMetadata(
                                "input", "any", "O estado a julgar (padrão do nó)", false)),
                        List.of(new ComponentMetadata.ParameterMetadata(
                                        "route", "string", "AUTO, REVIEW ou ESCALATE", true),
                                new ComponentMetadata.ParameterMetadata(
                                        "confidence", "number", "A menor confiança entre as perguntas que decidem", true),
                                new ComponentMetadata.ParameterMetadata(
                                        "decision", "any", "O valor da primeira pergunta", false),
                                new ComponentMetadata.ParameterMetadata(
                                        "answers", "object", "Resposta por pergunta", false)))),
                Map.of(), Set.of("decision", "classifier", "router"),
                Set.of("decisão", "classificar", "rotear", "triagem", "intenção"));
    }

    /** Uma tentativa que deu certo: o resultado, de quem veio e a confiança que decide a rota. */
    private record Tentativa(DecisionResult result, String provider, String model, double confidence) {
    }

    @Override
    public Object execute(String operation, Object input, ExecutionContext context) throws Exception {
        if (!initialized) {
            throw new IllegalStateException(COMPONENT_ID + " não inicializado");
        }
        Object state = stateTemplate == null ? input
                : StateTemplate.resolve(stateTemplate, StateTemplate.fromContext(context, input));
        DecisionRequest request = new DecisionRequest(state, questions);
        String tenantId = context == null ? null : context.getTenantId();
        Optional<DecisionUsageListener> uso = DecisionUsageListener.from(context);

        // CASCATA: o primeiro degrau é o provedor do nó; os seguintes só entram quando o anterior
        // respondeu mas com menos confiança que `acceptAt`. Para no primeiro que atinge o limiar; se
        // nenhum atinge, vale o mais confiante — e a rota (REVIEW/ESCALATE) diz que não é seguro.
        List<Map<String, Object>> degraus = new ArrayList<>();
        Map<String, Object> primeira = new LinkedHashMap<>();
        primeira.put("provider", provider);
        primeira.put("model", model);
        degraus.add(primeira);
        degraus.addAll(cascade);

        List<String> falhas = new ArrayList<>();
        List<Map<String, Object>> trilha = new ArrayList<>();
        Tentativa aceita = null;
        Tentativa melhor = null;
        for (int i = 0; i < degraus.size() && aceita == null; i++) {
            Tentativa t = comReserva(degraus.get(i), request, tenantId, uso, falhas);
            Map<String, Object> passo = new LinkedHashMap<>();
            passo.put("tier", i);
            passo.put("provider", t == null ? str(degraus.get(i).get("provider"), null) : t.provider());
            passo.put("model", t == null ? str(degraus.get(i).get("model"), null) : t.model());
            if (t == null) {
                passo.put("status", "FAILED");
            } else {
                passo.put("confidence", t.confidence());
                boolean basta = t.confidence() >= acceptAt;
                passo.put("status", basta ? "ACCEPTED" : "LOW_CONFIDENCE");
                if (melhor == null || t.confidence() > melhor.confidence()) {
                    melhor = t;
                }
                if (basta) {
                    aceita = t;
                }
            }
            trilha.add(passo);
        }
        Tentativa escolhida = aceita != null ? aceita : melhor;
        if (escolhida == null) {
            String erro = String.join("; ", falhas);
            if (failOnError) {
                throw new DecisionException("a decisão não saiu — " + erro, false);
            }
            return degradada(erro);
        }

        Map<String, Object> saida = montar(escolhida.result(), escolhida.confidence());
        if (degraus.size() > 1) {
            saida.put("cascade", trilha);
        }
        if (!consensusModels.isEmpty()) {
            consenso(saida, escolhida, request, tenantId, uso);
        }
        return saida;
    }

    /** Tenta o degrau e, se ele FALHAR, os `fallbacks`, em ordem. Nulo quando nenhum respondeu. */
    private Tentativa comReserva(Map<String, Object> degrau, DecisionRequest request, String tenantId,
                                 Optional<DecisionUsageListener> uso, List<String> falhas) {
        List<Map<String, Object>> candidatos = new ArrayList<>();
        candidatos.add(degrau);
        candidatos.addAll(fallbacks);
        for (Map<String, Object> candidato : candidatos) {
            String id = str(candidato.get("provider"), null);
            try {
                return tentar(id, candidato, request, tenantId, uso);
            } catch (DecisionException e) {
                log.warn("{}: provedor '{}' não decidiu: {}", COMPONENT_ID, id, e.getMessage());
                falhas.add(id + ": " + e.getMessage());
            }
        }
        return null;
    }

    private Tentativa tentar(String id, Map<String, Object> tentativa, DecisionRequest request,
                             String tenantId, Optional<DecisionUsageListener> uso)
            throws DecisionException {
        Optional<DecisionProvider> found = providers.find(id);
        if (found.isEmpty()) {
            throw new DecisionException("provedor de decisão desconhecido: " + id, false);
        }
        DecisionProvider p = found.get();
        Map<String, Object> options = new LinkedHashMap<>();
        if (providerOptions.get(id) instanceof Map<?, ?> m) {
            m.forEach((k, v) -> options.put(String.valueOf(k), v));
        }
        if (tentativa.get("options") instanceof Map<?, ?> m) {
            m.forEach((k, v) -> options.put(String.valueOf(k), v));
        }
        String key = null;
        if (p.requiresKey()) {
            key = p.keyRef(options).flatMap(ref -> keys.resolve(tenantId, ref)).orElse(null);
            if (key == null || key.isBlank()) {
                // Sem chave a chamada nem sai: falha de configuração, que escalar deixa visível.
                throw new DecisionException("sem chave de API para o provedor '" + id + "'", false);
            }
        }
        DecisionResult result = p.decide(request, new DecisionCall(tenantId,
                str(tentativa.get("model"), null), key, timeout, options));
        // O consumo é de quem chamou: cada chamada que foi feita conta, mesmo a de um degrau que
        // depois não foi o escolhido.
        if (result.usage() != null) {
            uso.ifPresent(u -> u.onDecision(result.provider(), result.model(),
                    result.usage().inputTokens(), result.usage().outputTokens(),
                    result.usage().costUsd()));
        }
        return new Tentativa(result, result.provider(), result.model(), confidenceOf(result));
    }

    /** A menor confiança entre as perguntas que decidem a rota; 0 quando nenhuma decide. */
    private double confidenceOf(DecisionResult result) {
        double minima = 1.0;
        boolean temGate = false;
        for (Question q : questions) {
            Answer a = result.answers().get(q.id());
            if (a != null && (gate.isEmpty() || gate.contains(q.id()))) {
                minima = Math.min(minima, a.confidence());
                temGate = true;
            }
        }
        return temGate ? minima : 0.0;
    }

    /**
     * CONSENSO: os mesmos pedidos a outros modelos, em paralelo. Se discordam do valor da pergunta
     * principal, a rota é rebaixada para no mínimo {@code onDisagree} — dois bons modelos que não
     * concordam são um sinal de que a decisão não é segura, e quem aprova é uma pessoa.
     */
    private void consenso(Map<String, Object> saida, Tentativa principal, DecisionRequest request,
                          String tenantId, Optional<DecisionUsageListener> uso) {
        List<Tentativa> votos = new ArrayList<>();
        votos.add(principal);
        List<String> falhasDoConsenso = new ArrayList<>();
        List<java.util.concurrent.Future<Tentativa>> futuros = new ArrayList<>();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (Map<String, Object> modelo : consensusModels) {
                futuros.add(executor.submit(() -> tentar(str(modelo.get("provider"), provider),
                        modelo, request, tenantId, uso)));
            }
            for (int i = 0; i < futuros.size(); i++) {
                try {
                    votos.add(futuros.get(i).get());
                } catch (java.util.concurrent.ExecutionException e) {
                    falhasDoConsenso.add(str(consensusModels.get(i).get("model"), "?") + ": "
                            + (e.getCause() == null ? e : e.getCause()).getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    falhasDoConsenso.add("interrompido");
                    break;
                }
            }
        }
        java.util.Set<Object> distintos = new java.util.LinkedHashSet<>();
        List<Map<String, Object>> lista = new ArrayList<>();
        for (Tentativa t : votos) {
            Object chave = chaveDeVoto(t.result().answers().get(primary));
            distintos.add(chave);
            Map<String, Object> voto = new LinkedHashMap<>();
            voto.put("provider", t.provider());
            voto.put("model", t.model());
            voto.put("decision", t.result().answers().get(primary).value());
            voto.put("confidence", t.confidence());
            lista.add(voto);
        }
        boolean concordam = distintos.size() <= 1;
        Map<String, Object> resumo = new LinkedHashMap<>();
        resumo.put("agree", concordam);
        resumo.put("votes", lista);
        if (!falhasDoConsenso.isEmpty()) {
            resumo.put("failures", falhasDoConsenso);
        }
        saida.put("consensus", resumo);
        if (!concordam) {
            RoutePolicy.Route atual = RoutePolicy.Route.valueOf((String) saida.get("route"));
            if (onDisagree.ordinal() > atual.ordinal()) {
                saida.put("route", onDisagree.name());
            }
            saida.put("routeReason", "consensus_disagreement");
        }
        // O consumo do resultado inclui o de todos os modelos que votaram.
        saida.put("usage", somarUso(votos));
    }

    /** O que conta como "a mesma decisão": a opção, o nível arredondado, ou o lado do sim/não. */
    private static Object chaveDeVoto(Answer a) {
        return switch (a) {
            case Answer.Choice c -> c.choice();
            case Answer.Score s -> Math.round(s.score());
            case Answer.YesNo y -> y.probability() >= 0.5;
            // Uma recusa não concorda com nenhuma resposta: o consenso a trata como discordância.
            case Answer.Refused r -> "refused";
        };
    }

    private static Map<String, Object> somarUso(List<Tentativa> votos) {
        long in = 0;
        long out = 0;
        double custo = 0;
        boolean temCusto = false;
        for (Tentativa t : votos) {
            var u = t.result().usage();
            if (u == null) {
                continue;
            }
            in += u.inputTokens() == null ? 0 : u.inputTokens();
            out += u.outputTokens() == null ? 0 : u.outputTokens();
            if (u.costUsd() != null) {
                custo += u.costUsd();
                temCusto = true;
            }
        }
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("inputTokens", in);
        usage.put("outputTokens", out);
        usage.put("costUsd", temCusto ? custo : null);
        return usage;
    }

    private Map<String, Object> montar(DecisionResult result, double confidence) {
        Map<String, Map<String, Object>> answers = new LinkedHashMap<>();
        for (Question q : questions) {
            Answer a = result.answers().get(q.id());
            Map<String, Object> no = new LinkedHashMap<>();
            no.put("value", a.value());
            no.put("confidence", a.confidence());
            switch (a) {
                case Answer.Choice c -> {
                    no.put("type", "choice");
                    no.put("choice", c.choice());
                    no.put("probabilities", c.probabilities());
                }
                case Answer.Score s -> {
                    no.put("type", "score");
                    no.put("score", s.score());
                    no.put("probabilities", s.probabilities());
                    no.put("legend", s.legend());
                }
                case Answer.YesNo y -> {
                    no.put("type", "noul");
                    no.put("probability", y.probability());
                    no.put("result", y.probability() >= 0.5);
                }
                case Answer.Refused r -> {
                    no.put("type", "refused");
                    no.put("refused", true);
                }
            }
            answers.put(q.id(), no);
        }
        Map<String, Object> saida = new LinkedHashMap<>();
        saida.put("route", policy.route(confidence).name());
        saida.put("confidence", confidence);
        saida.put("decision", answers.get(primary).get("value"));
        saida.put("answers", answers);
        saida.put("provider", result.provider());
        saida.put("model", result.model());
        saida.put("calibrated", result.calibrated());
        if (result.usage() != null) {
            Map<String, Object> usage = new LinkedHashMap<>();
            usage.put("inputTokens", result.usage().inputTokens());
            usage.put("outputTokens", result.usage().outputTokens());
            usage.put("costUsd", result.usage().costUsd());
            saida.put("usage", usage);
        }
        return saida;
    }

    private static Map<String, Object> degradada(String erro) {
        Map<String, Object> saida = new LinkedHashMap<>();
        saida.put("route", RoutePolicy.Route.ESCALATE.name());
        saida.put("confidence", 0.0);
        saida.put("decision", null);
        saida.put("answers", Map.of());
        saida.put("error", erro);
        return saida;
    }

    @Override
    public void shutdown() {
        initialized = false;
    }

    // ── Configuração ────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    static List<Question> parseQuestions(Object raw) {
        if (!(raw instanceof Map<?, ?> mapa) || mapa.isEmpty()) {
            throw new IllegalArgumentException(COMPONENT_ID + ": 'questions' exige ao menos uma pergunta");
        }
        List<Question> lista = new ArrayList<>();
        for (Map.Entry<?, ?> e : mapa.entrySet()) {
            String id = String.valueOf(e.getKey());
            if (!(e.getValue() instanceof Map<?, ?> q)) {
                throw new IllegalArgumentException(COMPONENT_ID + ": a pergunta '" + id + "' deve ser um objeto");
            }
            String type = str(q.get("type"), "").toLowerCase();
            String instructions = str(q.get("instructions"), "");
            Object criteria = q.get("criteria");
            switch (type) {
                case "choice" -> {
                    if (!(criteria instanceof Map<?, ?> c)) {
                        throw new IllegalArgumentException(COMPONENT_ID + ": 'criteria' de '" + id
                                + "' (choice) deve mapear opção → descrição");
                    }
                    Map<String, String> opcoes = new LinkedHashMap<>();
                    c.forEach((k, v) -> opcoes.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
                    lista.add(new Question.Choice(id, instructions, opcoes));
                }
                case "score" -> {
                    if (!(criteria instanceof List<?> l)) {
                        throw new IllegalArgumentException(COMPONENT_ID + ": 'criteria' de '" + id
                                + "' (score) deve ser a lista ordenada de níveis");
                    }
                    lista.add(new Question.Score(id, instructions, l.stream().map(String::valueOf).toList()));
                }
                case "noul", "boolean", "yesno" -> {
                    Map<String, Object> c = criteria instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
                    lista.add(new Question.YesNo(id, instructions,
                            str(c.get("true"), null), str(c.get("false"), null)));
                }
                default -> throw new IllegalArgumentException(COMPONENT_ID + ": tipo '" + type
                        + "' da pergunta '" + id + "' inválido (choice, score ou noul)");
            }
        }
        return lista;
    }

    private static String str(Object v, String padrao) {
        return v instanceof String s && !s.isBlank() ? s : padrao;
    }

    private static double num(Object v, double padrao) {
        return v instanceof Number n ? n.doubleValue() : padrao;
    }
}
