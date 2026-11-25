package br.com.archflow.api.flow.adapter;

import br.com.archflow.model.config.LLMConfigPatch;
import br.com.archflow.model.config.ResolvedLLMConfig;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * O que um nó de chat ({@code llm-chat}, {@code llm-streaming}, {@code chat}) herda quando não diz
 * provider ou modelo — a mesma cadeia que o {@code mcp-agent} já tinha: <b>nó → fluxo
 * ({@code configuration.llmConfig}) → plataforma ({@code archflow.llm.provider/model})</b>.
 *
 * <p>Antes, o nó sem provider usava o próprio tipo ("llm-chat") como id de provider, não achava
 * adapter e caía no catálogo com "component not found: llm-chat" (RCHFLW-16). O designer escondia
 * o buraco mostrando "OpenAI / GPT-4o" sem gravar nada.
 *
 * <p>Regras que não são óbvias:
 * <ul>
 *   <li><b>O modelo herdado só vale para o provider de onde ele veio.</b> Um nó que escolhe
 *       {@code anthropic} num deployment cujo padrão é {@code openrouter} / {@code openai/gpt-6-luna}
 *       não pode mandar esse id à Anthropic — fica com o default do adapter. O modelo do próprio nó
 *       sempre vale (é o caso "herda o provider, escolhe o modelo").</li>
 *   <li><b>Nada disto volta para o documento.</b> É só a config de execução: mudar
 *       {@code archflow.llm.provider} muda os fluxos existentes, e os outros leitores do documento
 *       (YAML/standalone, DSL) continuam vendo o nó como foi escrito.</li>
 *   <li><b>A chave não vem daqui.</b> Ela é do {@code TenantKeyResolver} (tenant → plataforma),
 *       aplicado pela ponte — o padrão da plataforma não carrega segredo para o nó.</li>
 * </ul>
 */
public final class ChatNodeDefaults {

    /** De onde veio o provider que o nó vai usar. */
    public enum Origin {
        /** O nó disse ({@code config.provider}, ou o {@code componentId} legado). */
        NODE,
        /** {@code configuration.llmConfig.provider} do fluxo. */
        FLOW,
        /** {@code archflow.llm.provider} do deployment. */
        PLATFORM
    }

    /**
     * @param provider provider efetivo
     * @param origin   de onde ele veio
     * @param config   config de execução do nó: a do documento com o que foi herdado por cima do
     *                 que faltava
     */
    public record Resolution(String provider, Origin origin, Map<String, Object> config) {
    }

    /** Chaves com que o nó pode dizer o modelo (o designer grava {@code model}). */
    private static final List<String> MODEL_KEYS = List.of("model", "modelName", "model.name");

    /** Campos de destino do nó — com qualquer um deles, o {@code base-url} da plataforma não entra. */
    private static final List<String> URL_KEYS = LangChainAdapterComponent.URL_PROPERTIES;

    private ChatNodeDefaults() {
    }

    /**
     * @param config           config do nó como está no documento
     * @param explicitProvider provider que o nó declarou, ou {@code null}
     * @param flowPatch        {@code configuration.llmConfig} do fluxo (pode ser vazio)
     * @param platform         padrão da plataforma, ou {@code null} quando o host não o forneceu
     *                         (testes, standalone)
     * @return vazio quando não há provider em nenhum nível — o nó segue o caminho antigo
     */
    public static Optional<Resolution> resolve(Map<String, Object> config, String explicitProvider,
                                               LLMConfigPatch flowPatch, ResolvedLLMConfig platform) {
        LLMConfigPatch flow = flowPatch != null ? flowPatch : LLMConfigPatch.empty();
        String platformProvider = platform != null ? texto(platform.provider()) : null;
        // O que o nó herdaria se não dissesse nada: fluxo por cima da plataforma.
        String inheritedProvider = flow.provider().map(ChatNodeDefaults::texto).orElse(platformProvider);

        String provider;
        Origin origin;
        if (explicitProvider != null) {
            provider = explicitProvider;
            origin = Origin.NODE;
        } else if (flow.provider().map(ChatNodeDefaults::texto).isPresent()) {
            provider = inheritedProvider;
            origin = Origin.FLOW;
        } else if (platformProvider != null) {
            provider = platformProvider;
            origin = Origin.PLATFORM;
        } else {
            return Optional.empty();
        }

        Map<String, Object> effective = new LinkedHashMap<>(config == null ? Map.of() : config);
        // Um campo nulo (YAML "provider: ~") é ausente; a ponte copia a config com Map.copyOf.
        effective.values().removeIf(Objects::isNull);
        effective.remove("provider");
        if (MODEL_KEYS.stream().noneMatch(k -> texto(effective.get(k)) != null)
                && Objects.equals(provider, inheritedProvider)) {
            // O modelo do fluxo vale para o provider herdado; o da plataforma, só quando o provider
            // herdado é o da plataforma (o fluxo pode trocar o provider sem dizer o modelo).
            String model = flow.model().map(ChatNodeDefaults::texto).orElse(
                    Objects.equals(inheritedProvider, platformProvider) && platform != null
                            ? texto(platform.model()) : null);
            if (model != null) {
                effective.put("model", model);
            }
        }
        normalizarNumero(effective, "temperature", false);
        normalizarNumero(effective, "maxTokens", true);
        // Temperatura e teto de tokens do fluxo (o painel "Padrões do fluxo" grava os dois). O da
        // plataforma não entra: os adapters têm os seus, e mudar o default de todo nó de chat
        // existente por causa do agente não é o que se pediu aqui.
        if (!effective.containsKey("temperature") && flow.temperature().isPresent()) {
            effective.put("temperature", flow.temperature().getAsDouble());
        }
        if (!effective.containsKey("maxTokens") && flow.maxTokens().isPresent()) {
            effective.put("maxTokens", flow.maxTokens().getAsInt());
        }
        // O endereço do deployment para o provider dele ({@code archflow.llm.base-url}) — o mesmo que
        // o caminho dos agentes usa. Só para esse provider, e nunca por cima de um destino do nó.
        if (platform != null && Objects.equals(provider, platformProvider)
                && URL_KEYS.stream().noneMatch(k -> texto(effective.get(k)) != null)) {
            String baseUrl = texto(platform.additionalConfig().get("baseUrl"));
            if (baseUrl != null) {
                effective.put("base.url", baseUrl);
            }
        }
        return Optional.of(new Resolution(provider, origin, Map.copyOf(effective)));
    }

    /**
     * Os adapters leem {@code temperature}/{@code maxTokens} com {@code (Number)}. O campo numérico do
     * painel, apagado, gravava {@code ""} — que passava daqui (só nulo era ausente) e virava um
     * {@code ClassCastException} no adapter, relatado como "Error executing operation: chat". Texto
     * em branco é ausente (o nó herda); texto numérico (YAML/API) vira número. Qualquer outro texto
     * fica como está: isto roda ao desserializar o fluxo, que não pode falhar só por abrir o documento
     * — a ponte recusa o valor na execução, dizendo qual campo
     * ({@code LangChainAdapterComponent.exigirNumeros}).
     */
    private static void normalizarNumero(Map<String, Object> config, String chave, boolean inteiro) {
        if (!(config.get(chave) instanceof String s)) {
            return;
        }
        if (s.isBlank()) {
            config.remove(chave);
            return;
        }
        try {
            config.put(chave, inteiro ? (Object) Integer.valueOf(s.trim()) : (Object) Double.valueOf(s.trim()));
        } catch (NumberFormatException e) {
            // Fica o texto; a ponte o recusa na execução.
        }
    }

    private static String texto(Object valor) {
        return valor instanceof String s && !s.isBlank() ? s.trim() : null;
    }
}
