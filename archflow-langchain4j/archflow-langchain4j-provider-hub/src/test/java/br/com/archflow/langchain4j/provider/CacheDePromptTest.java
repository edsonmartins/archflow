package br.com.archflow.langchain4j.provider;

import br.com.archflow.model.config.LLMConfigPatch;
import br.com.archflow.model.config.ResolvedLLMConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpMethod;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cache de prompt: o prefixo estável é marcado onde o provedor exige a marca.
 *
 * <h2>O que motivou</h2>
 *
 * <p>Bateria de 07/08, 20 execuções por braço, mesmo pedido real. Os dois
 * primeiros modelos cacheiam sozinhos; o terceiro não cacheia nada:
 *
 * <pre>
 * google/gemini-2.5-flash-lite   13/20   2,9 chamadas   29% cache   US$  1,85/1k
 * x-ai/grok-4.3                  20/20   3,0 chamadas   60% cache   US$ 16,23/1k
 * anthropic/claude-3-haiku       20/20   7,0 chamadas    0% cache   US$ 14,79/1k
 * </pre>
 *
 * <p>Os 0% do terceiro não são limitação do modelo: a Anthropic <b>só</b> cacheia
 * quando a requisição marca onde o prefixo estável termina, e
 * {@code grep -rn cache_control} no repositório não achava nada. Com 7 chamadas
 * por cotação e ~7.470 tokens de prompt em cada, esse prefixo repetido era o item
 * mais caro da conta do agente QP — e a escolha de qual modelo o roda estava
 * refém de um parâmetro que não era enviado.
 *
 * <h2>As duas armadilhas que estes testes guardam</h2>
 *
 * <p><b>O mínimo silencioso.</b> Abaixo do tamanho mínimo do modelo o breakpoint
 * não dá erro — dá nada. É o mesmo formato do {@code exclude: true} no
 * raciocínio: a opção que mais parece resolver é a que não faz efeito. Por isso
 * há teste sobre o log, e não só sobre o corpo.
 *
 * <p><b>O caminho comum não pode pagar.</b> Provedor que já cacheia sozinho não
 * recebe campo novo — nem por ganho nulo, nem pelo risco de um 400 por campo
 * desconhecido — e desligado o corpo tem que sair byte a byte como antes.
 */
@DisplayName("cache de prompt: breakpoint no fim do prefixo estável")
class CacheDePromptTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final ResolvedLLMConfig PLATAFORMA = ResolvedLLMConfig.builder()
            .provider("openrouter").model("anthropic/claude-3-haiku").maxTokens(4096).build();

    private static DefaultLLMConfigResolver resolvedor() {
        return new DefaultLLMConfigResolver(LLMProviderHub.getInstance());
    }

    /**
     * Corpo no formato OpenAI como o adaptador do langchain4j o monta: sistema
     * primeiro, tools ao lado, histórico depois. O prompt de sistema é longo de
     * propósito — passar do mínimo do modelo é o que separa "marcou" de "marcou e
     * foi ignorado".
     */
    private static String corpoDeRequisicao() {
        String sistema = "Voce e o agente de cotacao. ".repeat(700);
        return """
                {"model":"anthropic/claude-3-haiku",
                 "messages":[
                   {"role":"system","content":"%s"},
                   {"role":"user","content":"cotar 3 unidades do item X"},
                   {"role":"assistant","content":"consultando"}
                 ],
                 "tools":[{"type":"function","function":{"name":"buscar_produto",
                           "parameters":{"type":"object","properties":{"q":{"type":"string"}}}}}]
                }""".formatted(sistema);
    }

    /** Cliente HTTP de mentira: guarda o que seria enviado e não fala com ninguém. */
    private static final class Espiao implements HttpClient {
        private String corpoEnviado;

        @Override
        public SuccessfulHttpResponse execute(HttpRequest request) {
            this.corpoEnviado = request.body();
            return SuccessfulHttpResponse.builder().statusCode(200).body("{}").build();
        }

        @Override
        public void execute(HttpRequest request, ServerSentEventParser parser,
                            ServerSentEventListener listener) {
            this.corpoEnviado = request.body();
        }
    }

    private static String enviar(String corpo, String modelo) {
        Espiao espiao = new Espiao();
        HttpClient cliente = new PromptCacheHttpClient(espiao, modelo);
        cliente.execute(HttpRequest.builder()
                .method(HttpMethod.POST)
                .url("https://openrouter.ai/api/v1/chat/completions")
                .body(corpo)
                .build());
        return espiao.corpoEnviado;
    }

    @Nested
    @DisplayName("provedor que exige a marca: o corpo carrega cache_control")
    class Marcado {

        @Test
        @DisplayName("no fim do bloco de sistema, e em nenhum outro lugar")
        void noFimDoSistema() throws Exception {
            String enviado = enviar(corpoDeRequisicao(), "anthropic/claude-3-haiku");
            JsonNode raiz = JSON.readTree(enviado);

            JsonNode sistema = raiz.get("messages").get(0);
            assertThat(sistema.get("role").asText()).isEqualTo("system");
            JsonNode conteudo = sistema.get("content");
            assertThat(conteudo.isArray())
                    .as("cache_control so existe na forma de blocos; string simples nao o aceita")
                    .isTrue();
            JsonNode ultimo = conteudo.get(conteudo.size() - 1);
            assertThat(ultimo.get("type").asText()).isEqualTo("text");
            assertThat(ultimo.get("cache_control").get("type").asText()).isEqualTo("ephemeral");
        }

        /**
         * As tools são renderizadas ANTES do sistema pelo provedor
         * ({@code tools → system → messages}), então uma marca no fim do sistema
         * já cobre as definições de tools. Uma segunda marca dentro das tools seria
         * um breakpoint a mais para o mesmo prefixo.
         */
        @Test
        @DisplayName("um só breakpoint — as tools ficam intactas, cobertas pela ordem de render")
        void umSoBreakpoint() {
            String enviado = enviar(corpoDeRequisicao(), "anthropic/claude-3-haiku");

            assertThat(enviado.split("cache_control", -1).length - 1)
                    .as("a Anthropic aceita quatro; um basta enquanto o historico nao for estavel")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("o histórico não é tocado — num laço de tools ele muda a cada iteração")
        void historicoIntacto() throws Exception {
            JsonNode raiz = JSON.readTree(enviar(corpoDeRequisicao(), "anthropic/claude-3-haiku"));

            JsonNode usuario = raiz.get("messages").get(1);
            JsonNode assistente = raiz.get("messages").get(2);
            assertThat(usuario.get("content").asText()).isEqualTo("cotar 3 unidades do item X");
            assertThat(usuario.has("cache_control")).isFalse();
            assertThat(assistente.has("cache_control")).isFalse();
            assertThat(raiz.get("tools").toString()).doesNotContain("cache_control");
        }

        @Test
        @DisplayName("um corpo já marcado não ganha um segundo breakpoint")
        void naoDuplica() {
            String jaMarcado = enviar(corpoDeRequisicao(), "anthropic/claude-3-haiku");

            assertThat(PromptCacheBreakpoint.marcar(jaMarcado, "anthropic/claude-3-haiku", false))
                    .isSameAs(jaMarcado);
        }
    }

    /**
     * O requisito é literal: "corpo inalterado, byte a byte". Quem garante isso
     * não é uma reescrita cuidadosa, é a <b>ausência do decorador no caminho</b> —
     * e é a decisão testada aqui que decide instalá-lo ou não. Nada é marcado sem
     * ela dizer sim.
     */
    @Nested
    @DisplayName("caminho comum: quem cacheia sozinho não recebe campo novo")
    class CaminhoComum {

        @Test
        @DisplayName("gemini, grok e openai não exigem marcação")
        void naoExigem() {
            assertThat(PromptCacheBreakpoint.exigeMarcacaoExplicita(
                    LLMProvider.OPENROUTER, "google/gemini-2.5-flash-lite")).isFalse();
            assertThat(PromptCacheBreakpoint.exigeMarcacaoExplicita(
                    LLMProvider.OPENROUTER, "x-ai/grok-4.3")).isFalse();
            assertThat(PromptCacheBreakpoint.exigeMarcacaoExplicita(
                    LLMProvider.OPENAI, "gpt-4o")).isFalse();
        }

        @Test
        @DisplayName("a família Anthropic exige, sob qualquer um dos nomes")
        void anthropicExige() {
            assertThat(PromptCacheBreakpoint.exigeMarcacaoExplicita(
                    LLMProvider.OPENROUTER, "anthropic/claude-3-haiku")).isTrue();
            assertThat(PromptCacheBreakpoint.exigeMarcacaoExplicita(
                    LLMProvider.ANTHROPIC, "claude-3-5-sonnet-20241022")).isTrue();
            assertThat(PromptCacheBreakpoint.exigeMarcacaoExplicita(
                    LLMProvider.BEDROCK, "anthropic.claude-3-opus-20240229-v1:0")).isTrue();
        }

        @Test
        @DisplayName("desligado, nada de cachePrompt chega ao builder do modelo")
        void desligadoNaoChega() {
            LLMConfigPatch patch = LLMConfigPatch.fromMap(
                    Map.of("model", "anthropic/claude-3-haiku", "maxTokens", 4096));

            ResolvedLLMConfig resolvido = resolvedor().resolve(
                    LLMResolutionRequest.builder(PLATAFORMA).stepPatch(patch).build());

            assertThat(resolvido.cachePrompt()).isFalse();
            assertThat(resolvedor().toProviderConfig(resolvido, "k").isCachePrompt())
                    .as("desligado tem que sair byte a byte como antes desta funcionalidade")
                    .isFalse();
        }

        @Test
        @DisplayName("corpo sem mensagem de sistema volta na mesma instância")
        void semSistema() {
            String corpo = "{\"model\":\"anthropic/claude-3-haiku\","
                    + "\"messages\":[{\"role\":\"user\",\"content\":\"oi\"}]}";

            assertThat(PromptCacheBreakpoint.marcar(corpo, "anthropic/claude-3-haiku", false))
                    .isSameAs(corpo);
        }

        /** Uma otimização de custo não pode derrubar uma cotação. */
        @Test
        @DisplayName("corpo ilegível segue intacto em vez de estourar")
        void corpoIlegivel() {
            String lixo = "isto nao e json";

            assertThat(PromptCacheBreakpoint.marcar(lixo, "anthropic/claude-3-haiku", false))
                    .isSameAs(lixo);
        }
    }

    /**
     * A armadilha nº 1 da demanda: abaixo do mínimo do modelo o breakpoint vai no
     * corpo e o provedor o ignora <b>sem erro</b>. Sem este aviso a funcionalidade
     * parece entregue e não está.
     */
    @Nested
    @DisplayName("prefixo abaixo do mínimo: avisa em vez de calar")
    class Minimo {

        private ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender;
        private ch.qos.logback.classic.Logger logger;

        @BeforeEach
        void capturarLog() {
            logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(PromptCacheBreakpoint.class);
            appender = new ch.qos.logback.core.read.ListAppender<>();
            appender.start();
            logger.addAppender(appender);
        }

        @AfterEach
        void soltarLog() {
            logger.detachAppender(appender);
        }

        private List<String> avisos() {
            return appender.list.stream()
                    .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                    .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .toList();
        }

        @Test
        @DisplayName("prompt curto: marca, e avisa que será ignorado")
        void prefixoCurtoAvisa() {
            String corpo = "{\"model\":\"anthropic/claude-3-haiku\",\"messages\":["
                    + "{\"role\":\"system\",\"content\":\"seja breve\"}]}";

            String enviado = PromptCacheBreakpoint.marcar(corpo, "anthropic/claude-3-haiku", true);

            assertThat(enviado).contains("cache_control");
            assertThat(avisos()).anySatisfy(m -> assertThat(m)
                    .contains("abaixo do minimo")
                    .contains("IGNORADO em silencio"));
        }

        @Test
        @DisplayName("prompt longo o bastante: nenhum aviso")
        void prefixoSuficienteNaoAvisa() {
            PromptCacheBreakpoint.marcar(corpoDeRequisicao(), "anthropic/claude-3-haiku", true);

            assertThat(avisos()).isEmpty();
        }

        @Test
        @DisplayName("sem mensagem de sistema não há prefixo a marcar, e isso é dito")
        void semSistemaAvisa() {
            String corpo = "{\"messages\":[{\"role\":\"user\",\"content\":\"oi\"}]}";

            PromptCacheBreakpoint.marcar(corpo, "anthropic/claude-3-haiku", true);

            assertThat(avisos()).anySatisfy(m -> assertThat(m)
                    .contains("nao tem mensagem de sistema"));
        }

        /**
         * O mínimo é fato do provedor e varia por família. Fixá-lo num número só
         * daria aviso errado nos dois sentidos — falso alarme onde o mínimo é 512,
         * silêncio onde é 4096.
         */
        @Test
        @DisplayName("o mínimo é por família de modelo")
        void minimoPorFamilia() {
            assertThat(PromptCacheBreakpoint.minimoDeTokens("anthropic/claude-3-haiku")).isEqualTo(2048);
            assertThat(PromptCacheBreakpoint.minimoDeTokens("anthropic/claude-haiku-4-5")).isEqualTo(4096);
            assertThat(PromptCacheBreakpoint.minimoDeTokens("anthropic/claude-sonnet-4")).isEqualTo(1024);
            assertThat(PromptCacheBreakpoint.minimoDeTokens("anthropic/claude-opus-5")).isEqualTo(512);
        }
    }

    @Nested
    @DisplayName("o nó liga e a chave atravessa até o builder")
    class Atravessa {

        @Test
        @DisplayName("cachePrompt declarado no passo chega ao LLMProviderConfig")
        void chegaAoProviderConfig() {
            LLMConfigPatch patch = LLMConfigPatch.fromMap(Map.of("cachePrompt", true));

            ResolvedLLMConfig resolvido = resolvedor().resolve(
                    LLMResolutionRequest.builder(PLATAFORMA).stepPatch(patch).build());

            assertThat(resolvido.cachePrompt()).isTrue();
            assertThat(resolvedor().toProviderConfig(resolvido, "k").isCachePrompt())
                    .as("sem isto a chave morre no patch e nunca vira corpo de requisicao")
                    .isTrue();
        }

        @Test
        @DisplayName("declarado só no fluxo, o passo herda")
        void herdaDoFluxo() {
            ResolvedLLMConfig r = resolvedor().resolve(LLMResolutionRequest.builder(PLATAFORMA)
                    .flowPatch(LLMConfigPatch.fromMap(Map.of("cachePrompt", true)))
                    .build());

            assertThat(r.cachePrompt()).isTrue();
        }

        /**
         * Desligar num passo é o caso real do custo: um nó de chamada única sobre
         * um fluxo que liga o cache paga 25% a mais se não puder desligar.
         */
        @Test
        @DisplayName("o passo vence o fluxo, inclusive para desligar")
        void passoVenceFluxo() {
            ResolvedLLMConfig r = resolvedor().resolve(LLMResolutionRequest.builder(PLATAFORMA)
                    .flowPatch(LLMConfigPatch.fromMap(Map.of("cachePrompt", true)))
                    .stepPatch(LLMConfigPatch.fromMap(Map.of("cachePrompt", false)))
                    .build());

            assertThat(r.cachePrompt()).isFalse();
        }

        @Test
        @DisplayName("o default da plataforma vale quando ninguém declara")
        void defaultDaPlataforma() {
            ResolvedLLMConfig plataforma = ResolvedLLMConfig.builder()
                    .provider("openrouter").model("anthropic/claude-3-haiku")
                    .maxTokens(4096).cachePrompt(true).build();

            ResolvedLLMConfig r = resolvedor().resolve(
                    LLMResolutionRequest.builder(plataforma).build());

            assertThat(r.cachePrompt()).isTrue();
        }

        @Test
        @DisplayName("slots de cache distintos com e sem marcação")
        void slotsDistintos() {
            ResolvedLLMConfig com = ResolvedLLMConfig.builder().provider("openrouter")
                    .model("anthropic/claude-3-haiku").maxTokens(4096).cachePrompt(true).build();
            ResolvedLLMConfig sem = ResolvedLLMConfig.builder().provider("openrouter")
                    .model("anthropic/claude-3-haiku").maxTokens(4096).build();

            assertThat(resolvedor().cacheConfigId("t1", com, "k"))
                    .as("o modelo e construido diferente; um slot so devolveria o errado")
                    .isNotEqualTo(resolvedor().cacheConfigId("t1", sem, "k"));
            assertThat(resolvedor().toProviderConfig(com, "k"))
                    .isNotEqualTo(resolvedor().toProviderConfig(sem, "k"));
        }

        @Test
        @DisplayName("toMap só traz cachePrompt quando ele foi declarado")
        void toMapOmiteQuandoAusente() {
            assertThat(LLMConfigPatch.fromMap(Map.of("model", "x")).toMap())
                    .doesNotContainKey("cachePrompt");
            assertThat(LLMConfigPatch.fromMap(Map.of("cachePrompt", false)).toMap())
                    .containsEntry("cachePrompt", false);
        }
    }

    /**
     * Mesma escolha do {@code reasoning}: falhar em vez de ignorar. Quem declara
     * está tentando cortar a maior linha da conta; um valor mal escrito descartado
     * em silêncio devolveria o custo antigo com o operador convencido de que
     * resolveu — que é exatamente o modo de falha desta demanda.
     */
    @Nested
    @DisplayName("valor inválido falha, não é ignorado")
    class Validacao {

        @Test
        @DisplayName("cachePrompt que não é booleano")
        void naoEBooleano() {
            assertThatThrownBy(() -> LLMConfigPatch.fromMap(Map.of("cachePrompt", "sim")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cachePrompt");
        }

        @Test
        @DisplayName("texto \"true\"/\"false\" passa — é o que a UI serializa")
        void textoBooleanoPassa() {
            assertThat(LLMConfigPatch.fromMap(Map.of("cachePrompt", "true")).cachePrompt())
                    .contains(true);
        }
    }
}
