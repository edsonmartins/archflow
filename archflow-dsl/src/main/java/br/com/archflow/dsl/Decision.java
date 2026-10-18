package br.com.archflow.dsl;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * O nó de decisão ({@code decision}) com a configuração escrita por método: as
 * perguntas tipadas, quem responde, os limiares da rota e o que fazer com a
 * incerteza.
 *
 * <pre>{@code
 * Workflows.define("triagem-do-pedido")
 *     .step("intencao", Decision.node()
 *             .model("typesafe/jev-1.14")
 *             .choice("intencao", "O cliente está fechando um pedido?",
 *                     Map.of("PEDIDO", "Confirma itens e quantidades.",
 *                            "DUVIDA", "Pergunta sobre produto ou prazo."))
 *             .thresholds(0.9, 0.5))
 *     .step("fecha",  McpAgent.node().systemPrompt("..."))
 *     .step("humano", Nodes.approval())
 *     .route("intencao", "fecha",  Decision.AUTO)
 *     .route("intencao", "humano", Decision.REVIEW)
 *     .route("intencao", "humano", Decision.ESCALATE)
 *     .build();
 * }</pre>
 *
 * <h2>O que a DSL garante, e o que não garante</h2>
 *
 * <p>As chaves emitidas são as que o {@code DecisionComponent} lê — a
 * {@code DslDecisionConformanceTest}, no archflow-api, passa o documento pelo
 * desserializador real e executa o nó. O que a DSL <b>não</b> sabe é qual
 * provedor existe no runtime nem se a chave do tenant está configurada: o
 * {@code provider} segue sendo texto, e a chave nunca vai no documento — vem do
 * resolvedor do hospedeiro, pela referência em {@link #keyRef(String)}.
 *
 * <h2>A rota é a única ramificação</h2>
 *
 * <p>O nó grava {@code route} ({@link #AUTO}, {@link #REVIEW}, {@link #ESCALATE})
 * no contexto, e as arestas escolhem o caminho por ela. Em vez de escrever a
 * condição à mão ({@code ${passo.route} == 'AUTO'}), a aresta declara o ramo com
 * {@link WorkflowBuilder#route(String, String, String)}; o motor monta a condição
 * — a regra fica num lugar só, o dele.
 *
 * <p>Imutável, como a {@link NodeSpec}: cada método devolve uma instância nova.
 *
 * @since 1.5.0
 */
public final class Decision {

    /** Rota do nó: confiança no limiar {@code auto} ou acima — o fluxo segue sozinho. */
    public static final String AUTO = "AUTO";
    /** Rota do nó: entre {@code review} e {@code auto} — alguém confere. */
    public static final String REVIEW = "REVIEW";
    /** Rota do nó: abaixo de {@code review}, ou falha do provedor — escala. */
    public static final String ESCALATE = "ESCALATE";

    /** Tipo e id de componente do nó, como o motor os reconhece. */
    static final String NODE_TYPE = "decision";
    static final String OPERATION = "decide";

    private final Map<String, Object> config;
    private final Map<String, Object> questions;
    /** Opções do provedor principal, fundidas em {@code providerOptions} no {@link #spec()}. */
    private final Map<String, Object> mainProviderOptions;

    private Decision(Map<String, Object> config, Map<String, Object> questions,
                     Map<String, Object> mainProviderOptions) {
        this.config = config;
        this.questions = questions;
        this.mainProviderOptions = mainProviderOptions;
    }

    /** Um nó de decisão, ainda sem perguntas. */
    public static Decision node() {
        return new Decision(Map.of(), Map.of(), Map.of());
    }

    private Decision with(String key, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(config);
        merged.put(key, value);
        return new Decision(merged, questions, mainProviderOptions);
    }

    private Decision question(String id, Map<String, Object> question) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("pergunta sem id");
        }
        if (questions.containsKey(id)) {
            // Falha aqui: a segunda pergunta com o mesmo id sobrescreveria a primeira em silêncio,
            // e `primary`/`gate` apontariam para outra coisa do que o código aparenta.
            throw new IllegalArgumentException("pergunta duplicada: " + id);
        }
        Map<String, Object> merged = new LinkedHashMap<>(questions);
        merged.put(id, question);
        return new Decision(config, merged, mainProviderOptions);
    }

    // ------------------------------------------------------------ quem decide

    /**
     * O provedor que responde — {@code http-decisions} (padrão: a Decisions API do
     * OpenRouter), {@code langchain4j-decisions}, {@code llm}, {@code rules}, ou um
     * registrado por SPI. Quais existem só se sabe em runtime.
     */
    public Decision provider(String provider) {
        return with("provider", Objects.requireNonNull(provider, "provider"));
    }

    /**
     * O modelo de decisão ({@code typesafe/jev-1.14}). Fixe a versão em produção: um
     * {@code -latest} muda sozinho, e a confiança calibrada de um modelo não vale
     * para o outro.
     */
    public Decision model(String model) {
        return with("model", Objects.requireNonNull(model, "model"));
    }

    /**
     * De qual chave o hospedeiro resolve a credencial do provedor principal
     * (padrão do {@code http-decisions}: {@code openrouter}). É uma referência: a
     * chave em si nunca entra no documento — vem do resolvedor do tenant.
     */
    public Decision keyRef(String ref) {
        return providerOption("keyRef", Objects.requireNonNull(ref, "keyRef"));
    }

    /** O endpoint do provedor principal, quando não é o padrão dele. */
    public Decision endpoint(String url) {
        return providerOption("endpoint", Objects.requireNonNull(url, "endpoint"));
    }

    /**
     * Uma opção do provedor <b>principal</b> — vai em {@code providerOptions} sob o
     * id dele, seja ele declarado antes ou depois desta chamada.
     */
    public Decision providerOption(String key, Object value) {
        Objects.requireNonNull(key, "key");
        Map<String, Object> merged = new LinkedHashMap<>(mainProviderOptions);
        merged.put(key, value);
        return new Decision(config, questions, merged);
    }

    /** As opções de qualquer provedor, pelo id — para fallbacks e degraus que não são o principal. */
    @SuppressWarnings("unchecked")
    public Decision providerOptions(String provider, Map<String, Object> options) {
        Objects.requireNonNull(provider, "provider");
        Map<String, Object> all = new LinkedHashMap<>();
        if (config.get("providerOptions") instanceof Map<?, ?> m) {
            all.putAll((Map<String, Object>) m);
        }
        all.put(provider, options == null ? Map.of() : new LinkedHashMap<>(options));
        return with("providerOptions", all);
    }

    // ------------------------------------------------------------- perguntas

    /**
     * Qual destas opções? A resposta é a opção, com {@code probabilities} e
     * {@code confidence}.
     *
     * @param options opção → descrição do que ela quer dizer (a descrição é o que o
     *                modelo lê; opção sem descrição é adivinhação pelo nome)
     */
    public Decision choice(String id, String instructions, Map<String, String> options) {
        if (options == null || options.isEmpty()) {
            throw new IllegalArgumentException("choice '" + id + "' sem opções");
        }
        return question(id, pergunta("choice", instructions, new LinkedHashMap<>(options)));
    }

    /**
     * Onde isto cai numa escala ordenada? Os níveis vão do menor ao maior; a
     * resposta é um {@code score} fracionário nessa escala, com {@code legend}.
     */
    public Decision score(String id, String instructions, String... levels) {
        if (levels == null || levels.length < 2) {
            throw new IllegalArgumentException("score '" + id + "' precisa de ao menos 2 níveis");
        }
        return question(id, pergunta("score", instructions, List.of(levels)));
    }

    /** Esta condição vale? A resposta é {@code probability}, e a confiança é {@code |2p − 1|}. */
    public Decision yesNo(String id, String instructions) {
        return question(id, pergunta("noul", instructions, null));
    }

    /** Como {@link #yesNo(String, String)}, dizendo o que cada lado significa. */
    public Decision yesNo(String id, String instructions, String whenTrue, String whenFalse) {
        Map<String, String> lados = new LinkedHashMap<>();
        lados.put("true", Objects.requireNonNull(whenTrue, "whenTrue"));
        lados.put("false", Objects.requireNonNull(whenFalse, "whenFalse"));
        return question(id, pergunta("noul", instructions, lados));
    }

    private static Map<String, Object> pergunta(String type, String instructions, Object criteria) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("type", type);
        if (instructions != null && !instructions.isBlank()) {
            q.put("instructions", instructions);
        }
        if (criteria != null) {
            q.put("criteria", criteria);
        }
        return q;
    }

    // ---------------------------------------------------------------- estado

    /**
     * O estado julgado, campo a campo, com as expressões do fluxo
     * ({@code ${input}}, {@code ${cliente.plano}}). Ausente: a entrada do nó.
     */
    public Decision state(Map<String, Object> state) {
        if (state == null || state.isEmpty()) {
            throw new IllegalArgumentException("state vazio: omita para usar a entrada do nó");
        }
        return with("state", new LinkedHashMap<>(state));
    }

    // ---------------------------------------------------------- rota e limiares

    /**
     * Os limiares da rota: confiança {@code ≥ auto} → {@link #AUTO}; {@code ≥ review}
     * → {@link #REVIEW}; abaixo → {@link #ESCALATE}. Padrão do nó: 0,9 / 0,5.
     * Operação destrutiva pede limiar mais alto — e quem decide é o fluxo.
     */
    public Decision thresholds(double auto, double review) {
        if (auto < 0 || auto > 1 || review < 0 || review > 1) {
            throw new IllegalArgumentException("limiares fora de [0, 1]: auto=" + auto
                    + ", review=" + review);
        }
        if (review > auto) {
            throw new IllegalArgumentException("review (" + review + ") acima de auto (" + auto
                    + "): nenhuma confiança cairia em REVIEW");
        }
        Map<String, Object> th = new LinkedHashMap<>();
        th.put("auto", auto);
        th.put("review", review);
        return with("thresholds", th);
    }

    /** A pergunta cujo valor vira {@code decision}. Padrão: a primeira declarada. */
    public Decision primary(String questionId) {
        return with("primary", Objects.requireNonNull(questionId, "primary"));
    }

    /**
     * As perguntas que decidem a rota — a confiança do nó é a <b>menor</b> entre
     * elas. Padrão: todas. Uma pergunta informativa fora do {@code gate} não
     * rebaixa a decisão.
     */
    public Decision gate(String... questionIds) {
        if (questionIds == null || questionIds.length == 0) {
            throw new IllegalArgumentException("gate vazio: omita para que todas decidam a rota");
        }
        return with("gate", List.of(questionIds));
    }

    /**
     * Falha do provedor vira rota {@link #ESCALATE} com {@code confidence: 0} e
     * {@code error} — o fluxo segue pela escalada em vez de seguir confiante. É o
     * padrão do nó; o método existe para ser dito por extenso.
     */
    public Decision escalateOnError() {
        return with("onError", ESCALATE);
    }

    /** Falha do provedor lança, e o motor decide (caminho de erro da aresta). */
    public Decision failOnError() {
        return with("onError", "FAIL");
    }

    /** Quanto esperar por cada chamada ao provedor. Padrão do nó: 10 s. */
    public Decision timeout(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout deve ser positivo: " + timeout);
        }
        return with("timeoutMs", timeout.toMillis());
    }

    // ----------------------------------------------------- reserva e cascata

    /**
     * Um provedor tentado quando o anterior <b>falha</b> (fora do ar, sem chave,
     * resposta fora do contrato) — não quando responde com pouca confiança; isso
     * é {@link #cascade}.
     */
    public Decision fallback(String provider, String model) {
        return append("fallbacks", tier(provider, model));
    }

    /**
     * Um degrau tentado quando o anterior <b>respondeu com pouca confiança</b>
     * (abaixo de {@link #acceptAt}); a cascata para no primeiro que atinge. Se
     * nenhum atinge, vale o mais confiante — e a rota diz que não é seguro.
     * Cascata típica: {@code rules → modelo barato → modelo maior → humano}.
     */
    public Decision cascade(String provider, String model) {
        return append("cascade", tier(provider, model));
    }

    /** A confiança que encerra a cascata. Padrão: o limiar {@code auto}. */
    public Decision acceptAt(double confidence) {
        if (confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("acceptAt fora de [0, 1]: " + confidence);
        }
        return with("acceptAt", confidence);
    }

    // --------------------------------------------------------------- consenso

    /**
     * Outros modelos votam sobre o mesmo estado, em paralelo, no provedor
     * principal. Se discordam do valor da pergunta {@code primary}, a rota é
     * rebaixada para no mínimo {@link #REVIEW} (ou {@link #ESCALATE}, com
     * {@link #consensusEscalatesOnDisagree()}). A decisão segue sendo a do modelo
     * principal; o consumo soma todos os votos.
     */
    public Decision consensus(String... models) {
        if (models == null || models.length == 0) {
            throw new IllegalArgumentException("consensus sem modelos");
        }
        List<Map<String, Object>> lista = new ArrayList<>();
        for (String m : models) {
            lista.add(tier(null, Objects.requireNonNull(m, "model")));
        }
        return consensusWith("models", lista);
    }

    /** Como {@link #consensus(String...)}, com cada voto num provedor explícito. */
    public Decision consensusVote(String provider, String model) {
        List<Map<String, Object>> lista = new ArrayList<>(consensusModels());
        lista.add(tier(provider, model));
        return consensusWith("models", lista);
    }

    /** Discordância entre os votos escala, em vez de só pedir revisão. */
    public Decision consensusEscalatesOnDisagree() {
        return consensusWith("onDisagree", ESCALATE);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> consensusModels() {
        if (config.get("consensus") instanceof Map<?, ?> c
                && c.get("models") instanceof List<?> l) {
            return (List<Map<String, Object>>) l;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private Decision consensusWith(String key, Object value) {
        Map<String, Object> consenso = new LinkedHashMap<>();
        if (config.get("consensus") instanceof Map<?, ?> c) {
            consenso.putAll((Map<String, Object>) c);
        }
        consenso.put(key, value);
        return with("consensus", consenso);
    }

    private static Map<String, Object> tier(String provider, String model) {
        Map<String, Object> t = new LinkedHashMap<>();
        if (provider != null && !provider.isBlank()) {
            t.put("provider", provider);
        }
        if (model != null && !model.isBlank()) {
            t.put("model", model);
        }
        if (t.isEmpty()) {
            throw new IllegalArgumentException("degrau sem provider nem model");
        }
        return t;
    }

    @SuppressWarnings("unchecked")
    private Decision append(String key, Map<String, Object> item) {
        List<Map<String, Object>> lista = new ArrayList<>();
        if (config.get(key) instanceof List<?> l) {
            lista.addAll((List<Map<String, Object>>) l);
        }
        lista.add(item);
        return with(key, lista);
    }

    // ------------------------------------------------------------------ saída

    /** Uma chave que esta versão da DSL ainda não tenha método próprio. */
    public Decision with(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return this;
        }
        Map<String, Object> merged = new LinkedHashMap<>(config);
        merged.putAll(values);
        return new Decision(merged, questions, mainProviderOptions);
    }

    /**
     * O passo, pronto para {@link WorkflowBuilder#step(String, NodeSpec)}.
     *
     * @throws DslValidationException sem nenhuma pergunta — o nó recusaria a
     *                                configuração, e aqui o erro aparece com o
     *                                fluxo ainda em código
     */
    @SuppressWarnings("unchecked")
    public NodeSpec spec() {
        if (questions.isEmpty()) {
            throw new DslValidationException("nó de decisão sem perguntas: declare choice, score ou yesNo");
        }
        Map<String, Object> all = new LinkedHashMap<>(config);
        all.put("questions", new LinkedHashMap<>(questions));
        if (!mainProviderOptions.isEmpty()) {
            String provider = String.valueOf(all.getOrDefault("provider", "http-decisions"));
            Map<String, Object> porProvedor = new LinkedHashMap<>();
            if (all.get("providerOptions") instanceof Map<?, ?> m) {
                porProvedor.putAll((Map<String, Object>) m);
            }
            Map<String, Object> doPrincipal = new LinkedHashMap<>();
            if (porProvedor.get(provider) instanceof Map<?, ?> m) {
                doPrincipal.putAll((Map<String, Object>) m);
            }
            doPrincipal.putAll(mainProviderOptions);
            porProvedor.put(provider, doPrincipal);
            all.put("providerOptions", porProvedor);
        }
        return Nodes.decision().with(all);
    }

    /** O rótulo que o designer mostra. */
    public NodeSpec labeled(String label) {
        return spec().labeled(label);
    }
}
