package br.com.archflow.langchain4j.openrouter;

import br.com.archflow.langchain4j.core.chat.ChatTurn;
import br.com.archflow.langchain4j.core.spi.ChatModelProvider;
import br.com.archflow.langchain4j.core.spi.LangChainAdapter;
import br.com.archflow.model.config.ProviderPreferences;
import br.com.archflow.model.config.ResolvedLLMConfig;
import br.com.archflow.model.engine.ExecutionContext;
import br.com.archflow.model.engine.ExecutionKeys;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.memory.ChatMemory;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Adapter para OpenRouter via API compatível com OpenAI.
 *
 * <p>OpenRouter roteia chamadas para múltiplos providers (OpenAI, Anthropic,
 * Google, Meta, etc.) via uma API unificada compatível com OpenAI.
 *
 * <p>Suporta fallback automático para um provider local (ex: Ollama)
 * quando OpenRouter está indisponível.
 *
 * <p>Configuração:
 * <ul>
 *   <li>{@code api.key} — Chave de API do OpenRouter (obrigatória)</li>
 *   <li>{@code model.name} — Modelo (ex: "anthropic/claude-3.5-sonnet", default: "openai/gpt-4o-mini")</li>
 *   <li>{@code base.url} — URL base (default: "https://openrouter.ai/api/v1")</li>
 *   <li>{@code temperature} — Temperatura (default: 0.7)</li>
 *   <li>{@code fallback.base.url} — URL do provider fallback (ex: "http://localhost:11434/v1")</li>
 *   <li>{@code fallback.model.name} — Modelo fallback (ex: "llama3.1")</li>
 *   <li>{@link #PREFERENCES_PROPERTY} / {@link #PREFERENCES_REQUIRED_PROPERTY} — preferência de
 *       provedor do deployment (ZDR); ver abaixo</li>
 * </ul>
 *
 * <p><b>Preferência de provedor (ZDR):</b> o objeto {@code provider: {zdr, data_collection}} vai no
 * corpo de toda requisição cujo destino é o OpenRouter — modelo principal, modelo por contexto e
 * fallback — no mesmo formato do hub dos agentes ({@code LLMProviderHub.parametrosDoCorpo}) e só para
 * destino do OpenRouter ({@link #isOpenRouterDestination}): host {@code openrouter.ai} ou um endereço que
 * o deployment declarou ser o OpenRouter ({@link #PREFERENCES_OPENROUTER_URLS_PROPERTY} — o
 * {@code archflow.llm.base-url} quando {@code archflow.llm.provider=openrouter}). Um Ollama de fallback ou
 * outro host qualquer não recebe o campo (desconhecido → 400). Com {@code required=true} e nenhuma
 * preferência, um destino do OpenRouter falha <b>antes</b> de o modelo existir — nenhuma requisição sai
 * sem a garantia. Quem preenche as três propriedades é o {@code LangChainAdapterComponent}, a partir de
 * {@code archflow.llm.provider-preferences}; o que um nó escrever nelas é sobrescrito lá.
 *
 * <p><b>Concorrência:</b> modelo, fallback e config são mantidos em referências
 * {@code volatile}; o lock interno protege apenas a troca de configuração
 * (configure/shutdown), nunca a chamada de rede. Execuções paralelas capturam
 * as referências correntes em variáveis locais e chamam o modelo fora do lock.
 * Quem reconfigura troca as referências: chamadas em voo terminam no modelo
 * antigo e chamadas subsequentes usam o novo.
 */
public class OpenRouterChatAdapter implements LangChainAdapter, ChatModelProvider {

    /** Protege apenas leitura/troca de configuração — nunca a chamada de rede. */
    private final ReentrantLock lock = new ReentrantLock();
    private static final Logger logger = Logger.getLogger(OpenRouterChatAdapter.class.getName());
    private static final String DEFAULT_BASE_URL = "https://openrouter.ai/api/v1";
    private static final String DEFAULT_MODEL = "openai/gpt-4o-mini";

    /**
     * Preferência de provedor do deployment: um {@link ProviderPreferences} ou um mapa
     * {@code {zdr, data_collection}}. Ausente = nenhuma (corpo inalterado).
     */
    public static final String PREFERENCES_PROPERTY = "provider.preferences";
    /** {@code archflow.llm.provider-preferences.required}: destino do OpenRouter sem preferência falha. */
    public static final String PREFERENCES_REQUIRED_PROPERTY = "provider.preferences.required";
    /**
     * Endereços que o deployment declarou serem o OpenRouter sem o host {@code openrouter.ai} — um
     * gateway/proxy do operador posto em {@code archflow.llm.base-url} com
     * {@code archflow.llm.provider=openrouter}. Coleção de URLs ou texto separado por vírgula.
     *
     * <p>Sem isto, o mesmo deployment dava ZDR aos agentes (o hub decide pelo provider) e não aos nós
     * (aqui a decisão era só pelo host): o nó {@code llm-chat} sem provider herda esse
     * {@code base-url} e ia ao gateway sem o objeto {@code provider}, e {@code required=true} não o
     * barrava. Comparação por esquema + host + porta, como na {@code BaseUrlPolicy}.
     */
    public static final String PREFERENCES_OPENROUTER_URLS_PROPERTY = "provider.preferences.openrouter-urls";

    private volatile ChatModel model;
    private volatile ChatModel fallbackModel;
    private volatile Map<String, Object> config;
    private volatile Preferencia preferencia = Preferencia.NENHUMA;

    @Override
    public void validate(Map<String, Object> properties) {
        if (properties == null) {
            throw new IllegalArgumentException("Properties cannot be null");
        }
        String apiKey = (String) properties.get("api.key");
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalArgumentException("OpenRouter API key is required (api.key)");
        }
    }

    @Override
    public void configure(Map<String, Object> properties) {
        validate(properties);

        String apiKey = (String) properties.get("api.key");
        String modelName = (String) properties.getOrDefault("model.name", DEFAULT_MODEL);
        String baseUrl = (String) properties.getOrDefault("base.url", DEFAULT_BASE_URL);
        Double temperature = ((Number) properties.getOrDefault("temperature", 0.7)).doubleValue();
        Integer maxTokens = properties.get("maxTokens") != null
                ? ((Number) properties.get("maxTokens")).intValue() : 2048;
        Preferencia newPreferencia = Preferencia.de(properties);

        OpenAiChatModel.OpenAiChatModelBuilder mainBuilder = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(temperature)
                .maxTokens(maxTokens);
        newPreferencia.aplicar(mainBuilder, baseUrl);
        ChatModel newModel = mainBuilder.build();

        // Configurar fallback (ex: Ollama local)
        ChatModel newFallbackModel = null;
        String fallbackBaseUrl = (String) properties.get("fallback.base.url");
        if (fallbackBaseUrl != null && !fallbackBaseUrl.isBlank()) {
            String fallbackModelName = (String) properties.getOrDefault("fallback.model.name", "llama3.1");
            String fallbackApiKey = (String) properties.getOrDefault("fallback.api.key", "ollama");
            OpenAiChatModel.OpenAiChatModelBuilder fallbackBuilder = OpenAiChatModel.builder()
                    .apiKey(fallbackApiKey)
                    .baseUrl(fallbackBaseUrl)
                    .modelName(fallbackModelName)
                    .temperature(temperature)
                    .maxTokens(maxTokens);
            // Um fallback que também é o OpenRouter não pode ser a porta dos fundos da garantia.
            newPreferencia.aplicar(fallbackBuilder, fallbackBaseUrl);
            newFallbackModel = fallbackBuilder.build();
            logger.info("OpenRouter fallback configured: " + fallbackBaseUrl + " / " + fallbackModelName);
        }

        // Lock apenas para a troca de configuração (referências voláteis).
        lock.lock();
        try {
            this.config = properties;
            this.preferencia = newPreferencia;
            this.model = newModel;
            this.fallbackModel = newFallbackModel;
        } finally {
            lock.unlock();
        }

        logger.info("OpenRouter adapter configured: " + baseUrl + " / " + modelName);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Expõe o {@link ChatModel} default interno já configurado para
     * composição (ex.: RAG chain, agents). Fallback e overrides por contexto
     * não se aplicam ao modelo retornado.
     */
    @Override
    public ChatModel getChatModel() {
        ChatModel current = this.model;
        if (current == null) {
            throw new IllegalStateException(
                    "OpenRouterChatAdapter is not configured. Call configure() before getChatModel().");
        }
        return current;
    }

    @Override
    public Object execute(String operation, Object input, ExecutionContext context) throws Exception {
        // Leitura volátil: captura as referências correntes em variáveis locais e
        // faz a chamada de rede FORA do lock — execuções paralelas não são
        // serializadas. Reconfigurações trocam as referências; chamadas em voo
        // usam o modelo antigo.
        ChatModel currentModel = this.model;
        ChatModel currentFallback = this.fallbackModel;
        if (currentModel == null) {
            throw new IllegalStateException("Adapter not configured. Call configure() first.");
        }

        return doExecute(currentModel, currentFallback, operation, input, context);
    }

    private Object doExecute(ChatModel model, ChatModel fallbackModel, String operation, Object input,
                             ExecutionContext context) throws Exception {
            // Resolve modelo por tenant/agente via ExecutionContext.variables
            ChatModel resolved = resolveModel(model, context);

            try {
                return executeWithModel(resolved, operation, input, context);
            } catch (Exception e) {
                if (fallbackModel != null) {
                    logger.log(Level.WARNING,
                            "OpenRouter call failed, falling back to local provider: " + e.getMessage());
                    return executeWithModel(fallbackModel, operation, input, context);
                }
                throw e;
            }
    }

    /**
     * Resolve o modelo a usar com base no contexto. Prioridade:
     * <ol>
     *   <li>{@link ExecutionKeys#LLM_RESOLVED_CONFIG} — config resolvida pela
     *       cadeia de herança (model/temperature/maxTokens/apiKey/baseUrl);</li>
     *   <li>{@link ExecutionKeys#LLM_MODEL} — override legado só do nome;</li>
     *   <li>modelo default configurado.</li>
     * </ol>
     */
    private ChatModel resolveModel(ChatModel defaultModel, ExecutionContext context) {
        EffectiveModel eff = effectiveModel(context);
        if (eff == null) {
            return defaultModel;
        }
        OpenAiChatModel.OpenAiChatModelBuilder b = OpenAiChatModel.builder()
                .apiKey(eff.apiKey())
                .baseUrl(eff.baseUrl())
                .modelName(eff.modelName())
                .temperature(eff.temperature());
        if (eff.maxTokens() != null) {
            b.maxTokens(eff.maxTokens());
        }
        // O destino pode ter mudado com o contexto: a regra de host vale para o destino efetivo.
        this.preferencia.aplicar(b, eff.baseUrl());
        return b.build();
    }

    /**
     * Decisão pura (sem construir modelo) de qual config efetiva usar.
     * {@code null} ⇒ usar o modelo default. Visível ao pacote para testes.
     */
    EffectiveModel effectiveModel(ExecutionContext context) {
        if (context == null) {
            return null;
        }
        // Leitura volátil única do config corrente — decisão consistente mesmo
        // com reconfiguração concorrente.
        Map<String, Object> config = this.config;
        Object resolved = context.get(ExecutionKeys.LLM_RESOLVED_CONFIG).orElse(null);
        if (resolved instanceof ResolvedLLMConfig r && r.model() != null && !r.model().isBlank()) {
            String apiKey = strOr(r.additionalConfig().get("apiKey"), (String) config.get("api.key"));
            String baseUrl = strOr(r.additionalConfig().get("baseUrl"),
                    (String) config.getOrDefault("base.url", DEFAULT_BASE_URL));
            Integer maxTokens = r.maxTokens() > 0 ? r.maxTokens() : null;
            return new EffectiveModel(r.model(), r.temperature(), maxTokens, apiKey, baseUrl);
        }
        Object override = context.get(ExecutionKeys.LLM_MODEL).orElse(null);
        if (override instanceof String modelName && !modelName.isBlank()) {
            String apiKey = (String) config.get("api.key");
            String baseUrl = (String) config.getOrDefault("base.url", DEFAULT_BASE_URL);
            double temperature = ((Number) config.getOrDefault("temperature", 0.7)).doubleValue();
            return new EffectiveModel(modelName, temperature, null, apiKey, baseUrl);
        }
        return null;
    }

    private static String strOr(Object value, String fallback) {
        return (value instanceof String s && !s.isBlank()) ? s : fallback;
    }

    /** Parâmetros efetivos do modelo (resultado da resolução de contexto). */
    record EffectiveModel(String modelName, double temperature, Integer maxTokens,
                          String apiKey, String baseUrl) {}

    /**
     * Preferência de provedor do deployment e se ela é obrigatória.
     *
     * <p>Antes deste adapter, o nó {@code llm-chat} com provider {@code openrouter} ia ao OpenRouter sem
     * o objeto {@code provider} — o roteamento podia cair num provedor que retém ou treina com os dados,
     * sem erro nem log, mesmo com ZDR configurado no deployment (RCHFLW-41).
     */
    record Preferencia(ProviderPreferences preferences, boolean required, List<String> openRouterUrls) {

        static final Preferencia NENHUMA = new Preferencia(ProviderPreferences.NONE, false, List.of());

        static Preferencia de(Map<String, Object> properties) {
            Object valor = properties.get(PREFERENCES_PROPERTY);
            ProviderPreferences prefs;
            if (valor instanceof ProviderPreferences p) {
                prefs = p;
            } else if (valor instanceof Map<?, ?> mapa) {
                Object zdr = mapa.get("zdr");
                Object coleta = mapa.get("data_collection");
                prefs = ProviderPreferences.of(zdr == null ? null : String.valueOf(zdr),
                        coleta == null ? null : String.valueOf(coleta));
            } else if (valor == null) {
                prefs = ProviderPreferences.NONE;
            } else {
                // Um tipo inesperado ignorado seria a garantia que parece dada e não está.
                throw new IllegalArgumentException("[LLM-ZDR] " + PREFERENCES_PROPERTY
                        + " deve ser um mapa {zdr, data_collection}, veio " + valor.getClass().getSimpleName());
            }
            Object obrigatoria = properties.get(PREFERENCES_REQUIRED_PROPERTY);
            boolean required = obrigatoria instanceof Boolean b ? b
                    : obrigatoria != null && Boolean.parseBoolean(String.valueOf(obrigatoria).trim());
            return new Preferencia(prefs, required,
                    urlsDoOpenRouter(properties.get(PREFERENCES_OPENROUTER_URLS_PROPERTY)));
        }

        private static List<String> urlsDoOpenRouter(Object valor) {
            List<String> urls = new ArrayList<>();
            if (valor instanceof Collection<?> lista) {
                lista.forEach(v -> urls.add(v == null ? null : String.valueOf(v)));
            } else if (valor instanceof String texto) {
                urls.addAll(List.of(texto.split(",")));
            } else if (valor != null) {
                throw new IllegalArgumentException("[LLM-ZDR] " + PREFERENCES_OPENROUTER_URLS_PROPERTY
                        + " deve ser uma lista de URLs, veio " + valor.getClass().getSimpleName());
            }
            urls.removeIf(u -> u == null || u.isBlank());
            return urls.stream().map(String::trim).toList();
        }

        /**
         * Põe o objeto {@code provider} no corpo quando o destino é o OpenRouter; com {@code required}
         * e nada a pôr, falha antes de o modelo existir. Outro destino não é tocado: o builder fica
         * como era e o corpo, byte a byte o de antes.
         */
        void aplicar(OpenAiChatModel.OpenAiChatModelBuilder builder, String baseUrl) {
            if (!isOpenRouterDestination(baseUrl, openRouterUrls)) {
                return;
            }
            if (preferences.isEmpty()) {
                if (required) {
                    throw new IllegalStateException("[LLM-ZDR] archflow.llm.provider-preferences.required=true, "
                            + "mas nenhuma preferência de provedor está configurada para o OpenRouter "
                            + "(archflow.llm.provider-preferences.zdr / .data-collection) — o modelo não foi "
                            + "criado e nenhuma requisição saiu.");
                }
                return;
            }
            // Um mapa só: customParameters substitui o anterior (ver LLMProviderHub.parametrosDoCorpo).
            builder.customParameters(Map.of("provider", preferences.toWire()));
        }
    }

    /**
     * O destino recebe a preferência de provedor? Sim para host {@code openrouter.ai} (ou subdomínio) e
     * para os endereços que o deployment declarou serem o OpenRouter
     * ({@link #PREFERENCES_OPENROUTER_URLS_PROPERTY}). Pública porque a ponte
     * ({@code LangChainAdapterComponent}) faz a mesma pergunta antes de o adapter existir — uma regra
     * só, para as duas respostas não divergirem.
     *
     * @param baseUrl        destino; ausente = o default deste adapter, que é o OpenRouter
     * @param openRouterUrls endereços do deployment tratados como OpenRouter; nulo = nenhum
     */
    public static boolean isOpenRouterDestination(String baseUrl, Collection<String> openRouterUrls) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return true; // sem destino o builder usa o default, que aqui é o OpenRouter
        }
        URI destino;
        try {
            destino = URI.create(baseUrl.trim());
        } catch (IllegalArgumentException e) {
            return true; // destino ilegível: na dúvida, trate como o OpenRouter (falha fechado)
        }
        if (ProviderPreferences.isOpenRouterHost(destino)) {
            return true;
        }
        if (openRouterUrls == null || destino.getHost() == null) {
            return false;
        }
        for (String url : openRouterUrls) {
            if (url == null || url.isBlank()) {
                continue;
            }
            try {
                if (mesmoDestino(destino, URI.create(url.trim()))) {
                    return true;
                }
            } catch (IllegalArgumentException e) {
                // Entrada ilegível do deployment não iguala destino nenhum; vale a regra do host.
            }
        }
        return false;
    }

    /** Esquema + host + porta (efetiva); o caminho não conta — mesmo host, mesmo dono. */
    private static boolean mesmoDestino(URI a, URI b) {
        return a.getScheme() != null && a.getScheme().equalsIgnoreCase(b.getScheme())
                && b.getHost() != null && a.getHost().equalsIgnoreCase(b.getHost())
                && porta(a) == porta(b);
    }

    private static int porta(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return switch (uri.getScheme().toLowerCase(java.util.Locale.ROOT)) {
            case "https" -> 443;
            case "http" -> 80;
            default -> -1;
        };
    }

    private Object executeWithModel(ChatModel chatModel, String operation, Object input,
                                     ExecutionContext context) throws Exception {
        // O prompt de sistema do nó vai na frente da mensagem, no modelo principal e no fallback
        // (ChatTurn) — antes era descartado e o modelo respondia sem as instruções.
        String systemPrompt = ChatTurn.systemPromptOf(this.config);
        if ("generate".equals(operation)) {
            if (!(input instanceof String)) {
                throw new IllegalArgumentException("Input must be a string for 'generate'");
            }
            return ChatTurn.generate(chatModel, systemPrompt, (String) input);
        }

        if ("chat".equals(operation)) {
            if (!(input instanceof String)) {
                throw new IllegalArgumentException("Chat input must be a string");
            }
            ChatMemory memory = context.getChatMemory();
            if (memory == null) {
                throw new IllegalStateException("Chat memory not available in context");
            }
            return ChatTurn.chat(chatModel, systemPrompt, memory, (String) input);
        }

        throw new IllegalArgumentException("Unsupported operation: " + operation);
    }

    @Override
    public void shutdown() {
        lock.lock();
        try {
            this.model = null;
            this.fallbackModel = null;
            this.config = null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retorna true se um fallback está configurado.
     */
    public boolean hasFallback() {
        return fallbackModel != null;
    }
}
