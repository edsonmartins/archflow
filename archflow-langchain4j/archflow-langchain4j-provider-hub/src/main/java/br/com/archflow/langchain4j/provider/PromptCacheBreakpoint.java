package br.com.archflow.langchain4j.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;

/**
 * Marca o fim do prefixo estável de uma requisição — prompt de sistema mais
 * definições de tools — com o breakpoint de cache, para os provedores que o
 * exigem.
 *
 * <h2>Por que existe</h2>
 *
 * <p>Num laço MCP o prompt de sistema e os esquemas das tools se repetem em
 * <b>toda</b> chamada, e são a maior parte do que se paga. Gemini e xAI cacheiam
 * esse prefixo sozinhos — o provedor reconhece a repetição e cobra menos, sem
 * ninguém pedir. A Anthropic <b>só</b> cacheia quando a requisição marca
 * explicitamente onde o prefixo estável termina; sem a marca, cada chamada paga
 * o prompt inteiro a preço cheio.
 *
 * <p>Medido em 07/08, 20 execuções por braço sobre o mesmo pedido real:
 * {@code grok-4.3} chegou a 60% de cache sem configuração nenhuma e
 * {@code claude-3-haiku} ficou em 0%, com 7 chamadas por cotação e ~7.470 tokens
 * de prompt em cada uma. Os dois acertaram 20/20; o segundo custou quase o mesmo
 * que o primeiro <i>por não cachear</i>.
 *
 * <h2>Um breakpoint, no fim do bloco sistema + tools</h2>
 *
 * <p>A Anthropic aceita até quatro breakpoints, e aqui se usa <b>um</b>. O cache
 * é casamento de prefixo e a ordem de renderização é
 * {@code tools → system → messages}: uma marca no fim do bloco de sistema cobre
 * as definições de tools junto, porque elas vêm antes. Mais breakpoints só
 * valeriam se o histórico também fosse estável, e num laço de tools ele não é —
 * cada iteração acrescenta uma chamada e um resultado.
 *
 * <h2>Duas armadilhas</h2>
 *
 * <p><b>Existe tamanho mínimo, e abaixo dele a marca é ignorada em silêncio.</b>
 * Não há erro: a requisição é aceita, o breakpoint não produz nada, e a
 * funcionalidade parece entregue. Por isso {@link #minimoDeTokens(String)} e o
 * aviso em nível WARN quando o prefixo não alcança o mínimo do modelo.
 *
 * <p><b>Escrever no cache custa mais caro que não cachear.</b> A gravação sai a
 * ~1,25× o preço da entrada e a leitura a ~0,1×. Com N chamadas sobre o mesmo
 * prefixo, {@code N×P} vira {@code 1,25×P + (N-1)×0,1×P} — com N=7 são 26% do
 * custo anterior, mas com N=1 são 25% <i>a mais</i>. Um fluxo de passo único
 * fica pior. É por isso que nada aqui é automático: quem liga é quem conhece o
 * laço (ver {@code cachePrompt} em {@code LLMConfigPatch}).
 *
 * @see PromptCacheHttpClient
 */
final class PromptCacheBreakpoint {

    private static final Logger log = LoggerFactory.getLogger(PromptCacheBreakpoint.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** O valor do breakpoint que o OpenRouter repassa ao provedor. */
    private static final Map<String, String> EPHEMERAL = Map.of("type", "ephemeral");

    /**
     * Mínimo de tokens cacheáveis por família de modelo. Abaixo disso o provedor
     * ignora o breakpoint sem erro.
     *
     * <p>É fato do provedor, não escolha nossa, e muda quando ele muda: a fonte
     * de verdade é a documentação de prompt caching da Anthropic. O default
     * conservador de 2048 vale para o que não estiver mapeado — errar para mais
     * produz um aviso desnecessário, errar para menos esconde a armadilha.
     */
    private static final int MINIMO_PADRAO = 2048;

    private PromptCacheBreakpoint() {
    }

    /**
     * O provedor exige marcação explícita para cachear o prefixo?
     *
     * <p>Só a família Anthropic. Gemini, xAI e OpenAI cacheiam sozinhos e não
     * devem receber campo novo: o caminho comum não pode pagar por isto, e um
     * campo desconhecido pode virar 400.
     */
    static boolean exigeMarcacaoExplicita(LLMProvider provider, String modelId) {
        if (provider == LLMProvider.ANTHROPIC) {
            return true;
        }
        return modeloAnthropic(modelId);
    }

    /**
     * Modelo da família Anthropic, sob qualquer um dos nomes que os agregadores
     * usam: {@code anthropic/claude-3-haiku} (OpenRouter),
     * {@code anthropic.claude-3-opus-...} (Bedrock), {@code claude-...} (nativo).
     */
    static boolean modeloAnthropic(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return false;
        }
        String m = modelId.toLowerCase(Locale.ROOT);
        return m.startsWith("anthropic/") || m.startsWith("anthropic.") || m.contains("claude");
    }

    /**
     * Mínimo de tokens que o prefixo precisa ter para o breakpoint valer, no
     * modelo dado.
     */
    static int minimoDeTokens(String modelId) {
        String m = modelId == null ? "" : modelId.toLowerCase(Locale.ROOT);
        if (contemAlgum(m, "opus-5", "opus5", "fable-5", "mythos-5")) {
            return 512;
        }
        if (contemAlgum(m, "haiku-4-5", "haiku-4.5", "opus-4-5", "opus-4.5",
                "opus-4-6", "opus-4.6")) {
            return 4096;
        }
        if (contemAlgum(m, "sonnet", "opus-4-8", "opus-4.8")) {
            return 1024;
        }
        return MINIMO_PADRAO;
    }

    private static boolean contemAlgum(String texto, String... fragmentos) {
        for (String f : fragmentos) {
            if (texto.contains(f)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reescreve um corpo de requisição no formato OpenAI acrescentando
     * {@code cache_control} ao último bloco de conteúdo da mensagem de sistema.
     *
     * <p>Devolve <b>a mesma instância</b> quando não há o que marcar — corpo não
     * é JSON, não tem mensagem de sistema, ou já carrega a marca. Isso não é
     * detalhe: o requisito é que o caminho não marcado siga byte a byte idêntico,
     * e a identidade da string é o que torna isso verificável.
     *
     * <p>Falha de parse não derruba a chamada. O pior caso aceitável de uma
     * otimização de custo é não otimizar; derrubar uma cotação por causa dela não
     * é.
     *
     * @param corpo   JSON da requisição
     * @param modelId modelo, só para o aviso de tamanho mínimo
     * @param avisar  {@code true} para emitir o aviso de prefixo curto (o chamador
     *                controla a frequência — o tamanho é praticamente constante ao
     *                longo de um laço e avisar em toda chamada seria ruído)
     */
    static String marcar(String corpo, String modelId, boolean avisar) {
        if (corpo == null || corpo.isBlank()) {
            return corpo;
        }
        final ObjectNode raiz;
        try {
            JsonNode lido = JSON.readTree(corpo);
            if (!(lido instanceof ObjectNode obj)) {
                return corpo;
            }
            raiz = obj;
        } catch (Exception e) {
            log.warn("[LLM-CACHE] corpo da requisicao nao e JSON legivel; seguindo sem "
                    + "breakpoint de cache: {}", e.toString());
            return corpo;
        }

        ObjectNode sistema = ultimaMensagemDeSistema(raiz);
        if (sistema == null) {
            if (avisar) {
                log.warn("[LLM-CACHE] cachePrompt ligado, mas a requisicao para {} nao tem "
                        + "mensagem de sistema — nao ha prefixo estavel para marcar, e o "
                        + "breakpoint NAO sera enviado", modelId);
            }
            return corpo;
        }

        ArrayNode conteudo = comoBlocosDeTexto(sistema);
        if (conteudo == null || conteudo.isEmpty()) {
            return corpo;
        }
        JsonNode ultimo = conteudo.get(conteudo.size() - 1);
        if (!(ultimo instanceof ObjectNode bloco)) {
            return corpo;
        }
        if (bloco.has("cache_control")) {
            return corpo;   // já marcado: não duplica breakpoint
        }
        bloco.set("cache_control", JSON.valueToTree(EPHEMERAL));

        if (avisar) {
            avisarSePrefixoCurto(raiz, conteudo, modelId);
        }
        try {
            return JSON.writeValueAsString(raiz);
        } catch (Exception e) {
            log.warn("[LLM-CACHE] falha ao serializar o corpo marcado; seguindo com o "
                    + "original: {}", e.toString());
            return corpo;
        }
    }

    /**
     * A última mensagem do bloco de sistema inicial, ou {@code null}. É onde o
     * prefixo estável termina — depois dela vem o histórico, que num laço de
     * tools muda a cada iteração.
     */
    private static ObjectNode ultimaMensagemDeSistema(ObjectNode raiz) {
        if (!(raiz.get("messages") instanceof ArrayNode mensagens)) {
            return null;
        }
        ObjectNode ultima = null;
        for (JsonNode m : mensagens) {
            if (!(m instanceof ObjectNode msg)) {
                break;
            }
            JsonNode papel = msg.get("role");
            if (papel == null || !"system".equals(papel.asText())) {
                break;   // acabou o bloco inicial de sistema
            }
            ultima = msg;
        }
        return ultima;
    }

    /**
     * Normaliza o {@code content} da mensagem para a forma de blocos, que é a
     * única que aceita {@code cache_control}, e devolve o array resultante.
     */
    private static ArrayNode comoBlocosDeTexto(ObjectNode mensagem) {
        JsonNode conteudo = mensagem.get("content");
        if (conteudo instanceof ArrayNode array) {
            return array;
        }
        if (conteudo != null && conteudo.isTextual()) {
            ArrayNode array = JSON.createArrayNode();
            ObjectNode bloco = array.addObject();
            bloco.put("type", "text");
            bloco.put("text", conteudo.asText());
            mensagem.set("content", array);
            return array;
        }
        return null;
    }

    /**
     * Avisa quando o prefixo não alcança o mínimo do modelo — o caso em que a
     * marca vai no corpo e não produz efeito nenhum.
     *
     * <p>A contagem é <b>estimada</b> em ~4 caracteres por token. Em JSON e em
     * português a densidade real é maior que isso, então a estimativa tende a
     * ficar <i>abaixo</i> da contagem verdadeira: erra para o lado de avisar
     * quando talvez não precisasse, e não para o de calar quando precisava.
     */
    private static void avisarSePrefixoCurto(ObjectNode raiz, ArrayNode sistema, String modelId) {
        int caracteres = sistema.toString().length();
        JsonNode tools = raiz.get("tools");
        if (tools != null) {
            caracteres += tools.toString().length();
        }
        int estimado = caracteres / 4;
        int minimo = minimoDeTokens(modelId);
        if (estimado < minimo) {
            log.warn("[LLM-CACHE] prefixo estavel de ~{} tokens (sistema + tools, estimado) "
                            + "abaixo do minimo de {} do modelo {}: o breakpoint sera enviado e "
                            + "IGNORADO em silencio pelo provedor. Nao conclua que 'implementou "
                            + "e nao mudou' — aumente o prefixo ou desligue cachePrompt.",
                    estimado, minimo, modelId);
        }
    }
}
